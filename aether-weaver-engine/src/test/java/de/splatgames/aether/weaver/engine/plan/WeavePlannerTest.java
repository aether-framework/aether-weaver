package de.splatgames.aether.weaver.engine.plan;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import de.splatgames.aether.weaver.engine.plugin.PluginRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WeavePlannerTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("higher priority runs first")
        void priorityDescends() {
            final WeavePlan plan = plan(
                    weave("com.acme.Low", 10, "com.acme.Target", inject("onA")),
                    weave("com.acme.High", 100, "com.acme.Target", inject("onA")),
                    weave("com.acme.Mid", 50, "com.acme.Target", inject("onA")));

            assertThat(plan.planEntries()).extracting(PlanEntry::weaveClassName)
                    .containsExactly("com.acme.High", "com.acme.Mid", "com.acme.Low");
        }

        @Test
        @DisplayName("equal priority is broken by weave name, then handler name")
        void tiesAreBrokenDeterministically() {
            final WeavePlan plan = plan(
                    weave("com.acme.Bravo", 50, "com.acme.Target", inject("zulu"), inject("alpha")),
                    weave("com.acme.Alpha", 50, "com.acme.Target", inject("mike")));

            assertThat(plan.planEntries())
                    .extracting(e -> e.weaveClassName() + '#' + e.handler().name())
                    .containsExactly("com.acme.Alpha#mike",
                            "com.acme.Bravo#alpha",
                            "com.acme.Bravo#zulu");
        }

        @Test
        @DisplayName("the input order cannot reach the plan")
        void inputOrderIsIrrelevant() {
            final List<WeaveClass> weaves = List.of(
                    weave("com.acme.A", 50, "com.acme.Target", inject("one"), inject("two")),
                    weave("com.acme.B", 100, "com.acme.Target", inject("three")),
                    weave("com.acme.C", 50, "com.acme.Other", inject("four")));

            final String reference = plan(weaves).explain();

            final Random shuffler = new Random(20260805L);
            for (int run = 0; run < 20; run++) {
                final List<WeaveClass> shuffled = new ArrayList<>(weaves);
                Collections.shuffle(shuffled, shuffler);
                reported.clear();

                assertThat(plan(shuffled).explain())
                        .as("classpath enumeration order differs between machines; if it reached "
                                + "the plan it would reach the fingerprint, and the fingerprint is "
                                + "written into every woven class")
                        .isEqualTo(reference);
            }
        }

        @Test
        @DisplayName("two entries never compare equal")
        void theOrderIsTotal() {
            final WeavePlan plan = plan(
                    weave("com.acme.A", 50, "com.acme.Target", inject("same"), inject("same2")),
                    weave("com.acme.B", 50, "com.acme.Target", inject("same")));

            final List<OrderKey> keys = plan.planEntries().stream().map(PlanEntry::order).toList();
            for (int i = 0; i < keys.size(); i++) {
                for (int j = i + 1; j < keys.size(); j++) {
                    assertThat(keys.get(i).compareTo(keys.get(j)))
                            .as("%s vs %s — a tie leaves the outcome to whatever the sort was "
                                    + "handed", keys.get(i), keys.get(j))
                            .isNotZero();
                }
            }
        }
    }

    @Nested
    @DisplayName("flattening and indexing")
    class Flattening {

        @Test
        @DisplayName("one entry per target and handler")
        void oneEntryPerTargetAndHandler() {
            final WeavePlan plan = plan(weave("com.acme.W", 0,
                    List.of("com.acme.A", "com.acme.B"), inject("x"), inject("y")));

            assertThat(plan.size()).as("two targets times two handlers").isEqualTo(4);
            assertThat(plan.targets()).containsExactlyInAnyOrder("com/acme/A", "com/acme/B");
        }

        @Test
        @DisplayName("a class that is not woven returns an empty list, not null")
        void unwovenClassReturnsEmpty() {
            final WeavePlan plan = plan(weave("com.acme.W", 0, "com.acme.A", inject("x")));

            assertThat(plan.entriesFor("java/lang/String"))
                    .as("this is the answer for almost every class the JVM loads, so it must be "
                            + "cheap and must never be null")
                    .isEmpty();
        }

        @Test
        @DisplayName("the per-target list keeps the global order")
        void perTargetListIsOrdered() {
            final WeavePlan plan = plan(
                    weave("com.acme.Low", 1, "com.acme.T", inject("a")),
                    weave("com.acme.High", 9, "com.acme.T", inject("a")));

            assertThat(plan.entriesFor("com/acme/T"))
                    .extracting(e -> ((PlanEntry) e).weaveClassName())
                    .containsExactly("com.acme.High", "com.acme.Low");
        }

        @Test
        @DisplayName("no weaves produce an empty plan rather than a failure")
        void emptyPlan() {
            final WeavePlan plan = plan(List.of());

            assertThat(plan.isEmpty()).isTrue();
            assertThat(plan.fingerprint()).hasSize(64);
        }
    }

    @Nested
    @DisplayName("conflicts")
    class Conflicts {

        @Test
        @DisplayName("a weave targeting a weave class is AW1087")
        void weaveTargetingWeave() {
            plan(weave("com.acme.A", 0, "com.acme.B", inject("x")),
                    weave("com.acme.B", 0, "com.acme.Real", inject("y")));

            assertThat(codes()).containsExactly("AW1087");
            assertThat(reported.getFirst().message()).contains("com.acme.A").contains("com.acme.B");
        }

        @Test
        @DisplayName("two redirects on one call site is AW1060, naming both")
        void duplicateRedirect() {
            plan(weave("com.acme.A", 0, "com.acme.T", redirect("wrapA")),
                    weave("com.acme.B", 0, "com.acme.T", redirect("wrapB")));

            assertThat(codes()).containsExactly("AW1060");
            assertThat(reported.getFirst().details())
                    .anySatisfy(d -> assertThat(d).contains("com.acme.A"))
                    .anySatisfy(d -> assertThat(d).contains("com.acme.B"));
        }

        @Test
        @DisplayName("two injects on one point are allowed")
        void twoInjectsAreFine() {
            plan(weave("com.acme.A", 0, "com.acme.T", inject("x")),
                    weave("com.acme.B", 0, "com.acme.T", inject("y")));

            assertThat(reported)
                    .as("two handlers at one point compose; they are ordered, not conflicting")
                    .isEmpty();
        }

        @Test
        @DisplayName("two weaves merging the same member is AW1080")
        void mergedMembersCollide() {
            plan(weaveWithMember("com.acme.A", 0, "com.acme.T", merged("counter", false)),
                    weaveWithMember("com.acme.B", 0, "com.acme.T", merged("counter", false)));

            assertThat(codes()).containsExactly("AW1080");
        }

        @Test
        @DisplayName("both @Unique is not a collision")
        void uniqueMembersCoexist() {
            plan(weaveWithMember("com.acme.A", 0, "com.acme.T", merged("counter", true)),
                    weaveWithMember("com.acme.B", 0, "com.acme.T", merged("counter", true)));

            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("only one @Unique is still a collision")
        void oneUniqueIsNotEnough() {
            plan(weaveWithMember("com.acme.A", 0, "com.acme.T", merged("counter", true)),
                    weaveWithMember("com.acme.B", 0, "com.acme.T", merged("counter", false)));

            assertThat(codes())
                    .as("a mangled member and a plainly named one still collide on the plain name")
                    .containsExactly("AW1080");
        }

        @Test
        @DisplayName("shadowing a member added at equal priority is AW1034")
        void shadowOfEqualPriorityAddition() {
            plan(weaveWithMember("com.acme.Adder", 50, "com.acme.T", merged("added", false)),
                    weaveWithMember("com.acme.Shadower", 50, "com.acme.T", shadowed("added")));

            assertThat(codes()).containsExactly("AW1034");
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(r -> assertThat(r).contains("strictly higher"));
        }

        @Test
        @DisplayName("shadowing a member added at higher priority is fine")
        void shadowOfHigherPriorityAdditionIsFine() {
            plan(weaveWithMember("com.acme.Adder", 100, "com.acme.T", merged("added", false)),
                    weaveWithMember("com.acme.Shadower", 50, "com.acme.T", shadowed("added")));

            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("a plan is still returned when conflicts were found")
        void planSurvivesConflicts() {
            final WeavePlan plan = plan(
                    weave("com.acme.A", 0, "com.acme.T", redirect("wrapA")),
                    weave("com.acme.B", 0, "com.acme.T", redirect("wrapB")));

            assertThat(plan.size())
                    .as("returning the plan is what lets a report show everything that was wrong "
                            + "at once, rather than one problem per rebuild")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("the fingerprint")
    class Fingerprint {

        @Test
        @DisplayName("identical inputs produce an identical fingerprint")
        void stableAcrossRuns() {
            final List<WeaveClass> weaves = List.of(
                    weave("com.acme.A", 50, "com.acme.T", inject("x")),
                    weave("com.acme.B", 10, "com.acme.T", inject("y")));

            final String first = plan(weaves).fingerprint();
            reported.clear();
            final String second = plan(new ArrayList<>(weaves).reversed()).fingerprint();

            assertThat(second).isEqualTo(first);
            assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("a different priority is a different plan")
        void priorityChangesIt() {
            final String low = plan(weave("com.acme.A", 1, "com.acme.T", inject("x"))).fingerprint();
            reported.clear();
            final String high = plan(weave("com.acme.A", 2, "com.acme.T", inject("x"))).fingerprint();

            assertThat(high).isNotEqualTo(low);
        }

        @Test
        @DisplayName("where a weave was found does not change it")
        void originDoesNotChangeIt() {
            final WeaveClass here = weave("com.acme.A", 0, "com.acme.T", inject("x"));
            final WeaveClass there = new WeaveClass(here.weaveType(), here.targets(), here.kind(),
                    here.priority(), here.require(), here.phase(), here.tags(), here.groups(),
                    here.members(), here.injectors(),
                    Origin.of("manifest", "/somewhere/else/build/classes"));

            final String a = plan(here).fingerprint();
            reported.clear();
            final String b = plan(there).fingerprint();

            assertThat(b)
                    .as("the same weave found in a different directory is the same modification; a "
                            + "path in the digest would make two machines disagree for no reason")
                    .isEqualTo(a);
        }

        @Test
        @DisplayName("an empty plan still has a well-formed fingerprint")
        void emptyPlanIsFingerprinted() {
            assertThat(plan(List.of()).fingerprint()).matches("[0-9a-f]{64}");
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private WeavePlan plan(final WeaveClass... weaves) {
        return plan(List.of(weaves));
    }

    private WeavePlan plan(final List<WeaveClass> weaves) {
        return new WeavePlanner(this.listener).plan(weaves, PluginRegistry.empty());
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private static WeaveClass weave(final String name, final int priority, final String target,
                                    final InjectorSpec... injectors) {
        return weave(name, priority, List.of(target), injectors);
    }

    private static WeaveClass weave(final String name, final int priority,
                                    final List<String> targets, final InjectorSpec... injectors) {
        return new WeaveClass(ClassDesc.of(name),
                targets.stream().map(t -> TargetRef.ofClassLiteral(ClassDesc.of(t))).toList(),
                Weave.Kind.INSTANCE, priority, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(injectors),
                Origin.of("test", null));
    }

    private static WeaveClass weaveWithMember(final String name, final int priority,
                                              final String target, final WeaveMember member) {
        return new WeaveClass(ClassDesc.of(name),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of(target))),
                Weave.Kind.INSTANCE, priority, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(member), List.of(),
                Origin.of("test", null));
    }

    private static WeaveMember merged(final String name, final boolean unique) {
        return new WeaveMember.Merged(name, ConstantDescs.CD_int,
                Set.of(AccessFlag.PRIVATE), unique, false);
    }

    private static WeaveMember shadowed(final String name) {
        return new WeaveMember.Shadowed(name, ConstantDescs.CD_int,
                Set.of(AccessFlag.PRIVATE), name, false);
    }

    private static InjectorSpec inject(final String handler) {
        return spec(InjectorKind.INJECT, handler, PointSpec.builtIn(Point.HEAD).build());
    }

    private static InjectorSpec redirect(final String handler) {
        return spec(InjectorKind.REDIRECT, handler, PointSpec.builtIn(Point.INVOKE)
                .target("Gateway.send(Payment)")
                .ordinal(0)
                .build());
    }

    private static InjectorSpec spec(final InjectorKind kind, final String handler,
                                     final PointSpec point) {
        return new InjectorSpec(kind,
                new HandlerRef(ClassDesc.of("com.acme.W"), handler,
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.PRIVATE)),
                "work()",
                MemberSelector.parse("work()"),
                List.of(point), List.of(), handler, 1, 0, "", List.of());
    }
}
