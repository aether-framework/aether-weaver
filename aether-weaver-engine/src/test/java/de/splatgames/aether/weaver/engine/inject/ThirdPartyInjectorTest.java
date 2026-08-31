package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.Alias;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.Injector.Disposition;
import de.splatgames.aether.weaver.api.spi.Injector.Emitter;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.stamp.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ThirdPartyInjectorTest {

    private static final String NAMESPACE = "acme";

    private static final InjectorKind TRACE = InjectorKind.of(NAMESPACE + ":trace");

    private static final String LAST_RETURN = NAMESPACE + ":LAST_RETURN";

    private static final String LAST_RETURN_RETIRED = NAMESPACE + ":LAST_RET";

    private static final String THROWS = NAMESPACE + ":THROWS";

    static final List<String> CALLS = new ArrayList<>();

    private final List<Diagnostic> reported = new ArrayList<>();

    public static final class Handlers {

        private Handlers() {
        }

        public static void traced() {
            CALLS.add("traced");
        }
    }

    @Nested
    @DisplayName("a contributed injector reaches the pipeline")
    class Contributed {

        @Test
        @DisplayName("it is called, it emits, and the class runs")
        void thirdPartyInjectorWeaves() throws Exception {
            CALLS.clear();
            final Weaver weaver = weaver();

            final byte[] woven = weaver.weave("acmefixture/Target",
                    fixture());

            assertThat(reported)
                    .as("a clean run reports nothing")
                    .isEmpty();
            assertThat(woven)
                    .as("the contributed injector produced bytes")
                    .isNotNull();
            assertThat(ClassFile.of().verify(woven))
                    .as("and they verify, like anything the engine emits itself")
                    .isEmpty();

            assertThat(invoke(woven)).isEqualTo("done");
            assertThat(CALLS)
                    .as("code an external party wrote ran inside the woven class")
                    .containsExactly("traced");
        }

        @Test
        @DisplayName("the contributed kind and point are registered under the plugin's namespace")
        void registeredUnderItsOwnNamespace() {
            final Weaver weaver = weaver();

            assertThat(weaver.plugins().injectors().ids()).contains(TRACE.id());
            assertThat(weaver.plugins().points().ids()).contains(LAST_RETURN);
            assertThat(weaver.plugins().plugins()).extracting(PluginId::namespace)
                    .as("the built-in plugin and the contributed one, side by side")
                    .containsExactly("", NAMESPACE);
        }

        @Test
        @DisplayName("the plugin changes the fingerprint and is recorded in the woven class")
        void pluginIsRecorded() {
            final Weaver weaver = weaver();
            final byte[] woven = weaver.weave("acmefixture/Target", fixture());

            assertThat(Provenance.wovenBy(woven, weaver.fingerprint()))
                    .as("the artefact carries evidence of whose code shaped it")
                    .isTrue();

            final String withoutPlugin = Weaver.builder()
                    .weaves(List.of(weave())).build().fingerprint();
            assertThat(weaver.fingerprint())
                    .as("a different plugin set is a different plan")
                    .isNotEqualTo(withoutPlugin);
        }

        @Test
        @DisplayName("the emission is deterministic")
        void deterministic() {
            assertThat(weaver().weave("acmefixture/Target", fixture()))
                    .isEqualTo(weaver().weave("acmefixture/Target", fixture()));
        }
    }

    // --- the third-party plugin ------------------------------------------------------------

    private static final class AcmePlugin implements WeaverPlugin {

        private static final PluginId ID = new PluginId(NAMESPACE, "Acme Tracing", "1.0.0");

        @Override
        public PluginId id() {
            return ID;
        }

        @Override
        public int apiLevel() {
            return WeaverApi.LEVEL;
        }

        @Override
        public void contribute(final PluginContext ctx) {
            ctx.injectors(new AcmeInjectors()).points(new AcmePoints());
        }
    }

    private static final class AcmeInjectors implements InjectorFactory {

        @Override
        public String namespace() {
            return NAMESPACE;
        }

        @Override
        public Set<InjectorKind> kinds() {
            return Set.of(TRACE);
        }

        @Override
        public Injector create(final InjectorKind kind) {
            if (TraceInjector.FAIL_AT == TraceInjector.Fail.CREATE) {
                throw new IllegalStateException("boom");
            }
            return new TraceInjector();
        }
    }

    private static final class TraceInjector implements Injector {

        /** Where this fixture throws, so each guarded call site can be reached in turn. */
        enum Fail { NEVER, CREATE, VALIDATE, OPERANDS }

        static volatile Fail FAIL_AT = Fail.NEVER;

        @Override
        public InjectorKind kind() {
            return TRACE;
        }

        @Override
        public void validate(final PlanEntryView entry, final TargetView target,
                             final Reporter reporter) {
            if (FAIL_AT == Fail.VALIDATE) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public int stackOperandsAt(final InjectorSpec spec, final MethodView method,
                                   final CodeView body, final int site) {
            if (FAIL_AT == Fail.OPERANDS) {
                throw new IllegalStateException("boom");
            }
            return 0;
        }

        @Override
        public Emitter emitter(final InjectionContext context) {
            final Set<Integer> where = Set.copyOf(context.sites());
            final HandlerRef handler = context.entry().handler();

            // No element counter of its own, and no builder.accept(element). The engine owns the
            // walk and writes the element once, after every injector has been asked — which is what
            // lets several injectors share a method without any of them seeing another's output.
            return (builder, element, index) -> {
                if (where.contains(index)) {
                    final HandlerBinding binding = context.argumentsAt(index);
                    binding.emitArguments(builder);
                    binding.emitCaptures(builder);
                    builder.invokestatic(handler.owner(), handler.name(), handler.type());
                }
                return Disposition.KEEP;
            };
        }
    }

    private static final class AcmePoints implements InjectionPointFactory {

        @Override
        public String namespace() {
            return NAMESPACE;
        }

        @Override
        public Set<String> ids() {
            return Set.of(LAST_RETURN, THROWS);
        }

        @Override
        public Set<Alias> aliases() {
            return Set.of(new Alias(LAST_RETURN_RETIRED, LAST_RETURN, "0.2.0"));
        }

        @Override
        public InjectionPoint create(final String id) {
            return THROWS.equals(id) ? new ThrowingPoint() : new LastReturnPoint();
        }
    }

    private static final class ThrowingPoint implements InjectionPoint {

        @Override
        public String id() {
            return THROWS;
        }

        @Override
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        @Override
        public List<Site> find(final MethodView method, final CodeView code,
                               final PointSpec spec, final Reporter reporter) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class LastReturnPoint implements InjectionPoint {

        @Override
        public String id() {
            return LAST_RETURN;
        }

        @Override
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        @Override
        public List<Site> find(final MethodView method, final CodeView code,
                               final PointSpec spec, final Reporter reporter) {
            final List<CodeElement> elements = code.elements();
            for (int i = elements.size() - 1; i >= 0; i--) {
                if (elements.get(i) instanceof ReturnInstruction returning) {
                    return List.of(new Site(i, Site.Kind.METHOD_EXIT, returning));
                }
            }
            return List.of();
        }
    }

    // --- fixtures ---------------------------------------------------------------------------

    /**
     * The lookups the pipeline uses run once per point of every class woven, which is why they
     * once passed {@code DiagnosticListener.NOOP} — and why a retired spelling warned nobody at
     * all. They now warn once per identifier instead of never, and this is the test of both
     * halves.
     */
    @Nested
    @DisplayName("a retired spelling")
    class RetiredSpellings {

        @Test
        @DisplayName("warns once, however many classes name it")
        void warnsOncePerIdentifier() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weaveNaming(LAST_RETURN_RETIRED)))
                    .plugin(new AcmePlugin())
                    .diagnostics(ThirdPartyInjectorTest.this.reported::add)
                    .build();

            assertThat(weaver.weave("acmefixture/Target", fixture())).isNotNull();
            assertThat(weaver.weave("acmefixture/Target", fixture())).isNotNull();

            assertThat(codes())
                    .as("the warning has to reach the build log, and exactly once: reporting it "
                            + "per point of every class woven is why it used to be dropped")
                    .containsExactly("AW3120");
            assertThat(ThirdPartyInjectorTest.this.reported.getFirst().message())
                    .contains(LAST_RETURN_RETIRED)
                    .contains(LAST_RETURN);
        }

        @Test
        @DisplayName("the current spelling warns about nothing")
        void currentSpellingIsSilent() {
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weaveNaming(LAST_RETURN)))
                    .plugin(new AcmePlugin())
                    .diagnostics(ThirdPartyInjectorTest.this.reported::add)
                    .build();

            assertThat(weaver.weave("acmefixture/Target", fixture())).isNotNull();

            assertThat(codes())
                    .as("without this the test above would pass against a listener that "
                            + "reported on every lookup")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("a contributed point on a slice bound")
    class SliceBounds {

        @Test
        @DisplayName("is contained even when the declaration's own @At is built in")
        void aBoundIsContainedUnderABuiltInPoint() {
            // The guard used to be chosen on the declaration's own identifier alone. A built-in
            // @At therefore took the direct branch and then reached the contributed point as its
            // slice bound anyway, so a throw there left Weaver.weave with no diagnostic at all.
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(weaveWithThrowingBound()))
                    .plugin(new AcmePlugin())
                    .diagnostics(ThirdPartyInjectorTest.this.reported::add)
                    .build();

            weaver.weave("acmefixture/Target", fixture());

            assertThat(codes())
                    .as("the throw has to be contained and attributed, not propagate")
                    .contains("AW3116");
            assertThat(ThirdPartyInjectorTest.this.reported.stream()
                    .filter(d -> "AW3116".equals(d.code().code()))
                    .findFirst().orElseThrow().message())
                    .as("the failure belongs to the point that threw, not to the built-in @At "
                            + "that happened to name it")
                    .contains(THROWS);
        }
    }

    @Nested
    @DisplayName("a contributed injector that throws")
    class ThrowingInjector {

        @Test
        @DisplayName("is contained at validate, and the class is left alone")
        void validateIsContained() {
            assertContainedAt(TraceInjector.Fail.VALIDATE);
        }

        @Test
        @DisplayName("is contained at stackOperandsAt, and the class is left alone")
        void stackOperandsIsContained() {
            assertContainedAt(TraceInjector.Fail.OPERANDS);
        }

        @Test
        @DisplayName("is contained at create, before there is an injector at all")
        void createIsContained() {
            assertContainedAt(TraceInjector.Fail.CREATE);
        }

        private void assertContainedAt(final TraceInjector.Fail where) {
            TraceInjector.FAIL_AT = where;
            try {
                final Weaver weaver = Weaver.builder()
                        .weaves(List.of(weave()))
                        .plugin(new AcmePlugin())
                        .diagnostics(ThirdPartyInjectorTest.this.reported::add)
                        .build();

                // Reaching the assertion at all is half the test: every one of these used to
                // leave Weaver.weave as an IllegalStateException. The declaration is withdrawn,
                // so nothing is left to write and the pipeline answers null for "unchanged".
                assertThat(weaver.weave("acmefixture/Target", fixture()))
                        .as("the only declaration was withdrawn, so the class is unchanged")
                        .isNull();
                assertThat(codes())
                        .as("contained is not enough; the plugin has to be named for it")
                        .contains("AW3117");
            } finally {
                TraceInjector.FAIL_AT = TraceInjector.Fail.NEVER;
            }
        }
    }

    private static WeaveClass weaveWithThrowingBound() {
        final PointSpec head = PointSpec.builtIn(de.splatgames.aether.weaver.api.Point.HEAD)
                .slice("s").build();
        final SliceSpec slice = new SliceSpec("s",
                PointSpec.named(THROWS).ordinal(0).build(),
                PointSpec.named(LAST_RETURN).ordinal(0).build());
        final InjectorSpec spec = new InjectorSpec(TRACE,
                new HandlerRef(ClassDesc.of(Handlers.class.getName()), "traced",
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(head), List.of(slice),
                "traced", 0, 0, "", List.of());

        return new WeaveClass(ClassDesc.of("acme.Tracing"),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of("acmefixture.Target"))),
                Weave.Kind.INSTANCE, 0, Require.OPTIONAL, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private Weaver weaver() {
        return Weaver.builder()
                .weaves(List.of(weave()))
                .plugin(new AcmePlugin())
                .diagnostics(this.reported::add)
                .build();
    }

    private static WeaveClass weave() {
        return weaveNaming(LAST_RETURN);
    }

    private static WeaveClass weaveNaming(final String point) {
        final InjectorSpec spec = new InjectorSpec(TRACE,
                new HandlerRef(ClassDesc.of(Handlers.class.getName()), "traced",
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(PointSpec.named(point).build()), List.of(),
                "traced", 1, 0, "", List.of());

        return new WeaveClass(ClassDesc.of("acme.Tracing"),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of("acmefixture.Target"))),
                Weave.Kind.INSTANCE, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
    }

    private static Object invoke(final byte[] woven) throws Exception {
        final ClassLoader loader = new ClassLoader(ThirdPartyInjectorTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("acmefixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Class<?> type = loader.loadClass("acmefixture.Target");
        final Method target = type.getDeclaredMethod("work");
        return target.invoke(type.getDeclaredConstructor().newInstance());
    }

    private static byte[] fixture() {
        return ClassFile.of().build(ClassDesc.of("acmefixture.Target"), builder -> {
            builder.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .return_());
            builder.withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code.ldc("done").areturn());
        });
    }
}
