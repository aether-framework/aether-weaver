package de.splatgames.aether.weaver.engine.inject;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The operation standing at one element of a body, described as the method a handler would have to
 * be to stand in for it.
 *
 * <p>Three unrelated bytecode shapes are reduced to one here — a call, a field access and an
 * instantiation — so that the redirect and the wrap injectors can ask the same question of a
 * matched position. What they need is the same in all three cases: the values already on the stack,
 * in stack order, the value the operation leaves behind, and a handle that performs it.
 *
 * <p>{@link #inputs()} is stack order rather than descriptor order, which is where the three shapes
 * stop looking alike. An instance call pushes its receiver first and does not mention it in its
 * descriptor; a {@code putfield} pushes the instance and then the value; a {@code new} pushes
 * nothing, because the reference the site was building is the one the handler now produces.
 *
 * @param kind     which of the three shapes the position holds
 * @param inputs   the operation's inputs in stack order, receiver first where there is one
 * @param result   what the operation leaves on the stack, {@code void} where it leaves nothing
 * @param handle   a handle performing the operation, for a wrap to hand to its handler
 * @param describe the operation as it reads in a diagnostic
 * @author Erik Pförtner
 * @since 0.1.0
 */
record RedirectedOperation(@NotNull Kind kind,
                 @NotNull @Unmodifiable List<ClassDesc> inputs,
                 @NotNull ClassDesc result,
                 @NotNull DirectMethodHandleDesc handle,
                 @NotNull String describe) {

    /**
     * Checks that every component is present and takes a defensive copy of the inputs.
     *
     * @throws NullPointerException if any component is {@code null}, or {@code inputs} holds a
     *                              {@code null}
     */
    RedirectedOperation {
        Objects.requireNonNull(kind, "kind");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(describe, "describe");
    }

    /**
     * Which bytecode shape an operation was read from.
     *
     * <p>Only {@link #INSTANTIATION} changes what a caller has to do: it spans several elements,
     * where the other two are one instruction that can be replaced where it stands.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum Kind {

        /**
         * A call: {@code invokevirtual}, {@code invokestatic}, {@code invokeinterface} or
         * {@code invokespecial}.
         */
        INVOCATION,

        /**
         * A field read or write: {@code getfield}, {@code getstatic}, {@code putfield} or
         * {@code putstatic}.
         */
        FIELD_ACCESS,

        /** A {@code new} together with the constructor call that completes it. */
        INSTANTIATION
    }

    /**
     * Reads the operation at one element, if that element holds one.
     *
     * <p>An index outside the body and an element that is not one of the three shapes both answer
     * {@code null}, which is what the callers turn into {@code AW1061}: a position an injection
     * point matched need not be an operation at all.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the element index to read
     * @return the operation, or {@code null} when the position holds none and when a {@code new}
     *         has no reachable constructor call
     * @throws NullPointerException if {@code elements} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    static RedirectedOperation at(@NotNull final List<CodeElement> elements, final int site) {
        Objects.requireNonNull(elements, "elements");
        if (site < 0 || site >= elements.size()) {
            return null;
        }
        return switch (elements.get(site)) {
            case InvokeInstruction invoke -> ofInvocation(invoke);
            case FieldInstruction field -> ofFieldAccess(field);
            case NewObjectInstruction created -> ofInstantiation(elements, site, created);
            default -> null;
        };
    }

    /**
     * Describes a call.
     *
     * @param invoke the call instruction; must not be {@code null}
     * @return the operation, with the receiver ahead of the descriptor's own parameters for
     *         anything but {@code invokestatic}
     */
    @Contract(pure = true)
    @NotNull
    private static RedirectedOperation ofInvocation(@NotNull final InvokeInstruction invoke) {
        final List<ClassDesc> inputs = new ArrayList<>();
        if (invoke.opcode() != Opcode.INVOKESTATIC) {
            // The receiver is pushed first and is not part of the descriptor, which is exactly the
            // asymmetry a handler signature has to mirror.
            inputs.add(invoke.owner().asSymbol());
        }
        inputs.addAll(invoke.typeSymbol().parameterList());
        return new RedirectedOperation(Kind.INVOCATION, inputs, invoke.typeSymbol().returnType(),
                MethodHandleDesc.ofMethod(kindOf(invoke), invoke.owner().asSymbol(),
                        invoke.name().stringValue(), invoke.typeSymbol()),
                invoke.owner().asInternalName().replace('/', '.') + '.'
                        + invoke.name().stringValue() + invoke.typeSymbol().displayDescriptor());
    }

    /**
     * Maps a call opcode to the handle kind that performs the same call.
     *
     * <p>{@code invokestatic} and {@code invokespecial} each split by whether the owner is an
     * interface, which the constant pool records separately from the opcode.
     *
     * @param invoke the call instruction; must not be {@code null}
     * @return the handle kind
     * @throws IllegalStateException if the opcode is not one of the four call opcodes
     */
    @Contract(pure = true)
    @NotNull
    private static DirectMethodHandleDesc.Kind kindOf(@NotNull final InvokeInstruction invoke) {
        return switch (invoke.opcode()) {
            case INVOKESTATIC -> invoke.isInterface()
                    ? DirectMethodHandleDesc.Kind.INTERFACE_STATIC
                    : DirectMethodHandleDesc.Kind.STATIC;
            case INVOKEVIRTUAL -> DirectMethodHandleDesc.Kind.VIRTUAL;
            case INVOKEINTERFACE -> DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL;
            case INVOKESPECIAL -> invoke.isInterface()
                    ? DirectMethodHandleDesc.Kind.INTERFACE_SPECIAL
                    : DirectMethodHandleDesc.Kind.SPECIAL;
            default -> throw new IllegalStateException(
                    "not a call opcode: " + invoke.opcode());
        };
    }

    /**
     * Describes a field read or write.
     *
     * <p>The four opcodes differ in both directions at once: an instance access pushes the owner
     * first, and a write pushes the value and yields {@code void} where a read yields the field's
     * type.
     *
     * @param field the field instruction; must not be {@code null}
     * @return the operation
     * @throws IllegalStateException if the opcode is not one of the four field opcodes
     */
    @Contract(pure = true)
    @NotNull
    private static RedirectedOperation ofFieldAccess(@NotNull final FieldInstruction field) {
        final ClassDesc owner = field.owner().asSymbol();
        final ClassDesc type = field.typeSymbol();
        final List<ClassDesc> inputs = new ArrayList<>();
        final ClassDesc result;
        final DirectMethodHandleDesc.Kind kind;
        switch (field.opcode()) {
            case GETFIELD -> {
                inputs.add(owner);
                result = type;
                kind = DirectMethodHandleDesc.Kind.GETTER;
            }
            case GETSTATIC -> {
                result = type;
                kind = DirectMethodHandleDesc.Kind.STATIC_GETTER;
            }
            case PUTFIELD -> {
                inputs.add(owner);
                inputs.add(type);
                result = ConstantDescs.CD_void;
                kind = DirectMethodHandleDesc.Kind.SETTER;
            }
            case PUTSTATIC -> {
                inputs.add(type);
                result = ConstantDescs.CD_void;
                kind = DirectMethodHandleDesc.Kind.STATIC_SETTER;
            }
            default -> throw new IllegalStateException(
                    "not a field instruction opcode: " + field.opcode());
        }
        return new RedirectedOperation(Kind.FIELD_ACCESS, inputs, result,
                MethodHandleDesc.ofField(kind, owner, field.name().stringValue(), type),
                field.opcode() + " " + field.owner().asInternalName().replace('/', '.')
                        + '.' + field.name().stringValue());
    }

    /**
     * Describes an instantiation, reading its argument types from the constructor call rather than
     * from the {@code new}.
     *
     * <p>The uninitialised reference the {@code new} and its {@code dup} put on the stack is not an
     * input: a handler standing in for the site produces the object itself, so the inputs are the
     * constructor's parameters alone.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the index of the {@code new}
     * @param created  the {@code new} instruction; must not be {@code null}
     * @return the operation, or {@code null} when the matching constructor call is not in this body
     */
    @Contract(pure = true)
    @Nullable
    private static RedirectedOperation ofInstantiation(@NotNull final List<CodeElement> elements,
                                             final int site,
                                             @NotNull final NewObjectInstruction created) {
        final ClassDesc type = created.className().asSymbol();
        final int initializer = initializerOf(elements, site);
        if (initializer < 0) {
            return null;
        }
        final InvokeInstruction call = (InvokeInstruction) elements.get(initializer);
        return new RedirectedOperation(Kind.INSTANTIATION, call.typeSymbol().parameterList(), type,
                // A constructor handle yields the new object, which is exactly what the site's
                // `new`/`dup` scaffolding was producing and what the handler now returns.
                MethodHandleDesc.ofConstructor(type,
                        call.typeSymbol().parameterList().toArray(new ClassDesc[0])),
                "new " + type.displayName() + call.typeSymbol().displayDescriptor());
    }

    /**
     * Finds the constructor call that completes a {@code new}.
     *
     * <p>A nested allocation in the argument list has its own constructor call between the two, so
     * the scan counts the intervening {@code new} instructions and takes the first
     * {@code invokespecial <init>} that is not owed to one of them.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the index of the {@code new}
     * @return the index of its constructor call, or {@code -1} when the scan reaches the end of the
     *         body without finding one
     */
    @Contract(pure = true)
    static int initializerOf(@NotNull final List<CodeElement> elements, final int site) {
        int depth = 0;
        for (int i = site + 1; i < elements.size(); i++) {
            final CodeElement element = elements.get(i);
            if (element instanceof NewObjectInstruction) {
                depth++;
            } else if (element instanceof InvokeInstruction invoke
                    && invoke.opcode() == Opcode.INVOKESPECIAL
                    && ConstantDescs.INIT_NAME.equals(invoke.name().stringValue())) {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /**
     * Returns the operation as a method descriptor, for comparison against a handler and for
     * printing beside one in a diagnostic.
     *
     * @return the descriptor taking {@link #inputs()} and returning {@link #result()}
     */
    @Contract(pure = true)
    @NotNull
    MethodTypeDesc signature() {
        return MethodTypeDesc.of(this.result, this.inputs);
    }

    /**
     * Reports whether a handler of the given descriptor can stand where this operation stands.
     *
     * <p>The operation's inputs have to be a prefix of the handler's parameters rather than all of
     * them, which is what lets a redirect handler append the enclosing method's own arguments. A
     * wrap needs more than this and checks the arity itself.
     *
     * <p>Each comparison runs in the direction the value moves: a stack value flows into a
     * parameter, so the parameter is the declaration and the input is what is found, while the
     * handler's return value flows into the slot the operation's result would have filled.
     *
     * @param handler the handler's descriptor; must not be {@code null}
     * @return {@code true} when the handler accepts every input in order and returns something the
     *         operation's result position accepts
     * @throws NullPointerException if {@code handler} is {@code null}
     */
    @Contract(pure = true)
    boolean isMatchedBy(@NotNull final MethodTypeDesc handler) {
        Objects.requireNonNull(handler, "handler");
        if (!Assignability.allows(handler.returnType(), this.result)
                || handler.parameterCount() < this.inputs.size()) {
            return false;
        }
        for (int i = 0; i < this.inputs.size(); i++) {
            // The value flows from the stack INTO the parameter, so the parameter is what must be
            // wide enough — not the other way round.
            if (!Assignability.allows(this.inputs.get(i), handler.parameterType(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns how many values the operation takes off the stack.
     *
     * <p>This is what an injector answers when the engine asks how many of a handler's leading
     * parameters the stack already supplies.
     *
     * @return the number of inputs
     */
    @Contract(pure = true)
    int arity() {
        return this.inputs.size();
    }
}
