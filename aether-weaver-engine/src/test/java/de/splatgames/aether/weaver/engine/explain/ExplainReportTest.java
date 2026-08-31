package de.splatgames.aether.weaver.engine.explain;

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
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainReportTest {

    @Nested
    @DisplayName("the plan half, which is known before anything is woven")
    class PlanHalf {

        @Test
        @DisplayName("the header names the framework and the plan")
        void header() {
            final Weaver weaver = explaining(audit());

            assertThat(weaver.explain().lines().findFirst().orElseThrow())
                    .startsWith("Aether Weaver 0.1.0 — plan ")
                    .hasSizeGreaterThan("Aether Weaver 0.1.0 — plan ".length());
        }

        @Test
        @DisplayName("each weave carries its kind, its priority and where it came from")
        void weaveBlock() {
            assertThat(explaining(audit()).explain())
                    .contains("Weaves (1):")
                    .contains("com.acme.PaymentAudit  [STATIC, priority 100, "
                            + "origin: test (target/classes)]")
                    .contains("    → com.acme.PaymentService");
        }

        @Test
        @DisplayName("an injection names its handler, its target method and its point")
        void injectionLine() {
            assertThat(explaining(audit()).explain())
                    .contains("INJECT")
                    .contains("onCharge()")
                    .contains("→ charge(java.math.BigDecimal) @HEAD");
        }

        @Test
        @DisplayName("execution order is listed, and it is the plan's order")
        void executionOrder() {
            final Weaver weaver = explaining(audit(), tracing());

            final String report = weaver.explain();
            assertThat(report).contains(
                    "Execution order at com.acme.PaymentService.charge(java.math.BigDecimal) @HEAD:");
            assertThat(report.indexOf("1. com.acme.PaymentAudit#onCharge"))
                    .as("priority 100 runs before priority 0, and the report says so in the order "
                            + "the handlers will actually run — emission order is execution order, "
                            + "with no inversion anywhere")
                    .isLessThan(report.indexOf("2. com.acme.SessionTracing#onCharge"))
                    .isPositive();
        }

        @Test
        @DisplayName("the footer counts what the plan contains")
        void footer() {
            assertThat(explaining(audit(), tracing()).explain())
                    .contains("Targets: 1   Injections: 2   Merges: 0   Warnings: 0   Errors: 0");
        }

        @Test
        @DisplayName("a merged member is listed and counted")
        void merges() {
            assertThat(explaining(merging()).explain())
                    .contains("MERGE")
                    .contains("startedAt:long")
                    .contains("(unique)")
                    .contains("Merges: 1");
        }
    }

    @Nested
    @DisplayName("the half that only weaving can answer")
    class ResolvedHalf {

        @Test
        @DisplayName("before the target is woven, the report says so rather than saying zero")
        void beforeWeaving() {
            assertThat(explaining(audit()).explain())
                    .as("\"nothing matched\" is a selector to fix and \"nobody asked yet\" is a "
                            + "class the driver never offered; printing 0 sites for both would be "
                            + "misleading in exactly the case a load-time driver produces")
                    .contains("not woven yet")
                    .doesNotContain("no site");
        }

        @Test
        @DisplayName("after the target is woven, the report says what the point matched")
        void afterWeaving() {
            final Weaver weaver = explaining(charging());

            assertThat(weaver.weave("com/acme/PaymentService", target()))
                    .as("the fixture must really be woven, or the next assertion proves nothing")
                    .isNotNull();

            assertThat(weaver.explain())
                    .as("this is the whole feature: a plan cannot know this, and it is the "
                            + "answer to the only question the plan leaves open")
                    .contains("1 site  @")
                    .doesNotContain("not woven yet");
        }

        @Test
        @DisplayName("a point that matched nothing says so, and differently")
        void matchedNothing() {
            final Weaver weaver = explaining(missing());

            weaver.weave("com/acme/PaymentService", target());

            assertThat(weaver.explain())
                    .as("the selector named a method the target does not have; the plan is fine "
                            + "and the resolution is not, which is exactly the distinction")
                    .contains("no site");
        }

        @Test
        @DisplayName("errors reported while planning reach the footer")
        void errorsAreCounted() {
            // A weave that targets another weave: AW1087, reported inside plan(…) — which happens
            // before the report exists, and is the whole reason counting starts before planning.
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.A", "com.acme.B", 0),
                            weave("com.acme.B", "com.acme.Real", 0)))
                    .explain(true)
                    .build();

            assertThat(weaver.explain())
                    .as("counting only what happens after planning would report Errors: 0 for the "
                            + "exact run somebody switched explain on for")
                    .contains("Errors: 1");
        }
    }

    @Nested
    @DisplayName("configuration provenance")
    class Configuration {

        @Test
        @DisplayName("each setting names the layer that decided it, in aligned columns")
        void settingsAreAligned() {
            final Weaver weaver = explaining(audit());
            weaver.report().orElseThrow().configuration("enabled, verification=strict",
                    List.of(new ExplainReport.Setting("verification", "strict", "weaver.properties"),
                            new ExplainReport.Setting("onError", "fail", "default")));

            assertThat(weaver.explain())
                    .contains("Configuration: enabled, verification=strict")
                    .contains("  verification ← weaver.properties")
                    .contains("  onError      ← default");
        }

        @Test
        @DisplayName("a report nobody configured simply omits the block")
        void configurationIsOptional() {
            assertThat(explaining(audit()).explain())
                    .doesNotContain("Configuration:")
                    .contains("Weaves (1):");
        }
    }

    @Nested
    @DisplayName("counter-probe: the report is not built unless it was asked for")
    class NotAskedFor {

        @Test
        @DisplayName("without explain(true) there is no report")
        void noReport() {
            assertThat(Weaver.builder().weaves(List.of(audit())).build().report())
                    .as("the report holds one entry per point per target it was resolved against, "
                            + "which for a load-time driver grows with the run")
                    .isEmpty();
        }

        @Test
        @DisplayName("and explain() falls back to the terse plan listing")
        void terseFallback() {
            assertThat(Weaver.builder().weaves(List.of(audit())).build().explain())
                    .doesNotContain("Weaves (1):")
                    .contains("com/acme/PaymentService");
        }

        @Test
        @DisplayName("the observer the pipeline calls when nobody listens does nothing")
        void noneObserverIsHarmless() {
            SiteObserver.NONE.resolved(new Resolution("a", "b", "c", "@HEAD", "d", List.of(1)));
        }
    }

    // -------------------------------------------------------------------------------------

    private static Weaver explaining(final WeaveClass... weaves) {
        return Weaver.builder().weaves(List.of(weaves)).explain(true).build();
    }

    private static WeaveClass audit() {
        return weave("com.acme.PaymentAudit", "com.acme.PaymentService", 100);
    }

    private static WeaveClass tracing() {
        return weave("com.acme.SessionTracing", "com.acme.PaymentService", 0);
    }

    private static WeaveClass charging() {
        return weave("com.acme.PaymentAudit", "com.acme.PaymentService", 0, "work()");
    }

    private static WeaveClass missing() {
        return weave("com.acme.PaymentAudit", "com.acme.PaymentService", 0, "absent()");
    }

    private static WeaveClass merging() {
        final WeaveMember member = new WeaveMember.Merged("startedAt", ConstantDescs.CD_long,
                Set.of(AccessFlag.PRIVATE), true, false);
        return new WeaveClass(ClassDesc.of("com.acme.PaymentAudit"),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of("com.acme.PaymentService"))),
                Weave.Kind.INSTANCE, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(member), List.of(),
                Origin.of("test", "target/classes"));
    }

    private static WeaveClass weave(final String name, final String target, final int priority) {
        return weave(name, target, priority, "charge(java.math.BigDecimal)");
    }

    private static WeaveClass weave(final String name, final String target, final int priority,
                                    final String method) {
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of(name), "onCharge",
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                method, MemberSelector.parse(method),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                "onCharge", 0, 0, "", List.of());

        return new WeaveClass(ClassDesc.of(name),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of(target))),
                Weave.Kind.STATIC, priority, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec),
                Origin.of("test", "target/classes"));
    }

    private static byte[] target() {
        return ClassFile.of().build(ClassDesc.of("com.acme.PaymentService"), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.return_()));
    }
}
