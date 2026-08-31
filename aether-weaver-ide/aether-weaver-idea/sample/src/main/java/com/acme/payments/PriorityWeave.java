package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Second weave on {@link Gateway#charge(java.math.BigDecimal)}, declared solely to give that
 * position two handlers with an order between them.
 *
 * <p>{@code AuditWeave} injects at the same head and holds {@link Weave#priority()} at its default
 * of zero. This weave declares 100, and higher runs first, so the two handlers run in the reverse of
 * their alphabetical order. That is what makes the pair worth having: on two weaves whose names
 * happened to agree with their priorities, a listing sorted by name would be indistinguishable from
 * one in execution order.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(value = Gateway.class, priority = 100)
public final class PriorityWeave {

    /**
     * Runs on entry to {@link Gateway#charge(java.math.BigDecimal)}, ahead of the handler
     * {@code AuditWeave} injects at the same position.
     */
    @Inject(method = "charge(BigDecimal)", at = @At(Point.HEAD))
    void runsFirst() {
        System.out.println("priority 100: this one runs before AuditWeave's");
    }
}
