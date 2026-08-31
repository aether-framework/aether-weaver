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

class OversizeMethodTest {

    private static final ClassDesc TARGET = ClassDesc.of("oversize.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(OversizeMethodTest.class.getName());

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static void onRun() {
        // Nothing: this test never runs woven code.
    }

    @Test
    @DisplayName("AW4003 — an injection that no longer fits is a diagnostic, not a crash")
    void anInjectionThatDoesNotFitIsReported() {
        final byte[] woven = weave(nearlyFull());

        assertThat(codes())
                .as("the alternative is an IllegalArgumentException from an internal writer, "
                        + "escaping the weaver and taking the build with it")
                .containsExactly("AW4003");
        assertThat(woven)
                .as("a class that could not be written is not a class; the target as it arrived "
                        + "still works")
                .isNull();
        assertThat(this.reported.getFirst().remedy())
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the remedy has to say what the author can do about it, and the answer is not "
                        + "'write a smaller handler' — the handler's body is not in the target")
                .contains("fewer injection points");
    }

    @Test
    @DisplayName("the same injection into a method with room is written normally")
    void aMethodWithRoomIsWoven() {
        final byte[] woven = weave(roomy());

        assertThat(this.reported)
                .as("without this the test above would pass against an engine that refused "
                        + "every injection, which is a different bug with the same diagnostic")
                .isEmpty();
        assertThat(woven).isNotNull();
        assertThat(ClassFile.of().verify(woven)).isEmpty();
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static byte[] nearlyFull() {
        // 65534 nops plus the return is exactly 65535 bytes: the largest method the format can
        // hold, and therefore one that any injection at all pushes over.
        return targetWith(65534);
    }

    private static byte[] roomy() {
        return targetWith(16);
    }

    private static byte[] targetWith(final int padding) {
        return ClassFile.of().build(TARGET, builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC)
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                            for (int emitted = 0; emitted < padding; emitted++) {
                                code.nop();
                            }
                            code.return_();
                        }));
    }

    private byte[] weave(final byte[] original) {
        final HandlerRef handler = new HandlerRef(HANDLER_OWNER, "onRun",
                MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC));
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                "run()", MemberSelector.parse("run()"),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                "oversize", 0, 0, "", List.<LocalSpec>of());
        final PlanEntryView entry = new PlanEntry(TARGET, spec, "oversize.Weave",
                Origin.of("test", null),
                new OrderKey(0, "oversize.Weave", handler.name(),
                        handler.type().descriptorString()));

        return new WeavingPipeline(BuiltInPoints.all()::get,
                kind -> InjectorKind.INJECT.id().equals(kind) ? new InjectInjector() : null)
                .weave(ClassFile.of().parse(original), List.of(entry), List.of(), this.reporter);
    }
}
