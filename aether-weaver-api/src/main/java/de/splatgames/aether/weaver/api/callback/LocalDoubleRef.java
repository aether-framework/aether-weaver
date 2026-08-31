package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Carries a {@code double} local variable of a target method into an
 * {@link de.splatgames.aether.weaver.api.Inject @Inject} handler, and the handler's writes back
 * out.
 *
 * <p>A Java parameter is passed by value, so a handler that assigns to a plain {@code double}
 * parameter changes its own copy and leaves the target holding the old one. Declaring the
 * parameter as a {@code LocalDoubleRef} and the capture as
 * {@link de.splatgames.aether.weaver.api.Local @Local(mutable = true)} gives the write somewhere
 * to land: the injected code constructs a carrier around the variable's value at the matched
 * position, passes it to the handler, and once the handler returns reads {@link #get()} back into
 * the target's own slot.
 *
 * <h2>Which variable it matches</h2>
 *
 * <p>A carrier is resolved by what it holds and not by its own type, so this parameter matches a
 * target local declared {@code double}, and only that. Resolution never widens a primitive: a
 * variable declared {@code float} needs {@link LocalFloatRef} and one declared {@code long} needs
 * {@link LocalLongRef}, however freely Java itself would convert between them.
 *
 * <p>Which {@code double} is meant is settled by the elements of
 * {@link de.splatgames.aether.weaver.api.Local @Local} — by name, by slot, by ordinal, or by there
 * being exactly one of that type live at the matched position — and the request is refused rather
 * than guessed at: {@code AW1050} where no variable answers it, {@code AW1051} where more than one
 * does and nothing chooses between them, and {@code AW1052} where the strategy needs the target's
 * {@code LocalVariableTable} and the target was compiled without debug information. Resolution
 * runs once per matched position, so one declaration that matched two positions may read two
 * different slots.
 *
 * <p>A {@code double} occupies two consecutive local slots. The slot it is known by is the first
 * of the pair — the one the {@code LocalVariableTable} records and the one
 * {@code @Local(index = ...)} has to name — and the load and the write-back both address the pair
 * as a whole. Naming the second half is not caught as {@code AW1050}, because the check behind
 * that code compares the types of a slot the table does describe and the second half is described
 * by nothing. What is emitted is a load of half a value, which the woven class then fails
 * verification on: {@code AW4001} where the weaver verifies its output.
 *
 * <h2>What the declaration must say</h2>
 *
 * <p>The carrier and {@code mutable = true} are two halves of one statement of intent and neither
 * is accepted alone. A carrier parameter without {@code mutable = true} is reported as
 * {@code AW1054}, and {@code mutable = true} on a plain {@code double} parameter is reported as
 * {@code AW1053} — accepting it would emit code that does nothing and says nothing. A handler that
 * only reads the variable declares a plain {@code double} parameter instead.
 *
 * <p>Every capture sits in the trailing run of the handler's parameter list, after the target's
 * argument prefix and after any {@link Callback}. A capture elsewhere in the list is reported as
 * {@code AW1040}.
 *
 * <h2>What it does not do</h2>
 *
 * <p>A carrier is a value holder, not a view of the target's frame. The target's slot keeps its
 * old value for as long as the handler runs, so a write is visible to the target only from the
 * write-back onwards. The field is plain and neither it nor the write-back is synchronised: a
 * carrier is constructed afresh for each execution of the injected position, and the engine
 * publishes it nowhere.
 *
 * <p>The write-back is emitted immediately after the handler call and before the callback's
 * cancellation is tested, so a handler that writes a local and cancels in the same call gets both.
 * It belongs to the {@code @Inject} path alone: a carrier declared on a
 * {@link de.splatgames.aether.weaver.api.Redirect @Redirect} handler is still constructed and
 * passed, and nothing reads it afterwards, so a write there is dropped without a diagnostic.
 *
 * <p>Nothing reads the carrier again after the write-back either. One kept in a static field and
 * written from another thread later changes nothing in any target.
 *
 * <p>The value is stored exactly as given, including {@link Double#NaN} and negative zero. The
 * carrier compares nothing and rejects nothing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Quote.class)
 * public final class RateWeave {
 *
 *     // double rate is a local of price(java.math.BigDecimal), live where round() is called.
 *     @Inject(method = "price(java.math.BigDecimal)",
 *             at = @At(value = Point.INVOKE, target = "#round"),
 *             require = 1)
 *     private static void beforeRound(BigDecimal amount,
 *                                     @Local(name = "rate", mutable = true)
 *                                     LocalDoubleRef rate) {
 *         rate.set(rate.get() * 1.05d);   // price() rounds the adjusted rate
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Local
 * @see LocalRef
 */
public final class LocalDoubleRef {

    /** The value held; the target's own variable is untouched until the write-back. */
    private double value;

    /**
     * Creates a carrier holding the given value.
     *
     * <p>The injected code calls this with the target variable's value at the matched position, so
     * a handler's first {@link #get()} sees what the target has, not a default. Constructing one
     * directly is what lets a handler be called as an ordinary method from a test; a carrier made
     * that way is written back nowhere.
     *
     * @param value the initial value
     */
    public LocalDoubleRef(final double value) {
        this.value = value;
    }

    /**
     * Returns the value held.
     *
     * <p>The injected code calls this once after the handler returns, and what it reads is what
     * the target's slot then holds.
     *
     * @return the value held
     */
    @Contract(pure = true)
    public double get() {
        return this.value;
    }

    /**
     * Replaces the value held.
     *
     * <p>This writes the carrier, not the target's slot; the two agree again at the write-back
     * once the handler returns. Writing several times is not an error, and the last value written
     * is the one the target receives.
     *
     * @param value the value to hold
     */
    public void set(final double value) {
        this.value = value;
    }

    /**
     * Returns a description of the value held, as {@code LocalDoubleRef[1.05]}.
     *
     * <p>The value is rendered by {@link Double#toString(double)}, so a whole number appears with
     * its fractional part and a non-finite value appears as {@code NaN} or {@code Infinity}.
     *
     * @return the description, never {@code null}
     */
    @Override
    @NotNull
    public String toString() {
        return "LocalDoubleRef[" + this.value + ']';
    }
}
