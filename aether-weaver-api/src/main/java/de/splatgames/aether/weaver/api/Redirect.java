package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces an operation the target performs with a call to a handler.
 *
 * <p>The matched call, field access or instantiation stops happening: the handler stands in
 * for it, receives what the operation was about to consume, and produces what it would have
 * produced. Where {@link Wrap} keeps a handle to the original and may perform it, a redirect
 * never sees it — a handler that needs to decide whether the operation happens wants
 * {@link Wrap}, and one that only wants to run code beside it wants {@link Inject}.
 *
 * <p>Every matched operation is replaced, not only the first; {@link At#ordinal()} is how a
 * declaration names one of several.
 *
 * <h2>What may be redirected</h2>
 *
 * <p>Three points name an operation: {@link Point#INVOKE}, {@link Point#FIELD} and
 * {@link Point#NEW}. Any other built-in point names a position in the method rather than
 * something to take over, and is reported as {@code AW1061} — by the annotation processor
 * against the source and again by the engine, which also refuses a contributed point that
 * turns out to resolve to the position after an operation rather than to the operation
 * itself. {@link Point#INVOKE_AFTER} is the common mistake here: for a redirect the point is
 * {@link Point#INVOKE}.
 *
 * <p>{@link At#shift()} is refused with {@code AW1102}. A shifted position names a
 * neighbouring instruction, and the handler's signature describes the operation, so the two
 * cannot both be true.
 *
 * <h2>The handler's shape</h2>
 *
 * <p>The handler's leading parameters are the operation's own inputs, in the order the JVM
 * pushes them, and its return type is what the operation produced. The rest of the shape
 * follows the same rules as {@link Inject}: it must be reachable from the woven class, which
 * for an instance handler means the weave dissolves into its target, and {@code AW1005}
 * otherwise.
 *
 * <table>
 *   <caption>The inputs, by operation</caption>
 *   <tr><th>Operation</th><th>Leading parameters</th><th>Return type</th></tr>
 *   <tr><td>Instance call</td><td>the receiver, then the call's arguments</td>
 *       <td>the call's return type</td></tr>
 *   <tr><td>Static call</td><td>the call's arguments</td><td>the call's return type</td></tr>
 *   <tr><td>Instance field read</td><td>the field's owner</td><td>the field's type</td></tr>
 *   <tr><td>Static field read</td><td>none</td><td>the field's type</td></tr>
 *   <tr><td>Instance field write</td><td>the owner, then the value</td><td>{@code void}</td></tr>
 *   <tr><td>Static field write</td><td>the value</td><td>{@code void}</td></tr>
 *   <tr><td>Instantiation</td><td>the constructor's arguments</td>
 *       <td>the created type</td></tr>
 * </table>
 *
 * <p>The receiver of an instance operation is pushed before the descriptor's own arguments
 * and is not part of that descriptor, and the handler's signature has to mirror that
 * asymmetry. A primitive input must be exactly the same primitive in the handler, and a
 * reference input is accepted by a parameter of any reference type. A
 * handler whose shape does not fit is reported as {@code AW1040}, with the operation's
 * signature and the handler's printed side by side.
 *
 * <p>Beyond the operation's inputs a handler may take more, in this order:
 *
 * <ul>
 *   <li><b>A prefix of the enclosing target method's own arguments.</b> These follow the
 *       operation's inputs and are matched by exact type, exactly as for {@link Inject}.
 *   <li><b>{@link Local} captures</b>, last. They are supplied, but the write-back is not: a
 *       capture declared {@code mutable = true} is handed to the handler as a carrier and
 *       what the handler stores into it is not read back into the target's variable. Writing
 *       a local from a handler is what the {@link Inject} path does.
 * </ul>
 *
 * <h2>Instantiation is one operation, not three</h2>
 *
 * <p>A {@code new} in a class file is an allocation, a duplication and a constructor call.
 * Redirecting {@link Point#NEW} replaces all three: the allocation and its duplication
 * disappear, because the handler now produces the reference itself, and the constructor call
 * becomes the call to the handler. Everything between them stays, because that is the code
 * computing the arguments the handler is about to receive.
 *
 * <h2>One redirect per site</h2>
 *
 * <p>A call has one callee, so two redirects of one operation cannot both apply, and a
 * redirect meeting a {@link Wrap} on the same operation is no better: the redirect removes
 * the operation the wrap would hold a handle to. Either case is reported as {@code AW1060}.
 * Two wraps compose and nest, so making both of them {@link Wrap} is one fix; narrowing one
 * with an {@link At#ordinal()} or a {@link Slice} is the other.
 *
 * <p>The comparison is between declarations rather than between resolved instructions: the
 * target class, the {@link #method()} text, the point, its target text, its ordinal and its
 * slice. Two declarations that differ in any of those are not reported even where they turn
 * out to match the same instruction.
 *
 * <h2>How many matches are required</h2>
 *
 * <p>{@link #require()} is the fewest matches that count as success and {@link #allow()} the
 * most; falling short is {@code AW1043} and exceeding is {@code AW1044}. An omitted
 * {@link #require()} means one, while an explicit {@code require = 0} means the declaration
 * is optional. A declaration naming a {@link #group()} is accounted through the group and
 * neither of its own bounds is checked.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class OfflineLedger {
 *
 *     // Ledger.charge calls: gateway.send(payment), where gateway is a Gateway.
 *     @Redirect(method = "charge(java.math.BigDecimal)",
 *               at = @At(value = Point.INVOKE, target = "Gateway.send"),
 *               require = 1)
 *     private static Receipt insteadOfSend(Gateway gateway, Payment payment) {
 *         return Receipt.deferred(payment);   // gateway.send is never called
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Wrap
 * @see Inject
 * @see Point
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {

    /**
     * The method to weave into, written as a selector.
     *
     * <p>This names the method that performs the operation, not the operation itself, which
     * is {@link At#target()}. A selector that resolves to no method is reported as
     * {@code AW1020} and one that resolves to more than one as {@code AW1021}; naming the
     * parameter types disambiguates an overload.
     *
     * @return the target method selector
     */
    String method();

    /**
     * The operation to replace.
     *
     * <p>One point, not an array: a handler's signature describes one operation, and two
     * points naming operations of different shapes could not both fit it.
     *
     * @return the injection point
     */
    At at();

    /**
     * Narrows the search to one or more regions of the target rather than its whole body.
     *
     * <p>The point selects the slice it searches by {@link At#slice()}, matched against
     * {@link Slice#id()}. An ordinal counts within the narrowed region, not within the
     * method.
     *
     * @return the slices to search, or an empty array to search the whole method
     */
    Slice[] slice() default {};

    /**
     * Distinguishes this declaration from others in diagnostics.
     *
     * <p>Left empty, an identifier is derived from the handler and the kind of injection:
     * the weave class, the handler's name and descriptor, and the suffix {@code #redirect}.
     *
     * @return the identifier, or an empty string to let one be derived
     */
    String id() default "";

    /**
     * The fewest matches that count as success.
     *
     * <p>An omitted {@code require} is read as one, so a redirect that matches nothing is an
     * error; an explicit {@code require = 0} makes the declaration deliberately optional. The
     * class file records only the elements that were written, which is what makes the two
     * distinguishable.
     *
     * <p>Not checked when {@link #group()} is set; the group's total is checked instead.
     *
     * @return the minimum number of matches, or {@code 0} to require none
     */
    int require() default 0;

    /**
     * The most matches that count as success.
     *
     * <p>Not checked when {@link #group()} is set.
     *
     * @return the maximum number of matches; {@code 0} imposes no upper bound rather than
     *         permitting none
     */
    int allow() default 0;

    /**
     * Accounts this declaration's matches against a named {@link Group} rather than on its
     * own, so that several declarations can answer for one another.
     *
     * <p>A grouped declaration's own {@link #require()} and {@link #allow()} are not checked
     * at all, which is why one is normally written with {@code require = 0}.
     *
     * @return the group name, or an empty string to be accounted alone
     */
    String group() default "";
}
