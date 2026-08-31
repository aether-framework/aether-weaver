package com.acme.payments;

import de.splatgames.aether.weaver.api.experimental.Extension;
import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.experimental.Receiver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Extension holder contributing members to {@link BigDecimal}, written to be correct so that every
 * contribution the editor marks resolves to real code.
 *
 * <p>All three ways of naming a receiver are here, and a contributed constant besides, which is why
 * the class is shaped the way it is. {@link Extension#value()} names the receiver once for the whole
 * class, so {@link #split(BigDecimal, int)} and {@link #plus(BigDecimal, BigDecimal...)} take theirs
 * from parameter zero by position and carry no annotation at all. The three methods that do carry
 * {@link Receiver} on the parameter carry it for the {@link Nulls} policy, which lives on that
 * annotation and nowhere else, rather than to name a type the class has already named.
 * {@link #parse(String)} carries it on the method, which contributes a {@code static} member reached
 * as {@code BigDecimal.parse(...)}, and {@link #CENT} carries it on a field, which contributes a
 * constant read as {@code BigDecimal.CENT}.
 *
 * <p>Nothing here is woven or stubbed: the sample POM sets {@code <proc>none</proc>} and no weaver
 * runs. The declarations exist so that the plugin has real contributions to resolve, complete and
 * navigate to, and so that {@code compileSample} fails the moment an annotation this file writes
 * stops existing. The caller lives in {@code src/test/java}, because an extension has to be compiled
 * before the code that calls it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension(BigDecimal.class)
public final class Amounts {

    /**
     * A contributed constant, read at a call site as {@code BigDecimal.CENT}.
     *
     * <p>{@link Receiver} on a field names the receiver with its own {@link Receiver#value()}, and
     * the class-level receiver does not stand in for it. The field is {@code public static final},
     * which a contributed constant must be.
     */
    @Receiver(BigDecimal.class)
    public static final BigDecimal CENT = new BigDecimal("0.01");

    /**
     * Refuses instantiation; every member of this class is contributed to {@link BigDecimal} rather
     * than called on a holder instance.
     *
     * @throws AssertionError always
     */
    private Amounts() {
        throw new AssertionError("no instances");
    }

    /**
     * Formats an amount with a currency symbol, as {@code amount.asMoney("EUR")}.
     *
     * <p>The receiver is declared {@link Nulls#CHECKED}, the one policy that changes the emitted
     * code: the holder gains a prologue rejecting a {@code null} receiver, so the body may read
     * {@code self} without a guard of its own.
     *
     * @param self   the receiver
     * @param symbol the text put in front of the amount
     * @return the symbol followed by the receiver at scale two, rounded
     *         {@link RoundingMode#HALF_UP}
     */
    public static String asMoney(@Receiver(nulls = Nulls.CHECKED) final BigDecimal self,
                                 final String symbol) {
        return symbol + self.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Substitutes {@link BigDecimal#ZERO} for a missing amount, as {@code amount.orZero()}.
     *
     * <p>The receiver is declared {@link Nulls#NULLABLE}, so no check is emitted and the body is
     * reached with {@code null} — which is the case this member exists to answer.
     *
     * @param self the receiver, which may be {@code null}
     * @return the receiver, or {@link BigDecimal#ZERO} when it is {@code null}
     */
    public static BigDecimal orZero(@Receiver(nulls = Nulls.NULLABLE) final BigDecimal self) {
        return self == null ? BigDecimal.ZERO : self;
    }

    /**
     * Reports whether a charge for this amount should be turned down, as
     * {@code amount.isRefusable()}.
     *
     * <p>Declared {@link Nulls#NULLABLE} for the same reason as {@link #orZero(BigDecimal)}: a
     * missing amount is one of the answers, not a failure.
     *
     * @param self the receiver, which may be {@code null}
     * @return {@code true} when the receiver is {@code null}, zero or negative
     */
    public static boolean isRefusable(@Receiver(nulls = Nulls.NULLABLE) final BigDecimal self) {
        return self == null || self.signum() <= 0;
    }

    /**
     * Divides an amount into parts that add back up to it, as {@code amount.split(3)}.
     *
     * <p>Carries no {@link Receiver}, so the class-level receiver applies and {@code self} is the
     * receiver by position. The declared return type is {@code List<BigDecimal>} rather than a raw
     * {@link List}, which is what lets a call site resolve {@link BigDecimal} members on an element.
     *
     * <p>Each part after the first is the quotient at scale two rounded {@link RoundingMode#DOWN},
     * and the first carries whatever that rounding dropped, so the parts sum to the receiver.
     *
     * @param self  the receiver
     * @param parts the number of parts to produce
     * @return a list of size {@code parts} when {@code parts} is positive, the remainder-carrying
     *         part first; when {@code parts} is negative the loop that adds the rest never runs, so
     *         the result is a single-element list carrying the whole amount
     * @throws IllegalArgumentException if {@code self} is {@code null}
     * @throws ArithmeticException if {@code parts} is zero
     */
    public static List<BigDecimal> split(final BigDecimal self, final int parts) {
        final BigDecimal whole = required(self);
        final BigDecimal each = whole.divide(BigDecimal.valueOf(parts), 2, RoundingMode.DOWN);
        final List<BigDecimal> split = new ArrayList<>();
        split.add(whole.subtract(each.multiply(BigDecimal.valueOf(parts - 1L))));
        for (int i = 1; i < parts; i++) {
            split.add(each);
        }
        return split;
    }

    /**
     * Reads an amount written with thousands separators, as {@code BigDecimal.parse("1,234.50")}.
     *
     * <p>{@link Receiver} sits on the method rather than on a parameter, which contributes a
     * {@code static} member to {@link BigDecimal} instead of an instance member to its values. There
     * is no receiver before the dot at the call site, only the type name, and the call already
     * passes exactly what this method takes.
     *
     * @param text the text to read, with every comma removed before it is parsed
     * @return the amount the text denotes
     * @throws NumberFormatException if the text is not a valid {@link BigDecimal} once the commas
     *                               are removed
     */
    @Receiver(BigDecimal.class)
    public static BigDecimal parse(final String text) {
        return new BigDecimal(text.replace(",", ""));
    }

    /**
     * Adds any number of further amounts to this one, as {@code fee.plus(tax, surcharge)}.
     *
     * <p>Declared {@code varargs} and contributed as such, so the call site passes arguments rather
     * than an array. Missing entries are read as zero through {@link #orZero(BigDecimal)}; a missing
     * receiver is not, because there would be nothing to add to.
     *
     * @param self   the receiver
     * @param others the amounts to add, each treated as zero when {@code null}
     * @return the sum
     * @throws IllegalArgumentException if {@code self} is {@code null}
     */
    public static BigDecimal plus(final BigDecimal self, final BigDecimal... others) {
        BigDecimal total = required(self);
        for (final BigDecimal other : others) {
            total = total.add(orZero(other));
        }
        return total;
    }

    /**
     * Rejects a missing receiver in the members that cannot answer for one.
     *
     * <p>Not a contribution: only a {@code public} method of a holder is examined, so a
     * {@code private} helper keeps its ordinary meaning and the class-level receiver does not reach
     * it.
     *
     * @param amount the amount to check
     * @return the amount, unchanged
     * @throws IllegalArgumentException if {@code amount} is {@code null}
     */
    private static BigDecimal required(final BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("an amount is required");
        }
        return amount;
    }
}
