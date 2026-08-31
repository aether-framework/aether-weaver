package com.acme.payments;

import java.math.BigDecimal;

/**
 * Weaving target for the call and position points, read from the target's side.
 *
 * <p>Its shape is chosen for the positions it offers rather than for what it computes. Three
 * {@code charge} overloads make a bare {@code "charge"} selector name all three, which is what
 * {@code AW1021} refuses rather than resolving arbitrarily; the two-argument overload calls
 * {@code format} twice on one line, so a call point with the default ordinal matches both;
 * {@link #settle()} takes nothing and returns {@code void}, which is the shortest method descriptor
 * a selector can be written with; and {@code internal} is {@code private}, which is still a legal
 * target.
 *
 * <p>{@code AuditWeave}, {@code PriorityWeave}, {@code DescriptorWeave} and {@code TargetsByName}
 * name this class, and so do several of the deliberately wrong weaves in {@code Reported.java}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public class Gateway {

    /**
     * Charges an amount.
     *
     * <p>Three weaves inject at the head of this method — {@code AuditWeave}, {@code PriorityWeave}
     * and the deliberately wrong {@code HandlersThatCannotBeCalled} in {@code Reported.java}, which
     * contributes two more handlers of its own — which is what makes the target-side gutter list four
     * handlers and order them by priority.
     *
     * @param amount the amount to charge
     * @return a line naming the amount
     */
    public String charge(final BigDecimal amount) {
        return "charged " + amount;
    }

    /**
     * Charges an amount in a currency.
     *
     * <p>The two calls to {@code format} sit on one line, so a call point that names it without an
     * ordinal resolves to two positions on the same line.
     *
     * @param amount   the amount to charge
     * @param currency the currency to name
     * @return a line naming the formatted amount twice and the currency
     */
    public String charge(final BigDecimal amount, final String currency) {
        // Calls format twice, so an INVOKE injection with the default ordinal marks both.
        return format(amount) + " " + format(amount) + " " + currency;
    }

    /**
     * Formats an amount for display.
     *
     * <p>Package-private, and the operation the redirect in {@code AuditWeave} names.
     *
     * @param amount the amount to format
     * @return the amount with a currency code in front of it
     */
    String format(final BigDecimal amount) {
        return "EUR " + amount;
    }

    /**
     * Charges nothing; the third {@code charge} overload, present so that a bare {@code "charge"}
     * selector is ambiguous among three methods rather than two.
     *
     * @return a fixed line
     */
    public String charge() {
        return "charged nothing";
    }

    /**
     * Settles what has been charged.
     *
     * <p>Named by a source-form selector in {@code AuditWeave} and by the descriptor form
     * {@code desc:settle()V} in {@code DescriptorWeave}. The conversion intention rewrites only in
     * that direction, from descriptor to source; applied to {@code desc:settle()V} it would produce
     * {@code settle():void}, carrying the return type, not the bare {@code "settle"} form
     * {@code AuditWeave} uses.
     */
    public void settle() {
        // nothing
    }

    /**
     * Called by nothing, and present because a {@code private} method is still a target a selector
     * may name.
     */
    private void internal() {
        // A private member is still a legal target, and completion must offer it.
    }
}
