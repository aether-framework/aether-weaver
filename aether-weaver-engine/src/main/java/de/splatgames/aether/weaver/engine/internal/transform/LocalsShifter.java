package de.splatgames.aether.weaver.engine.internal.transform;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.reflect.AccessFlag;

/**
 * Moves a body's local variables up by a fixed number of slots, leaving the receiver and the
 * parameters where they are.
 *
 * <p>A body copied into another method keeps using slot numbers that mean something else there.
 * Shifting everything would work if the slots meant nothing, but the low slots do mean something:
 * they are the frame the caller filled in, and the JVM puts {@code this} in slot 0 and the
 * arguments after it. So the transform splits the frame at {@link #mapSlot(int)} — below the prefix
 * nothing moves, at or above it everything moves by the same amount.
 *
 * <p>{@code maxLocals} is not adjusted here. The {@link CodeBuilder} recomputes it from the slots
 * actually written, and {@code BodyMergeTest} asserts that it comes out as the original plus the
 * shift for shifts of 0, 1, 4 and 17.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class LocalsShifter implements CodeTransform {

    /** The number of leading slots that keep their numbers. */
    private final int fixedPrefix;

    /** How far every slot at or above {@link #fixedPrefix} moves up. */
    private final int shift;

    /**
     * Stores the split point and the distance.
     *
     * @param fixedPrefix the number of leading slots that keep their numbers
     * @param shift       how far the remaining slots move up
     * @throws IllegalArgumentException if either argument is negative
     */
    private LocalsShifter(final int fixedPrefix, final int shift) {
        if (fixedPrefix < 0) {
            throw new IllegalArgumentException("fixedPrefix must not be negative: " + fixedPrefix);
        }
        if (shift < 0) {
            throw new IllegalArgumentException("shift must not be negative: " + shift);
        }
        this.fixedPrefix = fixedPrefix;
        this.shift = shift;
    }

    /**
     * Returns a shifter whose fixed prefix is the given method's own frame.
     *
     * <p>The prefix is one slot for {@code this} unless the method is static, plus the slot size of
     * each parameter — two for a {@code long} or a {@code double}, one for anything else. Counting
     * parameters rather than slots would leave the second half of a wide parameter above the split
     * and move it away from its first half.
     *
     * @param method the method whose receiver and parameters must not move; must not be
     *               {@code null}
     * @param shift  how far every other slot moves up; must not be negative
     * @return a shifter for that method's frame
     * @throws IllegalArgumentException if {@code shift} is negative
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static LocalsShifter forMethod(@NotNull final MethodModel method, final int shift) {
        final boolean isStatic = method.flags().flags().contains(AccessFlag.STATIC);
        int prefix = isStatic ? 0 : 1;
        for (final var parameter : method.methodTypeSymbol().parameterList()) {
            prefix += TypeKind.from(parameter).slotSize();
        }
        return new LocalsShifter(prefix, shift);
    }

    /**
     * Returns a shifter with the split point stated outright.
     *
     * @param fixedPrefix the number of leading slots that keep their numbers; must not be negative
     * @param shift       how far every other slot moves up; must not be negative
     * @return a shifter splitting the frame there
     * @throws IllegalArgumentException if either argument is negative
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static LocalsShifter of(final int fixedPrefix, final int shift) {
        return new LocalsShifter(fixedPrefix, shift);
    }

    /**
     * Returns the slot a given slot becomes.
     *
     * <p>Arithmetic on the number alone: nothing about the body is consulted, so a slot the body
     * never uses is answered the same way as one it does.
     *
     * @param slot the slot as the original body numbers it
     * @return the mapped slot
     */
    @Contract(pure = true)
    public int mapSlot(final int slot) {
        return slot < this.fixedPrefix ? slot : slot + this.shift;
    }

    /**
     * Rewrites one element, renumbering whatever slot it names.
     *
     * <p>Five element kinds carry a slot: the three that read or write one, and the two debug
     * pseudo-elements. Everything else is forwarded unchanged. Stack-map frames name slots too but
     * need no case here — a parsed body's element stream contains no stack-map element even when
     * the method carries a {@code StackMapTable}, so there is no stale frame to forward and the
     * builder derives new ones from the instructions it is given.
     *
     * @param cb      the builder receiving the rewritten element; must not be {@code null}
     * @param element the element to rewrite; must not be {@code null}
     */
    @Override
    public void accept(@NotNull final CodeBuilder cb, @NotNull final CodeElement element) {
        switch (element) {
            case LoadInstruction load ->
                    cb.loadLocal(load.typeKind(), mapSlot(load.slot()));

            case StoreInstruction store ->
                    cb.storeLocal(store.typeKind(), mapSlot(store.slot()));

            case IncrementInstruction increment ->
                    cb.iinc(mapSlot(increment.slot()), increment.constant());

            // Debug information records slots too. Leaving it unshifted would make a debugger
            // show the wrong variable — the code would run correctly and be impossible to debug.
            case LocalVariable variable -> cb.localVariable(
                    mapSlot(variable.slot()), variable.name(), variable.type(),
                    variable.startScope(), variable.endScope());

            case LocalVariableType variable -> cb.localVariableType(
                    mapSlot(variable.slot()), variable.name(), variable.signature(),
                    variable.startScope(), variable.endScope());

            default -> cb.with(element);
        }
    }

    /**
     * Returns the split point and the distance.
     *
     * @return a description naming the fixed prefix and the shift
     */
    @Override
    public String toString() {
        return "LocalsShifter[prefix=" + this.fixedPrefix + ", shift=" + this.shift + ']';
    }
}
