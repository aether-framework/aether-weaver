package com.acme.payments;

import java.math.BigDecimal;

/**
 * Weaving target for the points that are not calls: a field access, an allocation and a throw.
 *
 * <p>{@link #record(BigDecimal)} is written so that a field write, a field access and a throw each
 * occur exactly once and in one method. It touches an instance counter and a static one, so a field
 * point can be narrowed to a write on the first and left at any access on the second; it allocates
 * twice there — a {@link Receipt} and, when that receipt is not valid, an
 * {@link IllegalStateException} — so {@code Point.NEW} matches two positions in the method, and only
 * the declaration in {@code LedgerWeave} that names {@link Receipt} explicitly picks out one of them;
 * and it throws, which is the only exceptional exit. The call to {@link Receipt#valid()} it makes is
 * what {@code GeneratedShapes} pins by ordinal and slices against.
 *
 * <p>{@code LedgerWeave} and {@code GeneratedShapes} name this class, and so do two of the
 * deliberately wrong weaves in {@code Reported.java}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public class Ledger {

    /** Counts the amounts recorded on this ledger; the instance field a field write is matched on. */
    private int entries;

    /** Counts the amounts recorded on every ledger; the static field a field access is matched on. */
    private static int posted;

    /**
     * Records an amount.
     *
     * <p>Both counter statements are simple assignments whose right-hand side adds one, so each still
     * compiles to a read followed by a write of the same field, which is what makes the difference
     * between a field point narrowed to a write and one left at any access visible on a single line.
     *
     * @param amount the amount to record
     * @throws IllegalStateException if the receipt made for the amount is not valid
     */
    public void record(final BigDecimal amount) {
        entries = entries + 1;
        posted = posted + 1;

        final Receipt receipt = new Receipt(amount);
        if (!receipt.valid()) {
            throw new IllegalStateException("a receipt must carry an amount");
        }
    }

    /**
     * Returns the number of amounts recorded on this ledger.
     *
     * @return the count held by {@code entries}
     */
    public int entries() {
        return entries;
    }
}
