package de.splatgames.aether.weaver.engine;

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
import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginEvent;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.policy.DefaultWeavePolicy;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WeaverTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    @Nested
    @DisplayName("the no-match fast path")
    class FastPath {

        @Test
        @DisplayName("an unplanned class yields null")
        void unplannedClassYieldsNull() {
            final Weaver weaver = weaver(weave("com.acme.W", "com.acme.Target"));

            assertThat(weaver.weave("java/lang/String", new byte[]{1, 2, 3}))
                    .as("null is the JVM's own signal for 'unchanged', and the answer for almost "
                            + "every class")
                    .isNull();
        }

        @Test
        @DisplayName("the bytes are never even fetched")
        void bytesAreNeverFetched() {
            final Weaver weaver = weaver(weave("com.acme.W", "com.acme.Target"));
            final AtomicInteger fetches = new AtomicInteger();

            weaver.weave("java/lang/String", () -> {
                fetches.incrementAndGet();
                return new byte[]{1};
            });

            assertThat(fetches.get())
                    .as("reading a class file, or copying the buffer the JVM handed over, is real "
                            + "work — and it is wasted for every class that is not woven")
                    .isZero();
        }

        @Test
        @DisplayName("nothing is reported for a class nobody wanted")
        void noDiagnosticsForUnplannedClasses() {
            weaver(weave("com.acme.W", "com.acme.Target"))
                    .weave("java/util/HashMap", new byte[]{1});

            assertThat(reported)
                    .as("refusing something nobody asked for is not worth a message")
                    .isEmpty();
        }

        @Test
        @DisplayName("an empty plan matches nothing at all")
        void emptyPlanMatchesNothing() {
            final Weaver weaver = Weaver.builder().build();

            assertThat(weaver.weave("com/acme/Anything", new byte[]{1})).isNull();
            assertThat(weaver.plan().isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("the policy gate")
    class Policy {

        @Test
        @DisplayName("a planned but denied class is refused, and says so")
        void deniedClassIsRefused() {
            final Weaver weaver = weaver(weave("com.acme.W", "java.lang.String"));

            assertThat(weaver.weave("java/lang/String", compiledClass("java.lang.String")))
                    .isNull();
            assertThat(codes())
                    .as("here the refusal IS worth reporting: a weave explicitly asked for this "
                            + "class")
                    .containsExactly("AW3001");
        }

        @Test
        @DisplayName("a custom policy composes with the built-in one")
        void customPolicyNarrows() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.W", "com.acme.Target")))
                    .policy(DefaultWeavePolicy.standard().and(
                            target -> new de.splatgames.aether.weaver.api.spi.WeavePolicy
                                    .Decision.Deny(
                                    de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode
                                            .POLICY_DENIED_JDK_PACKAGE, "everything is denied")))
                    .diagnostics(reported::add)
                    .build();

            assertThat(weaver.weave("com/acme/Target", compiledClass("com.acme.Target"))).isNull();
            assertThat(codes()).containsExactly("AW3001");
        }
    }

    @Nested
    @DisplayName("the builder")
    class Building {

        @Test
        @DisplayName("the defaults produce a usable weaver")
        void defaultsAreUsable() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.W", "com.acme.Target")))
                    .build();

            assertThat(weaver.plan().size()).isEqualTo(1);
            assertThat(weaver.fingerprint()).hasSize(64);
        }

        @Test
        @DisplayName("the programmatic example from the configuration document runs")
        void documentedExampleRuns() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.PaymentAudit", "com.acme.PaymentService")))
                    .policy(DefaultWeavePolicy.standard())
                    .verification(VerificationPolicy.STRICT)
                    .diagnostics(reported::add)
                    .build();

            final byte[] woven = weaver.weave("com/acme/PaymentService",
                    compiledClass("com.acme.PaymentService"));

            assertThat(weaver.plan().targets()).containsExactly("com/acme/PaymentService");
            assertThat(woven)
                    .as("the pipeline is closed: a planned class comes back woven")
                    .isNotNull();
            assertThat(java.lang.classfile.ClassFile.of().verify(woven))
                    .as("nothing leaves the weaver that has not verified")
                    .isEmpty();
            assertThat(codes()).isEmpty();

            assertThat(de.splatgames.aether.weaver.engine.stamp.Provenance.wovenBy(
                    woven, weaver.fingerprint()))
                    .as("the woven class records the plan that produced it, so a second driver "
                            + "can tell it has already been applied")
                    .isTrue();
        }

        @Test
        @DisplayName("conflicts are reported and a weaver is still returned")
        void conflictsDoNotPreventBuilding() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.A", "com.acme.B"),
                            weave("com.acme.B", "com.acme.Real")))
                    .diagnostics(reported::add)
                    .build();

            assertThat(codes()).contains("AW1087");
            assertThat(weaver.plan().size())
                    .as("returning the weaver lets a report show everything at once")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("plugins")
    class Plugins {

        @Test
        @DisplayName("a programmatically registered plugin is loaded and hears the plan")
        void pluginHearsPrepared() {
            final List<String> heard = new ArrayList<>();
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weave("com.acme.W", "com.acme.Target")))
                    .plugin(observingPlugin(heard))
                    .diagnostics(reported::add)
                    .build();

            assertThat(weaver.plugins().plugins()).extracting(PluginId::namespace)
                    .as("the built-in plugin is always present and sorts first, because the "
                            + "unqualified namespace is the empty string")
                    .containsExactly("", "acme");
            assertThat(heard)
                    .as("a plugin learns what will be woven before anything is")
                    .containsExactly("PluginsLoaded", "Prepared:" + weaver.fingerprint());
        }

        @Test
        @DisplayName("the plugin set reaches the fingerprint")
        void pluginsChangeTheFingerprint() {
            final WeaveClass weave = weave("com.acme.W", "com.acme.Target");

            final String without = Weaver.builder().weaves(List.of(weave)).build().fingerprint();
            final String with = Weaver.builder().weaves(List.of(weave))
                    .plugin(observingPlugin(new ArrayList<>())).build().fingerprint();

            assertThat(with)
                    .as("a plugin changes woven bytes; an identical fingerprint for a different "
                            + "program would silently break idempotence and the AOT-cache check")
                    .isNotEqualTo(without);
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    @Nested
    @DisplayName("a class that already carries somebody else's weave record")
    class AlreadyWoven {

        @Test
        @DisplayName("the same plan is still skipped in silence")
        void theSamePlanIsSkippedSilently() {
            final Weaver weaver = weaver(weave("com.acme.A", "com.acme.PaymentService"));
            final byte[] once = weaver.weave("com/acme/PaymentService",
                    compiledClass("com.acme.PaymentService"));
            assertThat(once).isNotNull();

            reported.clear();
            assertThat(weaver.weave("com/acme/PaymentService", once))
                    .as("the gate this sits beside, unchanged: re-offering a class woven by THIS "
                            + "plan is the ordinary build-then-run case and must stay silent. A "
                            + "diagnostic here would fire on every class of every application that "
                            + "weaves at build time and runs with the agent")
                    .isNull();
            assertThat(codes()).isEmpty();
        }

        @Test
        @DisplayName("AW2201 — a different plan is refused at build time")
        void aDifferentPlanIsRefusedAtBuildTime() {
            final byte[] once = weaver(weave("com.acme.A", "com.acme.PaymentService"))
                    .weave("com/acme/PaymentService", compiledClass("com.acme.PaymentService"));
            assertThat(once).isNotNull();

            final Weaver other = weaver(weave("com.acme.B", "com.acme.PaymentService"));
            reported.clear();

            assertThat(other.weave("com/acme/PaymentService", once))
                    .as("before this gate existed the class was woven a second time, silently, "
                            + "so both plans applied and every injection they share fired twice")
                    .isNull();
            assertThat(codes()).containsExactly("AW2201");
        }

        @Test
        @DisplayName("AW2202 — at load time it is reported and weaving proceeds")
        void aDifferentPlanIsReportedAtLoadTime() {
            final byte[] once = weaver(weave("com.acme.A", "com.acme.PaymentService"))
                    .weave("com/acme/PaymentService", compiledClass("com.acme.PaymentService"));
            assertThat(once).isNotNull();

            final Weaver agent = Weaver.builder()
                    .driver(Weaver.Driver.LOAD)
                    .weaves(List.of(weave("com.acme.B", "com.acme.PaymentService")))
                    .diagnostics(reported::add)
                    .build();
            reported.clear();

            assertThat(agent.weave("com/acme/PaymentService", once))
                    .as("an application woven during its build and then started with the agent is "
                            + "ordinary; it is worth saying and not worth stopping")
                    .isNotNull();
            assertThat(codes()).containsExactly("AW2202");
        }

        @Test
        @DisplayName("a class nobody has woven is not reported at all")
        void anUntouchedClassIsSilent() {
            final Weaver weaver = weaver(weave("com.acme.A", "com.acme.PaymentService"));
            reported.clear();

            assertThat(weaver.weave("com/acme/PaymentService",
                    compiledClass("com.acme.PaymentService")))
                    .isNotNull();
            assertThat(codes())
                    .as("without this the three above would pass against a gate that reported "
                            + "on every class it ever saw")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("what the target's own class file says about itself")
    class TargetShape {

        @Test
        @DisplayName("AW1092 — an anonymous target is warned about and still woven")
        void anAnonymousTargetIsWarnedAbout() {
            final Weaver weaver = weaver(weave("com.acme.A", "com.acme.PaymentService"));

            assertThat(weaver.weave("com/acme/PaymentService", anonymousClass()))
                    .as("the name works today; what it does not do is survive somebody adding a "
                            + "lambda earlier in that file")
                    .isNotNull();
            assertThat(codes()).containsExactly("AW1092");
        }

        @Test
        @DisplayName("AW2004 — a preview class file of another release is refused")
        void aPreviewClassOfAnotherReleaseIsRefused() {
            final Weaver weaver = weaver(weave("com.acme.A", "com.acme.PaymentService"));

            assertThat(weaver.weave("com/acme/PaymentService", previewClass()))
                    .as("a preview class file loads on the exact JVM version that produced it "
                            + "and on no other, so weaving it produces output nothing can load")
                    .isNull();
            assertThat(codes()).containsExactly("AW2004");
        }

        @Test
        @DisplayName("an ordinary class file says nothing about either")
        void anOrdinaryClassIsSilent() {
            final Weaver weaver = weaver(weave("com.acme.A", "com.acme.PaymentService"));

            assertThat(weaver.weave("com/acme/PaymentService",
                    compiledClass("com.acme.PaymentService")))
                    .isNotNull();
            assertThat(codes())
                    .as("without this the two above would pass against checks that fired on "
                            + "every class the weaver was ever offered")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the built-in kinds")
    class BuiltInKinds {

        @Test
        @DisplayName("the engine can map every constant InjectorKind declares")
        void everyDeclaredKindIsMappable() throws ReflectiveOperationException {
            final List<InjectorKind> declared = new ArrayList<>();
            for (final java.lang.reflect.Field field : InjectorKind.class.getFields()) {
                if (field.getType() == InjectorKind.class
                        && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    declared.add((InjectorKind) field.get(null));
                }
            }

            assertThat(declared)
                    .as("a constant this test cannot see is a constant it cannot guard")
                    .isNotEmpty();
            assertThat(Weaver.BUILT_IN_KINDS)
                    .as("the engine keeps its own list of the built-in kinds so that an "
                            + "unqualified identifier resolves at all, and a constant missing "
                            + "from it does not degrade: InjectorKind.of refuses the unqualified "
                            + "namespace, so every declaration naming that kind throws out of "
                            + "the pipeline. That is how @Wrap shipped unreachable.")
                    .containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    private Weaver weaver(final WeaveClass... weaves) {
        return Weaver.builder().weaves(List.of(weaves)).diagnostics(this.reported::add).build();
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private static WeaveClass weave(final String name, final String target) {
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of(name), "onWork",
                        MethodTypeDesc.of(ConstantDescs.CD_void),
                        // static, because an @Inject handler is called with invokestatic — the
                        // pipeline reports AW1005 for anything else, which is how this fixture
                        // was found to be wrong in the first place.
                        Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                "onWork", 1, 0, "", List.of());

        // STATIC, and the kind is load-bearing. An INSTANCE weave is DISSOLVED into its target,
        // so a handler declared in the weave class stops existing there and the call is emitted
        // against the target instead — which needs the weave's own class file. This fixture supplies
        // none and wants none: it is a static weave in everything but the word, and it declared
        // INSTANCE only because that is the annotation default. AW1096 caught it the moment
        // dissolving became real.
        return new WeaveClass(ClassDesc.of(name),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of(target))),
                Weave.Kind.STATIC, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
    }

    private static byte[] anonymousClass() {
        return ClassFile.of().build(ClassDesc.of("com.acme.PaymentService"), builder -> {
            builder.with(java.lang.classfile.attribute.EnclosingMethodAttribute.of(
                    ClassDesc.of("com.acme.Outer"), java.util.Optional.empty(),
                    java.util.Optional.empty()));
            builder.withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, code -> code.return_());
        });
    }

    private static byte[] previewClass() {
        return ClassFile.of().build(ClassDesc.of("com.acme.PaymentService"), builder -> {
            builder.withVersion(ClassFile.latestMajorVersion() - 1,
                    ClassFile.PREVIEW_MINOR_VERSION);
            builder.withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, code -> code.return_());
        });
    }

    private static byte[] compiledClass(final String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.return_()));
    }

    private static WeaverPlugin observingPlugin(final List<String> heard) {
        final PluginId id = new PluginId("acme", "Acme", "1.0");
        return new WeaverPlugin() {
            @Override
            public PluginId id() {
                return id;
            }

            @Override
            public int apiLevel() {
                return WeaverApi.LEVEL;
            }

            @Override
            public void contribute(final PluginContext ctx) {
                ctx.metadata("mode", "test");
            }

            @Override
            public void observe(final PluginEvent event) {
                switch (event) {
                    case PluginEvent.PluginsLoaded ignored -> heard.add("PluginsLoaded");
                    case PluginEvent.Prepared prepared ->
                            heard.add("Prepared:" + prepared.plan().fingerprint());
                    default -> { }
                }
            }
        };
    }
}
