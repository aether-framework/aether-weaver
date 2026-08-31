package de.splatgames.aether.weaver.engine.stamp;

import de.splatgames.aether.weaver.api.Woven;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the {@link Woven} annotation that goes onto a woven class.
 *
 * <p>The readable half of the stamp, and the only one that carries plugin coordinates and plugin
 * metadata: the {@code AetherWeave} attribute holds neither. How much is written is decided by the
 * detail level the caller passes: nothing at all, the plan, or the plan with its declarations
 * listed up to {@link WeaveRecord#MAX_ANNOTATION_ENTRIES}.
 *
 * <p>The three types are named as descriptors rather than through the annotation classes, since
 * what is being built is a class file structure and not a live annotation.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WovenAnnotationWriter {

    /** The annotation type being written. */
    private static final ClassDesc WOVEN =
            ClassDesc.of("de.splatgames.aether.weaver.api.Woven");

    /** The nested annotation type of one listed declaration. */
    private static final ClassDesc WOVEN_ENTRY =
            ClassDesc.of("de.splatgames.aether.weaver.api.Woven$Entry");

    /** The enum the {@code detail} element is written as. */
    private static final ClassDesc WOVEN_DETAIL =
            ClassDesc.of("de.splatgames.aether.weaver.api.Woven$Detail");

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WovenAnnotationWriter() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds the annotation for a record at the given detail level.
     *
     * <p>{@link Woven.Detail#NONE} produces no annotation rather than an empty one, so a class
     * stamped under it carries the attribute and nothing a reflective reader can see.
     * {@link Woven.Detail#SUMMARY} writes everything except the per-declaration listing, and
     * {@link Woven.Detail#FULL} adds that listing, capped at
     * {@link WeaveRecord#MAX_ANNOTATION_ENTRIES}. The truncation flag is set only when the listing
     * was actually cut, which under {@link Woven.Detail#SUMMARY} is never, since no listing was
     * promised.
     *
     * <p>The {@code detail} element records the level the annotation was written at, so a reader
     * can tell an absent listing from an empty one.
     *
     * @param record what was done to the class; must not be {@code null}
     * @param detail how much to write; must not be {@code null}
     * @return the annotation, or empty under {@link Woven.Detail#NONE}
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static Optional<Annotation> annotation(@NotNull final WeaveRecord record,
                                                  @NotNull final Woven.Detail detail) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(detail, "detail");
        if (detail == Woven.Detail.NONE) {
            return Optional.empty();
        }

        final boolean full = detail == Woven.Detail.FULL;
        final boolean truncated = full && record.exceedsAnnotationCap();

        final List<AnnotationElement> elements = new ArrayList<>();
        elements.add(AnnotationElement.of("schema", AnnotationValue.ofInt(1)));
        elements.add(AnnotationElement.of("weaver",
                AnnotationValue.ofString(record.weaverVersion())));
        elements.add(AnnotationElement.of("fingerprint",
                AnnotationValue.ofString(record.fingerprint())));
        elements.add(AnnotationElement.of("detail",
                AnnotationValue.ofEnum(WOVEN_DETAIL, detail.name())));
        elements.add(AnnotationElement.of("flags",
                AnnotationValue.ofInt(record.flags(truncated))));
        elements.add(AnnotationElement.of("weaves", strings(record.weaves())));
        elements.add(AnnotationElement.of("plugins", strings(record.plugins())));
        elements.add(AnnotationElement.of("extra", strings(record.flatMetadata())));
        if (full) {
            elements.add(AnnotationElement.of("entries", entries(record)));
        }
        return Optional.of(Annotation.of(WOVEN, elements));
    }

    /**
     * Wraps strings as an annotation array value.
     *
     * @param values the strings to wrap; must not be {@code null}
     * @return the array value, empty when {@code values} is
     */
    @Contract(pure = true)
    @NotNull
    private static AnnotationValue strings(@NotNull final List<String> values) {
        final List<AnnotationValue> elements = new ArrayList<>(values.size());
        values.forEach(value -> elements.add(AnnotationValue.ofString(value)));
        return AnnotationValue.ofArray(elements);
    }

    /**
     * Builds the listing of declarations, stopping at the cap.
     *
     * <p>The first {@link WeaveRecord#MAX_ANNOTATION_ENTRIES} in the record's own order are kept.
     * The cap bounds what the annotation costs on a class that many declarations aim at; the
     * attribute keeps the rest.
     *
     * @param record what was done to the class; must not be {@code null}
     * @return the array of {@code Woven.Entry} annotations
     */
    @Contract(pure = true)
    @NotNull
    private static AnnotationValue entries(@NotNull final WeaveRecord record) {
        final List<AnnotationValue> elements = new ArrayList<>();
        for (final WeaveRecord.Entry entry : record.entries()) {
            if (elements.size() >= WeaveRecord.MAX_ANNOTATION_ENTRIES) {
                break;
            }
            elements.add(AnnotationValue.ofAnnotation(Annotation.of(WOVEN_ENTRY, List.of(
                    AnnotationElement.of("weave", AnnotationValue.ofString(entry.weave())),
                    AnnotationElement.of("kind", AnnotationValue.ofString(entry.kind())),
                    AnnotationElement.of("handler", AnnotationValue.ofString(entry.handler())),
                    AnnotationElement.of("target", AnnotationValue.ofString(entry.target()))))));
        }
        return AnnotationValue.ofArray(elements);
    }
}
