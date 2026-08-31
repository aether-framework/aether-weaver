package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Weave on {@link Router} carrying the two points that {@code SilentWeave} cannot demonstrate on
 * {@link Silent}.
 *
 * <p>Neither declaration pins an ordinal, names a shift or carries a slice, so both are matched
 * against the target's source rather than against its class file, and both are drawn on a project
 * that has never been compiled. What makes them worth a target of their own is
 * {@link Router#route(String)}'s shape: one {@code return} written last, which is what
 * {@link Point#TAIL} needs from a body, and one call to {@code lookup}, which is what lets
 * {@link Point#INVOKE_AFTER} resolve without an ordinal.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Router.class)
public final class RouterWeave {

    /**
     * Runs at the last point {@link Router#route(String)} returns from.
     *
     * <p>{@link Point#TAIL} takes no {@link At#target()}; writing one is reported as
     * {@code AW1043}. It resolves to at most one position, which is what distinguishes it from
     * {@link Point#RETURN} on a body with several exits.
     */
    @Inject(method = "route", at = @At(Point.TAIL))
    void onWayOut() {
        System.out.println("leaving route, once");
    }

    /**
     * Runs immediately after the call to {@code Router.lookup} has returned.
     *
     * <p>{@link Point#INVOKE_AFTER} requires a {@link At#target()} and a declaration without one is
     * reported as {@code AW1043}. The target is written unqualified, which the plugin's point-target
     * check parses but does not look up: without an owner that resolves to exactly one class there
     * is nothing to say the member should have been found in.
     *
     * <p>The position drawn for this declaration is the call itself, the same anchor
     * {@link Point#INVOKE} would get; which side of the call is meant is carried by the preview
     * section's own heading rather than by a different line.
     */
    @Inject(method = "route", at = @At(value = Point.INVOKE_AFTER, target = "lookup"))
    void onLookupReturned() {
        System.out.println("lookup has answered");
    }
}
