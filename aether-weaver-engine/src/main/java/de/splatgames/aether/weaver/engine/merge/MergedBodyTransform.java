package de.splatgames.aether.weaver.engine.merge;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.util.Objects;

/**
 * Rewrites the references a body makes to its own weave class as it moves into the target.
 *
 * <p>A merged method arrives still speaking of the weave: it reads the weave's fields, calls the
 * weave's methods, and names a shadowed member under the weave's own spelling. Each such
 * instruction is re-emitted against the target, under the name and with the opcode
 * {@link MemberBindings} resolved for it.
 *
 * <p>It matches on the owner still being the weave type, which is why it has to run before the
 * remapper that rewrites the type itself; {@link StructuralWeaver} composes the two in that order.
 * Every other element is passed on unchanged.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MergedBodyTransform {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private MergedBodyTransform() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the transform for one weave's bodies against one target.
     *
     * @param weaveType the weave class being dissolved; must not be {@code null}
     * @param target    the class its members are moving into; must not be {@code null}
     * @param bindings  the bindings resolved for that pair; must not be {@code null}
     * @return a transform that rewrites the weave's own member references and passes everything else
     *         through
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    static CodeTransform of(@NotNull final ClassDesc weaveType,
                            @NotNull final ClassDesc target,
                            @NotNull final MemberBindings bindings) {
        Objects.requireNonNull(weaveType, "weaveType");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bindings, "bindings");

        return (builder, element) -> {
            if (!rebind(builder, element, weaveType, target, bindings)) {
                builder.accept(element);
            }
        };
    }

    /**
     * Re-emits one instruction against the target, if it names a member of the weave.
     *
     * <p>A bound call is emitted with the opcode of its {@link MemberBindings.MethodRebind} rather
     * than the one written in the weave, because a shadowed member's real flags in the target may
     * call for another. Every shadowed member, merged member and handler the weave owns has such a
     * binding. A call the bindings do not know is a call to one of the weave's own accessors or
     * invokers, which are generated on the target from their declaration and never bound, so its name
     * and its opcode are re-emitted unchanged from the call site. A field access keeps its opcode
     * either way: only the owner and the name change.
     *
     * @param builder   where the replacement is written; must not be {@code null}
     * @param element   the instruction to consider; must not be {@code null}
     * @param weaveType the weave class being dissolved; must not be {@code null}
     * @param target    the class its members are moving into; must not be {@code null}
     * @param bindings  the bindings resolved for that pair; must not be {@code null}
     * @return whether the element was replaced, so that the caller does not also copy it
     */
    private static boolean rebind(@NotNull final CodeBuilder builder,
                                  @NotNull final CodeElement element,
                                  @NotNull final ClassDesc weaveType,
                                  @NotNull final ClassDesc target,
                                  @NotNull final MemberBindings bindings) {
        if (element instanceof InvokeInstruction invoke
                && weaveType.equals(invoke.owner().asSymbol())) {
            final MemberBindings.MethodRebind rebind =
                    bindings.method(invoke.name().stringValue(), invoke.typeSymbol());
            if (rebind == null) {
                // A handler calling another handler: not in the member list, but still the weave's
                // own method, so it moves to the target under its own name.
                builder.invoke(invoke.opcode(), target, invoke.name().stringValue(),
                        invoke.typeSymbol(), invoke.isInterface());
                return true;
            }
            builder.invoke(rebind.opcode(), target, rebind.targetName(), invoke.typeSymbol(),
                    rebind.isInterface());
            return true;
        }
        if (element instanceof FieldInstruction access
                && weaveType.equals(access.owner().asSymbol())) {
            final MemberBindings.FieldRebind rebind = bindings.field(access.name().stringValue());
            final String name = rebind == null ? access.name().stringValue() : rebind.targetName();
            builder.fieldAccess(access.opcode(), target, name, access.typeSymbol());
            return true;
        }
        return false;
    }
}
