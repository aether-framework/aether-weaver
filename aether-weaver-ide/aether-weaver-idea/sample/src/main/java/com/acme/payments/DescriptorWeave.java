package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Weave on {@link Gateway} that exists for one selector: the descriptor form.
 *
 * <p>{@code desc:settle()V} names the method {@code AuditWeave} names as {@code "settle"}, and is
 * what the conversion intention rewrites into the source form. The descriptor carries its return
 * type; written as {@code desc:settle()} it would be reported as {@code AW1019} rather than read as
 * matching any return type.
 *
 * <p>The weave carries nothing else, so the descriptor form stands alone rather than sharing a file
 * with the source form it is compared against.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Gateway.class)
public final class DescriptorWeave {

    /**
     * Runs on entry to {@link Gateway#settle()}, selected by descriptor rather than by name.
     */
    @Inject(method = "desc:settle()V", at = @At(Point.HEAD))
    void onSettle() {
        System.out.println("settling, selected by descriptor");
    }
}
