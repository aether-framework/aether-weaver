package de.splatgames.aether.weaver.processor;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads annotations off elements without loading the annotation classes.
 *
 * <p>An annotation processor cannot call {@code Element.getAnnotation} for anything whose type may
 * be absent from the compile classpath. It can call {@code getAnnotation} for an element whose
 * value is a class literal — the call itself succeeds and returns a proxy — but a
 * {@code Class}-returning accessor on that proxy throws
 * {@link javax.lang.model.type.MirroredTypeException} rather than answering, since the literal may
 * itself name a type absent from the classpath. Everything here works on the mirror form instead:
 * names are compared as strings and values are unwrapped by {@code instanceof}, so a value of an
 * unexpected shape reads as absent rather than as a {@link ClassCastException} inside the compiler.
 *
 * <p>The distinction these accessors preserve is between an element the author wrote and one the
 * compiler would default. {@link AnnotationMirror#getElementValues()} holds only what the source
 * spells out, so an omitted element is invisible here whatever its declared default. Most
 * accessors that read a typed value take an explicit fallback or return {@code null}, and
 * {@link #wrote(AnnotationMirror, String)} answers the question directly for a caller that needs
 * to keep the distinction itself. {@link #arrayOf(AnnotationMirror, String)} and
 * {@link #stringsOf(AnnotationMirror, String)} do not: both return an empty list for an omitted
 * element, which is indistinguishable from one written empty — a caller that needs to tell the two
 * apart calls {@link #wrote(AnnotationMirror, String)} first.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Anchors {

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private Anchors() {
        throw new AssertionError("no instances");
    }

    /**
     * Finds the annotation of a given type written directly on an element.
     *
     * <p>Only the element's own annotations are searched: an annotation inherited from a
     * superclass, or one nested inside a container such as a repeatable annotation's
     * {@code Container}, is not found here.
     *
     * @param element       the element to search; must not be {@code null}
     * @param qualifiedName the annotation type's fully qualified name; must not be {@code null}
     * @return the mirror, or {@code null} when the element carries no such annotation
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static AnnotationMirror mirrorOf(@NotNull final Element element,
                                            @NotNull final String qualifiedName) {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        for (final AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (nameOf(mirror).equals(qualifiedName)) {
                return mirror;
            }
        }
        return null;
    }

    /**
     * Reports the fully qualified name of an annotation's type.
     *
     * <p>For a nested annotation type this is the canonical name, with a dot before the nested
     * part rather than a dollar sign: a repeatable annotation's container reads as
     * {@code de.splatgames.aether.weaver.api.Inject.Container}. That is the spelling the name
     * constants in this package are written in, so the comparison against them is exact.
     *
     * @param mirror the annotation to name; must not be {@code null}
     * @return the annotation type's fully qualified name
     * @throws NullPointerException if {@code mirror} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String nameOf(@NotNull final AnnotationMirror mirror) {
        Objects.requireNonNull(mirror, "mirror");
        return ((TypeElement) mirror.getAnnotationType().asElement())
                .getQualifiedName().toString();
    }

    /**
     * Finds the value the source wrote for one annotation element.
     *
     * <p>An element the source omitted is absent here even when the annotation declares a default
     * for it, because the mirror records only what was written. Callers that need the effective
     * value supply the default themselves.
     *
     * <p>The returned value is also what an {@link Anchor} needs to narrow a diagnostic onto the
     * literal rather than onto the whole annotation, which is why a {@code null} return has to be
     * tolerable: {@link Anchor#at(Element, AnnotationMirror, AnnotationValue)} accepts it and
     * falls back.
     *
     * @param mirror the annotation to read, or {@code null}
     * @param name   the annotation element's name, as declared; must not be {@code null}
     * @return the written value, or {@code null} when {@code mirror} is {@code null} or the source
     *         did not write that element
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static AnnotationValue valueOf(@Nullable final AnnotationMirror mirror,
                                          @NotNull final String name) {
        Objects.requireNonNull(name, "name");
        if (mirror == null) {
            return null;
        }
        for (final Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Reports whether the source wrote a given annotation element out.
     *
     * <p>Distinguishes an element left to its default from one written with the same value as its
     * default; the two are indistinguishable to anything reading the effective value.
     *
     * @param mirror the annotation to read, or {@code null}
     * @param name   the annotation element's name, as declared; must not be {@code null}
     * @return {@code true} when the source wrote that element, {@code false} when it did not or
     *         {@code mirror} is {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    public static boolean wrote(@Nullable final AnnotationMirror mirror,
                                @NotNull final String name) {
        return valueOf(mirror, name) != null;
    }

    /**
     * Reads an annotation element declared as an enum type, as the constant's simple name.
     *
     * <p>The name is returned rather than the constant, because resolving the constant would need
     * the enum class on the processor's own classpath. Callers compare it against a string
     * literal, or hand it to {@code Enum.valueOf} of an enum this module does depend on.
     *
     * @param mirror the annotation to read, or {@code null}
     * @param name   the annotation element's name, as declared; must not be {@code null}
     * @return the constant's simple name, or {@code null} when the element was not written or its
     *         value is not an enum constant
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static String enumOf(@Nullable final AnnotationMirror mirror,
                                @NotNull final String name) {
        final AnnotationValue value = valueOf(mirror, name);
        if (value == null || !(value.getValue() instanceof Element constant)) {
            return null;
        }
        return constant.getSimpleName().toString();
    }

    /**
     * Reads an annotation element declared as a {@code String}.
     *
     * @param mirror   the annotation to read, or {@code null}
     * @param name     the annotation element's name, as declared; must not be {@code null}
     * @param fallback what to return when the element was not written or is not a string; may be
     *                 {@code null}
     * @return the written string, or {@code fallback}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    public static String stringOf(@Nullable final AnnotationMirror mirror,
                                  @NotNull final String name,
                                  final String fallback) {
        final AnnotationValue value = valueOf(mirror, name);
        return value != null && value.getValue() instanceof String text ? text : fallback;
    }

    /**
     * Reads an annotation element declared as a {@code boolean}.
     *
     * @param mirror   the annotation to read, or {@code null}
     * @param name     the annotation element's name, as declared; must not be {@code null}
     * @param fallback what to return when the element was not written or is not a boolean
     * @return the written flag, or {@code fallback}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    public static boolean booleanOf(@Nullable final AnnotationMirror mirror,
                                    @NotNull final String name,
                                    final boolean fallback) {
        final AnnotationValue value = valueOf(mirror, name);
        return value != null && value.getValue() instanceof Boolean flag ? flag : fallback;
    }

    /**
     * Reads an annotation element declared as an array, entry by entry.
     *
     * <p>The entries are left as {@link AnnotationValue} rather than unwrapped, because an anchor
     * built from one of them underlines that entry alone — which is how a diagnostic about the
     * third class literal in a list points at the third literal.
     *
     * <p>A value that is not a list is returned as the single entry, rather than as nothing. The
     * cast on the list is unchecked because {@link AnnotationValue#getValue()} is declared to
     * return {@code Object}; every entry of an array-typed value is an {@link AnnotationValue} by
     * that method's own contract.
     *
     * @param mirror the annotation to read, or {@code null}
     * @param name   the annotation element's name, as declared; must not be {@code null}
     * @return the entries in source order, or an empty list when the element was not written or
     *         was written as an empty array — the two are indistinguishable here
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    @SuppressWarnings("unchecked")
    public static List<AnnotationValue> arrayOf(@Nullable final AnnotationMirror mirror,
                                                @NotNull final String name) {
        final AnnotationValue value = valueOf(mirror, name);
        if (value == null) {
            return List.of();
        }
        final Object raw = value.getValue();
        if (!(raw instanceof List<?> entries)) {
            return List.of(value);
        }
        return List.copyOf((List<AnnotationValue>) entries);
    }

    /**
     * Reads an annotation element declared as a {@code String[]}, unwrapped.
     *
     * <p>An entry whose value is not a string is dropped silently, so the result can be shorter
     * than {@link #arrayOf(AnnotationMirror, String)} reports for the same element. Positions are
     * lost with the unwrapping: a check that has to underline one entry reads the array form
     * instead.
     *
     * @param mirror the annotation to read, or {@code null}
     * @param name   the annotation element's name, as declared; must not be {@code null}
     * @return the string entries in source order, or an empty list when the element was not
     *         written
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    public static List<String> stringsOf(@Nullable final AnnotationMirror mirror,
                                         @NotNull final String name) {
        final List<String> strings = new ArrayList<>();
        for (final AnnotationValue entry : arrayOf(mirror, name)) {
            if (entry.getValue() instanceof String text) {
                strings.add(text);
            }
        }
        return List.copyOf(strings);
    }
}
