package de.splatgames.aether.weaver.agent;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetransformApplicabilityTest {

    private static final String TARGET = "com.acme.Target";

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("what cannot be applied to a loaded class")
    class Refused {

        @Test
        @DisplayName("AW2101 — a merged field")
        void mergedFieldsAreRefused() {
            applicable(weave(Weave.Kind.INSTANCE, List.of(mergedField()), List.of()));

            assertThat(codes()).containsExactly("AW2101");
            assertThat(reported().getFirst().message())
                    .as("naming the member is what tells an operator whether it was deliberate")
                    .contains("merges the field 'startedAt'");
        }

        @Test
        @DisplayName("AW2101 — a generated accessor")
        void generatedMembersAreRefused() {
            applicable(weave(Weave.Kind.INSTANCE, List.of(accessor()), List.of()));

            assertThat(codes()).containsExactly("AW2101");
            assertThat(reported().getFirst().message()).contains("accessor 'getName'");
        }

        @Test
        @DisplayName("AW2101 — @Shadow(mutable = true), which rewrites the target's own flags")
        void unfinalisingIsRefused() {
            applicable(weave(Weave.Kind.INSTANCE, List.of(mutableShadow()), List.of()));

            assertThat(codes()).containsExactly("AW2101");
            assertThat(reported().getFirst().message()).contains("removes final");
        }

        @Test
        @DisplayName("AW2101 — an instance weave's handler, which is merged into the target")
        void mergedHandlersAreRefused() {
            applicable(weave(Weave.Kind.INSTANCE, List.of(), List.of(injection())));

            assertThat(codes())
                    .as("an instance weave dissolves its handler into the target, which adds a "
                            + "method to an already-defined class — the handler is easy to forget "
                            + "because it is not a WeaveMember")
                    .containsExactly("AW2101");
            assertThat(reported().getFirst().message()).contains("handlers are merged");
        }

        @Test
        @DisplayName("the refusal says what to do instead")
        void theRefusalIsActionable() {
            applicable(weave(Weave.Kind.INSTANCE, List.of(mergedField()), List.of()));

            final Diagnostic refusal = reported().getFirst();
            assertThat(refusal.details())
                    .as("a limit of retransformation is not a defect in the weave, and the "
                            + "message must not read as though it were")
                    .anyMatch(detail -> detail.contains("limit of retransformation"))
                    .anyMatch(detail -> detail.contains("not been loaded yet"));
            assertThat(refusal.remedy().orElseThrow())
                    .contains("build time")
                    .contains("-javaagent");
        }

        private List<Diagnostic> reported() {
            return RetransformApplicabilityTest.this.reported;
        }
    }

    @Nested
    @DisplayName("what can")
    class Allowed {

        @Test
        @DisplayName("a static weave's injections apply, because they change only method bodies")
        void staticInjectionsApply() {
            applicable(weave(Weave.Kind.STATIC, List.of(), List.of(injection())));

            assertThat(reported)
                    .as("an injection changes a method body, which retransformation permits")
                    .isEmpty();
        }

        @Test
        @DisplayName("an instance weave that merges nothing applies too")
        void anInstanceWeaveWithoutStructureApplies() {
            applicable(weave(Weave.Kind.INSTANCE, List.of(shadow()), List.of()));

            assertThat(reported)
                    .as("refusing by kind would be a rule about the annotation rather than about "
                            + "the JVM: a @Shadow adds nothing to the target, it only rewrites "
                            + "references inside code that was merged")
                    .isEmpty();
        }

        @Test
        @DisplayName("a structural weave whose target is not loaded yet is not reported at all")
        void notLoadedYetIsNotAProblem() {
            notLoadedYet(weave(Weave.Kind.INSTANCE, List.of(mergedField()), List.of()));

            assertThat(reported)
                    .as("a class that has not been loaded is being defined for the first time "
                            + "rather than redefined, so it can be woven structurally. Reporting "
                            + "here would name a problem that does not exist")
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing is ever removed from the plan, only reported")
        void thePlanIsLeftAlone() {
            final List<WeaveClass> plan = List.of(
                    named("com.acme.First", Weave.Kind.STATIC),
                    weave(Weave.Kind.INSTANCE, List.of(mergedField()), List.of()),
                    named("com.acme.Third", Weave.Kind.STATIC));

            RetransformApplicability.report(plan, Set.of(TARGET),
                    RetransformApplicabilityTest.this.listener);

            assertThat(codes())
                    .as("the inapplicable weave is reported")
                    .containsExactly("AW2101");
            assertThat(plan)
                    .extracting(WeaveClass::binaryName)
                    .as("and the plan is untouched. Removing a weave would change the "
                            + "fingerprint, so the same weave set attached dynamically would stamp "
                            + "classes differently from the same set under premain — driver "
                            + "dependent output, which is the one thing the architecture rules out")
                    .containsExactly("com.acme.First", "com.acme.Audit", "com.acme.Third");
        }
    }

    // -------------------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private void applicable(final WeaveClass weave) {
        RetransformApplicability.report(List.of(weave), Set.of(TARGET), this.listener);
    }

    private void notLoadedYet(final WeaveClass weave) {
        RetransformApplicability.report(List.of(weave), Set.of(), this.listener);
    }

    private static WeaveClass weave(final Weave.Kind kind,
                                    final List<WeaveMember> members,
                                    final List<InjectorSpec> injectors) {
        return new WeaveClass(ClassDesc.of("com.acme.Audit"),
                List.of(new TargetRef(ClassDesc.of("com.acme.Target"), true)),
                kind, 0, Require.REQUIRED, Phase.DEFAULT, Set.of(), List.of(),
                members, injectors, Origin.of("test", null));
    }

    private static WeaveClass named(final String binaryName, final Weave.Kind kind) {
        return new WeaveClass(ClassDesc.of(binaryName),
                List.of(new TargetRef(ClassDesc.of("com.acme.Target"), true)),
                kind, 0, Require.REQUIRED, Phase.DEFAULT, Set.of(), List.of(),
                List.of(), List.of(), Origin.of("test", null));
    }

    private static WeaveMember mergedField() {
        return new WeaveMember.Merged("startedAt", ConstantDescs.CD_long,
                Set.of(AccessFlag.PRIVATE), true, false);
    }

    private static WeaveMember accessor() {
        return new WeaveMember.Accessor("getName", MethodTypeDesc.of(ConstantDescs.CD_String),
                Set.of(AccessFlag.ABSTRACT), "name");
    }

    private static WeaveMember shadow() {
        return new WeaveMember.Shadowed("name", ConstantDescs.CD_String,
                Set.of(AccessFlag.PRIVATE), "name", false);
    }

    private static WeaveMember mutableShadow() {
        return new WeaveMember.Shadowed("name", ConstantDescs.CD_String,
                Set.of(AccessFlag.PRIVATE), "name", true);
    }

    private static InjectorSpec injection() {
        return new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of("com.acme.Audit"), "onRun",
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                "run()", MemberSelector.parse("run()"),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                "onRun", 0, 0, "", List.of());
    }
}
