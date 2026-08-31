package de.splatgames.aether.weaver.engine.observe;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.StatisticsView;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityTest {

    private static final String EVENT = "de.splatgames.aether.weaver.ClassWoven";

    @Nested
    @DisplayName("the Flight Recorder event")
    class Flight {

        @Test
        @DisplayName("a woven class produces an event a recording can read back")
        void theEventIsRecorded(@TempDir final Path work) throws Exception {
            final Weaver weaver = weaver();
            final List<RecordedEvent> events;

            try (Recording recording = new Recording()) {
                recording.enable(EVENT).withoutThreshold();
                recording.start();

                assertThat(weaver.weave("com/acme/Target", target()))
                        .as("the fixture must really be woven, or there is nothing to record")
                        .isNotNull();

                recording.stop();
                final Path dump = work.resolve("recording.jfr");
                recording.dump(dump);
                events = RecordingFile.readAllEvents(dump);
            }

            assertThat(events)
                    .as("an event with a mistyped name, a field JFR refuses, or a type that was "
                            + "never registered all look identical from the caller's side; only "
                            + "reading the recording back can tell them apart")
                    .hasSize(1);
            final RecordedEvent event = events.getFirst();
            assertThat(event.getEventType().getName()).isEqualTo(EVENT);
            assertThat(event.getString("wovenClass"))
                    .as("binary form, because every other tool in a JFR view shows binary names")
                    .isEqualTo("com.acme.Target");
            assertThat(event.getInt("modifications")).isEqualTo(1);
            assertThat(event.getString("fingerprint")).isEqualTo(weaver.fingerprint());
            assertThat(event.getDuration("weavingTime"))
                    .as("recorded as a @Timespan, so a JFR view can filter on it")
                    .isPositive();
        }

        @Test
        @DisplayName("counter-probe: a class the plan ignores produces no event")
        void unmatchedClassesAreNotRecorded(@TempDir final Path work) throws Exception {
            final Weaver weaver = weaver();
            final List<RecordedEvent> events;

            try (Recording recording = new Recording()) {
                recording.enable(EVENT).withoutThreshold();
                recording.start();

                assertThat(weaver.weave("com/acme/Bystander", bystander())).isNull();

                recording.stop();
                final Path dump = work.resolve("recording.jfr");
                recording.dump(dump);
                events = RecordingFile.readAllEvents(dump);
            }

            assertThat(events)
                    .as("an agent offers the weaver very nearly every class an application loads; "
                            + "an event for each would make the recording useless and expensive")
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing is recording by default, and the weaver says so")
        void quietWhenNobodyRecords() {
            assertThat(weaver().recording())
                    .as("asked before the work, so a class nobody is measuring is not timed twice")
                    .isFalse();
        }

        @Test
        @DisplayName("this JDK has JFR, so the reflective lookup finds the real implementation")
        void discoveryFindsIt() {
            assertThat(WeaveEvents.discover())
                    .as("the lookup is reflective because naming JfrWeaveEvents would make the "
                            + "verifier load jdk.jfr.Event, which is the failure it prevents")
                    .isNotSameAs(WeaveEvents.NONE);
        }

        @Test
        @DisplayName("and the fallback is safe to call")
        void noneIsHarmless() {
            assertThat(WeaveEvents.NONE.enabled()).isFalse();
            WeaveEvents.NONE.classWoven("a/B", 1, "f", 1L);
        }
    }

    @Nested
    @DisplayName("the counters")
    class Counters {

        @Test
        @DisplayName("a planned target that is never offered shows as a gap")
        void theGapIsVisible() {
            final Weaver weaver = weaver();

            weaver.weave("com/acme/Target", target());

            final StatisticsView statistics = weaver.statistics();
            assertThat(statistics.plannedTargets()).isEqualTo(2);
            assertThat(statistics.classesWoven())
                    .as("the plan names two targets and only one was ever offered. In an agent "
                            + "that is ordinary; in a build it is a weave that did not apply to an "
                            + "artefact about to ship, and nothing else would say so")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("every class offered is counted, matched or not")
        void everythingSeenIsCounted() {
            final Weaver weaver = weaver();

            weaver.weave("com/acme/Target", target());
            weaver.weave("com/acme/Bystander", bystander());
            weaver.weave("com/acme/Other", bystander());

            assertThat(weaver.statistics().classesSeen()).isEqualTo(3);
            assertThat(weaver.statistics().classesWoven()).isEqualTo(1);
        }

        @Test
        @DisplayName("applied entries are counted, not just classes")
        void entriesAreCounted() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.A", "com.acme.Target", "onOne"),
                            weave("com.acme.B", "com.acme.Target", "onTwo")))
                    .build();

            weaver.weave("com/acme/Target", target());

            assertThat(weaver.statistics().entriesApplied())
                    .as("one class, two modifications; a count of classes alone would hide the "
                            + "difference between a plan that applied and one that half applied")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("only work is timed, so an unmatched class costs no clock reads")
        void unmatchedClassesAreNotTimed() {
            final Weaver weaver = weaver();

            weaver.weave("com/acme/Bystander", bystander());

            assertThat(weaver.statistics().weavingTimeNanos())
                    .as("in an agent this path runs for very nearly every class an application "
                            + "loads; two System.nanoTime() calls there would tax the exact path "
                            + "the design keeps cheap, to measure the measurement")
                    .isZero();
            assertThat(weaver.statistics().classesSeen()).isOne();
        }

        @Test
        @DisplayName("a matched class is timed")
        void matchedClassesAreTimed() {
            final Weaver weaver = weaver();

            weaver.weave("com/acme/Target", target());

            assertThat(weaver.statistics().weavingTimeNanos()).isPositive();
        }

        @Test
        @DisplayName("a snapshot does not move afterwards")
        void snapshotsAreStable() {
            final Weaver weaver = weaver();
            weaver.weave("com/acme/Target", target());
            final StatisticsView first = weaver.statistics();

            weaver.weave("com/acme/Bystander", bystander());

            assertThat(first.classesSeen()).isOne();
            assertThat(weaver.statistics().classesSeen()).isEqualTo(2);
        }

        @Test
        @DisplayName("a fresh weaver has counted nothing but knows its plan")
        void freshCounters() {
            final StatisticsView statistics = weaver().statistics();

            assertThat(statistics.classesSeen()).isZero();
            assertThat(statistics.classesWoven()).isZero();
            assertThat(statistics.entriesApplied()).isZero();
            assertThat(statistics.failures()).isZero();
            assertThat(statistics.weavingTimeNanos()).isZero();
            assertThat(statistics.plannedTargets()).isEqualTo(2);
        }

        @Test
        @DisplayName("a clock that went backwards does not make a total meaningless")
        void negativeElapsedIsIgnored() {
            final Statistics statistics = new Statistics(0);
            statistics.spent(-5_000L);
            statistics.spent(100L);

            assertThat(statistics.snapshot().weavingTimeNanos()).isEqualTo(100L);
        }

        @Test
        @DisplayName("a negative target count is refused rather than reported")
        void plannedTargetsMustBeSane() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Statistics(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------------------

    private static Weaver weaver() {
        return Weaver.builder()
                .weaves(List.of(weave("com.acme.Audit", "com.acme.Target", "onWork"),
                        weave("com.acme.Audit", "com.acme.NeverLoaded", "onWork")))
                .build();
    }

    private static WeaveClass weave(final String name, final String target, final String handler) {
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of(name), handler,
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                handler, 1, 0, "", List.of());

        return new WeaveClass(ClassDesc.of(name),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of(target))),
                Weave.Kind.STATIC, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
    }

    private static byte[] target() {
        return compiled("com.acme.Target");
    }

    private static byte[] bystander() {
        return compiled("com.acme.Bystander");
    }

    private static byte[] compiled(final String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.return_()));
    }
}
