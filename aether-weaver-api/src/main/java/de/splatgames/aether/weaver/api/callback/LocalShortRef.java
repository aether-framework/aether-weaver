package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Carries a {@code short} local variable of a target method into a handler and the handler's writes back out.
 *
 * <p>A Java parameter is passed by value, so a handler that assigns to a {@code short} parameter changes its own
 * copy and leaves the target holding the value it had before. A parameter declared as this type is handed a
 * carrier instead: the engine constructs one around the variable's current value, passes it to the handler, and
 * reads {@link #get()} back into the target's own slot as soon as the handler returns.
 *
 * <p>Only a parameter annotated {@link de.splatgames.aether.weaver.api.Local} with {@code mutable = true} may be
 * declared as a carrier. A carrier without that flag is reported as {@code AW1054} — a carrier the handler may
 * not write to is a handle nobody should be holding — and {@code mutable = true} on a parameter that is not a
 * carrier is reported as {@code AW1053}, because assigning to such a parameter could only be a no-op. Both codes
 * are reported by the annotation processor at compile time and again by the engine.
 *
 * <h2>Which variable it matches</h2>
 *
 * <p>A carrier is matched by what it holds rather than by its own type, so this parameter resolves against a
 * variable the target declares {@code short}. A {@code short} shares its storage with an {@code int} in a
 * compiled method, and where the target carries a usable {@code LocalVariableTable} entry for the slot, the
 * match is made against the declared type rather than the storage: an {@code int} variable is reported as
 * {@code AW1050} against this carrier, and a {@code short} variable is reported as {@code AW1050} against
 * {@link LocalIntRef}. Where no such entry exists — a variable captured {@code by index} on a target with none
 * — the check is skipped and an {@code int} slot is accepted in place of a {@code short}. A reference variable
 * needs {@link LocalRef}.
 *
 * <h2>What is written back, and when</h2>
 *
 * <p>The write-back is emitted immediately after the handler's call returns, and it reads the carrier once. A
 * handler that stores the carrier somewhere and calls {@link #set(short)} later changes nothing in the target:
 * the target's slot has already been written and the carrier is no longer consulted.
 *
 * <p>On the {@link de.splatgames.aether.weaver.api.Inject} path the write-back precedes the check of the
 * handler's callback, so a handler that both writes the variable and cancels through a {@link Callback} gets
 * both — the value is in the slot even though the target returns without reaching the code that would read it.
 *
 * <p>A {@link de.splatgames.aether.weaver.api.Redirect} handler is handed its captures and no write-back is
 * emitted for them, so what it stores into a carrier is discarded. Writing a local from a handler is what the
 * {@link de.splatgames.aether.weaver.api.Inject} path does.
 *
 * <h2>Thread safety</h2>
 *
 * <p>A carrier is constructed at each execution of the injected call and reaches only the handler that call
 * invokes, so an ordinary handler never shares one. The class itself synchronizes nothing and its field is not
 * volatile, so a carrier a handler publishes to another thread carries no visibility guarantee.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class LedgerAudit {
 *
 *     @Inject(method = "settle()",
 *             at = @At(value = Point.INVOKE, target = "#post"),
 *             require = 1)
 *     private void beforePost(@Local(name = "attempt", mutable = true) LocalShortRef attempt) {
 *         attempt.set((short) (attempt.get() + 1));   // settle() goes on with the new value
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Local
 * @see LocalRef
 */
public final class LocalShortRef {

    /** The value handed to the handler, and the value read back into the target's slot. */
    private short value;

    /**
     * Creates a carrier holding the given value.
     *
     * <p>The engine emits this constructor at the injected call with the target variable's current value, so a
     * carrier a handler receives always starts at what the target holds rather than at zero.
     *
     * @param value the value to carry
     */
    public LocalShortRef(final short value) {
        this.value = value;
    }

    /**
     * Returns the value currently carried.
     *
     * <p>Before the handler writes anything this is the value the target variable held at the injected call.
     * This method is also what the emitted write-back calls to obtain the value it stores into the target's
     * slot.
     *
     * @return the value currently carried
     */
    @Contract(pure = true)
    public short get() {
        return this.value;
    }

    /**
     * Replaces the value carried.
     *
     * <p>The target's own variable is not changed here; it is changed by the write-back, which reads the carrier
     * once after the handler returns. The last value stored before that point is the one the target goes on
     * with, and a value stored afterwards is never read.
     *
     * @param value the value the target variable is to hold
     */
    public void set(final short value) {
        this.value = value;
    }

    /**
     * Returns a description of this carrier and the value it holds, in the form {@code LocalShortRef[7]}.
     *
     * @return a description of this carrier and the value it holds
     */
    @Override
    @NotNull
    public String toString() {
        return "LocalShortRef[" + this.value + ']';
    }
}
