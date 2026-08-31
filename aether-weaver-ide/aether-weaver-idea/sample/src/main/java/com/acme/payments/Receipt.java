package com.acme.payments;

import java.math.BigDecimal;

/**
 * Weaving target for the two generated-member forms, and the one class here that another sample
 * class extends.
 *
 * <p>Its shape is chosen for what a declaration can be pointed at rather than for what it computes.
 * The field is {@code private}, which is what makes it worth an {@code @Accessor} and what
 * {@code ReceiptAccess} binds a {@code @Shadow} to; {@link #valid()} is {@code public} and its only
 * work is a call to a {@code private} method, which is the method the same weave's
 * {@code @Invoker} emits a forwarder for. The class is not {@code final} and its constructor is
 * accessible, which is what lets {@code HasASupertype} in {@code ReportedExtensions.java} extend it
 * and be reported for doing so.
 *
 * <p>{@link Ledger#record(BigDecimal)} allocates one and then calls {@link #valid()} on it, which
 * are the positions {@code LedgerWeave} and {@code GeneratedShapes} name.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public class Receipt {

    /**
     * The amount this receipt was made for, or {@code null} when it was made for none.
     *
     * <p>{@code private final}. {@code ReceiptAccess} declares a {@code @Shadow} of the same name
     * and type and generates a getter for it, neither of which needs the field to be reachable from
     * outside the class.
     */
    private final BigDecimal amount;

    /**
     * Creates a receipt for an amount.
     *
     * <p>The only constructor, and accessible, which is what {@code HasASupertype} in
     * {@code ReportedExtensions.java} calls through {@code super}.
     *
     * @param amount the amount the receipt is for, which may be {@code null}
     */
    public Receipt(final BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Reports whether this receipt carries an amount.
     *
     * <p>Called from {@link Ledger#record(BigDecimal)}, which is the call {@code GeneratedShapes}
     * pins by ordinal, slices against and redirects. The work is delegated rather than written here
     * so that the class has a {@code private} method for an {@code @Invoker} to reach.
     *
     * @return whether the amount is present
     */
    public boolean valid() {
        return hasAmount();
    }

    /**
     * Reports whether the amount is present.
     *
     * <p>{@code private}, and reached from outside the class only through the method
     * {@code ReceiptAccess} generates for it: a call made from inside the target uses
     * {@code invokespecial}, so a {@code private} method is a legal thing for an {@code @Invoker} to
     * forward to.
     *
     * @return whether the amount is not {@code null}
     */
    private boolean hasAmount() {
        return amount != null;
    }
}
