package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The callback handed to an injected handler whose target returns a value, which the handler may end early with
 * a value of its own.
 *
 * <p>A plain {@link Callback} can only stop the target; a target that returns something has to be given
 * something to return, and that is what this adds. A handler declares a parameter of this type, and the engine
 * constructs the callback, passes it, and — immediately after the handler returns — asks whether it was
 * cancelled. If it was, the target returns {@link #value()} at the point of the injected call and runs no
 * further.
 *
 * <p>A callback is supplied on the {@link de.splatgames.aether.weaver.api.Inject} path only.
 * {@link de.splatgames.aether.weaver.api.Wrap} hands the handler the operation itself, which it controls by
 * performing it or not.
 *
 * <h2>Where the parameter goes</h2>
 *
 * <p>A handler's parameter list is, in order: the result of the matched call where the handler captures it with
 * {@link de.splatgames.aether.weaver.api.Result}, then a prefix of the target method's own arguments, then at
 * most one {@link Callback} or {@link ReturnableCallback}, then every {@link de.splatgames.aether.weaver.api.Local}
 * capture. A callback anywhere else in that list is not recognised as one, and the resulting shape is reported
 * as {@code AW1040}.
 *
 * <h2>Which callback a target admits</h2>
 *
 * <ul>
 *   <li><b>A target that returns a value takes this one.</b> A plain {@link Callback} on such a target is
 *       reported as {@code AW1070}: cancelling it would leave the method with nothing to return.
 *   <li><b>A {@code void} target takes a plain {@link Callback}.</b> This type on a {@code void} target is
 *       reported as {@code AW1071}, since there is no value for such a method to return instead.
 *   <li><b>The type argument is the target's return type, boxed.</b> A target returning {@code int} takes a
 *       {@code ReturnableCallback<Integer>}, because a type argument cannot be a primitive. A mismatch is
 *       reported as {@code AW1071} by the annotation processor. The engine does not repeat that half of the
 *       check — it compares only {@code void} against non-{@code void} — so under a build that skips the
 *       processor a wrong type argument weaves, and surfaces as a {@link ClassCastException} on the cancelled
 *       return, where the value is cast to the target's own return type.
 * </ul>
 *
 * <h2>What cancelling does</h2>
 *
 * <p>Cancellation is read once, at the injected call, immediately after the handler returns. Everything the
 * target did before that point has already happened and is not undone; everything below the injected call does
 * not run, and neither does any other handler whose call was emitted after this one at the same position.
 *
 * <p>Mutable {@link de.splatgames.aether.weaver.api.Local} captures are written back into the target's slots
 * before the cancellation is read, so a handler that both writes a local and cancels gets both.
 *
 * <p>The value the target returns is {@link #value()} as it stands when the handler returns, converted to the
 * target's return type: cast for a reference type, cast to the boxed type and unboxed for a primitive one.
 * Cancelling a primitive-returning target with a {@code null} value therefore fails with a
 * {@link NullPointerException} at the return rather than returning a default, and {@link Callback#cancel()} on a
 * primitive-returning target does the same wherever the callback carries no value.
 *
 * <h2>Reading the value the target computed</h2>
 *
 * <p>At {@link de.splatgames.aether.weaver.api.Point#RETURN} and
 * {@link de.splatgames.aether.weaver.api.Point#TAIL} the callback is created holding the value the target is
 * about to return, so {@link #value()} reports it before anything is cancelled — which is how a handler
 * inspects, and then replaces, the target's own result. At every other point no such value exists yet and the
 * callback starts empty.
 *
 * <p>A handler that calls {@link #value()} and is declared at any other point is reported as {@code AW1072}.
 * The check reads the handler's own compiled instructions for a call to this method, so it sees a direct call
 * and not one made on the handler's behalf by another method; a callback reached that way reports {@code null},
 * which is indistinguishable from a target that genuinely computed {@code null}.
 *
 * <h2>Implementations</h2>
 *
 * <p>{@link Callback} is sealed and permits this interface alone. This interface is {@code non-sealed}. The
 * engine constructs its own implementation at each injected call.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     @Inject(method = "charge(java.math.BigDecimal)",
 *             at = @At(Point.HEAD),
 *             require = 1)
 *     private void beforeCharge(BigDecimal amount, ReturnableCallback<Receipt> callback) {
 *         if (amount.signum() <= 0) {
 *             callback.cancel(Receipt.rejected());   // charge() returns this and runs no further
 *         }
 *     }
 *
 *     @Inject(method = "charge(java.math.BigDecimal)", at = @At(Point.RETURN), require = 1)
 *     private void afterCharge(ReturnableCallback<Receipt> callback) {
 *         Receipt computed = callback.value();       // the value charge() is about to return
 *         callback.cancel(computed.stamped());
 *     }
 * }
 * }</pre>
 *
 * @param <T> the target method's return type, boxed
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Callback
 * @see de.splatgames.aether.weaver.api.Inject
 */
public non-sealed interface ReturnableCallback<T> extends Callback {

    /**
     * Ends the target method with the given value.
     *
     * <p>Marks the callback cancelled, exactly as {@link Callback#cancel()} does, and supplies what the target
     * returns instead. The target does not stop here: the handler runs to its own end, and the injected code
     * acts on the cancellation once the handler returns. Calling this more than once leaves the last value
     * supplied.
     *
     * <p>The value has to fit the target's return type, which is not checked until the return itself: a value of
     * the wrong type fails with a {@link ClassCastException} there, and a {@code null} on a target returning a
     * primitive fails with a {@link NullPointerException} at the unboxing.
     *
     * @param value the value the target method returns instead, which may be {@code null} only where the target
     *              returns a reference type
     */
    void cancel(@Nullable T value);

    /**
     * Returns the value the target method returns if the callback is cancelled as it stands.
     *
     * <p>This is the last value passed to {@link #cancel(Object)}. Where none has been passed, it is the value
     * the callback was created with: the value the target had already computed at
     * {@link de.splatgames.aether.weaver.api.Point#RETURN} and
     * {@link de.splatgames.aether.weaver.api.Point#TAIL}, and {@code null} at every other point — which is why a
     * handler that calls this at any other point is reported as {@code AW1072}.
     *
     * <p>Reading this does not cancel anything, and a cancelled callback goes on reporting the value it carries.
     *
     * @return the value the target method returns on cancellation, or {@code null} when none has been supplied
     *         and none was computed
     */
    @Contract(pure = true)
    @Nullable
    T value();
}
