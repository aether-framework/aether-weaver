package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionCallsTest {

    private static final ClassDesc RECEIVER = ClassDesc.of("com.acme.Widget");

    private static final ExtensionIndex INDEX = ExtensionIndex.of(List.of(
            new WeaveManifest.Extension("com.acme.WidgetExtensions", "com.acme.Widget",
                    "spin", "(I)V")));

    @Nested
    @DisplayName("what it rewrites")
    class Rewrites {

        @Test
        @DisplayName("an invokevirtual on the receiver becomes an invokestatic on the holder")
        void virtualBecomesStatic() {
            final byte[] rewritten = ExtensionCalls.rewrite(
                    caller(Opcode.INVOKEVIRTUAL, RECEIVER, "spin", MethodTypeDesc.of(
                            ConstantDescs.CD_void, ConstantDescs.CD_int)), INDEX);

            assertThat(rewritten).isNotNull();
            assertThat(invocations(rewritten)).singleElement().satisfies(invoke -> {
                assertThat(invoke.opcode()).isEqualTo(Opcode.INVOKESTATIC);
                assertThat(invoke.owner().asInternalName()).isEqualTo("com/acme/WidgetExtensions");
                assertThat(invoke.name().stringValue()).isEqualTo("spin");
                assertThat(invoke.type().stringValue())
                        .as("the receiver becomes parameter zero and nothing else moves")
                        .isEqualTo("(Lcom/acme/Widget;I)V");
            });
        }

        @Test
        @DisplayName("an invokeinterface is rewritten too")
        void interfaceCallIsRewritten() {
            final byte[] rewritten = ExtensionCalls.rewrite(
                    caller(Opcode.INVOKEINTERFACE, RECEIVER, "spin", MethodTypeDesc.of(
                            ConstantDescs.CD_void, ConstantDescs.CD_int)), INDEX);

            assertThat(rewritten).isNotNull();
            assertThat(invocations(rewritten)).singleElement()
                    .extracting(InvokeInstruction::opcode)
                    .isEqualTo(Opcode.INVOKESTATIC);
        }
    }

    @Nested
    @DisplayName("what it refuses to touch")
    class Refusals {

        @Test
        @DisplayName("an invokestatic matching everything but the opcode is left alone")
        void staticCallIsLeftAlone() {
            // Same owner, same name, same descriptor — and a completely different call. Rewriting
            // it would drop a `Widget` in front of arguments that never had one.
            final byte[] original = caller(Opcode.INVOKESTATIC, RECEIVER, "spin",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));

            assertThat(ExtensionCalls.rewrite(original, INDEX))
                    .as("nothing was rewritten, so there is nothing to hand back")
                    .isNull();
        }

        @Test
        @DisplayName("a call on a different owner is left alone")
        void differentOwnerIsLeftAlone() {
            assertThat(ExtensionCalls.rewrite(
                    caller(Opcode.INVOKEVIRTUAL, ClassDesc.of("com.acme.Gadget"), "spin",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int)), INDEX))
                    .isNull();
        }

        @Test
        @DisplayName("a call with a different descriptor is left alone")
        void differentDescriptorIsLeftAlone() {
            assertThat(ExtensionCalls.rewrite(
                    caller(Opcode.INVOKEVIRTUAL, RECEIVER, "spin", MethodTypeDesc.of(
                            ConstantDescs.CD_void, ConstantDescs.CD_long)), INDEX))
                    .isNull();
        }

        @Test
        @DisplayName("an empty index short-circuits before the class is even parsed")
        void emptyIndexDoesNothing() {
            assertThat(ExtensionCalls.rewrite(new byte[]{1, 2, 3}, ExtensionIndex.EMPTY))
                    .as("not even a valid class file, and it must still not be parsed")
                    .isNull();
        }
    }

    // --- building the classes the tests vary -------------------------------------------------

    private static byte[] caller(final Opcode opcode,
                                 final ClassDesc owner,
                                 final String name,
                                 final MethodTypeDesc descriptor) {
        return ClassFile.of().build(ClassDesc.of("probe.Caller"), builder -> builder
                .withMethodBody("call", MethodTypeDesc.of(ConstantDescs.CD_void, owner),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> {
                            if (opcode != Opcode.INVOKESTATIC) {
                                code.aload(0);
                            }
                            // One operand per declared parameter, of the right width. A test that
                            // pushed an int for a long argument fails while the class is being
                            // built, which looks exactly like the rewrite having gone wrong.
                            for (final ClassDesc parameter : descriptor.parameterList()) {
                                if (ConstantDescs.CD_long.equals(parameter)) {
                                    code.lconst_0();
                                } else if (ConstantDescs.CD_int.equals(parameter)) {
                                    code.bipush(7);
                                } else {
                                    throw new IllegalArgumentException(
                                            "no operand for " + parameter.displayName());
                                }
                            }
                            switch (opcode) {
                                case INVOKEVIRTUAL -> code.invokevirtual(owner, name, descriptor);
                                case INVOKEINTERFACE -> code.invokeinterface(owner, name, descriptor);
                                case INVOKESTATIC -> code.invokestatic(owner, name, descriptor);
                                default -> throw new IllegalArgumentException(opcode.name());
                            }
                            code.return_();
                        }));
    }

    private static List<InvokeInstruction> invocations(final byte[] bytes) {
        final List<InvokeInstruction> found = new ArrayList<>();
        ClassFile.of().parse(bytes).methods().forEach(method -> method.code()
                .ifPresent(code -> code.elementStream()
                        .filter(InvokeInstruction.class::isInstance)
                        .map(InvokeInstruction.class::cast)
                        .forEach(found::add)));
        return found;
    }
}
