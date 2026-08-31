package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The handle an {@link de.splatgames.aether.weaver.api.Inject @Inject} handler uses to end the
 * target method it was called from.
 *
 * <p>An injection adds a call and changes nothing else, so a handler cannot alter the target's
 * control flow by returning: it returns {@code void}, and a handler that does not is reported as
 * {@code AW1041}. A callback is the one channel back. The injected code constructs a callback
 * immediately before the handler call, passes it as an argument, and once the call returns — after
 * any {@code @Local(mutable = true)} writes-back run — reads {@link #isCancelled()}.
 * When the answer is {@code true} the target method returns at that position; when it is
 * {@code false} the target's own code carries on as though the call had only observed.
 *
 * <p>A handler that only observes declares no callback at all. Taking one costs an object
 * allocation at every execution of the injected position, and it is only worth taking for a
 * handler that can decide to stop the target.
 *
 * <h2>Declaring one</h2>
 *
 * <p>A handler's parameter list is, in order: a prefix of the target method's own arguments, then
 * at most one callback, then every {@link de.splatgames.aether.weaver.api.Local @Local} capture.
 * The engine recognises a callback by looking at the last parameter in front of the captures and
 * nowhere else, so a callback declared anywhere earlier is matched against the target's arguments
 * instead and the handler is reported as {@code AW1040}.
 *
 * <p>Which of the two callback types to declare is decided by what the target method returns, and
 * neither substitutes for the other.
 *
 * <ul>
 *   <li>A target returning {@code void} takes a {@code Callback}. A {@link ReturnableCallback}
 *       there is reported as {@code AW1071}, because a {@code void} method has nothing for the
 *       handler to supply a value for.
 *   <li>A target returning a value takes a {@link ReturnableCallback} whose type argument is that
 *       return type, boxed — {@code ReturnableCallback<Integer>} for a target returning
 *       {@code int}. A plain {@code Callback} there is reported as {@code AW1070}: cancelling
 *       would leave the method with nothing to return. A type argument that is not the boxed
 *       return type is reported as {@code AW1071} by the annotation processor — the engine reads
 *       only the erased parameter type from the class file, so it cannot repeat that check.
 * </ul>
 *
 * <h2>When cancellation takes effect</h2>
 *
 * <p>{@link #cancel()} sets a flag and returns. It does not unwind the handler: every statement
 * after it in the handler's body still runs, and the target is left only once the handler returns
 * normally. A handler that throws instead never reaches the test, and the exception propagates out
 * of the target at the injected position.
 *
 * <p>What "the target returns here" means depends on the position the declaration matched, because
 * the injected code is emitted immediately before the matched instruction.
 *
 * <ul>
 *   <li>At {@link de.splatgames.aether.weaver.api.Point#HEAD} the whole body is skipped, except in
 *       a constructor, where cancelling skips only what follows the {@code super()} or
 *       {@code this()} call — the delegated constructor has already run by that point, and it is
 *       what is left of the body, including any field initialisers javac emitted after the call,
 *       that is skipped.
 *   <li>At {@link de.splatgames.aether.weaver.api.Point#INVOKE} the call the target was about to
 *       make does not happen.
 *   <li>At {@link de.splatgames.aether.weaver.api.Point#RETURN} and
 *       {@link de.splatgames.aether.weaver.api.Point#TAIL} the target was returning anyway; what
 *       cancelling changes there is the value, through
 *       {@link ReturnableCallback#cancel(Object)}.
 * </ul>
 *
 * <p>The return is an ordinary return instruction in the target's own body, so anything that
 * follows it does not run — including the call another injection contributed to the same position
 * further down the emission order. A handler that must run regardless of another weave's decision
 * cannot rely on sharing a position with it.
 *
 * <p>A {@code try} range of the target that covered the matched position is split around the
 * injected call, which is reported as {@code AW1131}. The handler's own exceptions are therefore
 * not caught by the target's {@code catch} blocks.
 *
 * <h2>Captured locals and cancellation</h2>
 *
 * <p>A handler may take {@code @Local(mutable = true)} captures alongside a callback. The values
 * those carriers hold are written back into the target's own slots before {@link #isCancelled()}
 * is asked, so a handler that both writes a local and cancels gets both: the write lands in the
 * target's frame and is then discarded along with the frame, which matters where the local is one
 * the target would have used in code that is now skipped.
 *
 * <h2>One callback per call</h2>
 *
 * <p>A callback is created for a single handler call and is never reused. Two positions matched by
 * one declaration get one each per execution, two handlers at one position get one each, and
 * nothing the engine emits publishes a callback anywhere a second thread could reach it. Its state
 * is read once, immediately after the handler returns; a callback retained in a field and
 * cancelled later has no effect on any target, because nothing reads it again.
 *
 * <p>The interface is sealed and permits only {@link ReturnableCallback} as a direct subtype.
 * {@link CallbackSupport} is the implementation the injected code constructs.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     // void reset() -- a plain Callback, because there is no value to supply.
 *     @Inject(method = "reset()", at = @At(Point.HEAD), require = 1)
 *     private static void beforeReset(Callback callback) {
 *         if (Ledger.isFrozen()) {
 *             callback.cancel();      // reset() returns without running its body
 *             Audit.log(callback.id() + " refused a reset");   // this still runs
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see ReturnableCallback
 * @see de.splatgames.aether.weaver.api.Inject
 */
public sealed interface Callback permits ReturnableCallback {

    /**
     * Marks the target method as finished at the position the handler was injected at.
     *
     * <p>Takes effect when the handler returns, not at this call: the flag is set, the rest of the
     * handler's body runs, and the injected code then returns from the target. Cancelling twice is
     * the same as cancelling once, and there is no way to withdraw a cancellation.
     *
     * <p>A plain {@code Callback} is only ever handed to a handler whose target returns
     * {@code void}, so this form supplies no value. Called on a {@link ReturnableCallback} it
     * cancels without setting one, and the target returns whatever
     * {@link ReturnableCallback#value()} holds at that moment — the value the target itself
     * computed at {@link de.splatgames.aether.weaver.api.Point#RETURN} and
     * {@link de.splatgames.aether.weaver.api.Point#TAIL}, and {@code null} at every other point.
     * A {@code null} where the target returns a primitive fails the unboxing the injected code
     * performs: the target throws a {@link NullPointerException} naming
     * {@link ReturnableCallback#value()}, at the cancelled return rather than in the handler.
     * {@link ReturnableCallback#cancel(Object)} is the form that says what to return.
     */
    void cancel();

    /**
     * Reports whether this callback has been cancelled.
     *
     * <p>The injected code asks this once, after the handler has returned, and returns from the
     * target when the answer is {@code true}. A handler may ask it to find out whether a statement
     * of its own has already cancelled; it never reports another weave's decision, because a
     * callback belongs to one handler call.
     *
     * @return whether {@link #cancel()} or {@link ReturnableCallback#cancel(Object)} has been
     *         called on this callback
     */
    @Contract(pure = true)
    boolean isCancelled();

    /**
     * Returns the identifier of the injection declaration this callback was created for.
     *
     * <p>This is the {@code id} element of the declaration where one was written, and otherwise
     * the identifier derived from the declaration: the weave class, the handler's name and
     * descriptor, and the suffix {@code #inject}. It names the declaration rather than the
     * position, so a declaration that matched three positions hands the same string to all three,
     * and two declarations on one handler method that both leave {@code id} empty are
     * indistinguishable by it. Setting {@code id} explicitly is what makes them distinguishable in
     * a handler that is shared, and in the {@code AW1043} and {@code AW1044} diagnostics for a
     * declaration that names no {@link de.splatgames.aether.weaver.api.Group @Group}; a grouped
     * declaration's {@code AW1043} reports its handler instead, not this identifier.
     *
     * @return the declaration's identifier, never {@code null}
     */
    @Contract(pure = true)
    @NotNull
    String id();
}
