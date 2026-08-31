package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.Nullable;

/**
 * A handle to the operation a {@link de.splatgames.aether.weaver.api.Wrap} handler matched, which the handler
 * performs by calling it.
 *
 * <p>A wrap does not replace what it matches; it surrounds it. The handler receives the operation as its last
 * parameter and decides what happens to it: calling {@link #call(Object...)} performs it and yields what it
 * produced, calling it twice performs it twice, and never calling it stops it happening at all. Whatever the
 * handler returns is what the target goes on with, which need not be what the operation produced. Where a
 * {@link de.splatgames.aether.weaver.api.Redirect} substitutes an operation and never sees the original, a wrap
 * always keeps a handle to it.
 *
 * <p>The handle is not a callback and carries no cancellation: a handler that performs no operation and returns
 * a value of its own has already suppressed it. Nothing outside a wrap handler is handed one.
 *
 * <h2>What an operation can be</h2>
 *
 * <p>Only a position that names an operation can be wrapped: a method call
 * ({@link de.splatgames.aether.weaver.api.Point#INVOKE}), a field read or write
 * ({@link de.splatgames.aether.weaver.api.Point#FIELD}) or an instantiation
 * ({@link de.splatgames.aether.weaver.api.Point#NEW}). A bare position such as
 * {@link de.splatgames.aether.weaver.api.Point#HEAD} names no operation and is reported as {@code AW1061},
 * both when the declaration is validated and again if a matched instruction turns out not to be one.
 *
 * <h2>The arguments {@link #call(Object...)} takes</h2>
 *
 * <p>Exactly the operation's own inputs, in the order the JVM pushes them, and exactly as many of them as the
 * handler declares before its {@code Operation} parameter. They are the values the operation is performed with,
 * so a handler is free to pass something other than what it received.
 *
 * <table>
 *   <caption>The inputs, by operation</caption>
 *   <tr><th>Operation</th><th>Arguments to {@link #call(Object...)}</th><th>Result</th></tr>
 *   <tr><td>Instance call</td><td>the receiver, then the call's arguments</td>
 *       <td>the call's return type</td></tr>
 *   <tr><td>Static call</td><td>the call's arguments</td><td>the call's return type</td></tr>
 *   <tr><td>Instance field read</td><td>the field's owner</td><td>the field's type</td></tr>
 *   <tr><td>Static field read</td><td>none</td><td>the field's type</td></tr>
 *   <tr><td>Instance field write</td><td>the owner, then the value</td><td>{@code null}</td></tr>
 *   <tr><td>Static field write</td><td>the value</td><td>{@code null}</td></tr>
 *   <tr><td>Instantiation</td><td>the constructor's arguments</td><td>the created object</td></tr>
 * </table>
 *
 * <p>The receiver of an instance operation is pushed before the descriptor's own arguments and is not part of
 * that descriptor; the handler's parameters mirror that asymmetry, and so does this call. A handler whose
 * parameters do not have the operation's shape is reported as {@code AW1040}, with the operation's signature and
 * the handler's printed side by side.
 *
 * <h2>What the type argument means</h2>
 *
 * <p>The operation's result type, boxed: an operation producing {@code int} is an {@code Operation<Integer>} and
 * one producing nothing — a field write, or a call to a {@code void} method — is an {@code Operation<Void>} whose
 * {@link #call(Object...)} yields {@code null}. Nothing checks the type argument, because the match compares the
 * erased type, so a wrong one compiles and weaves and fails as a {@link ClassCastException} inside the handler at
 * the cast the compiler inserted.
 *
 * <h2>What the handler's shape must be</h2>
 *
 * <p>Every one of these fails the build rather than surfacing at run time. The first three are reported by the
 * annotation processor at compile time and again by the engine — except that a handler with no {@code Operation}
 * parameter at all is reported by the processor only as {@code AW1063}: the processor stops there and never
 * reaches the check behind {@code AW1062}, which the engine still performs. The last three are the engine's
 * alone, and a build that runs no processor still fails on all six.
 *
 * <ul>
 *   <li><b>The handler is {@code static}</b>, or {@code AW1005}. An inner level of a nest is reached through
 *       {@link #call(Object...)}, which carries the operation's own arguments and no receiver.
 *   <li><b>Its last parameter is an {@code Operation}</b>, or {@code AW1063} when there is none at all.
 *   <li><b>Nothing follows that parameter</b>, or {@code AW1062}. This also fires, alongside {@code AW1063},
 *       for a handler with parameters and no {@code Operation} among them at all. A
 *       {@link de.splatgames.aether.weaver.api.Redirect} handler may append the enclosing method's own
 *       arguments; a wrap handler may not, because an inner level receives only what
 *       {@link #call(Object...)} carries.
 *   <li><b>Everything before it is the operation's own inputs</b>, in order, or {@code AW1040}. Not a prefix of
 *       them: a wrap is matched on exact arity, and a redirect is what permits a handler to take
 *       <em>more</em> parameters than the operation has inputs, by appending the enclosing method's own
 *       arguments — a wrap has no enclosing method's arguments to append, so it admits no such handler.
 *   <li><b>Its return type is what the operation produced</b>, or {@code AW1040}. A primitive result must be
 *       exactly that primitive, a reference result is accepted by any reference return type, and a handler
 *       wrapping an operation that produces nothing returns {@code void}.
 *   <li><b>It names no shift</b>, or {@code AW1102}: a wrap takes over the operation it matched, so a
 *       neighbouring instruction is not something the handler's signature describes.
 * </ul>
 *
 * <h2>Nesting</h2>
 *
 * <p>Several weaves may wrap one operation, and they nest rather than collide. The outermost is the one whose
 * weave declares the highest {@code @Weave(priority)}; the handler in that position receives an operation that
 * performs the next handler in, not the target's own instruction, and only the innermost handler's call reaches
 * the instruction itself. A handler therefore cannot assume that calling the operation performed it: an inner
 * handler that never calls its own operation returns a value of its own, and the outer handler cannot tell.
 *
 * <p>A {@link de.splatgames.aether.weaver.api.Redirect} does not join that nest. A redirect removes the
 * operation a wrap would hold a handle to, so a redirect and a wrap claiming the same call site are reported as
 * {@code AW1060}.
 *
 * <h2>Identity, cost and threads</h2>
 *
 * <p>The woven call site holds the operation as a dynamic constant, which the JVM resolves once and caches, so
 * every execution of that site hands the handler the same instance, and the nesting is built into that constant
 * when it is resolved rather than assembled per call. {@link #call(Object...)} is itself varargs over
 * {@code Object[]}, so each call to it allocates an argument array and boxes any primitive argument on the way
 * in — the instance behind the handle is what costs nothing per call, not a call to it.
 *
 * <p>The handle holds nothing from the target's frame. A handler that keeps it in a field and calls it later
 * performs the operation again, with whatever arguments are passed then, outside the target's control flow. It
 * follows that the same instance is reachable from every thread executing that site: the handle itself carries
 * no per-call state, and whether performing the operation concurrently is safe is a property of the operation.
 *
 * <h2>What is thrown</h2>
 *
 * <p>Whatever the operation throws passes through unchanged and unwrapped, checked exceptions included, so the
 * target's own {@code catch} blocks see exactly what they were compiled against. {@link #call(Object...)}
 * declares no {@code throws}, so a {@code catch} clause naming a checked exception around nothing but this call
 * does not compile; catch a supertype the compiler admits, or let the exception propagate.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     // Ledger.charge calls this.post(BigDecimal), which returns a Receipt.
 *     @Wrap(method = "charge(java.math.BigDecimal)",
 *           at = @At(value = Point.INVOKE, target = "#post"),
 *           require = 1)
 *     private static Receipt aroundPost(Ledger receiver, BigDecimal amount,
 *                                       Operation<Receipt> operation) {
 *         if (amount.signum() <= 0) {
 *             return Receipt.rejected();                  // the operation is never performed
 *         }
 *         return operation.call(receiver, amount);        // the next level in, not necessarily the target
 *     }
 * }
 * }</pre>
 *
 * @param <T> the operation's result type, boxed, and {@link Void} for an operation that produces nothing
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Wrap
 * @see de.splatgames.aether.weaver.api.Redirect
 */
public interface Operation<T> {

    /**
     * Performs the operation and returns what it produced.
     *
     * <p>May be called any number of times, including none. In a nest of wraps this reaches the next handler in
     * rather than the target's own instruction, so what it performs is not necessarily the operation the
     * enclosing method was compiled with.
     *
     * <p>The number of arguments is fixed by the operation and checked on every call. Each argument is converted
     * to the operation's own parameter type as it is passed: a reference that is not an instance of that type
     * fails with a {@link ClassCastException}, and a {@code null} where the operation takes a primitive fails
     * with a {@link NullPointerException}.
     *
     * @param args the operation's own inputs, in the order the JVM pushes them — the receiver first for an
     *             instance call or field access — of which there are exactly as many as the handler declares
     *             before its {@code Operation} parameter; a {@code null} array is read as no arguments, and an
     *             individual argument may be {@code null} where the operation takes a reference
     * @return what the operation produced, boxed for a primitive result, or {@code null} for an operation that
     *         produces nothing
     * @throws IllegalArgumentException if the number of arguments is not the number the operation takes; the
     *                                  message names the operation and both counts
     * @throws ClassCastException if an argument is not of the operation's own parameter type
     * @throws NullPointerException if a {@code null} is passed where the operation takes a primitive
     */
    @Nullable
    T call(@Nullable Object... args);
}
