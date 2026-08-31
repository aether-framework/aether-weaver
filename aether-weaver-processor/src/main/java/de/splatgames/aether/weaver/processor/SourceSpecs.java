package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the engine's model of one injection declaration out of the compiler's model of it.
 *
 * <p>Two things consume what is built here: {@code PointChecks}, which needs a specification to
 * hand the resolver, and the manifest, which records one entry per specification. Both are driven
 * from {@code WeaveProcessor}, which builds the same specification twice — once per target while
 * checking points and once more while recording — so nothing may be cached in it and nothing it
 * does may be observable twice.
 *
 * <p>Nothing is reported from here. The failure mode is an exception instead: the components are
 * copied out of the annotation as written and handed to {@link InjectorSpec}, whose constructor
 * refuses a declaration that could never be satisfied. That exception is not caught anywhere in
 * this package and reaches the compiler as {@code An annotation processor threw an uncaught
 * exception}, which ends the compilation with a stack trace and no position in the user's source.
 *
 * <p>Three things the annotation says are not carried into the specification: its {@code slice}
 * declarations, its {@code @Local} captures, and whether the handler captures the matched call's
 * result. A compile-time check therefore sees the declaration as unsliced, with no local bindings.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class SourceSpecs {

    /**
     * The identifier given to a declaration whose {@code id} is empty or unwritten.
     *
     * <p>{@link InjectorSpec} refuses a blank identifier, and the weave class reader the engine
     * uses derives one from the handler's description and kind instead, so a declaration written
     * once is named one thing in the manifest and another in a weave-time diagnostic.
     */
    private static final String UNNAMED = "unnamed";

    /**
     * Refuses instantiation; the single entry point is static.
     *
     * @throws AssertionError always
     */
    private SourceSpecs() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds the specification for one injection annotation on one handler.
     *
     * <p>The kind is {@link InjectorKind#REDIRECT} for a {@code @Redirect} and
     * {@link InjectorKind#INJECT} for anything else, so a {@code @Wrap} is built — and recorded in
     * the manifest — as an inject. {@link InjectorKind#WRAP} is never produced here.
     *
     * <p>{@code require} and {@code allow} are the numbers the source wrote, and an element the
     * source omitted reads as {@code 0}. An omitted {@code require} is therefore not the one match
     * the engine's own reading of the same annotation makes it, and the manifest records the
     * {@code 0}.
     *
     * <p>Answers {@code null} for a declaration whose {@code at} array is empty, which is the one
     * way a well-formed annotation produces no specification. Nothing is reported for it: the
     * declaration is left out of the manifest, its points are not checked, and the first sign of it
     * is the weaver refusing the same declaration as {@code AW1043} when it reads the compiled
     * weave class.
     *
     * @param handler   the handler the annotation is written on; must not be {@code null}
     * @param injection the {@code @Inject}, {@code @Redirect} or {@code @Wrap} mirror; must not be
     *                  {@code null}
     * @param selector  the already-parsed target-method selector; must not be {@code null}
     * @param elements  the element utilities, used for the weave's binary name; must not be
     *                  {@code null}
     * @return the specification, or {@code null} when the declaration named no injection point
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if an {@code @At} names an ordinal below {@code -1}, or if
     *                                  {@code require} or {@code allow} is negative, or if
     *                                  {@code allow} is non-zero and below {@code require}
     */
    @Nullable
    static InjectorSpec of(@NotNull final ExecutableElement handler,
                           @NotNull final AnnotationMirror injection,
                           @NotNull final MemberSelector selector,
                           @NotNull final Elements elements) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(injection, "injection");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(elements, "elements");

        final List<PointSpec> points = pointsOf(injection);
        if (points.isEmpty()) {
            return null;
        }
        final boolean redirect = WeaveProcessor.REDIRECT.equals(Anchors.nameOf(injection));
        final String id = Anchors.stringOf(injection, "id", "");

        return new InjectorSpec(
                redirect ? InjectorKind.REDIRECT : InjectorKind.INJECT,
                handlerRefOf(handler, elements),
                Anchors.stringOf(injection, "method", ""),
                selector,
                points,
                List.of(),
                id.isBlank() ? UNNAMED : id,
                intOf(injection, "require"),
                intOf(injection, "allow"),
                Anchors.stringOf(injection, "group", ""),
                List.of());
    }

    /**
     * Reads the {@code at} element as a list of points.
     *
     * <p>An entry whose value is not an annotation is dropped, so the result can be shorter than
     * what the source wrote. {@link Anchors#arrayOf(AnnotationMirror, String)} answers a
     * single-entry list for a value that is not an array, which is what lets one reader serve all
     * three annotations: {@code @Inject} declares {@code at} as an array and {@code @Redirect} and
     * {@code @Wrap} declare it as one {@code At}.
     *
     * @param injection the injection annotation's mirror; must not be {@code null}
     * @return the points in source order, possibly empty
     */
    @NotNull
    private static List<PointSpec> pointsOf(@NotNull final AnnotationMirror injection) {
        final List<PointSpec> points = new ArrayList<>();
        for (final AnnotationValue value : Anchors.arrayOf(injection, "at")) {
            if (value.getValue() instanceof AnnotationMirror at) {
                points.add(pointOf(at));
            }
        }
        return points;
    }

    /**
     * Builds one point from an {@code @At}.
     *
     * <p>A {@code custom} that is not blank decides the identifier and {@code value} is not read at
     * all; otherwise the built-in constant named by {@code value} is used, or {@link Point#HEAD}
     * when the source omitted it. A blank {@code custom} is the same as none.
     *
     * <p>Every element is read as the source wrote it, and an element the source omitted takes the
     * annotation's own default rather than the builder's: {@code ordinal} falls back to {@code -1}
     * and {@code by} to {@code 0}, while {@code shift} and {@code access} are simply not set, which
     * leaves them at {@code NONE} and {@code ANY}. A blank {@code target} and a blank {@code slice}
     * are not set either, so a point written {@code target = ""} is indistinguishable here from one
     * that named no target.
     *
     * <p>The target is set as text and left unparsed, which is what the injection point that uses
     * it expects; its grammar is the point's own and is not decided at this stage.
     *
     * @param at the {@code @At} mirror; must not be {@code null}
     * @return the point
     * @throws IllegalArgumentException if the ordinal written is below {@code -1}
     */
    @NotNull
    private static PointSpec pointOf(@NotNull final AnnotationMirror at) {
        final String custom = Anchors.stringOf(at, "custom", "");
        final String named = Anchors.enumOf(at, "value");
        // A written `custom` wins, because that is the only way to name a point the enum does not
        // have; otherwise an omitted value means the annotation's own default.
        final PointSpec.Builder builder = !custom.isBlank()
                ? PointSpec.named(custom)
                : PointSpec.builtIn(named == null ? Point.HEAD : Point.valueOf(named));

        final String target = Anchors.stringOf(at, "target", "");
        if (!target.isBlank()) {
            builder.target(target);
        }
        builder.ordinal(intOrDefault(at, "ordinal", -1))
                .by(intOf(at, "by"));

        final String shift = Anchors.enumOf(at, "shift");
        if (shift != null) {
            builder.shift(At.Shift.valueOf(shift));
        }
        final String access = Anchors.enumOf(at, "access");
        if (access != null) {
            builder.access(At.Access.valueOf(access));
        }
        final String slice = Anchors.stringOf(at, "slice", "");
        if (!slice.isBlank()) {
            builder.slice(slice);
        }
        return builder.build();
    }

    /**
     * Names the handler as the runtime will have to find it: owner, name and erased signature.
     *
     * <p>The owner is the handler's immediately enclosing element cast to a type, so a handler
     * declared anywhere but directly in a class would fail here rather than be reported; an
     * annotation whose {@code @Target} is {@code METHOD} cannot reach such a place.
     *
     * @param handler  the handler method; must not be {@code null}
     * @param elements the element utilities, used for the owner's binary name; must not be
     *                 {@code null}
     * @return the reference, whose signature is subject to the substitutions
     *         {@link #descriptorOf(TypeMirror)} makes
     */
    @NotNull
    private static HandlerRef handlerRefOf(@NotNull final ExecutableElement handler,
                                           @NotNull final Elements elements) {
        final TypeElement weave = (TypeElement) handler.getEnclosingElement();
        final List<ClassDesc> parameters = new ArrayList<>();
        for (final VariableElement parameter : handler.getParameters()) {
            parameters.add(descriptorOf(parameter.asType()));
        }
        return new HandlerRef(
                ClassDesc.of(elements.getBinaryName(weave).toString()),
                handler.getSimpleName().toString(),
                MethodTypeDesc.of(descriptorOf(handler.getReturnType()), parameters),
                flagsOf(handler));
    }

    /**
     * Renders one type as a {@link ClassDesc}.
     *
     * <p>Exact for the primitives, {@code void}, an array and a declared type. Two cases are not,
     * and both change the descriptor the runtime would have to match against the compiled handler.
     *
     * <ul>
     *   <li>A type variable is rendered as its upper bound, which is the erasure only while the
     *       bound is single. A variable declared {@code <T extends CharSequence & Runnable>} has an
     *       intersection type as its upper bound, which no case matches, so it falls to the default
     *       and is rendered {@code Ljava/lang/Object;} where the compiler writes
     *       {@code Ljava/lang/CharSequence;} into the class file.
     *   <li>Every other kind the switch does not name — an unresolved type among them — is rendered
     *       {@code Ljava/lang/Object;} rather than refused.
     * </ul>
     *
     * @param type the type to render; must not be {@code null}
     * @return its descriptor
     */
    @Contract(pure = true)
    @NotNull
    private static ClassDesc descriptorOf(@NotNull final TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> ClassDesc.ofDescriptor("Z");
            case BYTE -> ClassDesc.ofDescriptor("B");
            case SHORT -> ClassDesc.ofDescriptor("S");
            case INT -> ClassDesc.ofDescriptor("I");
            case LONG -> ClassDesc.ofDescriptor("J");
            case CHAR -> ClassDesc.ofDescriptor("C");
            case FLOAT -> ClassDesc.ofDescriptor("F");
            case DOUBLE -> ClassDesc.ofDescriptor("D");
            case VOID -> ClassDesc.ofDescriptor("V");
            case ARRAY -> descriptorOf(((ArrayType) type).getComponentType()).arrayType();
            // A type variable erases to its first bound, which is what the class file holds.
            // Leaving it as "T" would build a descriptor for a class called T.
            case TYPEVAR -> descriptorOf(((TypeVariable) type).getUpperBound());
            case DECLARED -> ClassDesc.of(binaryNameOf((DeclaredType) type));
            default -> ClassDesc.ofDescriptor("Ljava/lang/Object;");
        };
    }

    /**
     * Assembles a declared type's binary name from the source nesting.
     *
     * <p>Each enclosing type's simple name is prepended with a {@code $}, and the package, where
     * there is one, with a dot. The result is the form {@link ClassDesc#of(String)} accepts.
     *
     * @param type the type to name; must not be {@code null}
     * @return the binary name
     */
    @Contract(pure = true)
    @NotNull
    private static String binaryNameOf(@NotNull final DeclaredType type) {
        final TypeElement element = (TypeElement) type.asElement();
        final StringBuilder name = new StringBuilder(element.getSimpleName());
        javax.lang.model.element.Element enclosing = element.getEnclosingElement();
        while (enclosing instanceof TypeElement outer) {
            name.insert(0, outer.getSimpleName() + "$");
            enclosing = outer.getEnclosingElement();
        }
        final String packageName = packageOf(element);
        return packageName.isEmpty() ? name.toString() : packageName + '.' + name;
    }

    /**
     * Walks out to the package a type is declared in.
     *
     * @param element the type; must not be {@code null}
     * @return the package's qualified name, or the empty string for the unnamed package and for a
     *         type whose enclosing elements run out before a package is reached
     */
    @Contract(pure = true)
    @NotNull
    private static String packageOf(@NotNull final TypeElement element) {
        javax.lang.model.element.Element enclosing = element.getEnclosingElement();
        while (enclosing != null
                && !(enclosing instanceof javax.lang.model.element.PackageElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        return enclosing instanceof javax.lang.model.element.PackageElement pkg
                ? pkg.getQualifiedName().toString()
                : "";
    }

    /**
     * Translates the handler's modifiers into the access flags the plan reads.
     *
     * <p>Four modifiers are carried and no others: a handler declared {@code final},
     * {@code synchronized} or {@code strictfp} loses that in the specification. A package-private
     * handler carries none of the three visibility flags, which is how package-private is spelled
     * in a class file as well.
     *
     * @param handler the handler method; must not be {@code null}
     * @return the flags, in the fixed order {@code STATIC}, {@code PRIVATE}, {@code PUBLIC},
     *         {@code PROTECTED}
     */
    @Contract(pure = true)
    @NotNull
    private static Set<AccessFlag> flagsOf(@NotNull final ExecutableElement handler) {
        final Set<AccessFlag> flags = new LinkedHashSet<>();
        final Set<Modifier> modifiers = handler.getModifiers();
        if (modifiers.contains(Modifier.STATIC)) {
            flags.add(AccessFlag.STATIC);
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            flags.add(AccessFlag.PRIVATE);
        }
        if (modifiers.contains(Modifier.PUBLIC)) {
            flags.add(AccessFlag.PUBLIC);
        }
        if (modifiers.contains(Modifier.PROTECTED)) {
            flags.add(AccessFlag.PROTECTED);
        }
        return flags;
    }

    /**
     * Reads an {@code int} element, treating an omitted one as {@code 0}.
     *
     * @param mirror the annotation to read; must not be {@code null}
     * @param name   the element's name; must not be {@code null}
     * @return the written value, or {@code 0} when the source did not write that element or wrote
     *         something that is not an {@code int}
     */
    @Contract(pure = true)
    private static int intOf(@NotNull final AnnotationMirror mirror, @NotNull final String name) {
        return intOrDefault(mirror, name, 0);
    }

    /**
     * Reads an {@code int} element with a caller-chosen fallback.
     *
     * <p>The fallback stands for an element the source omitted, which an annotation mirror does not
     * record whatever default the annotation declares, so the caller supplies the annotation's own
     * default here.
     *
     * @param mirror   the annotation to read; must not be {@code null}
     * @param name     the element's name; must not be {@code null}
     * @param fallback what to answer when the element was not written or is not an {@code int}
     * @return the written value, or {@code fallback}
     */
    @Contract(pure = true)
    private static int intOrDefault(@NotNull final AnnotationMirror mirror,
                                    @NotNull final String name,
                                    final int fallback) {
        final AnnotationValue value = Anchors.valueOf(mirror, name);
        return value != null && value.getValue() instanceof Integer number ? number : fallback;
    }
}
