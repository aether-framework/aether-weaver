package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Redirect;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Weave on {@link Gateway} read from the weave's side, where a selector is a string an editor has to
 * resolve on its own.
 *
 * <p>The four declarations cover the spellings a selector takes: a signature that disambiguates an
 * overload, a bare name that does not need to, and a call point naming an operation inside the
 * target. Every one of them resolves; the deliberately wrong declarations live in
 * {@code Reported.java}.
 *
 * <p>The weave holds {@link Weave#priority()} at its default of zero and {@link PriorityWeave}
 * declares 100 at the same position, so the two handlers there run in the reverse of their
 * alphabetical order.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Gateway.class)
public final class AuditWeave {

    /**
     * Runs on entry to {@link Gateway#charge(java.math.BigDecimal)}.
     *
     * <p>The parameter type is written by simple name rather than qualified, which is the shape the
     * selector-qualifying intention is offered on.
     */
    @Inject(method = "charge(BigDecimal)", at = @At(Point.HEAD))
    void onCharge() {
        System.out.println("charging");
    }

    // This read `method = "charge"` until AW1021 was implemented, on the belief that a bare name
    // deliberately names every overload. The grammar allows it; the build does not — Gateway has
    // three `charge` methods, and Inject#method() says the selector "becomes ambiguous and is
    // reported (AW1021) rather than resolved arbitrarily". The sample was teaching the mistake.
    /**
     * Runs at every return of {@link Gateway#charge(java.math.BigDecimal, String)}.
     *
     * <p>The second signature-form declaration in this weave, naming the two-argument overload and
     * paired with {@link Point#RETURN} rather than {@link Point#HEAD}; {@code Gateway#charge} is
     * overloaded three ways, so the signature disambiguates here exactly as it does for
     * {@link #onCharge()}.
     */
    @Inject(method = "charge(BigDecimal,String)", at = @At(Point.RETURN))
    void onAnyCharge() {
        System.out.println("charged");
    }

    /**
     * Runs on entry to {@link Gateway#settle()}.
     *
     * <p>The bare name needs no signature: {@code settle} is not overloaded, so the selector
     * resolves to one method. {@code DescriptorWeave} names the same method in descriptor form.
     */
    @Inject(method = "settle", at = @At(Point.HEAD))
    void onSettle() {
        System.out.println("settling");
    }

    // INVOKE needs a target: "at a call" is not a place until it says which call. Without it
    // neither the engine nor the IDE can resolve anything, and the plugin therefore draws nothing.
    /**
     * Stands in for the calls to {@code Gateway.format} inside
     * {@link Gateway#charge(java.math.BigDecimal, String)}.
     *
     * <p>The one declaration in this file that is not an injection: a redirect takes the operation
     * over instead of running beside it, which is what makes it worth drawing differently. No
     * ordinal is written, so both calls on that line are named.
     *
     * <p>The handler declares none of the parameters a redirect of an instance call takes — the
     * receiver, then the call's arguments, returning the call's own type. The annotation processor
     * does not check this shape for a {@code @Redirect}; the engine does, at weave time. Nothing in
     * this sample is woven, so the mismatch stays a declaration; weaving it would report it as
     * {@code AW1040}.
     */
    @Redirect(method = "charge(BigDecimal,String)",
            at = @At(value = Point.INVOKE, target = "format"))
    void onRedirect() {
        System.out.println("redirecting");
    }
}
