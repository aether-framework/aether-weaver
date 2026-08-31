package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
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
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
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
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectInjectorTest {

    private static final ClassDesc TARGET = ClassDesc.of("redirectfixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    private static final Path OUTPUT = compileFixture();

    private static final byte[] FIXTURE = read("redirectfixture/Target.class");

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static int onCompute(final Object receiver, final int value) {
            RedirectRecorder.HANDLED.add("compute(" + value + ')');
            return value * 100;
        }

        public static int onComputeWithSeed(final Object receiver, final int value, final int seed) {
            RedirectRecorder.HANDLED.add("compute(" + value + ",seed=" + seed + ')');
            return value * 100;
        }

        public static int onReadCounter(final Object receiver) {
            RedirectRecorder.HANDLED.add("readCounter");
            return 77;
        }

        public static void onWriteCounter(final Object receiver, final int value) {
            RedirectRecorder.HANDLED.add("writeCounter(" + value + ')');
        }

        public static StringBuilder onNewBuilder(final String initial) {
            RedirectRecorder.HANDLED.add("new(" + initial + ')');
            return new StringBuilder("replaced");
        }

        public static int misshapen(final String wrong) {
            return 0;
        }

        public static int misshapenTypes(final String first, final String second) {
            return 0;
        }
    }

    @Nested
    @DisplayName("the original operation stops happening")
    class Replacement {

        @Test
        @DisplayName("an instance call is replaced, and the collaborator is never asked")
        void instanceCallIsReplaced() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    handler("onCompute", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();

            assertThat(invoke(woven, "callsCompute", 3))
                    .as("the handler's return value is what the target goes on to use")
                    .isEqualTo(300);
            assertThat(RedirectRecorder.HANDLED).containsExactly("compute(3)");
            assertThat(RedirectRecorder.ORIGINAL)
                    .as("the point of a redirect: the operation it replaced did NOT run. A test "
                            + "that only checked the handler would pass against an implementation "
                            + "that merely inserted a call")
                    .isEmpty();
        }

        @Test
        @DisplayName("the unwoven fixture does call its collaborator")
        void theFixtureReallyDoesTheOperation() throws Exception {
            clear();
            assertThat(invoke(FIXTURE, "callsCompute", 3))
                    .as("without this the previous test proves nothing — an empty ORIGINAL would "
                            + "be the fixture's normal state")
                    .isEqualTo(6);
            assertThat(RedirectRecorder.ORIGINAL).containsExactly("compute(3)");
        }

        @Test
        @DisplayName("a field read is replaced")
        void fieldReadIsReplaced() throws Exception {
            clear();
            final byte[] woven = weave("readsCounter",
                    PointSpec.builtIn(Point.FIELD).target("#counter")
                            .access(At.Access.GET).build(),
                    handler("onReadCounter", ConstantDescs.CD_int, ConstantDescs.CD_Object));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "readsCounter", 0)).isEqualTo(77);
            assertThat(RedirectRecorder.HANDLED).containsExactly("readCounter");
        }

        @Test
        @DisplayName("a field write is replaced, and the field keeps its old value")
        void fieldWriteIsReplaced() throws Exception {
            clear();
            final byte[] woven = weave("writesCounter",
                    PointSpec.builtIn(Point.FIELD).target("#counter")
                            .access(At.Access.PUT).build(),
                    handler("onWriteCounter", ConstantDescs.CD_void,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "writesCounter", 9))
                    .as("the write never happened, so the field still holds what the constructor "
                            + "put there")
                    .isEqualTo(1);
            assertThat(RedirectRecorder.HANDLED).containsExactly("writeCounter(9)");
        }

        @Test
        @DisplayName("an instantiation is replaced — three instructions, not one")
        void instantiationIsReplaced() throws Exception {
            clear();
            final byte[] woven = weave("makesBuilder",
                    PointSpec.builtIn(Point.NEW).target("java.lang.StringBuilder").build(),
                    handler("onNewBuilder", ClassDesc.of("java.lang.StringBuilder"),
                            ConstantDescs.CD_String));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("leaving the `new` or its `dup` behind puts an uninitialised reference on "
                            + "the stack, which the verifier rejects — so a clean verify is real "
                            + "evidence that the whole span went")
                    .isEmpty();
            assertThat(invoke(woven, "makesBuilder", 0)).isEqualTo("replaced!");
            assertThat(RedirectRecorder.HANDLED).containsExactly("new(seed)");
        }

        @Test
        @DisplayName("the enclosing method's arguments may follow the operation's own")
        void enclosingArgumentsAreAppended() throws Exception {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    handler("onComputeWithSeed", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_int));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "callsCompute", 3)).isEqualTo(300);
            assertThat(RedirectRecorder.HANDLED)
                    .as("the operation's operands come first because the stack already holds "
                            + "them; the engine pushes only what follows")
                    .containsExactly("compute(3,seed=3)");
        }

        @Test
        @DisplayName("every matching operation is replaced, not just the first")
        void allMatchesAreReplaced() throws Exception {
            clear();
            final byte[] woven = weave("callsComputeTwice", invoke("#compute"),
                    handler("onCompute", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));

            assertThat(reported).isEmpty();
            assertThat(invoke(woven, "callsComputeTwice", 2)).isEqualTo(500);
            assertThat(RedirectRecorder.HANDLED).containsExactly("compute(2)", "compute(3)");
            assertThat(RedirectRecorder.ORIGINAL).isEmpty();
        }

        @Test
        @DisplayName("weaving twice from the same inputs is byte-identical")
        void emissionIsDeterministic() {
            clear();
            final byte[] first = weave("callsCompute", invoke("#compute"),
                    handler("onCompute", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));
            final byte[] second = weave("callsCompute", invoke("#compute"),
                    handler("onCompute", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));
            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("the shape is checked against the operation")
    class Shape {

        @Test
        @DisplayName("AW1040 — a handler that does not describe the operation is refused")
        void misshapenHandlerIsRefused() {
            clear();
            final byte[] woven = weave("callsCompute", invoke("#compute"),
                    handler("misshapen", ConstantDescs.CD_int, ConstantDescs.CD_String));

            assertThat(codes()).contains("AW1040");
            assertThat(woven)
                    .as("a refused redirect leaves the class alone rather than emitting something "
                            + "half-right")
                    .isEqualTo(FIXTURE);
        }

        @Test
        @DisplayName("the message names both signatures, because the mismatch is between them")
        void theMessageNamesBothShapes() {
            clear();
            weave("callsCompute", invoke("#compute"),
                    handler("misshapenTypes", ConstantDescs.CD_int,
                            ConstantDescs.CD_String, ConstantDescs.CD_String));

            assertThat(codes()).contains("AW1040");
            assertThat(reported.getFirst().details())
                    .as("the binding cannot catch this one. Those two parameters come off the "
                            + "stack, so the engine emits nothing for them and has nothing to "
                            + "compare — only the operation's own shape does")
                    .anySatisfy(detail -> assertThat(detail).contains("operation:"))
                    .anySatisfy(detail -> assertThat(detail).contains("handler:"));
        }

        @Test
        @DisplayName("the receiver counts as an operand of an instance operation")
        void theReceiverIsAnOperand() {
            clear();
            weave("callsCompute", invoke("#compute"),
                    handler("onCompute", ConstantDescs.CD_int, ConstantDescs.CD_int));

            assertThat(codes())
                    .as("dropping the receiver from the handler shifts every argument by one; the "
                            + "stack does not re-order itself to suit")
                    .contains("AW1040");
        }

        @Test
        @DisplayName("AW1102 — a shifted redirect is refused")
        void shiftIsRefused() {
            clear();
            weave("callsCompute",
                    PointSpec.builtIn(Point.INVOKE).target("#compute")
                            .shift(At.Shift.AFTER).build(),
                    handler("onCompute", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));

            assertThat(codes()).contains("AW1102");
        }

        @Test
        @DisplayName("AW1061 — a position that is not an operation is refused")
        void nonOperationIsRefused() {
            clear();
            weave("callsCompute", PointSpec.builtIn(Point.HEAD).build(),
                    handler("onCompute", ConstantDescs.CD_int,
                            ConstantDescs.CD_Object, ConstantDescs.CD_int));

            assertThat(codes())
                    .as("HEAD names a position, not an operation, so there is nothing to replace")
                    .contains("AW1061");
        }

        @ParameterizedTest
        @EnumSource(value = Point.class,
                names = {"HEAD", "RETURN", "TAIL", "INVOKE_AFTER", "CONSTANT", "THROW"})
        @DisplayName("AW1061 — every built-in point outside the allow-list, not just HEAD")
        void everyPositionNamingPointIsRefused(final Point point) {
            new RedirectInjector().validate(entryFor(PointSpec.builtIn(point).target("#compute")
                    .build()), ModelViews.of(ClassFile.of().parse(FIXTURE)), reporter);

            assertThat(codes())
                    .as("""
                            The allow-list holds three points and the suite checked one of the \
                            six it excludes. A check written as a set is only as tested as the \
                            members exercised — dropping any of the other five out of the \
                            condition would have gone unnoticed.

                            Asked of validate directly rather than through the pipeline: CONSTANT \
                            and THROW match nothing in this fixture, and a declaration that \
                            resolves to no site never reaches validate at all. Routing them \
                            through the pipeline would have tested resolution and reported this \
                            check as covered.""")
                    .contains("AW1061");
        }

        @Test
        @DisplayName("a contributed point is not refused by name, exactly as the remedy promises")
        void aContributedPointIsLeftToItsShape() {
            new RedirectInjector().validate(entryFor(PointSpec.named("acme:SOMEWHERE").build()),
                    ModelViews.of(ClassFile.of().parse(FIXTURE)), reporter);

            assertThat(codes())
                    .as("""
                            The other half of the condition, and the half with a promise \
                            attached: this check's own remedy tells authors that a contributed \
                            point "is not checked here and is judged by the shape it resolves to". \
                            Refusing every unfamiliar identifier would make the framework's \
                            extensibility a special case of its own validation — and nothing \
                            asserted that it does not.""")
                    .doesNotContain("AW1061");
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private static void clear() {
        RedirectRecorder.clear();
    }

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static PlanEntryView entryFor(final PointSpec point) {
        final HandlerRef handler = handler("onCompute", ConstantDescs.CD_int,
                ConstantDescs.CD_Object, ConstantDescs.CD_int);
        final InjectorSpec spec = new InjectorSpec(InjectorKind.REDIRECT, handler,
                "callsCompute", MemberSelector.parse("callsCompute"), List.of(point), List.of(),
                "redirect", 0, 0, "", List.<LocalSpec>of());
        return new PlanEntry(TARGET, spec, "redirectfixture.Weave", Origin.of("test", null),
                new OrderKey(0, "redirectfixture.Weave", handler.name(),
                        handler.type().descriptorString()));
    }

    private static PointSpec invoke(final String target) {
        return PointSpec.builtIn(Point.INVOKE).target(target).build();
    }

    private static HandlerRef handler(final String name, final ClassDesc returns,
                                      final ClassDesc... parameters) {
        return new HandlerRef(HANDLER_OWNER, name,
                MethodTypeDesc.of(returns, parameters), Set.of(AccessFlag.STATIC));
    }

    private byte[] weave(final String method, final PointSpec point, final HandlerRef handler) {
        final ClassModel model = ClassFile.of().parse(FIXTURE);
        final InjectorSpec spec = new InjectorSpec(InjectorKind.REDIRECT, handler,
                method, MemberSelector.parse(method), List.of(point), List.of(),
                "redirect", 0, 0, "", List.<LocalSpec>of());
        final PlanEntryView entry = new PlanEntry(TARGET, spec, "redirectfixture.Weave",
                Origin.of("test", null),
                new OrderKey(0, "redirectfixture.Weave", handler.name(),
                        handler.type().descriptorString()));

        final WeavingPipeline pipeline = new WeavingPipeline(
                BuiltInPoints.all()::get,
                kind -> InjectorKind.REDIRECT.id().equals(kind) ? new RedirectInjector() : null);
        final byte[] woven = pipeline.weave(model, List.of(entry), List.of(), this.reporter);
        return woven == null ? FIXTURE : woven;
    }

    private static Object invoke(final byte[] woven, final String method, final int seed)
            throws Exception {
        final ClassLoader loader = new ClassLoader(RedirectInjectorTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("redirectfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                if (!name.startsWith("redirectfixture.")) {
                    throw new ClassNotFoundException(name);
                }
                // The fixture's collaborator is a nested class, and the recorder it writes to only
                // proves anything if the WOVEN target is the one that reaches it — so the whole
                // fixture package is defined in this loader rather than shared with the test's.
                final byte[] plain = read(name.replace('.', '/') + ".class");
                return defineClass(name, plain, 0, plain.length);
            }
        };
        final Class<?> type = loader.loadClass("redirectfixture.Target");
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
            final Path output = Files.createTempDirectory("aether-weaver-redirect");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null,
                        List.of("-g", "-classpath", System.getProperty("java.class.path")), null,
                        List.of(new Source())).call();
                if (!ok) {
                    throw new AssertionError("the redirect fixture must compile");
                }
            }
            return output;
        } catch (final Exception failed) {
            throw new AssertionError("could not build the redirect fixture", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private static final String CODE = """
                package redirectfixture;

                import de.splatgames.aether.weaver.engine.inject.RedirectRecorder;

                public class Target {

                    private int counter = 1;

                    // The collaborator records every call, so an un-replaced operation is visible.
                    static final class Collaborator {
                        int compute(int value) {
                            RedirectRecorder.ORIGINAL.add("compute(" + value + ')');
                            return value * 2;
                        }
                    }

                    private final Collaborator collaborator = new Collaborator();

                    public int callsCompute(int seed) {
                        return collaborator.compute(seed);
                    }

                    public int callsComputeTwice(int seed) {
                        return collaborator.compute(seed) + collaborator.compute(seed + 1);
                    }

                    public int readsCounter(int seed) {
                        return this.counter;
                    }

                    public int writesCounter(int seed) {
                        this.counter = seed;
                        return this.counter;
                    }

                    public String makesBuilder(int seed) {
                        StringBuilder sb = new StringBuilder("seed");
                        return sb.append('!').toString();
                    }
                }
                """;

        Source() {
            super(URI.create("string:///redirectfixture/Target.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
