package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hands an {@link Inject} handler the value the call in front of the injected code just produced.
 *
 * <p>The annotation goes on the handler's first parameter and takes no elements. At each matched
 * position the engine duplicates the value on the operand stack, parks the copy in a local, and
 * passes it as the handler's first argument. The target keeps its own copy: a capture observes the
 * call, it does not consume its result, and the value the target was about to use is still there
 * when the injected call returns.
 *
 * <h2>Where it may be used</h2>
 *
 * <p>The position the declaration matches has to be immediately after a call that returns
 * something. The engine looks backwards from the matched position, skipping the labels and line
 * numbers that a code element list carries, at the first real instruction it finds; that
 * instruction must be an invocation whose descriptor has a return type other than {@code void}.
 * Anything else is reported as {@code AW1104} and nothing is emitted at all — neither the capture
 * nor the call to the handler.
 *
 * <p>{@link Point#INVOKE_AFTER} is the point written for this, and {@link Point#INVOKE} with
 * {@link At#shift()} set to {@link At.Shift#AFTER} resolves to the same index. {@link Point#HEAD}
 * never sits after a call and always produces {@code AW1104}. {@link Point#RETURN} and
 * {@link Point#TAIL} sit at the return instruction itself, not after it: the check looks at the
 * instruction immediately before that site, so a capture succeeds when the method returns a call's
 * result directly — {@code return f();} — and produces {@code AW1104} only when it does not, as in
 * {@code return x;}.
 *
 * <h2>Only the first parameter, and only on an injection</h2>
 *
 * <p>Exactly one parameter is read for this annotation: the handler's first. An {@code @Result} on
 * any later parameter is not a capture and is not reported — the parameter is then matched against
 * the target method's own arguments like any other, and a type that does not correspond is
 * reported as {@code AW1040}.
 *
 * <p>Only {@link Inject} acts on it. A handler carrying {@link Redirect} or {@link Wrap} is parsed
 * with the flag set and neither injector reads it, so the annotation changes nothing there. A
 * redirect or a wrap already receives the operation's own inputs, and a wrap reaches its result
 * through {@link de.splatgames.aether.weaver.api.callback.Operation#call(Object...)}.
 *
 * <h2>The parameter's type</h2>
 *
 * <p>The captured value keeps the call's own type, unboxed: a call returning {@code int} is
 * captured by an {@code int} parameter, not an {@code Integer}. A two-slot value — a {@code long}
 * or a {@code double} — is duplicated whole rather than half at a time. The parameters after the
 * capture are matched against the target method's arguments as usual, so the capture occupies the
 * first slot and the prefix rule described on {@link Inject} applies from the second onwards.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Gateway.class)
 * public final class GatewayAudit {
 *
 *     // Gateway.send calls: int code = socket.write(frame);
 *     @Inject(method = "send(Payment)",
 *             at = @At(value = Point.INVOKE_AFTER, target = "Socket.write"),
 *             require = 1)
 *     private void afterWrite(@Result int code, Payment payment) {
 *         if (code < 0) {
 *             Metrics.writeFailed(payment);   // the target still receives `code` itself
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Inject
 * @see Point#INVOKE_AFTER
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Result {
}
