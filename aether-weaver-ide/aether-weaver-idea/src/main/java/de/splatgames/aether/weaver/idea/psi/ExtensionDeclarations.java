package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads an {@code @Extension} class the way the annotation processor reads it.
 *
 * <p>Every feature of the plugin that has an opinion about an extension — the augment provider, the receiver index,
 * the inspection, the line marker, the tool window, the implicit usage provider — asks these methods rather than
 * inspecting annotations itself, so that all of them agree on what is contributed and to what.
 *
 * <h2>The three forms a receiver can take</h2>
 *
 * <ul>
 *   <li><b>On the first parameter.</b> {@code @Receiver} on parameter zero contributes an instance method to that
 *       parameter's type.
 *   <li><b>On the method.</b> {@code @Receiver(Type.class)} on the method itself contributes a {@code static} method
 *       to the named type. Marking both is {@code AW1313} and contributes nothing, because such a declaration names
 *       two receivers and picks neither; {@link #contributes(PsiMethod)} answers {@code false} for it.
 *   <li><b>On the class.</b> {@code @Extension(Type.class)} makes parameter zero the receiver by position for every
 *       method that names none of its own. A method with no parameters at all, and one whose first parameter is a
 *       different type, are both {@code AW1316} in the build rather than silently uncontributed; here, a method
 *       with no parameters answers with no receiver ({@link #contributes(PsiMethod)} requires at least one), and
 *       one with a wrong first parameter is shown taking what it actually takes rather than what {@code AW1316}
 *       expected.
 * </ul>
 *
 * <h2>What is not checked here</h2>
 *
 * <p>None of these methods looks at whether the enclosing class carries {@code @Extension}, so
 * {@link #contributes(PsiMethod)} answers about a method's own shape alone. A caller that means "is this contributed
 * in the build" tests {@link #isExtension(PsiClass)} on the containing class first.
 *
 * <p>Nothing here reports a diagnostic or refuses a shape the processor refuses: a receiver whose type is a
 * primitive or an array is {@code AW1304} and can carry no member, and it is answered here as simply having no
 * receiver class rather than as an error, which is
 * {@code de.splatgames.aether.weaver.idea.inspection.ExtensionDeclarationInspection}'s work. A type variable is
 * {@code AW1304} as well, but only where it can be written at all — a {@code @Receiver} parameter, or parameter
 * zero under a class-level {@code @Extension}, in a generic holder — and {@link #receiverOf(PsiMethod)} does not
 * exclude it there: {@code PsiTypeParameter} is itself a {@link PsiClass}, so resolving it succeeds and the type
 * variable is handed back as though it were an ordinary receiver class.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionDeclarations {

    /** The qualified name of {@link de.splatgames.aether.weaver.api.experimental.Extension}. */
    public static final String EXTENSION = "de.splatgames.aether.weaver.api.experimental.Extension";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.experimental.Receiver}. */
    public static final String RECEIVER = "de.splatgames.aether.weaver.api.experimental.Receiver";

    /** The simple name of the extension annotation, for a caller that has only the text of a reference. */
    public static final String EXTENSION_SIMPLE_NAME = "Extension";

    /** The simple name of the receiver annotation, for a caller that has only the text of a reference. */
    public static final String RECEIVER_SIMPLE_NAME = "Receiver";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ExtensionDeclarations() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports whether a class is an extension holder.
     *
     * @param candidate the class to test; must not be {@code null}
     * @return {@code true} when the class carries {@code @Extension}
     * @throws NullPointerException if {@code candidate} is {@code null}
     */
    @Contract(pure = true)
    public static boolean isExtension(@NotNull final PsiClass candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return WeaveDeclarations.annotation(candidate, EXTENSION) != null;
    }

    /**
     * Returns the methods a holder contributes.
     *
     * <p>Its own declarations only, read through {@code PsiExtensibleClass} where the class provides it, so that a
     * member merged in by an augment provider is not reported as something this class contributes. A class that is
     * not extensible falls back to {@code getMethods()}.
     *
     * @param holder the extension class; must not be {@code null}
     * @return the contributed methods, in declaration order and empty when there are none
     * @throws NullPointerException if {@code holder} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public static List<PsiMethod> contributedBy(@NotNull final PsiClass holder) {
        Objects.requireNonNull(holder, "holder");
        final List<PsiMethod> contributed = new ArrayList<>();
        // Own declarations. getMethods() would add everything the holder inherits, and while
        // Object's methods all fail the @Receiver test and are dropped a moment later, "what does
        // this class contribute" is a question about what it declares.
        final List<PsiMethod> declared = holder instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnMethods()
                : List.of(holder.getMethods());
        for (final PsiMethod method : declared) {
            if (contributes(method)) {
                contributed.add(method);
            }
        }
        return List.copyOf(contributed);
    }

    /**
     * Reports whether a method would be contributed to a receiver.
     *
     * <p>A contribution is {@code public static} and not a constructor; anything else is the holder's own helper and
     * is neither contributed nor checked. Beyond that the three receiver forms decide, and a method marking both the
     * method and a parameter — {@code AW1313} — is refused here rather than attributed to either.
     *
     * <p>The containing class is consulted only for the class-level form, and never for whether it is an extension
     * at all: a {@code public static} method taking a {@code @Receiver} parameter answers {@code true} in a class
     * with no {@code @Extension} on it.
     *
     * @param method the method to test; must not be {@code null}
     * @return {@code true} when the method's own shape makes it a contribution
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    public static boolean contributes(@NotNull final PsiMethod method) {
        Objects.requireNonNull(method, "method");
        if (!method.hasModifierProperty(PsiModifier.PUBLIC)
                || !method.hasModifierProperty(PsiModifier.STATIC)
                || method.isConstructor()) {
            return false;
        }
        if (receiverAnnotationOf(method) != null) {
            // The static form. A declaration that also marks a parameter is AW1313 and contributes
            // nothing, because it names two receivers and picks neither.
            return receiverParameterOf(method) == null;
        }
        if (receiverParameterOf(method) != null) {
            return true;
        }
        // Neither: contributed only when the class named a receiver for all of its methods, in
        // which case parameter zero is it by position.
        final PsiClass holder = method.getContainingClass();
        return holder != null
                && classReceiverOf(holder) != null
                && method.getParameterList().getParametersCount() > 0;
    }

    /**
     * Reports whether a method contributes a {@code static} member to its receiver rather than an instance one.
     *
     * <p>The distinction the call site depends on: a static contribution is written {@code Type.name(...)} and an
     * instance one {@code value.name(...)}.
     *
     * @param method the method to test; must not be {@code null}
     * @return {@code true} when {@code @Receiver} is on the method and on no parameter
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    public static boolean isStaticContribution(@NotNull final PsiMethod method) {
        Objects.requireNonNull(method, "method");
        return receiverAnnotationOf(method) != null && receiverParameterOf(method) == null;
    }

    /**
     * Returns the {@code @Receiver} written on the method itself.
     *
     * @param method the method to read; must not be {@code null}
     * @return the annotation, or {@code null} when the method carries none
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static PsiAnnotation receiverAnnotationOf(@NotNull final PsiMethod method) {
        Objects.requireNonNull(method, "method");
        return WeaveDeclarations.annotation(method, RECEIVER);
    }

    /**
     * Returns the parameter marked {@code @Receiver}.
     *
     * <p>Only parameter zero is examined. A {@code @Receiver} further along is {@code AW1303} in the build, and
     * treating it as the receiver here would describe a rewrite the engine never performs: the receiver is passed
     * through as argument zero, which is where the JVM already put it.
     *
     * @param method the method to read; must not be {@code null}
     * @return the first parameter when it carries {@code @Receiver}, and {@code null} otherwise
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static PsiParameter receiverParameterOf(@NotNull final PsiMethod method) {
        Objects.requireNonNull(method, "method");
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0) {
            return null;
        }
        final PsiAnnotation marked =
                WeaveDeclarations.annotation(parameters[0], RECEIVER);
        return marked == null ? null : parameters[0];
    }

    /**
     * Returns the constants a holder contributes.
     *
     * <p>Read from the class's own fields, for the same reason {@link #contributedBy(PsiClass)} reads its own
     * methods.
     *
     * @param holder the extension class; must not be {@code null}
     * @return the contributed fields, in declaration order and empty when there are none
     * @throws NullPointerException if {@code holder} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public static List<PsiField> constantsOf(@NotNull final PsiClass holder) {
        Objects.requireNonNull(holder, "holder");
        final List<PsiField> contributed = new ArrayList<>();
        final List<PsiField> declared = holder instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnFields()
                : List.of(holder.getFields());
        for (final PsiField field : declared) {
            if (contributesConstant(field)) {
                contributed.add(field);
            }
        }
        return List.copyOf(contributed);
    }

    /**
     * Reports whether a field would be contributed to a receiver.
     *
     * <p>All four conditions have to hold: {@code public static final}, and a {@code @Receiver} that names a type.
     * The class-level receiver does not reach a field, and neither does a bare {@code @Receiver} whose
     * {@code value()} was left at its {@code void.class} default — a field has no parameter zero for either to fall
     * back on. A field carrying {@code @Receiver} that is not {@code public static final} is {@code AW1314} in the
     * build and answers {@code false} here.
     *
     * @param field the field to test; must not be {@code null}
     * @return {@code true} when the field is a contributed constant
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @Contract(pure = true)
    public static boolean contributesConstant(@NotNull final PsiField field) {
        Objects.requireNonNull(field, "field");
        return field.hasModifierProperty(PsiModifier.PUBLIC)
                && field.hasModifierProperty(PsiModifier.STATIC)
                && field.hasModifierProperty(PsiModifier.FINAL)
                && receiverTypeOf(WeaveDeclarations.annotation(field, RECEIVER)) != null;
    }

    /**
     * Returns the receiver a class names for all of its methods.
     *
     * @param holder the class to read; must not be {@code null}
     * @return the type written as {@code @Extension(Type.class)}, or {@code null} when the class carries no
     *         {@code @Extension} or left its {@code value()} at the {@code void.class} default that names none
     * @throws NullPointerException if {@code holder} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static PsiType classReceiverOf(@NotNull final PsiClass holder) {
        Objects.requireNonNull(holder, "holder");
        return receiverTypeOf(WeaveDeclarations.annotation(holder, EXTENSION));
    }

    /**
     * Returns the class a constant is contributed to.
     *
     * @param field the field to read; must not be {@code null}
     * @return the resolved receiver, or {@code null} when the field names none, names one that does not resolve, or
     *         names something other than a class or interface, which is {@code AW1304} in the build
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static PsiClass receiverOf(@NotNull final PsiField field) {
        Objects.requireNonNull(field, "field");
        return receiverTypeOf(WeaveDeclarations.annotation(field, RECEIVER))
                instanceof final PsiClassType declared ? declared.resolve() : null;
    }

    /**
     * Returns the class a method is contributed to.
     *
     * <p>The three forms are consulted in the order a declaration overrides them: the marked parameter first, then
     * the {@code @Receiver} on the method, then the class's own.
     *
     * @param method the method to read; must not be {@code null}
     * @return the resolved receiver, or {@code null} when the method names none, names one that does not resolve, or
     *         names a primitive or an array, neither of which can carry a method
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static PsiClass receiverOf(@NotNull final PsiMethod method) {
        final PsiType type = receiverTypeOf(method);
        // A primitive or an array is AW1304 and can carry no method; resolving only PsiClassType
        // excludes both. A type variable is AW1304 too, but is not excluded here: PsiTypeParameter
        // is itself a PsiClass, so resolve() succeeds and hands the type variable back as a receiver.
        return type instanceof final PsiClassType declared ? declared.resolve() : null;
    }

    /**
     * Returns the receiver type a method declares, in whichever of the three forms it used.
     *
     * @param method the method to read; must not be {@code null}
     * @return the type, or {@code null} when no form applies
     */
    @Contract(pure = true)
    @Nullable
    private static PsiType receiverTypeOf(@NotNull final PsiMethod method) {
        final PsiParameter marked = receiverParameterOf(method);
        if (marked != null) {
            return marked.getType();
        }
        final PsiType named = receiverTypeOf(receiverAnnotationOf(method));
        if (named != null) {
            return named;
        }
        // The class-level form: parameter zero is the receiver by position. Its declared type is
        // read rather than the class's, so a method the build refuses (AW1316) shows the type it
        // actually takes instead of the one it was supposed to.
        final PsiClass holder = method.getContainingClass();
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        return holder != null && classReceiverOf(holder) != null && parameters.length > 0
                ? parameters[0].getType()
                : null;
    }

    /**
     * Returns the type an annotation's {@code value()} names.
     *
     * <p>Reads the declared value and never the default, so the {@code void.class} that both {@code @Extension} and
     * {@code @Receiver} default to answers {@code null} rather than a type: a declaration that wrote nothing named
     * no receiver. A {@code value()} that is not a class literal — a constant reference, or a malformed expression
     * in a file being edited — answers {@code null} as well.
     *
     * @param annotation the annotation to read, or {@code null} when there is none
     * @return the named type, or {@code null} when nothing was written
     */
    @Contract(pure = true)
    @Nullable
    public static PsiType receiverTypeOf(@Nullable final PsiAnnotation annotation) {
        if (annotation == null) {
            return null;
        }
        // findDeclaredAttributeValue, not findAttributeValue: the default is void.class, and a
        // method that asked for the default asked for nothing at all.
        if (!(annotation.findDeclaredAttributeValue(null)
                instanceof final PsiClassObjectAccessExpression literal)) {
            return null;
        }
        return literal.getOperand().getType();
    }
}
