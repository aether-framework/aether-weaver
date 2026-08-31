package com.acme.payments;

import de.splatgames.aether.weaver.api.Accessor;
import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Invoker;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Shadow;
import de.splatgames.aether.weaver.api.Weave;

import java.math.BigDecimal;

/**
 * Weave on {@link Receipt} holding one member of each kind a weave can declare, all of them
 * correct.
 *
 * <p>The four declarations are what the unused-declaration inspection has to be kept off: nothing
 * in the editor names a weave class or calls a handler, so without the plugin's implicit-usage
 * provider a correct weave is greyed out from top to bottom. {@link Accessor}, {@link Invoker} and
 * {@link Inject} are each claimed as used by that provider; {@link Shadow} is claimed only as read
 * and written, which keeps {@link #amount} off the never-assigned and value-never-used warnings but
 * not off the never-used one — {@link #amount} is kept off that one only because {@link #onValid()}
 * really does read it. {@link #reallyUnused()} is the control that must keep its warning.
 *
 * <p>The weave is not {@code abstract} and each generated declaration has a body, which is the
 * other spelling of the same thing: nothing of an {@code @Accessor} or {@code @Invoker} body is
 * used, so a body that can only be reached by mistake is a legal way to write one.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Receipt.class)
public final class ReceiptAccess {

    /**
     * Binds to {@link Receipt}'s own {@code amount} field.
     *
     * <p>The name and the erased type are both the target's, which is what {@code AW1031} compares:
     * a {@code @Shadow} declaring any other type for a field the target already has is reported
     * there. The field is read by {@link #onValid()} and assigned by nobody, and it must not be
     * reported as never assigned — the woven target assigns it.
     */
    @Shadow
    private BigDecimal amount;

    /**
     * Generates a {@code public} getter for {@link Receipt}'s {@code amount} field.
     *
     * <p>No field name is written, so it is taken from this method's own name by dropping the
     * {@code get} prefix. A getter takes nothing and returns the field's type; a declaration that
     * describes neither a read nor a write of that field is {@code AW1031}, reported by the engine
     * against the target's class file, and a generated name and descriptor {@link Receipt} already
     * declares is {@code AW1095}.
     *
     * @return nothing; the body is not used, and the generated method reads the field instead
     * @throws AssertionError always, if the declaration is called as written
     */
    @Accessor
    BigDecimal getAmount() {
        throw new AssertionError("accessor");
    }

    /**
     * Generates a {@code public} forwarder to {@link Receipt}'s {@code private hasAmount} method.
     *
     * <p>No method name is written, so it is taken from this method's own name by dropping the
     * {@code call} prefix. The prefix is also what keeps the generated name off the method being
     * called, which is the collision {@code AW1095} reports; a declaration matching no method of
     * that name and descriptor on the target is {@code AW1020}.
     *
     * @return nothing; the body is not used, and the generated method forwards instead
     * @throws AssertionError always, if the declaration is called as written
     */
    @Invoker
    boolean callHasAmount() {
        throw new AssertionError("invoker");
    }

    /**
     * Runs on entry to {@link Receipt#valid()}.
     *
     * <p>Reads the shadow field, which is what makes this weave a fixture for the shadow being an
     * implicit read rather than merely an implicit use: a field the weave never mentioned at all
     * would still be reported as unused.
     */
    @Inject(method = "valid", at = @At(Point.HEAD))
    void onValid() {
        System.out.println("validating a receipt for " + this.amount);
    }

    /**
     * Called by nothing, and the control for the implicit-usage provider.
     *
     * <p>{@code private} and carrying none of the framework's annotations, so the provider makes no
     * claim about it and the platform's unused-declaration inspection still greys it out. A run in
     * which this method loses its warning means the provider has begun claiming every member of a
     * weave, which would take the warning off genuinely dead code as well.
     */
    private void reallyUnused() {
    }
}
