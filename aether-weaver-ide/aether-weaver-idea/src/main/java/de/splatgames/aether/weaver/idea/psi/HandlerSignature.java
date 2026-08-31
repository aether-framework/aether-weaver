package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes a handler's parameter list apart into the roles the injected call fills.
 *
 * <p>A handler's parameters are, in order: the target's receiver for an instance target woven by a {@code static}
 * handler, then a prefix of the target's own arguments, then an optional {@code Callback}, then the {@code @Local}
 * captures. Nothing in a parameter list says which is which, so every inspection, fix and generator that has to
 * reason about one asks here instead of counting parameters itself.
 *
 * <h2>Types are compared erased, by canonical text</h2>
 *
 * <p>The comparison is between what the editor resolved and what a class file will hold, so a type argument is not
 * part of it: a handler taking {@code List} matches a target taking {@code List<String>}. A type that does not
 * resolve, or that erases to a type variable, is not compared at all — the methods answer {@code null} rather than
 * report a disagreement they cannot be sure of, which is what keeps an inspection quiet on a file mid-edit.
 *
 * <h2>The diagnostics this feeds</h2>
 *
 * <p>{@link #prefixFailure(PsiMethod, PsiMethod)} is what {@code AW1040} is reported from in the editor, and
 * {@link #callbackMismatch(PsiParameter, PsiMethod)} what {@code AW1071} is reported from. Neither reports anything
 * itself; both describe the disagreement and leave the code to the inspection.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class HandlerSignature {

    /** The qualified name of {@link de.splatgames.aether.weaver.api.callback.Callback}. */
    public static final String CALLBACK = "de.splatgames.aether.weaver.api.callback.Callback";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback}. */
    public static final String RETURNABLE_CALLBACK =
            "de.splatgames.aether.weaver.api.callback.ReturnableCallback";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Local}. */
    public static final String LOCAL = "de.splatgames.aether.weaver.api.Local";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private HandlerSignature() {
        throw new AssertionError("no instances");
    }

    /**
     * A handler's parameter list, sorted into the roles the injected call fills.
     *
     * <p>The captures are not carried: a {@code @Local} parameter is recognised by its annotation wherever it sits
     * and is dropped, so {@link #arguments()} holds only parameters that have to line up with the target's own.
     *
     * @param receiver  whether the first parameter is the target's receiver rather than an argument
     * @param arguments the parameters that must be a prefix of the target's arguments, in declaration order
     * @param callback  the {@code Callback} or {@code ReturnableCallback} parameter, or {@code null} when the
     *                  handler declares none
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Shape(boolean receiver,
                        @Unmodifiable @NotNull List<PsiParameter> arguments,
                        @Nullable PsiParameter callback) {
    }

    /**
     * Sorts a handler's parameters into their roles.
     *
     * <p>A callback is recognised by its erased type alone and may sit anywhere in the list; only the arguments keep
     * their order, because only they are compared position by position with the target's.
     *
     * @param handler the handler method; must not be {@code null}
     * @param target  the method being woven, which decides whether a receiver is taken; must not be {@code null}
     * @return the shape, or {@code null} when a parameter's type does not resolve, in which case nothing about the
     *         handler can be judged
     */
    @Nullable
    public static Shape shapeOf(@NotNull final PsiMethod handler, @NotNull final PsiMethod target) {
        final PsiParameter[] declared = handler.getParameterList().getParameters();
        final boolean receiver = takesReceiver(handler, target);

        final List<PsiParameter> arguments = new ArrayList<>(declared.length);
        PsiParameter callback = null;
        for (int i = receiver ? 1 : 0; i < declared.length; i++) {
            final PsiParameter parameter = declared[i];
            if (WeaveDeclarations.annotation(parameter, LOCAL) != null) {
                continue;
            }
            final String type = erasedNameOf(parameter.getType());
            if (type == null) {
                return null;
            }
            if (CALLBACK.equals(type) || RETURNABLE_CALLBACK.equals(type)) {
                callback = parameter;
                continue;
            }
            arguments.add(parameter);
        }
        return new Shape(receiver, List.copyOf(arguments), callback);
    }

    /**
     * Reports whether the handler's first parameter stands for the target's receiver.
     *
     * <p>Decided by shape rather than by an annotation, and only where a receiver exists to be passed: a static
     * target has none, and a non-static handler is already called on one. The test is that the first parameter's
     * erased type is exactly the target's declaring class; a parameter typed as a supertype of it is read as an
     * argument instead.
     *
     * @param handler the handler method; must not be {@code null}
     * @param target  the method being woven; must not be {@code null}
     * @return {@code true} when the handler is {@code static}, the target is not, and the first parameter is the
     *         target's own class
     */
    public static boolean takesReceiver(@NotNull final PsiMethod handler,
                                        @NotNull final PsiMethod target) {
        final PsiParameter[] declared = handler.getParameterList().getParameters();
        if (declared.length == 0
                || !handler.hasModifierProperty(PsiModifier.STATIC)
                || target.hasModifierProperty(PsiModifier.STATIC)) {
            return false;
        }
        final PsiClass owner = target.getContainingClass();
        final String qualified = owner == null ? null : owner.getQualifiedName();
        return qualified != null && qualified.equals(erasedNameOf(declared[0].getType()));
    }

    /**
     * One reason a handler's arguments are not a prefix of the target's.
     *
     * <p>Carries the element to underline as well as the wording, because the two failures highlight differently: a
     * type that disagrees is the parameter's problem, while taking more parameters than the target has is the list's.
     *
     * @param detail    the disagreement, phrased to be read after the handler's name
     * @param parameter the parameter to underline, or {@code null} when the whole parameter list is at fault
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Mismatch(@NotNull String detail, @Nullable PsiParameter parameter) {
    }

    /**
     * Reports the first way a handler's arguments fail to be a prefix of the target's.
     *
     * <p>The rule this checks is {@code AW1040}: the injected call pushes the target's own arguments in order, so a
     * handler may take the first n of them and nothing else. Parameters are compared by erased canonical name, so a
     * type argument that differs is not a mismatch and a widening conversion is.
     *
     * <p>The receiver, the callback and the {@code @Local} captures take no part: they are removed by
     * {@link #shapeOf(PsiMethod, PsiMethod)} before the comparison starts.
     *
     * @param handler the handler method; must not be {@code null}
     * @param target  the method being woven; must not be {@code null}
     * @return the first mismatch, or {@code null} when the arguments are a prefix of the target's and equally when a
     *         type on either side does not resolve, since an unresolved type is no evidence of a disagreement
     */
    @Nullable
    public static Mismatch prefixFailure(@NotNull final PsiMethod handler,
                                         @NotNull final PsiMethod target) {
        final Shape shape = shapeOf(handler, target);
        if (shape == null) {
            return null;
        }
        final PsiParameter[] expected = target.getParameterList().getParameters();
        if (shape.arguments().size() > expected.length) {
            return new Mismatch("it takes " + shape.arguments().size()
                    + " argument(s) where the target has only " + expected.length, null);
        }
        for (int i = 0; i < shape.arguments().size(); i++) {
            final PsiParameter argument = shape.arguments().get(i);
            final String written = erasedNameOf(argument.getType());
            final String actual = erasedNameOf(expected[i].getType());
            if (written == null || actual == null) {
                return null;
            }
            if (!written.equals(actual)) {
                return new Mismatch("parameter " + (i + 1) + " is " + written
                        + " where the target's is " + actual, argument);
            }
        }
        return null;
    }

    /**
     * Reports the type argument a callback should have been declared with.
     *
     * <p>The comparison boxes the target's return type first, because a type argument cannot be primitive: a target
     * returning {@code int} is matched by {@code ReturnableCallback<Integer>}.
     *
     * <p>A {@code ReturnableCallback} on a {@code void} target is one of the two shapes {@code AW1071} covers, and is
     * left to the caller: this method has no second type to name in its place. A raw {@code ReturnableCallback}, or a
     * plain {@code Callback} — which is not generic at all and so is just as raw in the sense this method cares
     * about — names no type argument and so disagrees with nothing; neither is {@code AW1071}, and a plain
     * {@code Callback} against a non-{@code void} target is {@code AW1070} instead, reported elsewhere.
     *
     * @param callback the callback parameter, as {@link Shape#callback()} found it; must not be {@code null}
     * @param target   the method being woven; must not be {@code null}
     * @return the type argument the callback should carry, or {@code null} when it already carries it, when the
     *         target returns {@code void} or nothing, when the parameter has no type argument to compare — whether
     *         because it is raw or because it is declared as the non-generic {@code Callback} — or when either type
     *         does not resolve
     */
    @Nullable
    public static String callbackMismatch(@NotNull final PsiParameter callback,
                                          @NotNull final PsiMethod target) {
        if (!(callback.getType() instanceof final PsiClassType declared)) {
            return null;
        }
        final PsiType[] arguments = declared.getParameters();
        // A raw ReturnableCallback names nothing, so there is nothing to disagree with. The
        // processor takes the same view; a raw-type warning is the Java compiler's business.
        if (arguments.length != 1) {
            return null;
        }
        final PsiType returned = target.getReturnType();
        if (returned == null || PsiTypes.voidType().equals(returned)) {
            return null;
        }
        final String boxed = boxedNameOf(returned);
        final String written = erasedNameOf(arguments[0]);
        return boxed == null || written == null || written.equals(boxed) ? null : boxed;
    }

    /**
     * Returns the callback type a handler for the given target should declare.
     *
     * <p>Written as source text for a generator to insert: a plain {@code Callback} for a {@code void} target, and
     * {@code ReturnableCallback} parameterised with the boxed return type otherwise. Both names are qualified, so
     * the text compiles wherever it is inserted; the callers that insert it shorten class references afterwards.
     *
     * @param target the method being woven; must not be {@code null}
     * @return the type to declare, or {@code null} when the target has no return type — a constructor — or its
     *         return type does not resolve
     */
    @Contract(pure = true)
    @Nullable
    public static String callbackTypeFor(@NotNull final PsiMethod target) {
        final PsiType returned = target.getReturnType();
        if (returned == null) {
            return null;
        }
        if (PsiTypes.voidType().equals(returned)) {
            return CALLBACK;
        }
        final String boxed = boxedNameOf(returned);
        return boxed == null ? null : RETURNABLE_CALLBACK + '<' + boxed + '>';
    }

    /**
     * Renders a type as source text a generated declaration can be written with.
     *
     * <p>Keeps the type arguments where they can be written — a target taking {@code List<String>} gives a handler
     * taking {@code List<String>} — and erases where they cannot: a type mentioning a type parameter would be
     * written into a method that has no such parameter in scope and would not compile, so {@code List<T>} is
     * rendered as {@code java.util.List}. A varargs parameter is normalised to its array type first, so it renders
     * as {@code java.lang.String[]} rather than as {@code String...}.
     *
     * @param type the type to render; must not be {@code null}
     * @return the source text, or {@code null} when the type does not resolve, in which case nothing legal can be
     *         written for it
     */
    @Contract(pure = true)
    @Nullable
    public static String writableTextOf(@NotNull final PsiType type) {
        final PsiType normalised = type instanceof final PsiEllipsisType varargs
                ? varargs.toArrayType()
                : type;
        if (!PsiTypesUtil.mentionsTypeParameters(normalised, parameter -> true)) {
            return resolves(normalised) ? normalised.getCanonicalText() : null;
        }
        return erasedNameOf(normalised);
    }

    /**
     * Returns a type's erased canonical name.
     *
     * <p>The comparison key everything in this class uses, and the one thing that makes a handler's declared type
     * comparable with a target's: the class file holds the erasure, so {@code List<String>} and {@code List} name
     * the same parameter and have to compare equal. Varargs is normalised to an array first, for the same reason.
     *
     * <p>A type that erases to something unresolved is refused rather than named, so a caller comparing two names
     * never compares against a name that only looks right.
     *
     * @param type the type to name, or {@code null} when there is none
     * @return the erased canonical name, or {@code null} when there is no type, the erasure is empty, or the erased
     *         type does not resolve to a class or a primitive
     */
    @Contract(pure = true)
    @Nullable
    public static String erasedNameOf(@Nullable final PsiType type) {
        if (type == null) {
            return null;
        }
        final PsiType normalised = type instanceof final PsiEllipsisType varargs
                ? varargs.toArrayType()
                : type;
        final PsiType erased = TypeConversionUtil.erasure(normalised);
        return erased == null || !resolves(erased) ? null : erased.getCanonicalText();
    }

    /**
     * Returns the name a type has as a type argument.
     *
     * @param type the type to name; must not be {@code null}
     * @return the boxed name for a primitive and the erased name otherwise, or {@code null} when neither resolves
     */
    @Contract(pure = true)
    @Nullable
    private static String boxedNameOf(@NotNull final PsiType type) {
        // A type argument cannot be primitive: a target returning int is
        // ReturnableCallback<Integer>, and comparing the two directly refuses every primitive.
        return type instanceof final PsiPrimitiveType primitive
                ? primitive.getBoxedTypeName()
                : erasedNameOf(type);
    }

    /**
     * Reports whether a type names something the editor could find.
     *
     * <p>An array is judged by its component type, so an array of an unknown class is unknown too.
     *
     * @param type the type to test; must not be {@code null}
     * @return {@code true} for a primitive and for a class type resolving to a class that is not a type parameter
     */
    @Contract(pure = true)
    private static boolean resolves(@NotNull final PsiType type) {
        if (type instanceof final PsiArrayType array) {
            return resolves(array.getComponentType());
        }
        if (type instanceof final PsiClassType declared) {
            final PsiClass resolved = declared.resolve();
            // A type parameter resolves, but erasure should already have removed it; if one survives
            // here the erasure was not what this class assumes, and silence is the safe answer.
            return resolved != null && !(resolved instanceof PsiTypeParameter);
        }
        return type instanceof PsiPrimitiveType;
    }
}
