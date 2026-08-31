package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
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
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UninjectableMethodTest {

    private static final ClassDesc TARGET = ClassDesc.of("uninjectable.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of("uninjectable.Handlers");

    private final List<Diagnostic> reported = new ArrayList<>();

    @Test
    @DisplayName("AW1025 — a native target method, distinct from an abstract one")
    void nativeIsItsOwnRefusal() {
        weave("nativeWork");

        assertThat(codes())
                .as("both are bodyless, and reporting AW1023 for a native method sends the author "
                        + "looking for an implementation to inject into instead — there is none")
                .containsExactly("AW1025");
        assertThat(this.reported.getFirst().remedy().orElseThrow()).contains("@Redirect");
    }

    @Test
    @DisplayName("AW1023 — an abstract target method keeps its own code")
    void abstractIsUnchanged() {
        weave("abstractWork");

        assertThat(codes()).containsExactly("AW1023");
    }

    @Test
    @DisplayName("AW1024 — a synthetic target method, which would have worked")
    void syntheticIsRefusedEvenThoughItWouldWork() {
        weave("syntheticWork");

        assertThat(codes())
                .as("it has a body and the injection would emit and verify; what it would not do "
                        + "is survive the next recompilation")
                .containsExactly("AW1024");
        assertThat(this.reported.getFirst().details())
                .anyMatch(detail -> detail.contains("recompilation"));
    }

    @Test
    @DisplayName("a bridge method is refused as the same thing under a different flag")
    void bridgesCountAsSynthetic() {
        weave("bridgeWork");

        assertThat(codes()).containsExactly("AW1024");
    }

    @Test
    @DisplayName("an ordinary method is still injected into")
    void anOrdinaryMethodIsUntouchedByAllOfThis() {
        assertThat(weave("work"))
                .as("three new refusals in one place is three chances to refuse everything")
                .isNotNull();
        assertThat(this.reported).isEmpty();
    }

    // -------------------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private byte[] weave(final String method) {
        final ClassModel model = ClassFile.of().parse(fixture());
        final HandlerRef handler = new HandlerRef(HANDLER_OWNER, "onWork",
                MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC));
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                method, MemberSelector.parse(method),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(), "refusal", 0, 0, "",
                List.of());
        final PlanEntryView entry = new PlanEntry(TARGET, spec, "uninjectable.Weave",
                Origin.of("test", null),
                new OrderKey(0, "uninjectable.Weave", handler.name(),
                        handler.type().descriptorString()));

        return new WeavingPipeline(
                BuiltInPoints.all()::get,
                kind -> InjectorKind.INJECT.id().equals(kind) ? new InjectInjector() : null)
                .weave(model, List.of(entry), List.of(), (Reporter) this.reported::add);
    }

    private static byte[] fixture() {
        final MethodTypeDesc returnsString = MethodTypeDesc.of(ConstantDescs.CD_String);
        return ClassFile.of().build(TARGET, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT);
            builder.withMethodBody("work", returnsString, ClassFile.ACC_PUBLIC,
                    code -> code.ldc("done").areturn());
            builder.withMethodBody("syntheticWork", returnsString,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC,
                    code -> code.ldc("generated").areturn());
            builder.withMethodBody("bridgeWork", returnsString,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE,
                    code -> code.ldc("bridged").areturn());
            builder.withMethod("nativeWork", returnsString,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_NATIVE, method -> { });
            builder.withMethod("abstractWork", returnsString,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, method -> { });
        });
    }
}
