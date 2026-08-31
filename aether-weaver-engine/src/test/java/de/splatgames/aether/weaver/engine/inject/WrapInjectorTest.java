package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.callback.Operation;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import de.splatgames.aether.weaver.engine.plan.OrderKey;
import de.splatgames.aether.weaver.engine.plan.PlanEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WrapInjectorTest {

    private static final ClassDesc TARGET = ClassDesc.of("wrapfixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    private static final ClassDesc CD_OPERATION = ClassDesc.of(Operation.class.getName());

    private static final Path OUTPUT = compileFixture();

    private static final byte[] FIXTURE = read("wrapfixture/Target.class");

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static int outer(final Object receiver, final int value,
                                final Operation<Integer> operation) {
            WrapRecorder.record("outer-enter");
            WrapRecorder.OPERATIONS.add(operation);
            final int result = operation.call(receiver, value);
            WrapRecorder.record("outer-exit");
            return result + 1;
        }

        public static int inner(final Object receiver, final int value,
                                final Operation<Integer> operation) {
            WrapRecorder.record("inner-enter");
            final int result = operation.call(receiver, value);
            WrapRecorder.record("inner-exit");
            return result + 10;
        }

        public static int skips(final Object receiver, final int value,
                                final Operation<Integer> operation) {
            WrapRecorder.record("skips");
            return -1;
        }

        public static int twice(final Object receiver, final int value,
                                final Operation<Integer> operation) {
            operation.call(receiver, value);
            return operation.call(receiver, value);
        }

        public static int passesThrough(final Object receiver, final int value,
                                        final Operation<Integer> operation) {
            WrapRecorder.record("passesThrough-enter");
            return operation.call(receiver, value);
        }

        public static int wrapsRead(final Object receiver, final Operation<Integer> operation) {
            WrapRecorder.record("wrapsRead");
            return operation.call(receiver) + 1;
        }

        public static StringBuilder wrapsNew(final String seed,
                                             final Operation<StringBuilder> operation) {
            WrapRecorder.record("wrapsNew");
            return operation.call(seed);
        }

        public static int afterTheOperation(final Object receiver, final int value,
                                            final Operation<Integer> operation, final int extra) {
            return 0;
        }

        public static int withoutAnOperation(final Object receiver, final int value) {
            return 0;
        }
    }

    @Nested
    @DisplayName("one wrap")
    class Single {

        @Test
        @DisplayName("the handler receives the target's own operation and can perform it")
        void theOperationIsPerformed() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    List.of(handler("outer")));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "callsCompute", 3))
                    .as("compute(3) is 6; the handler adds one")
                    .isEqualTo(7);
            assertThat(WrapRecorder.TRACE)
                    .as("the operation happened, and it happened between the handler's two ends — "
                            + "which is what distinguishes a wrap from an @Inject at INVOKE_AFTER")
                    .containsExactly("outer-enter", "compute(3)", "outer-exit");
        }

        @Test
        @DisplayName("a handler that does not call the operation stops it happening")
        void theOperationCanBeSkipped() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    List.of(handler("skips")));

            assertThat(reported).isEmpty();
            assertThat(invoke(woven, "callsCompute", 3)).isEqualTo(-1);
            assertThat(WrapRecorder.TRACE)
                    .as("without this a wrap would be an observer; the operation must really be "
                            + "under the handler's control")
                    .containsExactly("skips");
        }

        @Test
        @DisplayName("calling the operation twice performs it twice")
        void theOperationCanBeRepeated() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    List.of(handler("twice")));

            assertThat(invoke(woven, "callsCompute", 3)).isEqualTo(6);
            assertThat(WrapRecorder.TRACE).containsExactly("compute(3)", "compute(3)");
        }

        @Test
        @DisplayName("the operation is a constant, not an allocation per call")
        void theOperationIsCached() throws Exception {
            clear();
            final byte[] woven = weave("callsComputeTwice", invoke("#compute"),
                    List.of(handler("outer")));

            invoke(woven, "callsComputeTwice", 2);
            assertThat(WrapRecorder.OPERATIONS)
                    .as("two executions of one site must hand out the same instance: the site "
                            + "holds an ldc of a dynamic constant, so the JVM resolves it once and "
                            + "caches it. An allocation here would be invisible in behaviour and "
                            + "would put a `new` on every wrapped call in production")
                    .hasSize(2);
            assertThat(WrapRecorder.OPERATIONS.get(0))
                    .isSameAs(WrapRecorder.OPERATIONS.get(1));
        }

        @Test
        @DisplayName("what the operation throws passes through unchanged")
        void exceptionsAreTransparent() {
            clear();
            final byte[] woven = weave("callsFailing", invoke("#fail"),
                    List.of(handler("passesThrough")));

            assertThat(reported).isEmpty();
            assertThatThrownBy(() -> invoke(woven, "callsFailing", 3))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause()
                    .as("Operation.call does not declare throws, so an implementation that wrapped "
                            + "this would change which of the target's own catch blocks runs — and "
                            + "the target was compiled against the original exception")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
            assertThat(WrapRecorder.TRACE).contains("passesThrough-enter");
        }

        @Test
        @DisplayName("a field read is an operation a wrap can take")
        void aFieldReadIsWrapped() throws Exception {
            clear();
            final byte[] woven = weave("readsCounter",
                    PointSpec.builtIn(Point.FIELD).target("#counter").build(),
                    List.of(handler("wrapsRead", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, CD_OPERATION)));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "readsCounter", 0)).isEqualTo(2);
            assertThat(WrapRecorder.TRACE).containsExactly("wrapsRead");
        }

        @Test
        @DisplayName("an instantiation is wrapped — three instructions, not one")
        void anInstantiationIsWrapped() throws Exception {
            clear();
            final byte[] woven = weave("makesBuilder",
                    PointSpec.builtIn(Point.NEW).target("java.lang.StringBuilder").build(),
                    List.of(handler("wrapsNew", ClassDesc.of("java.lang.StringBuilder"),
                            ConstantDescs.CD_String, CD_OPERATION)));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("leaving the `new` or its `dup` behind puts an uninitialised reference on "
                            + "the stack, which the verifier rejects — so a clean verify is real "
                            + "evidence that the whole span went")
                    .isEmpty();
            assertThat(invoke(woven, "makesBuilder", 0)).isEqualTo("seed!");
            assertThat(WrapRecorder.TRACE).containsExactly("wrapsNew");
        }

        @Test
        @DisplayName("weaving twice from the same inputs is byte-identical")
        void emissionIsDeterministic() {
            clear();
            final byte[] first = weave("callsCompute", invoke("#compute"),
                    List.of(handler("outer")));
            final byte[] second = weave("callsCompute", invoke("#compute"),
                    List.of(handler("outer")));
            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("several wraps nest")
    class Nesting {

        @Test
        @DisplayName("two wraps at one site nest, and the innermost reaches the target")
        void twoWrapsNest() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    List.of(handler("outer"), handler("inner")));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("both emitting would put two calls where one operand set exists, which the "
                            + "verifier catches")
                    .isEmpty();
            assertThat(WrapRecorder.TRACE).isEmpty();

            final Object result = invoke(woven, "callsCompute", 3);
            assertThat(WrapRecorder.TRACE)
                    .as("this is the claim @Wrap exists for: two weaves that have never heard "
                            + "of each other both intervene, in plan order, and only the innermost "
                            + "call reaches the target's own operation")
                    .containsExactly("outer-enter", "inner-enter", "compute(3)",
                            "inner-exit", "outer-exit");
            assertThat(result)
                    .as("compute(3) is 6, the inner handler adds 10, the outer adds 1 — so the "
                            + "results really do flow back out through both")
                    .isEqualTo(17);
        }

        @Test
        @DisplayName("an inner handler that skips stops the operation and the outer still runs")
        void anInnerSkipStopsTheOperation() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    List.of(handler("outer"), handler("skips")));

            assertThat(invoke(woven, "callsCompute", 3))
                    .as("the inner returns -1 and the outer adds one to whatever it got")
                    .isEqualTo(0);
            assertThat(WrapRecorder.TRACE)
                    .as("the outer handler cannot tell that the operation did not happen, which is "
                            + "exactly what its javadoc warns it must not assume")
                    .containsExactly("outer-enter", "skips", "outer-exit");
        }

        @Test
        @DisplayName("an outer handler that skips never reaches the inner one")
        void anOuterSkipHidesTheInnerOne() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    List.of(handler("skips"), handler("inner")));

            assertThat(invoke(woven, "callsCompute", 3)).isEqualTo(-1);
            assertThat(WrapRecorder.TRACE).containsExactly("skips");
        }
    }

    @Nested
    @DisplayName("the shape is checked against the operation")
    class Shape {

        @Test
        @DisplayName("AW1062 — a parameter after the Operation is refused")
        void parametersAfterTheOperationAreRefused() {
            clear();
            weave("callsCompute", invoke("#compute"),
                    List.of(handler("afterTheOperation", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int, CD_OPERATION,
                            ConstantDescs.CD_int)));

            assertThat(codes())
                    .as("such a handler would work as the outermost wrap and fail as a nested "
                            + "one, so its correctness would depend on which other weaves exist")
                    .contains("AW1062");
        }

        @Test
        @DisplayName("AW1063 — a handler with no Operation is refused")
        void aMissingOperationIsRefused() {
            clear();
            weave("callsCompute", invoke("#compute"),
                    List.of(handler("withoutAnOperation", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int)));

            assertThat(codes())
                    .as("that is a @Redirect's shape; accepting it here would silently turn a wrap "
                            + "into one")
                    .contains("AW1063");
        }

        @Test
        @DisplayName("AW1061 — a position that is not an operation is refused")
        void nonOperationIsRefused() {
            clear();
            weave("callsCompute", PointSpec.builtIn(Point.HEAD).build(),
                    List.of(handler("outer")));

            assertThat(codes())
                    .as("HEAD names a position, not an operation, so there is nothing to wrap")
                    .contains("AW1061");
        }

        @ParameterizedTest
        @EnumSource(value = Point.class,
                names = {"HEAD", "RETURN", "TAIL", "INVOKE_AFTER", "CONSTANT", "THROW"})
        @DisplayName("AW1061 — every built-in point outside the allow-list, not just HEAD")
        void everyPositionNamingPointIsRefused(final Point point) {
            new WrapInjector().validate(entryFor(PointSpec.builtIn(point).target("#compute")
                    .build()), ModelViews.of(ClassFile.of().parse(FIXTURE)), reporter);

            assertThat(codes())
                    .as("""
                            The allow-list holds three points and the suite checked one of the \
                            six it excludes. Asked of validate directly, because CONSTANT and \
                            THROW match nothing in this fixture and a declaration resolving to no \
                            site never reaches validate at all — routed through the pipeline they \
                            would have reported this check as covered while testing resolution.""")
                    .contains("AW1061");
        }

        @Test
        @DisplayName("a contributed point is not refused by name, exactly as the remedy promises")
        void aContributedPointIsLeftToItsShape() {
            new WrapInjector().validate(entryFor(PointSpec.named("acme:SOMEWHERE").build()),
                    ModelViews.of(ClassFile.of().parse(FIXTURE)), reporter);

            assertThat(codes())
                    .as("""
                            The other half of the condition, and the half with a promise \
                            attached: this check's own remedy tells authors that a contributed \
                            point "is not checked here and is judged by the shape it resolves \
                            to". Nothing asserted that it is not refused by name anyway.""")
                    .doesNotContain("AW1061");
        }

        @Test
        @DisplayName("AW1102 — a shifted wrap is refused")
        void shiftIsRefused() {
            clear();
            weave("callsCompute",
                    PointSpec.builtIn(Point.INVOKE).target("#compute")
                            .shift(At.Shift.AFTER).build(),
                    List.of(handler("outer")));

            assertThat(codes()).contains("AW1102");
        }

        @Test
        @DisplayName("AW1005 — a non-static handler is refused")
        void anInstanceHandlerIsRefused() {
            clear();
            final HandlerRef instance = new HandlerRef(HANDLER_OWNER, "outer",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object,
                            ConstantDescs.CD_int, CD_OPERATION), Set.of());
            weave("callsCompute", invoke("#compute"), List.of(instance));

            assertThat(codes())
                    .as("an inner level is a bound method handle with no receiver to supply, so a "
                            + "non-static handler could only ever be outermost")
                    .contains("AW1005");
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private static void clear() {
        WrapRecorder.clear();
    }

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static PointSpec invoke(final String target) {
        return PointSpec.builtIn(Point.INVOKE).target(target).build();
    }

    /**
     * The gap this file left open until 2026-08-31.
     *
     * <p>Every other test here supplies its own lookup and hands {@code WrapInjector} to the
     * pipeline directly, so none of them ever reached the lambda {@code Weaver} installs — and
     * that lambda mapped an identifier back to a kind through a list {@code wrap} was missing
     * from. A declaration naming it threw out of the pipeline in every real run while this file
     * stayed green.
     */
    @Nested
    @DisplayName("driven through a real Weaver")
    class ThroughTheEngine {

        @Test
        @DisplayName("a wrap declaration reaches its injector instead of throwing")
        void wrapResolvesThroughTheEngineLookup() {
            final List<Diagnostic> reported = new ArrayList<>();
            final Weaver weaver = Weaver.builder()
                    .weaves(List.of(wrapWeave()))
                    .diagnostics(reported::add)
                    .build();

            final byte[] woven = weaver.weave("wrapfixture/Target", FIXTURE);

            assertThat(reported.stream().map(d -> d.code().code()).toList())
                    .as("nothing about this declaration is wrong, so nothing is reported")
                    .isEmpty();
            assertThat(woven)
                    .as("the engine's own lookup has to resolve 'wrap', and a null here means "
                            + "the declaration was planned and then dropped")
                    .isNotNull();
            assertThat(ClassFile.of().verify(woven))
                    .as("nothing leaves the weaver that has not verified")
                    .isEmpty();
        }

        private WeaveClass wrapWeave() {
            final HandlerRef handler = handler("outer");
            final InjectorSpec spec = new InjectorSpec(InjectorKind.WRAP, handler,
                    "callsCompute", MemberSelector.parse("callsCompute"),
                    List.of(invoke("#compute")), List.of(),
                    "wrap", 0, 0, "", List.<LocalSpec>of());
            return new WeaveClass(HANDLER_OWNER,
                    List.of(TargetRef.ofClassLiteral(TARGET)),
                    Weave.Kind.STATIC, 0, Require.REQUIRED, Phase.DEFAULT,
                    Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
        }
    }

    private static PlanEntryView entryFor(final PointSpec point) {
        final HandlerRef handler = handler("outer");
        final InjectorSpec spec = new InjectorSpec(InjectorKind.WRAP, handler,
                "callsCompute", MemberSelector.parse("callsCompute"), List.of(point), List.of(),
                "wrap", 0, 0, "", List.<LocalSpec>of());
        return new PlanEntry(TARGET, spec, "wrapfixture.Weave", Origin.of("test", null),
                new OrderKey(0, "wrapfixture.Weave", handler.name(),
                        handler.type().descriptorString()));
    }

    private static HandlerRef handler(final String name) {
        return handler(name, ConstantDescs.CD_int, ConstantDescs.CD_Object, ConstantDescs.CD_int,
                CD_OPERATION);
    }

    private static HandlerRef handler(final String name, final ClassDesc returns,
                                      final ClassDesc... parameters) {
        return new HandlerRef(HANDLER_OWNER, name,
                MethodTypeDesc.of(returns, parameters), Set.of(AccessFlag.STATIC));
    }

    private byte[] weave(final String method, final PointSpec point,
                         final List<HandlerRef> handlers) {
        final ClassModel model = ClassFile.of().parse(FIXTURE);
        final List<PlanEntryView> entries = new ArrayList<>();
        for (int level = 0; level < handlers.size(); level++) {
            final HandlerRef handler = handlers.get(level);
            final InjectorSpec spec = new InjectorSpec(InjectorKind.WRAP, handler,
                    method, MemberSelector.parse(method), List.of(point), List.of(),
                    "wrap" + level, 0, 0, "", List.<LocalSpec>of());
            entries.add(new PlanEntry(TARGET, spec, "wrapfixture.Weave" + level,
                    Origin.of("test", null),
                    new OrderKey(level, "wrapfixture.Weave" + level, handler.name(),
                            handler.type().descriptorString())));
        }

        final WeavingPipeline pipeline = new WeavingPipeline(
                BuiltInPoints.all()::get,
                kind -> InjectorKind.WRAP.id().equals(kind) ? new WrapInjector() : null);
        final byte[] woven = pipeline.weave(model, entries, List.of(), this.reporter);
        return woven == null ? FIXTURE : woven;
    }

    private static Object invoke(final byte[] woven, final String method, final int seed)
            throws Exception {
        final ClassLoader loader = new ClassLoader(WrapInjectorTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("wrapfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                if (!name.startsWith("wrapfixture.")) {
                    throw new ClassNotFoundException(name);
                }
                // The whole fixture package is defined here rather than shared with the test's
                // loader, so the recorder only ever hears from the WOVEN target.
                final byte[] plain = read(name.replace('.', '/') + ".class");
                return defineClass(name, plain, 0, plain.length);
            }
        };
        final Class<?> type = loader.loadClass("wrapfixture.Target");
        final Object instance = type.getDeclaredConstructor().newInstance();
        final Method target = type.getDeclaredMethod(method, int.class);
        return target.invoke(instance, seed);
    }

    private static byte[] read(final String resource) {
        try {
            return Files.readAllBytes(OUTPUT.resolve(resource));
        } catch (final Exception failed) {
            throw new AssertionError("could not read " + resource, failed);
        }
    }

    private static Path compileFixture() {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-wrap");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null,
                        List.of("-g", "-classpath", System.getProperty("java.class.path")), null,
                        List.of(new Source())).call();
                if (!ok) {
                    throw new AssertionError("the wrap fixture must compile");
                }
            }
            return output;
        } catch (final Exception failed) {
            throw new AssertionError("could not build the wrap fixture", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private static final String CODE = """
                package wrapfixture;

                import de.splatgames.aether.weaver.engine.inject.WrapRecorder;

                public class Target {

                    private int counter = 1;

                    // The collaborator records every call, so whether the operation really
                    // happened is visible rather than inferred.
                    static final class Collaborator {
                        int compute(int value) {
                            WrapRecorder.record("compute(" + value + ')');
                            return value * 2;
                        }

                        int fail(int value) {
                            throw new IllegalStateException("boom");
                        }
                    }

                    private final Collaborator collaborator = new Collaborator();

                    public int callsCompute(int seed) {
                        return collaborator.compute(seed);
                    }

                    public int callsComputeTwice(int seed) {
                        return collaborator.compute(seed) + collaborator.compute(seed + 1);
                    }

                    public int callsFailing(int seed) {
                        return collaborator.fail(seed);
                    }

                    public int readsCounter(int seed) {
                        return this.counter;
                    }

                    public String makesBuilder(int seed) {
                        StringBuilder sb = new StringBuilder("seed");
                        return sb.append('!').toString();
                    }
                }
                """;

        Source() {
            super(URI.create("string:///wrapfixture/Target.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
