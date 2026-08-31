package de.splatgames.aether.weaver.engine.parse;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads annotations out of a class file.
 *
 * <p>Everything here works on {@link java.lang.classfile.Annotation}, never on an annotation
 * instance, so a weave is read without its class or its target being loaded. An annotation type
 * is named only to take a descriptor from it.
 *
 * <p>Two properties shape every reader below. A class file records only the elements the source
 * actually wrote, so the caller supplies the default it wants; and the values are whatever the
 * file happens to hold, so an element of an unexpected kind yields that same default rather than
 * an exception. Nothing here reports a diagnostic — the caller decides whether a missing or
 * surprising value is worth one.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class Annotations {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private Annotations() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the runtime-visible annotations of a class, field or method.
     *
     * <p>Invisible annotations are not consulted: every annotation this parser looks for is declared
     * {@code RetentionPolicy.RUNTIME}, so one that did not survive into the visible attribute is not
     * one of them.
     *
     * @param element the class, field or method to read; must not be {@code null}
     * @return the annotations in declaration order, or an empty list when the attribute is absent
     * @throws NullPointerException if {@code element} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    static List<Annotation> on(@NotNull final AttributedElement element) {
        Objects.requireNonNull(element, "element");
        return element.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(RuntimeVisibleAnnotationsAttribute::annotations)
                .orElseGet(List::of);
    }

    /**
     * Finds the first annotation of a type.
     *
     * <p>The {@link Class} is used only to obtain a descriptor to compare against, which is what
     * keeps the match a string comparison instead of a reflective lookup on the annotated class.
     *
     * @param annotations the annotations to search; must not be {@code null}
     * @param type        the annotation type wanted; must not be {@code null}
     * @return the annotation, or {@code null} when the list holds none of that type
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    static @Nullable Annotation find(@NotNull final List<Annotation> annotations,
                                     @NotNull final Class<? extends java.lang.annotation.Annotation> type) {
        Objects.requireNonNull(annotations, "annotations");
        final ClassDesc wanted = ClassDesc.ofDescriptor(
                Objects.requireNonNull(type, "type").descriptorString());
        for (final Annotation annotation : annotations) {
            if (annotation.classSymbol().equals(wanted)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Finds an annotation that may have been written once or several times.
     *
     * <p>A compiler rewrites two or more occurrences of a repeatable annotation into one container,
     * so both spellings have to be read. Flattening them here is what makes one occurrence and three
     * the same shape to every caller.
     *
     * <p>The single form is looked for first and wins outright, so a class file carrying both it and
     * a container is read as though the container were absent. Anything in the container's array
     * that is not an annotation is skipped.
     *
     * @param annotations the annotations to search; must not be {@code null}
     * @param type        the repeatable annotation type; must not be {@code null}
     * @param container   the type holding its repetitions
     * @return the occurrences in the order the file records them, or an empty list when there are
     *         none
     * @throws NullPointerException if {@code annotations} or {@code type} is {@code null}, or if
     *                              {@code container} is {@code null} and no single occurrence was
     *                              found
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    static List<Annotation> findRepeated(@NotNull final List<Annotation> annotations,
                                         @NotNull final Class<? extends java.lang.annotation.Annotation> type,
                                         @NotNull final Class<? extends java.lang.annotation.Annotation> container) {
        final Annotation single = find(annotations, type);
        if (single != null) {
            return List.of(single);
        }
        final Annotation repeated = find(annotations, container);
        if (repeated == null) {
            return List.of();
        }
        final List<Annotation> found = new ArrayList<>();
        for (final AnnotationValue value : array(repeated, "value")) {
            if (value instanceof AnnotationValue.OfAnnotation nested) {
                found.add(nested.annotation());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Reports whether an element was written out.
     *
     * <p>The only way to tell an element left at its declared default from one written with that
     * same value, since a class file records neither the default nor the fact that it applied. An
     * annotation element whose default is a sentinel has to be read through this rather than through
     * a fallback.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return whether the annotation records that element
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    static boolean has(@NotNull final Annotation annotation, @NotNull final String name) {
        return element(annotation, name) != null;
    }

    /**
     * Returns one element's value as the file records it.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return the value, or {@code null} when the element was not written
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    static @Nullable AnnotationValue element(@NotNull final Annotation annotation,
                                             @NotNull final String name) {
        Objects.requireNonNull(annotation, "annotation");
        Objects.requireNonNull(name, "name");
        for (final AnnotationElement candidate : annotation.elements()) {
            if (candidate.name().stringValue().equals(name)) {
                return candidate.value();
            }
        }
        return null;
    }

    /**
     * Returns a string element.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @param fallback   the value to use instead; must not be {@code null}
     * @return the string, or {@code fallback} when the element was not written or is not a string
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    static String stringOr(@NotNull final Annotation annotation,
                           @NotNull final String name,
                           @NotNull final String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return element(annotation, name) instanceof AnnotationValue.OfString value
                ? value.stringValue()
                : fallback;
    }

    /**
     * Returns an integer element.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @param fallback   the value to use instead
     * @return the integer, or {@code fallback} when the element was not written or is not an integer
     * @throws NullPointerException if {@code annotation} or {@code name} is {@code null}
     */
    @Contract(pure = true)
    static int intOr(@NotNull final Annotation annotation,
                     @NotNull final String name,
                     final int fallback) {
        return element(annotation, name) instanceof AnnotationValue.OfInt value
                ? value.intValue()
                : fallback;
    }

    /**
     * Returns a boolean element.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @param fallback   the value to use instead
     * @return the boolean, or {@code fallback} when the element was not written or is not a boolean
     * @throws NullPointerException if {@code annotation} or {@code name} is {@code null}
     */
    @Contract(pure = true)
    static boolean booleanOr(@NotNull final Annotation annotation,
                             @NotNull final String name,
                             final boolean fallback) {
        return element(annotation, name) instanceof AnnotationValue.OfBoolean value
                ? value.booleanValue()
                : fallback;
    }

    /**
     * Returns an enum element, resolved against the constants this runtime declares.
     *
     * <p>A constant name the given enum does not declare falls back rather than throwing, which is
     * what a class file compiled against a different version of the annotation would carry.
     *
     * @param <E>        the enum type
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @param type       the enum whose constants the recorded name is matched against; must not be
     *                   {@code null}
     * @param fallback   the constant to use instead; must not be {@code null}
     * @return the constant, or {@code fallback} when the element was not written, is not an enum
     *         value, or names a constant {@code type} does not declare
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    static <E extends Enum<E>> E enumOr(@NotNull final Annotation annotation,
                                        @NotNull final String name,
                                        @NotNull final Class<E> type,
                                        @NotNull final E fallback) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fallback, "fallback");
        if (!(element(annotation, name) instanceof AnnotationValue.OfEnum value)) {
            return fallback;
        }
        final String constant = value.constantName().stringValue();
        for (final E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(constant)) {
                return candidate;
            }
        }
        return fallback;
    }

    /**
     * Returns an array element's entries.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return the entries, or an empty list when the element was not written or is not an array
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    static List<AnnotationValue> array(@NotNull final Annotation annotation,
                                       @NotNull final String name) {
        return element(annotation, name) instanceof AnnotationValue.OfArray value
                ? value.values()
                : List.of();
    }

    /**
     * Returns the string entries of an array element.
     *
     * <p>An entry that is not a string is dropped, so the result can be shorter than the array.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return the strings in order, or an empty list when the element was not written
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    static List<String> strings(@NotNull final Annotation annotation, @NotNull final String name) {
        final List<String> found = new ArrayList<>();
        for (final AnnotationValue value : array(annotation, name)) {
            if (value instanceof AnnotationValue.OfString string) {
                found.add(string.stringValue());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns the class-literal entries of an array element, as descriptors.
     *
     * <p>Descriptors rather than {@link Class} objects: a weave may name a target that this runtime
     * cannot load, and resolving it here would refuse the very case the parser exists to model.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return the descriptors in order, or an empty list when the element was not written
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    static List<ClassDesc> classes(@NotNull final Annotation annotation,
                                   @NotNull final String name) {
        final List<ClassDesc> found = new ArrayList<>();
        for (final AnnotationValue value : array(annotation, name)) {
            if (value instanceof AnnotationValue.OfClass type) {
                found.add(type.classSymbol());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns the annotation entries of an array element.
     *
     * <p>An entry that is not an annotation is dropped, so the result can be shorter than the array.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return the nested annotations in order, or an empty list when the element was not written
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    static List<Annotation> nested(@NotNull final Annotation annotation,
                                   @NotNull final String name) {
        final List<Annotation> found = new ArrayList<>();
        for (final AnnotationValue value : array(annotation, name)) {
            if (value instanceof AnnotationValue.OfAnnotation inner) {
                found.add(inner.annotation());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns a single nested annotation.
     *
     * <p>The counterpart to {@link #nested(Annotation, String)} for an element declared as one
     * annotation rather than an array of them; the two spellings are what tell an {@code @Inject}'s
     * {@code at} from a {@code @Redirect}'s.
     *
     * @param annotation the annotation to inspect; must not be {@code null}
     * @param name       the element's name; must not be {@code null}
     * @return the nested annotation, or {@code null} when the element was not written or is not a
     *         single annotation
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    static @Nullable Annotation nestedOne(@NotNull final Annotation annotation,
                                          @NotNull final String name) {
        return element(annotation, name) instanceof AnnotationValue.OfAnnotation inner
                ? inner.annotation()
                : null;
    }
}
