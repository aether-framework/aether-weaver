package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Weave naming its target as a string rather than as a class literal.
 *
 * <p>The one declaration this file exists for is on the class: {@link Weave#targets()} instead of
 * {@link Weave#value()}. Everything downstream of it has to work the same way — the selector
 * {@code "settle"} still resolves to {@link Gateway#settle()}, the target-side gutter still lists
 * this handler among the ones injecting into that method, and the weave still appears under
 * {@link Gateway} in the tool window — because a target named as a string is a target.
 *
 * <p>The string form is what {@code AW1009} suggests replacing with {@link Gateway}{@code .class}
 * where the class is on the compile classpath. It is informational, because the string form works,
 * and only the annotation processor reports it, since only the processor knows what the compile
 * classpath holds; this sample runs no processor, so nothing reports it here. Naming the target both
 * ways at once is a different matter and is {@code AW1002}, which the deliberately wrong
 * {@code NamesTheTargetTwice} in {@code Reported.java} carries.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(targets = "com.acme.payments.Gateway")
public final class TargetsByName {

    /**
     * Runs on entry to {@link Gateway#settle()}.
     *
     * <p>A bare name needs no signature: {@code settle} is not overloaded. The selector is resolved
     * against the class the {@code targets} string names, which is the step that would go silent
     * if the string form were ever read as anything but a target.
     */
    @Inject(method = "settle", at = @At(Point.HEAD))
    void onSettle() {
        System.out.println("settling, found through a named target");
    }
}
