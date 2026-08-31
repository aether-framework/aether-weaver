package de.splatgames.aether.weaver.engine.inject;

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
import de.splatgames.aether.weaver.engine.plan.OrderKey;
import de.splatgames.aether.weaver.engine.plan.PlanEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResultCaptureTest {

    private static final ClassDesc TARGET = ClassDesc.of("resultfixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    static final List<Object> SEEN = new ArrayList<>();

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void onInt(final int produced) {
            SEEN.add(produced);
        }

        public static void onLong(final long produced) {
            SEEN.add(produced);
        }

        public static void onNothing() {
            SEEN.add("ran");
        }
    }

    @Test
    @DisplayName("the handler receives the call's result and the target still returns it")
    void theResultIsCopiedRatherThanTaken() throws Exception {
        SEEN.clear();
        final byte[] woven = weave("runsInt", "#produceInt", handler("onInt", ConstantDescs.CD_int),
                true);

        assertThat(this.reported).isEmpty();
        assertThat(ClassFile.of().verify(woven))
                .as("consuming the value instead of copying it leaves the stack a slot short, "
                        + "which is what the verifier is for")
                .isEmpty();
        assertThat(invoke(woven, "runsInt"))
                .as("@Result observes the call; it does not intercept it. A target that lost "
                        + "its own value would be @Redirect wearing a different annotation")
                .isEqualTo(7);
        assertThat(SEEN).containsExactly(7);
    }

    @Test
    @DisplayName("a two-slot result is copied without splitting it")
    void aWideResultIsCopied() throws Exception {
        SEEN.clear();
        final byte[] woven = weave("runsLong", "#produceLong",
                handler("onLong", ConstantDescs.CD_long), true);

        assertThat(this.reported).isEmpty();
        assertThat(ClassFile.of().verify(woven))
                .as("dup would copy half a long and the verifier would refuse the method")
                .isEmpty();
        assertThat(invoke(woven, "runsLong")).isEqualTo(9L);
        assertThat(SEEN).containsExactly(9L);
    }

    @Test
    @DisplayName("AW1104 — @Result where the matched call returns void")
    void aVoidCallHasNoResult() throws Exception {
        SEEN.clear();
        final byte[] woven = weave("runsVoid", "#produceNothing",
                handler("onNothing", ConstantDescs.CD_void), true);

        assertThat(codes())
                .as("without the annotation this shape is a perfectly ordinary injection, so the "
                        + "declaration is what makes it wrong — and nothing else would notice")
                .containsExactly("AW1104");
        assertThat(invoke(woven, "runsVoid"))
                .as("the class still round-trips; what must not have happened is the injection")
                .isNull();
        assertThat(SEEN)
                .as("a refused emission must emit nothing rather than something almost right")
                .isEmpty();
    }

    @Test
    @DisplayName("the same void call without @Result is injected normally")
    void aVoidCallWithoutTheAnnotationIsFine() throws Exception {
        SEEN.clear();
        final byte[] woven = weave("runsVoid", "#produceNothing",
                handler("onNothing", ConstantDescs.CD_void), false);

        assertThat(this.reported)
                .as("without this the test above would pass against an engine that refused "
                        + "every INVOKE_AFTER on a void call, which is a different defect")
                .isEmpty();
        assertThat(invoke(woven, "runsVoid")).isNull();
        assertThat(SEEN).containsExactly("ran");
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static HandlerRef handler(final String name, final ClassDesc... parameters) {
        final ClassDesc[] declared = ConstantDescs.CD_void.equals(parameters[0])
                ? new ClassDesc[0]
                : parameters;
        return new HandlerRef(HANDLER_OWNER, name,
                MethodTypeDesc.of(ConstantDescs.CD_void, declared), Set.of(AccessFlag.STATIC));
    }

    private byte[] weave(final String method, final String target, final HandlerRef handler,
                         final boolean capturesResult) {
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                method, MemberSelector.parse(method),
                List.of(PointSpec.builtIn(Point.INVOKE_AFTER).target(target).build()), List.of(),
                "result", 0, 0, "", List.<LocalSpec>of(), capturesResult);
        final PlanEntryView entry = new PlanEntry(TARGET, spec, "resultfixture.Weave",
                Origin.of("test", null),
                new OrderKey(0, "resultfixture.Weave", handler.name(),
                        handler.type().descriptorString()));

        return new WeavingPipeline(BuiltInPoints.all()::get,
                kind -> InjectorKind.INJECT.id().equals(kind) ? new InjectInjector() : null)
                .weave(ClassFile.of().parse(fixture()), List.of(entry), List.of(), this.reporter);
    }

    private static byte[] fixture() {
        return ClassFile.of().build(TARGET, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC);
            builder.withMethodBody("produceInt", MethodTypeDesc.of(ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.loadConstant(7).ireturn());
            builder.withMethodBody("produceLong", MethodTypeDesc.of(ConstantDescs.CD_long),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.loadConstant(9L).lreturn());
            builder.withMethodBody("produceNothing", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_());
            builder.withMethodBody("runsInt", MethodTypeDesc.of(ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.invokestatic(TARGET, "produceInt",
                            MethodTypeDesc.of(ConstantDescs.CD_int)).ireturn());
            builder.withMethodBody("runsLong", MethodTypeDesc.of(ConstantDescs.CD_long),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.invokestatic(TARGET, "produceLong",
                            MethodTypeDesc.of(ConstantDescs.CD_long)).lreturn());
            builder.withMethodBody("runsVoid", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.invokestatic(TARGET, "produceNothing",
                            MethodTypeDesc.of(ConstantDescs.CD_void)).return_());
        });
    }

    private static Object invoke(final byte[] woven, final String method) throws Exception {
        final ClassLoader loader = new ClassLoader(ResultCaptureTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("resultfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        return loader.loadClass("resultfixture.Target").getDeclaredMethod(method).invoke(null);
    }
}
