package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;

/**
 * Writes the call to a handler that takes a callback, together with the cancellation branch that
 * follows it.
 *
 * <p>The whole of the shape here is decided by one constraint: the callback object has to be
 * reachable both as an argument to the handler and again after the handler has returned, and an
 * operand stack consumed by an {@code invoke} is not a place anything can wait. It is therefore
 * constructed into a local slot before any of the handler's operands are pushed, which also means
 * the construction itself leaves nothing additional on the stack: the handler's own operands are
 * emitted exactly as for an ordinary call, on top of whatever the matched position already left
 * there.
 *
 * <p>The emission order is fixed and each step depends on the one before it: receiver, the captured
 * result if the declaration asked for one, the target arguments, the callback, the captures, the
 * {@code invoke}, the write-backs, and only then the test of {@code isCancelled}. Two of those
 * positions are not interchangeable for reasons the verifier will not always point out. The captures
 * follow the callback because that is the declared parameter order, and swapping them is an argument
 * transposition the verifier catches only when the two types differ. The write-backs precede the
 * cancellation test because the cancelled path returns and never comes back through this point, and
 * a handler that both writes a local and cancels expects both to have happened.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class CallbackEmission {

    /** The concrete class the emitted code constructs; the two interfaces are what it calls it as. */
    private static final ClassDesc CD_SUPPORT =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.CallbackSupport");

    /** Declares {@code isCancelled}, which every injected callback is tested through. */
    private static final ClassDesc CD_CALLBACK =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.Callback");

    /** Declares {@code value}, reached only on the cancelled path of a value-returning target. */
    private static final ClassDesc CD_RETURNABLE =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.ReturnableCallback");

    /**
     * Boxes the value on top of the stack, so that it can be handed to the callback as an
     * {@link Object}.
     *
     * <p>A reference is left alone, which is what makes this callable unconditionally on the
     * captured value. The switch refuses the two kinds that have no wrapper: a reference cannot
     * reach it, and {@code void} cannot either, because the only caller boxes a value the target was
     * about to return.
     *
     * @param builder    the code being written; must not be {@code null}
     * @param returnType the descriptor of the value on the stack; must not be {@code null}
     * @throws IllegalStateException if {@code returnType} is {@code void}
     */
    private static void box(@NotNull final CodeBuilder builder,
                            @NotNull final ClassDesc returnType) {
        if (!returnType.isPrimitive()) {
            return;
        }
        final ClassDesc wrapper = switch (TypeKind.from(returnType)) {
            case INT -> ConstantDescs.CD_Integer;
            case LONG -> ConstantDescs.CD_Long;
            case FLOAT -> ConstantDescs.CD_Float;
            case DOUBLE -> ConstantDescs.CD_Double;
            case BOOLEAN -> ConstantDescs.CD_Boolean;
            case BYTE -> ConstantDescs.CD_Byte;
            case SHORT -> ConstantDescs.CD_Short;
            case CHAR -> ConstantDescs.CD_Character;
            case REFERENCE, VOID -> throw new IllegalStateException(
                    "not a boxable return type: " + returnType.displayName());
        };
        builder.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, returnType));
    }

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private CallbackEmission() {
        throw new AssertionError("no instances");
    }

    /**
     * Writes one handler call with its callback, at one matched element.
     *
     * <p>Local slots are allocated here, one for the callback always and a second when the target's
     * own value has to be parked, and each emission allocates its own. A method with several injected
     * sites therefore grows its frame by that much per site rather than sharing one pair of slots.
     *
     * <p>{@code onStack} decides which of {@code CallbackSupport}'s two constructors is called, and
     * it is the caller's reading of the element rather than a property of the target: the value is
     * captured only where the injection sits on a value-returning {@code return}. Capturing it goes
     * through a local rather than through a stack dance, which is what makes one shape work for a
     * two-slot value as well as for a reference.
     *
     * <p>Whether the callback the handler declared can carry a value is settled before this is
     * reached — {@code AW1070} for a plain callback on a value-returning target and {@code AW1071}
     * for the reverse — but that check is against the declared type, not against {@code onStack}.
     * The instance constructed here starts with no value at every position other than
     * {@code onStack}; only a handler call to {@code cancel(Object)} supplies one, which is what
     * {@code AW1072} exists to catch when the handler reads it.
     *
     * @param builder    the code being written; must not be {@code null}
     * @param handler    the handler to call; must not be {@code null}
     * @param owner      the class the {@code invoke} names, which is the target itself for a
     *                   dissolved weave; must not be {@code null}
     * @param opcode     the invocation opcode chosen for the handler; must not be {@code null}
     * @param binding    how to push the handler's arguments at this site; must not be {@code null}
     * @param result     the kind of the captured call result, or {@code null} when none was captured
     * @param copy       the slot the captured result was parked in, or negative when none was
     * @param returnType the target method's return type, which decides the cancelled path; must not
     *                   be {@code null}
     * @param onStack    whether the target's own return value is on the stack at this element
     * @param id         the declaration's identifier, carried by the callback; must not be
     *                   {@code null}
     * @throws NullPointerException if any argument other than {@code result} is {@code null}
     */
    static void emit(@NotNull final CodeBuilder builder,
                     @NotNull final HandlerRef handler,
                     @NotNull final ClassDesc owner,
                     @NotNull final Opcode opcode,
                     @NotNull final HandlerBinding binding,
                     @Nullable final TypeKind result,
                     final int copy,
                     @NotNull final ClassDesc returnType,
                     final boolean onStack,
                     @NotNull final String id) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(opcode, "opcode");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(id, "id");

        final int callbackSlot = builder.allocateLocal(TypeKind.REFERENCE);

        if (onStack) {
            // The value the target computed is the bottom of what is on the stack, and the
            // constructor wants it on top. Copying it aside into a local is the only shape that
            // works for a `long` or a `double` as well as for a reference — a dup_x dance would
            // work until somebody injected at the return of a method returning one.
            final TypeKind kind = TypeKind.from(returnType);
            final int captured = builder.allocateLocal(TypeKind.REFERENCE);
            if (kind.slotSize() == 2) {
                builder.dup2();
            } else {
                builder.dup();
            }
            box(builder, returnType);
            builder.astore(captured);
            builder.new_(CD_SUPPORT)
                    .dup()
                    .ldc(id)
                    .aload(captured)
                    .invokespecial(CD_SUPPORT, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String,
                                    ConstantDescs.CD_Object))
                    .astore(callbackSlot);
        } else {
            builder.new_(CD_SUPPORT)
                    .dup()
                    .ldc(id)
                    .invokespecial(CD_SUPPORT, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                    .astore(callbackSlot);
        }

        // The callback was constructed into a local and left nothing additional on the stack, so
        // the handler's own operands go on top of whatever was already there — receiver first,
        // exactly as an ordinary call.
        binding.emitReceiver(builder);
        if (copy >= 0 && result != null) {
            builder.loadLocal(result, copy);
        }
        binding.emitArguments(builder);
        builder.aload(callbackSlot);
        // After the callback, never before it. The declared order is prefix, callback, captures,
        // and swapping the last two is an argument transposition the verifier only catches when the
        // types happen to differ.
        final List<HandlerBinding.WriteBack> pending = binding.emitCaptures(builder);
        builder.invoke(opcode, owner, handler.name(), handler.type(), false);
        // Before the cancellation branch, not after. A handler that both cancels and writes a
        // local expects both to have happened, and one of the two paths out of here does not come
        // back through this point.
        HandlerBinding.emitWriteBacks(builder, pending);

        final Label carryOn = builder.newLabel();
        builder.aload(callbackSlot)
                .invokeinterface(CD_CALLBACK, "isCancelled",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean))
                .ifeq(carryOn);

        emitCancelledReturn(builder, callbackSlot, returnType);
        builder.labelBinding(carryOn);
    }

    /**
     * Writes the return the target makes when the handler cancelled it.
     *
     * <p>A {@code void} target returns without reading the callback at all, which is why a plain
     * callback is never asked for a value it does not have.
     *
     * <p>The cast for a reference return names the target's own return type rather than
     * {@link Object}. {@code value} is declared to return {@link Object}, and
     * {@code areturn} from a method declaring anything narrower is a verify error — the class file
     * is rejected outright with a message about the two types not being assignable, so nothing about
     * this is caught at run time. A primitive return is cast to its wrapper and unboxed for the same
     * reason.
     *
     * @param builder      the code being written; must not be {@code null}
     * @param callbackSlot the local the callback was parked in
     * @param returnType   the target method's return type; must not be {@code null}
     */
    private static void emitCancelledReturn(@NotNull final CodeBuilder builder,
                                            final int callbackSlot,
                                            @NotNull final ClassDesc returnType) {
        final TypeKind returnKind = TypeKind.from(returnType);
        if (returnKind == TypeKind.VOID) {
            builder.return_();
            return;
        }

        builder.aload(callbackSlot)
                .invokeinterface(CD_RETURNABLE, "value",
                        MethodTypeDesc.of(ConstantDescs.CD_Object));

        if (returnKind == TypeKind.REFERENCE) {
            // Cast to the target's OWN return type, not to Object. TypeKind says "a reference"
            // and not WHICH reference, and `areturn` after `checkcast Object` from a method
            // declaring String is exactly the VerifyError the first version of this produced:
            // "java/lang/String is not assignable from java/lang/Object".
            builder.checkcast(returnType);
        } else {
            final ClassDesc boxed = boxOf(returnKind);
            builder.checkcast(boxed)
                    .invokevirtual(boxed, unboxMethodOf(returnKind),
                            MethodTypeDesc.of(primitiveOf(returnKind)));
        }
        builder.return_(returnKind);
    }

    /**
     * Returns the wrapper class a primitive kind is cast to before being unboxed.
     *
     * <p>Kept apart from {@code box}, which works from a {@link ClassDesc} because it is emitted
     * where the value's own descriptor is what is known, while the cancelled path has already
     * reduced the return type to a kind.
     *
     * @param kind the primitive kind; must not be {@code null}
     * @return the wrapper class
     * @throws IllegalArgumentException if {@code kind} is a reference or {@code void}
     */
    @Contract(pure = true)
    @NotNull
    private static ClassDesc boxOf(@NotNull final TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_Boolean;
            case BYTE -> ConstantDescs.CD_Byte;
            case CHAR -> ConstantDescs.CD_Character;
            case SHORT -> ConstantDescs.CD_Short;
            case INT -> ConstantDescs.CD_Integer;
            case LONG -> ConstantDescs.CD_Long;
            case FLOAT -> ConstantDescs.CD_Float;
            case DOUBLE -> ConstantDescs.CD_Double;
            case REFERENCE, VOID ->
                    throw new IllegalArgumentException(kind + " has no boxed form");
        };
    }

    /**
     * Returns the primitive descriptor a kind unboxes to, for the descriptor of the unboxing call.
     *
     * <p>{@link TypeKind#upperBound()} gives the same descriptor for every primitive kind. What the
     * explicit mapping adds is the refusal of the two kinds that are not primitives at all, which
     * {@link TypeKind#upperBound()} answers with {@link Object} and {@code void} rather than
     * rejecting.
     *
     * @param kind the primitive kind; must not be {@code null}
     * @return the primitive descriptor
     * @throws IllegalArgumentException if {@code kind} is a reference or {@code void}
     */
    @Contract(pure = true)
    @NotNull
    private static ClassDesc primitiveOf(@NotNull final TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case CHAR -> ConstantDescs.CD_char;
            case SHORT -> ConstantDescs.CD_short;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            default -> throw new IllegalArgumentException(kind + " is not a primitive");
        };
    }

    /**
     * Returns the name of the wrapper method that unboxes a kind.
     *
     * <p>The name and the class come from two switches over the same kind, and the emitted
     * {@code invokevirtual} pairs them: a disagreement between the two would name a method the
     * wrapper does not declare, which fails when the class is linked rather than when it is
     * written.
     *
     * @param kind the primitive kind; must not be {@code null}
     * @return the unboxing method's name
     * @throws IllegalArgumentException if {@code kind} is a reference or {@code void}
     */
    @Contract(pure = true)
    @NotNull
    private static String unboxMethodOf(@NotNull final TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "booleanValue";
            case BYTE -> "byteValue";
            case CHAR -> "charValue";
            case SHORT -> "shortValue";
            case INT -> "intValue";
            case LONG -> "longValue";
            case FLOAT -> "floatValue";
            case DOUBLE -> "doubleValue";
            default -> throw new IllegalArgumentException(kind + " is not a primitive");
        };
    }
}
