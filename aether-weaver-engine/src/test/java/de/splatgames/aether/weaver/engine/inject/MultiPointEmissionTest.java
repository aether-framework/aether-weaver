package de.splatgames.aether.weaver.engine.inject;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MultiPointEmissionTest {

    @Test
    @DisplayName("a RETURN injection lands immediately before the return, whatever else was woven")
    void aReturnInjectionLandsBeforeTheReturn() {
        final Weaver weaver = Weaver.builder().weaves(List.of(twoPoints())).build();

        final byte[] woven = weaver.weave("bench/Target", target());
        assertThat(woven).as("the fixture must be woven, or this asserts nothing").isNotNull();

        final List<String> tail = lastThreeInstructions(woven);
        assertThat(tail)
                .as("the handler must land after the value is computed and immediately before "
                        + "the return. It used to land before the constant load, because the HEAD "
                        + "injection's own instruction had shifted this one's element counting")
                .containsExactly("ldc", "invokestatic onReturn", "areturn");
    }

    // -------------------------------------------------------------------------------------

    private static List<String> lastThreeInstructions(final byte[] woven) {
        final List<String> names = new ArrayList<>();
        ClassFile.of().parse(woven).methods().stream()
                .filter(method -> method.methodName().equalsString("greet"))
                .findFirst().orElseThrow()
                .code().map(CodeModel::elementStream).orElseThrow()
                .filter(element -> element instanceof Instruction)
                .forEach(element -> {
                    if (element instanceof final InvokeInstruction invoke) {
                        names.add("invokestatic " + invoke.name().stringValue());
                    } else if (element instanceof ReturnInstruction) {
                        names.add("areturn");
                    } else {
                        names.add("ldc");
                    }
                });
        return names.subList(Math.max(0, names.size() - 3), names.size());
    }

    private static WeaveClass twoPoints() {
        return new WeaveClass(ClassDesc.of("bench.Both"),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of("bench.Target"))),
                Weave.Kind.STATIC, 0, Require.REQUIRED, Phase.DEFAULT, Set.of(), List.of(),
                List.of(), List.of(injection("onHead", Point.HEAD),
                        injection("onReturn", Point.RETURN)),
                Origin.of("test", null));
    }

    private static InjectorSpec injection(final String handler, final Point point) {
        return new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of("bench.Both"), handler,
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                "greet()", MemberSelector.parse("greet()"),
                List.of(PointSpec.builtIn(point).build()), List.of(),
                handler, 0, 0, "", List.of());
    }

    private static byte[] target() {
        return ClassFile.of().build(ClassDesc.of("bench.Target"), builder -> builder
                .withMethodBody("greet", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC, code -> code.ldc("hello").areturn()));
    }
}
