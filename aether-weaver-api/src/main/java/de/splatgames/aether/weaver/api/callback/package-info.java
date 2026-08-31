/**
 * The objects a handler declares as parameters and the engine constructs for it at each woven
 * position.
 *
 * <p>A handler is an ordinary Java method, so everything it learns and everything it can decide has to
 * arrive through its parameter list. The types here are that channel. A handler never constructs one
 * for a weave; what a weave author writes is a parameter type and nothing else. A callback is
 * constructed by the injected code immediately before it calls the handler and read back immediately
 * after; a carrier is too, on an {@link de.splatgames.aether.weaver.api.Inject} handler, where the
 * write-back is read into the target's own slot. An {@link Operation} is neither: it is a dynamic
 * constant resolved once and cached for the life of the class, and nothing is read back from it once
 * the handler returns.
 *
 * <h2>The three families</h2>
 *
 * <ul>
 *   <li><b>Control.</b> {@link Callback} and {@link ReturnableCallback} let an
 *       {@link de.splatgames.aether.weaver.api.Inject} handler end the target method at the position
 *       the injection matched. {@link Callback} is sealed and permits {@link ReturnableCallback} as
 *       its only direct subtype.
 *   <li><b>Captured locals.</b> {@link LocalRef} and the eight primitive carriers —
 *       {@link LocalIntRef}, {@link LocalLongRef}, {@link LocalFloatRef}, {@link LocalDoubleRef},
 *       {@link LocalBooleanRef}, {@link LocalByteRef}, {@link LocalShortRef} and {@link LocalCharRef}
 *       — let a handler write a local variable of the target rather than only read it.
 *   <li><b>The wrapped operation.</b> {@link Operation} is the handle a
 *       {@link de.splatgames.aether.weaver.api.Wrap} handler receives, and the only way it can perform
 *       the instruction it took over.
 * </ul>
 *
 * <p>{@link CallbackSupport} and {@link OperationSupport} are the implementations behind
 * {@link Callback}, {@link ReturnableCallback} and {@link Operation}. Woven code instantiates
 * {@link CallbackSupport} with {@code new}; an {@link OperationSupport} is never constructed by woven
 * code directly, but is built by its own bootstrap method as the target of an {@code ldc} of a
 * dynamic constant. Both carry {@code @ApiStatus.Internal} and are {@code public} only because the
 * code naming them is emitted into the target class, which is in another package. A handler declares
 * {@link Callback}, {@link ReturnableCallback} or {@link Operation} and never one of those two.
 *
 * <h2>Where the parameters go</h2>
 *
 * <p>A handler's parameter list is read as consecutive runs, in this order and no other:
 *
 * <ol>
 *   <li>the value the matched call produced, where the handler's <em>first</em> parameter carries
 *       {@link de.splatgames.aether.weaver.api.Result};
 *   <li>a prefix of the target method's own arguments — the first, the first two, and so on, or none;
 *       never a subset and never a suffix;
 *   <li>at most one {@link Callback} or {@link ReturnableCallback};
 *   <li>every parameter carrying {@link de.splatgames.aether.weaver.api.Local}.
 * </ol>
 *
 * <p>A callback or a capture out of that order is reported as {@code AW1040}. The engine looks for a
 * callback at exactly one position — the last parameter in front of the captures — so one written
 * earlier is not recognised as a callback at all and is matched against the target's arguments
 * instead, which is what the diagnostic then describes.
 *
 * <h2>Ending the target early</h2>
 *
 * <p>Which of the two callback types a handler may declare is decided by what the target method
 * returns, and neither substitutes for the other.
 *
 * <ul>
 *   <li>A target returning {@code void} takes a plain {@link Callback}. A {@link ReturnableCallback}
 *       there is {@code AW1071}.
 *   <li>A target returning a value takes a {@link ReturnableCallback} whose type argument is that
 *       return type, boxed. A plain {@link Callback} there is {@code AW1070}, because cancelling would
 *       leave the method with nothing to return. A type argument that is not the boxed return type is
 *       {@code AW1071} from the annotation processor alone: the engine reads only the erased parameter
 *       type from the class file and cannot repeat that half of the check.
 * </ul>
 *
 * <p>Cancelling sets a flag and returns. It does not unwind the handler — every statement after the
 * call still runs — and the target is left only once the handler has returned normally. The injected
 * code then reads {@link Callback#isCancelled()} once, and where the answer is {@code true} the target
 * returns at the position the declaration matched.
 *
 * <p>{@link ReturnableCallback#value()} is the value the target returns on that path. It carries the
 * value the target had already computed only at {@link de.splatgames.aether.weaver.api.Point#RETURN}
 * and {@link de.splatgames.aether.weaver.api.Point#TAIL}, which are the two positions where such a
 * value exists; a handler that calls it at any other point is reported as {@code AW1072}. At every
 * other position the callback starts empty, so {@link Callback#cancel()} on a target returning a
 * primitive fails the unboxing the injected code performs and the target throws a
 * {@link NullPointerException} at the cancelled return rather than in the handler.
 *
 * <h2>Writing a local variable</h2>
 *
 * <p>A Java parameter is passed by value, so a handler that assigns to an ordinary parameter changes
 * its own copy. A carrier is the way round that: the engine constructs one holding the variable's
 * value at the matched position, passes it, and reads {@code get()} back into the target's own slot as
 * soon as the handler returns.
 *
 * <p>The carrier type and {@code @Local(mutable = true)} are two halves of one statement of intent and
 * neither is accepted alone. A carrier parameter without the flag is {@code AW1054}, and the flag on a
 * parameter that is not a carrier is {@code AW1053}. A handler that only reads a local declares the
 * variable's own type instead.
 *
 * <p>A carrier is matched by what it <em>holds</em> rather than by its own type, so a
 * {@link LocalIntRef} parameter resolves against an {@code int} variable. Resolution never widens a
 * primitive: a variable of a primitive a carrier does not hold is reported as {@code AW1050} rather
 * than converted. {@link LocalRef} erases to {@link Object}, so the two strategies that draw their
 * candidates from variables whose declared type equals the parameter's —
 * {@link de.splatgames.aether.weaver.api.Local#ordinal()} and resolution by type alone — look for a
 * variable declared exactly {@link Object}; a reference variable of any other type is captured by
 * {@link de.splatgames.aether.weaver.api.Local#name()} or
 * {@link de.splatgames.aether.weaver.api.Local#index()}.
 *
 * <p>The write-back happens once, immediately after the handler's call returns, and only on the
 * {@link de.splatgames.aether.weaver.api.Inject} path. It precedes the check of the callback, so a
 * handler that both writes a local and cancels gets both. A
 * {@link de.splatgames.aether.weaver.api.Redirect} handler is handed its captures and no write-back is
 * emitted for them, so what it stores into a carrier is discarded without a diagnostic. Nothing reads
 * a carrier again afterwards either: one kept in a field and written later changes nothing in any
 * target.
 *
 * <h2>Performing a wrapped operation</h2>
 *
 * <p>{@link Operation} stands for the call, field access or instantiation a
 * {@link de.splatgames.aether.weaver.api.Wrap} matched. Calling
 * {@link Operation#call(Object...)} performs it and yields what it produced; calling it twice performs
 * it twice; never calling it stops it happening at all. Only a position that names an operation can be
 * wrapped — {@link de.splatgames.aether.weaver.api.Point#INVOKE},
 * {@link de.splatgames.aether.weaver.api.Point#FIELD} or
 * {@link de.splatgames.aether.weaver.api.Point#NEW} — and anything else is {@code AW1061}.
 *
 * <p>The arguments are exactly the operation's own inputs, in the order the JVM pushes them, and
 * exactly as many of them as the handler declares before its {@link Operation} parameter; the receiver
 * of an instance operation comes first and is not part of the operation's descriptor. Passing a
 * different number throws {@link IllegalArgumentException} at run time, naming the operation and both
 * counts, and is not checked when the class is woven. Arguments and result pass as {@link Object}, so
 * a primitive is boxed in both directions and an operation producing nothing yields {@code null}.
 * Nothing checks the type argument of {@link Operation}: the match compares the erased type, so a
 * wrong one compiles and weaves and fails as a {@link ClassCastException} inside the handler.
 *
 * <p>Several weaves may wrap one operation and they nest rather than collide, the highest
 * {@code @Weave(priority)} ending up outermost. A handler therefore cannot assume that calling its
 * operation performed the target's own instruction: it reaches the next level in, which may be another
 * weave's handler, and only the innermost call reaches the instruction. Whatever the operation throws
 * passes through unchanged and unwrapped, checked exceptions included, so the target's own
 * {@code catch} blocks see what they were compiled against.
 *
 * <h2>Threading and lifetime</h2>
 *
 * <p>A callback and a carrier are constructed afresh at each execution of the injected position and
 * reach only the handler that call invokes. Neither synchronizes anything and neither is published by
 * the engine anywhere a second thread could reach it, so an ordinary handler never shares one; a
 * handler that publishes one itself carries no visibility guarantee with it. An {@link Operation} is
 * the opposite: the woven call site holds it as a dynamic constant, which the JVM resolves once and
 * caches, so every execution of that site — on every thread — hands the handler the same instance. The
 * handle carries no per-call state, and whether performing the operation concurrently is safe is a
 * property of the operation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     // Ledger declares: Receipt charge(BigDecimal amount), with a local `int attempts`.
 *     @Inject(method = "charge(java.math.BigDecimal)",
 *             at = @At(value = Point.INVOKE, target = "#post"),
 *             require = 1)
 *     private void beforePost(java.math.BigDecimal amount,
 *                             ReturnableCallback<Receipt> callback,
 *                             @Local(name = "attempts", mutable = true) LocalIntRef attempts) {
 *         attempts.set(attempts.get() + 1);      // written into Ledger's own slot
 *         if (attempts.get() > 3) {
 *             callback.cancel(Receipt.rejected());  // charge() returns this and runs no further
 *         }
 *     }
 *
 *     // Ledger.charge calls: Receipt r = this.gateway.send(amount);
 *     @Wrap(method = "charge(java.math.BigDecimal)",
 *           at = @At(value = Point.INVOKE, target = "Gateway.send"),
 *           require = 1)
 *     private static Receipt aroundSend(Gateway gateway,
 *                                       java.math.BigDecimal amount,
 *                                       Operation<Receipt> operation) {
 *         if (amount.signum() <= 0) {
 *             return Receipt.rejected();         // the operation is never performed
 *         }
 *         return operation.call(gateway, amount);   // the next level in, not necessarily the target
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.callback;
