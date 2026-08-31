package de.splatgames.aether.weaver.engine.stamp;

import de.splatgames.aether.weaver.api.Woven;
import de.splatgames.aether.weaver.api.WovenInfo;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WeaveRecordTest {

    private static final WeaveRecord RECORD = new WeaveRecord(
            "0.1.0", "a".repeat(64),
            List.of("com.acme.Audit", "com.acme.Trace"),
            List.of("acme:1.4.0", "corp:2.0.0"),
            Map.of("acme:mode", "strict", "corp:level", "2"),
            List.of(new WeaveRecord.Entry("com.acme.Audit", "inject", "onCharge()V", "charge()"),
                    new WeaveRecord.Entry("com.acme.Trace", "redirect", "wrap()V", "charge()")),
            false, false);

    @Nested
    @DisplayName("the annotation's shape cannot let it lie")
    class Shape {

        @Test
        @DisplayName("@Woven is NOT @Inherited")
        void notInherited() {
            assertThat(Woven.class.isAnnotationPresent(Inherited.class))
                    .as("an inherited annotation is reported for subclasses that never declared "
                            + "it, so every subclass of a woven class would claim a provenance it "
                            + "does not have — not conservative, simply false")
                    .isFalse();
        }

        @Test
        @DisplayName("a subclass of a woven class reports itself as not woven")
        void subclassIsNotWoven() {
            assertThat(WovenInfo.of(Stamped.class))
                    .as("the fixture must actually carry the annotation")
                    .isPresent();
            assertThat(WovenInfo.of(Subclass.class))
                    .as("this is the observable consequence of not being @Inherited, and it is "
                            + "what the audit trail depends on")
                    .isEmpty();
        }

        @Test
        @DisplayName("it is RUNTIME-retained, TYPE-targeted, not documented and not repeatable")
        void theRemainingShapeRules() {
            assertThat(Woven.class.getAnnotation(Retention.class).value())
                    .as("CLASS retention would make it invisible to the audience it exists for")
                    .isEqualTo(RetentionPolicy.RUNTIME);
            assertThat(Woven.class.getAnnotation(Target.class).value())
                    .containsExactly(ElementType.TYPE);
            assertThat(Woven.class.isAnnotationPresent(Documented.class))
                    .as("it would otherwise appear in the JavaDoc of every woven type")
                    .isFalse();
            assertThat(Woven.class.isAnnotationPresent(Repeatable.class))
                    .as("two weaving passes must not be able to leave two disagreeing records")
                    .isFalse();
        }

        @Test
        @DisplayName("Entry cannot be written by hand")
        void entryIsUnwritable() {
            assertThat(Woven.Entry.class.getAnnotation(Target.class).value())
                    .as("@Target({}) is what makes it a structure the framework builds rather than "
                            + "a declaration a user makes")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the two carriers agree")
    class Agreement {

        @Test
        @DisplayName("the attribute and the annotation carry the same weaver and fingerprint")
        void headerFieldsAgree() {
            final WeaveAttribute attribute = WeaveAttributeWriter.attribute(RECORD);
            final Annotation annotation = annotationOf(Woven.Detail.FULL);

            assertThat(attribute.weaverVersion()).isEqualTo(RECORD.weaverVersion());
            assertThat(attribute.fingerprint()).isEqualTo(RECORD.fingerprint());
            assertThat(stringElement(annotation, "weaver")).isEqualTo(RECORD.weaverVersion());
            assertThat(stringElement(annotation, "fingerprint")).isEqualTo(RECORD.fingerprint());
        }

        @Test
        @DisplayName("every modification in the attribute is in the FULL annotation")
        void entriesAgree() {
            final WeaveAttribute attribute = WeaveAttributeWriter.attribute(RECORD);

            assertThat(attribute.entries()).hasSize(RECORD.entries().size());
            assertThat(arrayElement(annotationOf(Woven.Detail.FULL), "entries"))
                    .hasSize(RECORD.entries().size());
        }

        @Test
        @DisplayName("the attribute is never truncated, whatever the annotation does")
        void attributeKeepsEverything() {
            final WeaveRecord many = withEntries(WeaveRecord.MAX_ANNOTATION_ENTRIES + 5);

            assertThat(WeaveAttributeWriter.attribute(many).entries())
                    .as("the attribute is read by tooling, so completeness beats size — and the "
                            + "annotation's truncation flag exists to say 'ask the attribute'")
                    .hasSize(WeaveRecord.MAX_ANNOTATION_ENTRIES + 5);
        }
    }

    @Nested
    @DisplayName("detail levels")
    class Detail {

        @Test
        @DisplayName("NONE emits no annotation at all")
        void noneEmitsNothing() {
            assertThat(WovenAnnotationWriter.annotation(RECORD, Woven.Detail.NONE))
                    .as("a host asking for no annotation gets none, not an empty one")
                    .isEmpty();
        }

        @Test
        @DisplayName("SUMMARY carries the plan but no per-modification entries")
        void summaryOmitsEntries() {
            final Annotation summary = annotationOf(Woven.Detail.SUMMARY);

            assertThat(arrayElement(summary, "weaves")).hasSize(2);
            assertThat(arrayElement(summary, "plugins")).hasSize(2);
            assertThat(arrayElement(summary, "extra")).hasSize(2);
            assertThat(element(summary, "entries"))
                    .as("an annotation that grew with the number of injections would tax every "
                            + "class in a heavily woven application")
                    .isEmpty();
        }

        @Test
        @DisplayName("FULL adds the entries")
        void fullAddsEntries() {
            assertThat(arrayElement(annotationOf(Woven.Detail.FULL), "entries")).hasSize(2);
        }
    }

    @Nested
    @DisplayName("truncation")
    class Truncation {

        @Test
        @DisplayName("FULL caps the listing and sets flag bit 2")
        void truncationSetsTheFlag() {
            final WeaveRecord many = withEntries(WeaveRecord.MAX_ANNOTATION_ENTRIES + 5);
            final Annotation annotation = WovenAnnotationWriter
                    .annotation(many, Woven.Detail.FULL).orElseThrow();

            assertThat(arrayElement(annotation, "entries"))
                    .hasSize(WeaveRecord.MAX_ANNOTATION_ENTRIES);
            assertThat(intElement(annotation, "flags") & WeaveRecord.FLAG_TRUNCATED)
                    .as("a short listing must never be mistaken for a complete one")
                    .isNotZero();
        }

        @Test
        @DisplayName("an untruncated record does not set the flag")
        void noFlagWhenComplete() {
            assertThat(intElement(annotationOf(Woven.Detail.FULL), "flags")
                    & WeaveRecord.FLAG_TRUNCATED).isZero();
        }

        @Test
        @DisplayName("SUMMARY never claims truncation, because it never promised entries")
        void summaryIsNotTruncated() {
            final WeaveRecord many = withEntries(WeaveRecord.MAX_ANNOTATION_ENTRIES + 5);
            final Annotation annotation = WovenAnnotationWriter
                    .annotation(many, Woven.Detail.SUMMARY).orElseThrow();

            assertThat(intElement(annotation, "flags") & WeaveRecord.FLAG_TRUNCATED).isZero();
        }
    }

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {

        @Test
        @DisplayName("everything is sorted, whatever order it arrived in")
        void listsAreSorted() {
            final WeaveRecord record = WeaveRecord.of("0.1.0", "b".repeat(64), List.of(),
                    List.of(new PluginId("zebra", "Z", "1.0"), new PluginId("acme", "A", "2.0")),
                    Map.of("zebra:k", "1", "acme:k", "2"), false, false);

            assertThat(record.plugins()).containsExactly("acme:2.0", "zebra:1.0");
            assertThat(record.flatMetadata()).containsExactly("acme:k=2", "zebra:k=1");
        }

        @Test
        @DisplayName("the flags word is derived, so it cannot disagree with the booleans")
        void flagsAreDerived() {
            final WeaveRecord overridden = new WeaveRecord("0.1.0", "c".repeat(64),
                    List.of(), List.of(), Map.of(), List.of(), true, true);

            assertThat(overridden.flags(false))
                    .isEqualTo(WeaveRecord.FLAG_POLICY_OVERRIDE | WeaveRecord.FLAG_STRUCTURAL);
            assertThat(RECORD.flags(false)).isZero();
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private static Annotation annotationOf(final Woven.Detail detail) {
        return WovenAnnotationWriter.annotation(RECORD, detail).orElseThrow();
    }

    private static WeaveRecord withEntries(final int count) {
        final List<WeaveRecord.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new WeaveRecord.Entry("com.acme.W" + i, "inject", "h" + i + "()V", "t()"));
        }
        return new WeaveRecord("0.1.0", "d".repeat(64), List.of("com.acme.W"),
                List.of(), Map.of(), entries, false, false);
    }

    private static Optional<java.lang.classfile.AnnotationValue> element(final Annotation annotation,
                                                                         final String name) {
        return annotation.elements().stream()
                .filter(e -> name.equals(e.name().stringValue()))
                .map(java.lang.classfile.AnnotationElement::value)
                .findFirst();
    }

    private static String stringElement(final Annotation annotation, final String name) {
        return ((java.lang.classfile.AnnotationValue.OfString) element(annotation, name)
                .orElseThrow()).stringValue();
    }

    private static int intElement(final Annotation annotation, final String name) {
        return ((java.lang.classfile.AnnotationValue.OfInt) element(annotation, name)
                .orElseThrow()).intValue();
    }

    private static List<java.lang.classfile.AnnotationValue> arrayElement(
            final Annotation annotation, final String name) {
        return element(annotation, name)
                .map(v -> ((java.lang.classfile.AnnotationValue.OfArray) v).values())
                .orElseGet(List::of);
    }

    @Woven(weaver = "0.1.0", fingerprint = "e", weaves = {"com.acme.Audit"})
    private static class Stamped {
    }

    private static final class Subclass extends Stamped {
    }
}
