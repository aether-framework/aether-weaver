package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
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
import de.splatgames.aether.weaver.engine.plan.OrderKey;
import de.splatgames.aether.weaver.engine.plan.PlanEntry;
import de.splatgames.aether.weaver.engine.verify.StructuralCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
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

class ExceptionRangeTest {

    private static final ClassDesc TARGET = ClassDesc.of("rangefixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    private static final byte[] FIXTURE = compileFixture();

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void boom() {
            throw new IllegalStateException("boom");
        }

        public static void note() {
            // Deliberately empty: a handler that threw would pre-empt the failure under test.
        }
    }

    @Test
    @DisplayName("the fixture really guards the site — without this the rest proves nothing")
    void theFixtureGuardsTheSite() {
        assertThat(callIsProtected(FIXTURE, "mark"))
                .as("if the call the injection attaches to were outside the target's try, "
                        + "everything below would hold for an uninteresting reason and this whole "
                        + "file would be measuring nothing")
                .isTrue();
    }

    @Test
    @DisplayName("the injected call is outside the range, and the target's own call is still in it")
    void theSplitIsSurgical() {
        final byte[] woven = weave();

        assertThat(callIsProtected(woven, "boom"))
                .as("the handler call is what had to leave the protected range")
                .isFalse();
        assertThat(callIsProtected(woven, "mark"))
                .as("and the target's own call had to stay in it. A split that simply dropped "
                        + "the range would satisfy the assertion above and quietly stop the target "
                        + "handling its own failures")
                .isTrue();
    }

    @Test
    @DisplayName("an injection at the very first instruction of a try emits no empty range")
    void aSplitAtTheRangeStartDropsTheEmptyPiece() {
        final byte[] woven = weave("guardedStatic", "boom", "#stat");

        assertThat(StructuralCheck.of(woven))
                .as("nothing precedes the injection inside that try, so the piece running up to "
                        + "it covers no instruction at all. A range whose start equals its end "
                        + "protects nothing, and this engine refuses one as AW4004 — so emitting "
                        + "it would turn a correct weave into a refused class")
                .isEmpty();
        assertThat(ClassFile.of().verify(woven)).isEmpty();
        assertThatThrownBy(() -> run(woven, "guardedStatic"))
                .as("and the split still has to do its job")
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AW1131 — the split is reported, because it changes what the target observes")
    void theSplitIsReported() {
        weave();

        assertThat(this.reported)
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code().code()).isEqualTo("AW1131");
                    assertThat(diagnostic.severity()).isEqualTo(Severity.INFO);
                });
    }

    @Test
    @DisplayName("the handler's exception is not caught by the target")
    void aHandlerExceptionPassesTheTargetsCatch() {
        final byte[] woven = weave();

        assertThat(ClassFile.of().verify(woven))
                .as("a split range is still a range: bounds in order, inside the code, and never "
                        + "empty — a wrong one is AW4004 rather than a wrong answer")
                .isEmpty();

        assertThatThrownBy(() -> run(woven))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .as("This expectation used to be \"caught:boom\", asserted on purpose while the "
                        + "defect stood. The target's own catch handled a failure that did not come "
                        + "from the target, and guarded() returned normally as though nothing had "
                        + "gone wrong — silent by construction, because a target catching Exception "
                        + "makes a failed handler indistinguishable from a successful one.\n\n"
                        + "The range is now split around the injected call, so the handler's "
                        + "exception leaves the method. Anything that reverts the split turns this "
                        + "back into a returned string rather than a throw, which is why the "
                        + "assertion is on the exception and not on the absence of one.")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    @DisplayName("the target's own exceptions are still caught, on both sides of the injection")
    void theTargetsOwnFailuresAreUntouched() throws Exception {
        assertThat(run(weave("throwsInside", "note"), "throwsInside"))
                .as("the other half, and the one a careless split breaks: the target's catch "
                        + "still has to cover the target's code. A split that simply dropped the "
                        + "range would pass the propagation test and silently stop the target "
                        + "handling its own failures")
                .isEqualTo("caught:mine");
        assertThat(run(weave("throwsBefore", "note"), "throwsBefore"))
                .as("and specifically the code BEFORE the injection, which lives in a different "
                        + "piece of the split range. Dropping that piece is invisible unless the "
                        + "target can actually throw there — which is why the fixture makes it")
                .isEqualTo("caught:early");
    }

    // --- fixtures -------------------------------------------------------------------------

    private static boolean callIsProtected(final byte[] bytes, final String called) {
        final MethodModel guarded = ClassFile.of().parse(bytes).methods().stream()
                .filter(method -> "guarded".equals(method.methodName().stringValue()))
                .findFirst().orElseThrow();
        final List<CodeElement> elements = guarded.code().orElseThrow().elementList();

        int call = -1;
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index) instanceof final InvokeInstruction invoke
                    && called.equals(invoke.name().stringValue())) {
                call = index;
            }
        }
        assertThat(call).as("%s must be called by guarded() at all", called).isNotEqualTo(-1);

        for (final CodeElement element : elements) {
            if (!(element instanceof final ExceptionCatch handler)) {
                continue;
            }
            final int start = indexOf(elements, handler.tryStart());
            final int end = indexOf(elements, handler.tryEnd());
            if (start >= 0 && end >= 0 && start < call && call < end) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(final List<CodeElement> elements, final Label label) {
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index) instanceof final LabelTarget bound
                    && bound.label().equals(label)) {
                return index;
            }
        }
        return -1;
    }

    private byte[] weave() {
        return weave("guarded");
    }

    private byte[] weave(final String method) {
        return weave(method, "boom");
    }

    private byte[] weave(final String method, final String handled) {
        return weave(method, handled, "#mark");
    }

    private byte[] weave(final String method, final String handled, final String target) {
        final HandlerRef handler = new HandlerRef(HANDLER_OWNER, handled,
                MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC));
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                method, MemberSelector.parse(method),
                List.of(PointSpec.builtIn(Point.INVOKE).target(target).build()), List.of(),
                "range", 0, 0, "", List.<LocalSpec>of());
        final PlanEntryView entry = new PlanEntry(TARGET, spec, "rangefixture.Weave",
                Origin.of("test", null),
                new OrderKey(0, "rangefixture.Weave", handler.name(),
                        handler.type().descriptorString()));

        final byte[] woven = new WeavingPipeline(BuiltInPoints.all()::get,
                kind -> InjectorKind.INJECT.id().equals(kind) ? new InjectInjector() : null)
                .weave(ClassFile.of().parse(FIXTURE), List.of(entry), List.of(), this.reporter);
        return woven == null ? FIXTURE : woven;
    }

    private static Object run(final byte[] woven) throws Exception {
        return run(woven, "guarded");
    }

    private static Object run(final byte[] woven, final String method) throws Exception {
        final ClassLoader loader = new ClassLoader(ExceptionRangeTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("rangefixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Class<?> type = loader.loadClass("rangefixture.Target");
        final Method target = type.getDeclaredMethod(method);
        return target.invoke(type.getDeclaredConstructor().newInstance());
    }

    private static byte[] compileFixture() {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-range");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null,
                        List.of("-g", "-classpath", System.getProperty("java.class.path")), null,
                        List.of(new Source())).call();
                if (!ok) {
                    throw new AssertionError("the range fixture must compile");
                }
            }
            return Files.readAllBytes(output.resolve("rangefixture/Target.class"));
        } catch (final Exception failed) {
            throw new AssertionError("could not build the range fixture", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private static final String CODE = """
                package rangefixture;

                public class Target {

                    // An ordinary defensive try/catch, of the kind that is everywhere and that
                    // nobody writes with weaving in mind.
                    public String guarded() {
                        try {
                            mark();
                            return "ok";
                        } catch (RuntimeException failed) {
                            return "caught:" + failed.getMessage();
                        }
                    }

                    // The target's own failure BEFORE the injection point, which lands in the
                    // piece of the range that runs up to the injected code.
                    public String throwsBefore() {
                        try {
                            risky();
                            mark();
                            return "ok";
                        } catch (RuntimeException failed) {
                            return "caught:" + failed.getMessage();
                        }
                    }

                    public void risky() {
                        throw new IllegalArgumentException("early");
                    }

                    // The target's OWN failure after it, in the piece that resumes past the
                    // injected code.
                    public String throwsInside() {
                        try {
                            mark();
                            throw new IllegalArgumentException("mine");
                        } catch (RuntimeException failed) {
                            return "caught:" + failed.getMessage();
                        }
                    }

                    // A try whose very first instruction is the call: a static one, so there is
                    // no aload_0 in front of it and nothing at all before the injection point.
                    public String guardedStatic() {
                        try {
                            stat();
                            return "ok";
                        } catch (RuntimeException failed) {
                            return "caught:" + failed.getMessage();
                        }
                    }

                    public static void stat() {
                    }

                    public void mark() {
                    }
                }
                """;

        Source() {
            super(URI.create("string:///rangefixture/Target.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
