package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.At;
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
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceHandlerTest {

    private static final Path OUTPUT = compileFixtures();

    private static final ClassDesc TARGET = ClassDesc.of("handlerfixture.Session");

    private static final ClassDesc WEAVE = ClassDesc.of("handlerfixture.SessionTracing");

    private final List<Diagnostic> reported = new ArrayList<>();

    @Nested
    @DisplayName("the merged handler runs as a method of the target")
    class EndToEnd {

        @Test
        @DisplayName("it reads the target's own state and writes the weave's merged field")
        void theHandlerSeesTheTarget() throws Exception {
            final byte[] woven = weave(handler("onOpen", false));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();

            final Object session = instantiate(woven);
            call(session, "open");
            call(session, "open");

            assertThat(call(session, "trace"))
                    .as("'the-session' is the TARGET's field, read from a handler that was written "
                            + "in the weave class; 2 is the weave's own merged field, counting on "
                            + "the target instance")
                    .isEqualTo("the-session:2");
        }

        @Test
        @DisplayName("the weave class is not mentioned in the woven bytes at all")
        void theWeaveClassIsGone() {
            final byte[] woven = weave(handler("onOpen", false));

            assertThat(new String(woven, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as("an instance weave is dissolved. A surviving reference would link against "
                            + "a class the weave itself declares no longer participates at runtime")
                    .doesNotContain("handlerfixture/SessionTracing");
        }

        @Test
        @DisplayName("weaving twice from the same inputs is byte-identical")
        void emissionIsDeterministic() {
            assertThat(weave(handler("onOpen", false)))
                    .isEqualTo(weave(handler("onOpen", false)));
        }
    }

    @Nested
    @DisplayName("the instruction follows rule R6")
    class Opcodes {

        @Test
        @DisplayName("a package-private handler is called with invokevirtual")
        void packagePrivateUsesInvokevirtual() {
            assertThat(callsTo(weave(handler("onOpen", false)), "onOpen"))
                    .containsExactly(Opcode.INVOKEVIRTUAL);
        }

        @Test
        @DisplayName("a private handler is called with invokespecial")
        void privateUsesInvokespecial() {
            assertThat(callsTo(weave(handler("onQuiet", true)), "onQuiet"))
                    .as("invokevirtual runs here too — the caller and the callee are the same "
                            + "class, so nestmate rules permit it. It dispatches virtually on a "
                            + "member that has no virtual dispatch, and the two disagree the "
                            + "moment the target is subclassed")
                    .containsExactly(Opcode.INVOKESPECIAL);
        }

        @Test
        @DisplayName("the call names the target, not the weave")
        void theCallNamesTheTarget() {
            final List<String> owners = new ArrayList<>();
            ClassFile.of().parse(weave(handler("onOpen", false))).methods().stream()
                    .filter(method -> method.methodName().equalsString("open"))
                    .findFirst().orElseThrow()
                    .code().orElseThrow()
                    .elementList().forEach(element -> {
                        if (element instanceof InvokeInstruction invoke
                                && invoke.name().equalsString("onOpen")) {
                            owners.add(invoke.owner().asInternalName());
                        }
                    });

            assertThat(owners).containsExactly("handlerfixture/Session");
        }
    }

    @Nested
    @DisplayName("a redirect by an instance handler, where the receiver has nowhere to go")
    class RedirectedByAnInstanceHandler {

        @Test
        @DisplayName("the operands are slid aside and the handler runs on the target")
        void theReceiverIsSlidUnderneath() throws Exception {
            final byte[] woven = redirect();

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("the long operand occupies two slots. Sliding the receiver underneath "
                            + "by index rather than by width reads its high half, which is a "
                            + "verify error — so a clean verify is what makes this test mean "
                            + "something")
                    .isEmpty();

            assertThat(call(instantiate(woven), "compute"))
                    .as("'woven:' proves the redirect replaced the operation; 'the-session' proves "
                            + "the handler's own `this` is the target instance; the value and the "
                            + "suffix prove the operands came back in order")
                    .isEqualTo("woven:the-session:9000000000!");
        }

        @Test
        @DisplayName("the target's own operation no longer runs")
        void theOriginalIsGone() throws Exception {
            assertThat(call(instantiate(redirect()), "compute").toString())
                    .doesNotStartWith("plain:");
        }

        private byte[] redirect() {
            final HandlerRef handler = new HandlerRef(WEAVE, "onLabel",
                    MethodTypeDesc.of(ConstantDescs.CD_String, TARGET, ConstantDescs.CD_long,
                            ConstantDescs.CD_String),
                    Set.of());
            final InjectorSpec spec = new InjectorSpec(InjectorKind.REDIRECT, handler,
                    "compute()", MemberSelector.parse("compute()"),
                    List.of(PointSpec.builtIn(Point.INVOKE).target("#label").build()), List.of(),
                    "onLabel", 0, 0, "", List.of());
            return weaveWith(spec);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("AW1005 — an instance handler in a static weave")
        void instanceHandlerInAStaticWeave() {
            final byte[] woven = weave(Weave.Kind.STATIC, handler("onOpen", false), "open");

            assertThat(codes())
                    .as("a static weave is not dissolved, so its handler stays a method of a class "
                            + "the target has no instance of")
                    .contains("AW1005");
            assertThat(woven).isNull();
        }

        @Test
        @DisplayName("AW1005 — an instance handler targeting a static method")
        void instanceHandlerAtAStaticTarget() {
            final byte[] woven = weave(Weave.Kind.INSTANCE, handler("onOpen", false), "utility");

            assertThat(codes())
                    .as("the weave dissolves, so the handler IS a method of the target — but a "
                            + "static target method has no `this` to invoke it against, and no "
                            + "argument arrangement produces one")
                    .contains("AW1005");
            assertThat(woven).isNull();
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static HandlerRef handler(final String name, final boolean isPrivate) {
        return new HandlerRef(WEAVE, name, MethodTypeDesc.of(ConstantDescs.CD_void),
                isPrivate ? Set.of(AccessFlag.PRIVATE) : Set.of());
    }

    private byte[] weave(final HandlerRef handler) {
        return weave(Weave.Kind.INSTANCE, handler, "open");
    }

    private byte[] weave(final Weave.Kind kind, final HandlerRef handler,
                         final String targetMethod) {
        return weaveWith(kind, new InjectorSpec(InjectorKind.INJECT, handler,
                targetMethod, MemberSelector.parse(targetMethod),
                List.of(PointSpec.builtIn(Point.HEAD).shift(At.Shift.NONE).build()), List.of(),
                handler.name(), 0, 0, "", List.of()));
    }

    private byte[] weaveWith(final InjectorSpec spec) {
        return weaveWith(Weave.Kind.INSTANCE, spec);
    }

    private byte[] weaveWith(final Weave.Kind kind, final InjectorSpec spec) {
        final WeaveClass weave = new WeaveClass(WEAVE,
                List.of(new TargetRef(TARGET, true)), kind, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(),
                List.<WeaveMember>of(
                        new WeaveMember.Shadowed("name", ConstantDescs.CD_String,
                                Set.of(AccessFlag.PRIVATE), "name", false),
                        new WeaveMember.Merged("opens", ConstantDescs.CD_int,
                                Set.of(AccessFlag.PRIVATE), true, false),
                        new WeaveMember.Merged("trace",
                                MethodTypeDesc.of(ConstantDescs.CD_String),
                                Set.of(AccessFlag.PUBLIC), false, false)),
                List.of(spec), Origin.of("test", null));

        return Weaver.builder()
                .weaves(List.of(weave))
                .weaveBytes(type -> WEAVE.equals(type)
                        ? read("handlerfixture/SessionTracing.class") : null)
                .diagnostics(this.reported::add)
                .build()
                .weave("handlerfixture/Session", read("handlerfixture/Session.class"));
    }

    private static List<Opcode> callsTo(final byte[] woven, final String name) {
        final List<Opcode> opcodes = new ArrayList<>();
        ClassFile.of().parse(woven).methods().stream()
                .filter(method -> method.methodName().equalsString("open"))
                .findFirst().orElseThrow()
                .code().orElseThrow()
                .elementList().forEach(element -> {
                    if (element instanceof InvokeInstruction invoke
                            && invoke.name().equalsString(name)) {
                        opcodes.add(invoke.opcode());
                    }
                });
        return opcodes;
    }

    private static Object instantiate(final byte[] woven) throws Exception {
        final ClassLoader loader = new ClassLoader(InstanceHandlerTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("handlerfixture.Session".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        return loader.loadClass("handlerfixture.Session").getDeclaredConstructor().newInstance();
    }

    private static Object call(final Object instance, final String method) throws Exception {
        return instance.getClass().getMethod(method).invoke(instance);
    }

    private static byte[] read(final String resource) {
        try {
            return Files.readAllBytes(OUTPUT.resolve(resource));
        } catch (final Exception failed) {
            throw new AssertionError("could not read " + resource, failed);
        }
    }

    private static Path compileFixtures() {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-handler");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null, List.of("-g"), null,
                        List.of(new TargetSource(), new WeaveSource())).call();
                if (!ok) {
                    throw new AssertionError("the handler fixtures must compile");
                }
            }
            return output;
        } catch (final Exception failed) {
            throw new AssertionError("could not build the handler fixtures", failed);
        }
    }

    private static final class TargetSource extends SimpleJavaFileObject {

        private static final String CODE = """
                package handlerfixture;

                public class Session {

                    private final String name = "the-session";

                    public void open() {
                    }

                    public static void utility() {
                    }

                    // The operation a redirect replaces. The long is deliberate: it occupies two
                    // slots, so sliding the receiver underneath it by index rather than by width
                    // reads its high half.
                    String label(long value, String suffix) {
                        return "plain:" + value + suffix;
                    }

                    public String compute() {
                        return label(9000000000L, "!");
                    }
                }
                """;

        TargetSource() {
            super(URI.create("string:///handlerfixture/Session.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }

    private static final class WeaveSource extends SimpleJavaFileObject {

        private static final String CODE = """
                package handlerfixture;

                public final class SessionTracing {

                    // @Shadow — Session really has this.
                    private String name;

                    // @Unique — new state on Session.
                    private int opens;

                    // @Inject(method = "open()", at = @At(Point.HEAD)) — an ordinary instance
                    // method, using `this`, which after weaving IS the Session.
                    void onOpen() {
                        this.opens++;
                    }

                    private void onQuiet() {
                        this.opens++;
                    }

                    // @Redirect — an instance handler replacing an operation. Reading `this.name`
                    // is what proves the receiver arrived, and arrived first.
                    String onLabel(Session receiver, long value, String suffix) {
                        return "woven:" + this.name + ':' + value + suffix;
                    }

                    public String trace() {
                        return this.name + ':' + this.opens;
                    }
                }
                """;

        WeaveSource() {
            super(URI.create("string:///handlerfixture/SessionTracing.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
