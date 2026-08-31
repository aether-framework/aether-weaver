package de.splatgames.aether.weaver.api.experimental;

import org.jetbrains.annotations.ApiStatus;

/**
 * What a contributed instance member promises about a {@code null} receiver.
 *
 * <p>Declared with {@link Receiver#nulls()}, and meaningful only where {@link Receiver} sits on a
 * parameter: a static contribution and a constant are read off the type itself, so there is no
 * receiver value for a policy to speak about, and one declared there is reported as
 * {@code AW1315}.
 *
 * <p>An extension is called through a rewritten call site rather than through a virtual dispatch,
 * so the {@link NullPointerException} a reader expects from {@code value.name()} on a {@code null}
 * value does not happen by itself. That is the gap these constants exist to close, and it is why
 * declaring one is a decision rather than a formality.
 *
 * <h2>Stability</h2>
 *
 * <p>Marked {@link ApiStatus.Experimental}, as is every other type in this package. That annotation
 * is the whole of the promise the source makes: no compatibility guarantee is stated for this
 * declaration, and nothing here names a release in which its shape is fixed. A policy other than
 * {@link #UNCHECKED} is written into the generated manifest by the constant's name; {@link #UNCHECKED}
 * itself is omitted rather than written out, and an entry that omits the field is read back as
 * {@link #UNCHECKED} for exactly that reason. A reader that does not know a constant it does find
 * there reports {@code AW2300} and likewise treats the entry as {@link #UNCHECKED} rather than
 * dropping it; a contributed member therefore keeps working across such a gap, without its declared
 * policy being applied.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // amount.asMoney("EUR") throws NullPointerException when amount is null
 * public static String asMoney(@Receiver(nulls = Nulls.CHECKED) BigDecimal self, String symbol) {
 *     return symbol + self.setScale(2, RoundingMode.HALF_UP);
 * }
 *
 * // amount.orZero() is written to answer for a null receiver itself
 * public static BigDecimal orZero(@Receiver(nulls = Nulls.NULLABLE) BigDecimal self) {
 *     return self == null ? BigDecimal.ZERO : self;
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Receiver#nulls()
 */
@ApiStatus.Experimental
public enum Nulls {

    /**
     * The member accepts a {@code null} receiver and answers for it in its own body.
     *
     * <p>No check is emitted. This is the opposite promise to {@link #CHECKED}, and weaving a check
     * into such a member would refuse exactly the call the member was written to handle.
     */
    NULLABLE,

    /**
     * The member requires a non-{@code null} receiver, and the framework enforces it.
     *
     * <p>The only constant that changes the emitted code. The holder's implementation gains a
     * prologue that rejects a {@code null} receiver with a {@link NullPointerException} naming the
     * receiver type, the member, the holder class and this policy, so the failure reads as the one
     * an ordinary instance call would have produced rather than as something from inside the
     * member's body.
     *
     * <p>The prologue is linear and branch-free, and a member whose first invocation is already a
     * {@code java.util.Objects.requireNonNull} is left alone, so hardening a holder twice adds one
     * check rather than two.
     *
     * <p>Only an instance contribution can be hardened. Asking for this on a static contribution or
     * a constant is {@code AW1315} at compile time, and a hand-edited manifest asking for the same
     * combination is not honoured.
     */
    CHECKED,

    /**
     * Nothing is promised and no check is emitted.
     *
     * <p>The default of {@link Receiver#nulls()}, and the value a manifest that names no policy
     * reads as. A {@code null} receiver reaches the member's body, where it behaves as any other
     * {@code null} argument would.
     */
    UNCHECKED
}
