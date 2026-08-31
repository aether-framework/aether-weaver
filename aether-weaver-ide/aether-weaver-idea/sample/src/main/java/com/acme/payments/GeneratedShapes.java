package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Local;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Redirect;
import de.splatgames.aether.weaver.api.Slice;
import de.splatgames.aether.weaver.api.Weave;

import java.math.BigDecimal;

/**
 * The handler shapes the generator writes, kept by hand so that a build compiles them.
 *
 * <p>Four things the {@code Weave Handler} action can produce for {@link Ledger} are here: a point
 * pinned by ordinal, the same point narrowed by a {@link Slice} whose bounds are pinned in the same
 * way, a {@link Local} capture, and a {@link Redirect} mirroring the operation it stands in for.
 * {@code compileSample} compiles this file on every build, so a shape that stops being expressible
 * fails the build rather than the next time somebody runs the generator.
 *
 * <p>Two choices happen to be the same across every declaration below, but neither is a property of
 * generated code in general. Selectors and targets are written in their qualified source form because
 * that is the dialog's default spelling; it also offers the simple and the descriptor form. The
 * ordinal is written on every declaration here because each names an operation picked from a row of a
 * list — the operation's own ordinal is used rather than the match rule's — but a declaration that
 * pins no operation and leaves the default {@code EVERY} match rule, as {@link LedgerWeave#onThrow()}
 * would if generated, writes no ordinal at all.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Ledger.class)
public final class GeneratedShapes {

    /**
     * Declared explicitly, and doing nothing.
     *
     * <p>A weave declares no constructor — the target has its own and two cannot be merged — which
     * the annotation processor reports as {@code AW1081}. A hand-written no-argument constructor
     * whose body is trivial is treated as implicit at weave time and is not reported there, and this
     * sample runs no processor, so nothing reports this one at all. Merged state belongs in an
     * injection at the target constructor's head, which is the code that runs once per instance.
     */
    public GeneratedShapes() {
        // Weaves hold no state of their own here.
    }

    /**
     * Runs immediately before the call to {@link Receipt#valid()} in
     * {@link Ledger#record(BigDecimal)}.
     *
     * <p>The plainest generated shape: a call point with its ordinal written out, and the enclosing
     * target method's own argument as the handler's only parameter. The point moved to the call; the
     * parameters are still the method's.
     *
     * @param amount the target method's argument, taken by the prefix rule
     */
    @Inject(method = "record(java.math.BigDecimal)",
            at = @At(value = Point.INVOKE,
                    target = "com.acme.payments.Receipt.valid()",
                    ordinal = 0))
    private void onValid(final BigDecimal amount) {
        System.out.println("about to check the receipt for " + amount);
    }

    /**
     * The same point, narrowed by a slice, with the target's local variable captured.
     *
     * <p>The slice carries no {@link Slice#id()} and the point names none, which is how a lone slice
     * and a lone point find each other. Both bounds are pinned by ordinal, because a bound must
     * resolve to exactly one position. The upper bound names the very call the point names, and a
     * slice's upper bound is exclusive, so the region stops immediately in front of it.
     *
     * <p>An ordinal inside a slice is counted within the region rather than within the method, which
     * is why a generator that computed one against the whole method and then wrote a slice beside it
     * would bind the declaration to a different call.
     *
     * @param amount  the target method's argument, taken by the prefix rule
     * @param receipt the target's local of that name, read from its local variable table at the
     *                matched position; a capture goes after the target's own arguments
     */
    @Inject(method = "record(java.math.BigDecimal)",
            slice = @Slice(
                    from = @At(value = Point.NEW, target = "com.acme.payments.Receipt", ordinal = 0),
                    to = @At(value = Point.INVOKE,
                            target = "com.acme.payments.Receipt.valid()", ordinal = 0)),
            at = @At(value = Point.INVOKE,
                    target = "com.acme.payments.Receipt.valid()", ordinal = 0))
    private void onValidInSlice(final BigDecimal amount,
                                @Local(name = "receipt") final Receipt receipt) {
        System.out.println("the receipt exists by now: " + receipt);
    }

    /**
     * Stands in for the call to {@link Receipt#valid()} instead of running beside it.
     *
     * <p>The generated redirect signature is the operation's own: the receiver of the instance call
     * first, then its arguments — none here — returning what the call returned. The body performs
     * the original operation, which a redirect is free not to do.
     *
     * @param receipt the receiver the target was about to call
     * @return the value the target uses in place of the redirected call's own result
     */
    @Redirect(method = "record(java.math.BigDecimal)",
            at = @At(value = Point.INVOKE,
                    target = "com.acme.payments.Receipt.valid()",
                    ordinal = 0))
    private boolean redirectValid(final Receipt receipt) {
        // Perform the original operation, or do not — that is what a redirect buys.
        return receipt.valid();
    }
}
