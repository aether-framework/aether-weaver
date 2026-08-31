package de.splatgames.aether.weaver.api;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hands a matched operation to a handler that decides whether, when and how often to perform it.
 *
 * <p>A wrap does not replace what it matches; it surrounds it. The handler receives a
 * {@link de.splatgames.aether.weaver.api.callback.Operation} standing for the matched instruction
 * and may call it once, several times or not at all, and may return a value other than the one it
 * would have produced. Where {@link Redirect} substitutes an operation and never sees the original,
 * a wrap always keeps a handle to it; where {@link Inject} adds code beside an operation and leaves
 * it running, a wrap takes it over.
 *
 * <p>Every matched operation is wrapped, not only the first. {@link At#ordinal()} is how a
 * declaration names one of several.
 *
 * <h2>What the matched position must be</h2>
 *
 * <p>Only three points name an operation: {@link Point#INVOKE}, {@link Point#FIELD} and
 * {@link Point#NEW}. Any other built-in point names a position in the method with nothing to take
 * over, and is reported as {@code AW1061} — by the annotation processor against the source and
 * again by the engine before any bytes are written. {@link Point#HEAD} is what an omitted
 * {@link At#value()} means, so a point left unwritten is exactly as wrong as a wrong one.
 * {@link Point#INVOKE_AFTER} is the common mistake: for a wrap the point is {@link Point#INVOKE}.
 *
 * <p>The engine does not check a point contributed by a plugin against that list; it is judged
 * instead by the shape it lands on, and a resolved position that is not a call, a field access or an
 * instantiation is reported as {@code AW1061} when the emitter reaches it. The annotation processor
 * does not make that distinction: it reads only {@link At#value()}, so a declaration whose point is
 * named entirely through {@link At#custom()} is treated as an unwritten {@link At#value()} — which
 * means {@link Point#HEAD} — and is rejected as {@code AW1061} at compile time regardless of what
 * the contributed point would resolve to.
 *
 * <p>{@link At#shift()} is refused with {@code AW1102}, whatever the point. A shifted position names
 * a neighbouring instruction, and the handler's signature describes the operation, so the two cannot
 * both be true.
 *
 * <h2>The handler's shape</h2>
 *
 * <p>A handler is a method of the weave class satisfying all of the following.
 *
 * <ul>
 *   <li><b>It is {@code static}.</b> Reported as {@code AW1005} otherwise, by the annotation
 *       processor and by the engine alike, and independently of {@link Weave#kind()} — a wrap
 *       handler must be static even in an instance weave, where an ordinary {@link Inject} handler
 *       need not be. A wrap can end up nested inside another weave's wrap, and an inner level is
 *       reached through {@link de.splatgames.aether.weaver.api.callback.Operation#call(Object...)},
 *       which carries the operation's own arguments and no receiver. State beyond those arguments
 *       belongs in a static field of the weave.
 *   <li><b>Its last parameter is an
 *       {@link de.splatgames.aether.weaver.api.callback.Operation}.</b> A handler that declares
 *       parameters and whose last one is something else is {@code AW1062}; a handler that declares
 *       no {@link de.splatgames.aether.weaver.api.callback.Operation} anywhere is {@code AW1063}.
 *       A handler that wants no handle to the original wants {@link Redirect} instead.
 *   <li><b>Nothing follows that
 *       {@link de.splatgames.aether.weaver.api.callback.Operation}.</b> This is the rule that holds
 *       until a second weave arrives: a handler with trailing parameters can work as the outermost
 *       wrap, because the enclosing method's arguments are still on the stack, and fails the moment
 *       another weave nests inside it, since an inner level receives only what
 *       {@link de.splatgames.aether.weaver.api.callback.Operation#call(Object...)} carries. It is
 *       refused up front, as {@code AW1062}, rather than left to break later.
 *   <li><b>The parameters before it are the operation's own inputs — all of them, in order.</b>
 *       Not a prefix: a wrap handler declares exactly as many parameters as the operation has
 *       inputs, plus the {@link de.splatgames.aether.weaver.api.callback.Operation}. A handler with
 *       the wrong number, or with an input in the wrong place, is reported as {@code AW1040}, with
 *       the operation's signature and the handler's printed side by side. This is where a wrap and
 *       a {@link Redirect} differ: a redirect handler may take a prefix of the enclosing method's
 *       arguments after the operation's inputs, and a wrap handler may not.
 *   <li><b>It returns what the operation produced.</b> A primitive result must be exactly that
 *       primitive, including {@code void} for a field write; a reference result is satisfied by any
 *       reference type. The same rule governs each input: a primitive input needs the same
 *       primitive and a reference input is accepted by any reference parameter. A mismatch is
 *       {@code AW1040}.
 *   <li><b>It is reachable from the woven class.</b> For a {@code @Weave(kind = Kind.STATIC)} weave
 *       the injected call is an ordinary cross-class invocation, so the handler and its class must
 *       be {@code public} or share the target's package, and a private handler is never reachable:
 *       {@code AW1042}, checked by the annotation processor only. An instance weave is dissolved
 *       into its target and the handler travels with it, so the question does not arise there.
 * </ul>
 *
 * <h2>The inputs, by operation</h2>
 *
 * <table>
 *   <caption>What the handler's leading parameters are, and what it returns</caption>
 *   <tr><th>Operation</th><th>Leading parameters</th><th>Return type</th></tr>
 *   <tr><td>Instance call</td><td>the receiver, then the call's arguments</td>
 *       <td>the call's return type</td></tr>
 *   <tr><td>Static call</td><td>the call's arguments</td><td>the call's return type</td></tr>
 *   <tr><td>Instance field read</td><td>the field's owner</td><td>the field's type</td></tr>
 *   <tr><td>Static field read</td><td>none</td><td>the field's type</td></tr>
 *   <tr><td>Instance field write</td><td>the owner, then the value</td><td>{@code void}</td></tr>
 *   <tr><td>Static field write</td><td>the value</td><td>{@code void}</td></tr>
 *   <tr><td>Instantiation</td><td>the constructor's arguments</td><td>the created type</td></tr>
 * </table>
 *
 * <p>The receiver of an instance operation is pushed before the descriptor's own arguments and is
 * not part of that descriptor, and the handler's signature has to mirror that asymmetry.
 *
 * <h2>The Operation the handler receives</h2>
 *
 * <p>The {@link de.splatgames.aether.weaver.api.callback.Operation} chain is built once, at constant
 * resolution, rather than assembled anew at each call, so a nested chain costs nothing per call.
 * Calling
 * {@link de.splatgames.aether.weaver.api.callback.Operation#call(Object...)} performs the next level
 * in, with these properties:
 *
 * <ul>
 *   <li><b>The arguments are the operation's own inputs, in the same order and the same number.</b>
 *       Passing a different number throws {@link IllegalArgumentException} naming the operation, at
 *       run time; the count is not checked when the class is woven.
 *   <li><b>Arguments and result pass as {@link Object}.</b> A primitive is boxed on the way in and
 *       on the way out, and a {@code void} operation yields {@code null}, which is what an
 *       {@code Operation<Void>} promises.
 *   <li><b>The type argument is not checked.</b> Neither the annotation processor nor the engine
 *       compares it against the operation's result: both compare the erased
 *       {@link de.splatgames.aether.weaver.api.callback.Operation} type alone. Declaring the boxed
 *       result type is what makes the handler's own code correct — declaring something else compiles
 *       and weaves, and fails with a {@link ClassCastException} where the handler uses the value.
 *   <li><b>Anything the operation throws is rethrown as it stands</b>, unwrapped and undeclared, so
 *       a checked exception the target declared reaches the target's own handlers unchanged.
 * </ul>
 *
 * <h2>Instantiation is one operation, not three</h2>
 *
 * <p>A {@code new} in a class file is an allocation, a duplication and a constructor call. Wrapping
 * {@link Point#NEW} takes over all three: the allocation and its duplication stop existing, because
 * the handler produces the reference itself, and the constructor call becomes the call to the
 * handler. Everything between them stays, because that is the code computing the arguments the
 * handler is about to receive.
 *
 * <h2>Nesting, and which wrap ends up outermost</h2>
 *
 * <p>Several weaves may wrap one operation, and they nest rather than collide — unlike
 * {@link Redirect}, where a second declaration on one call site is reported as {@code AW1060}. A
 * redirect meeting a wrap on the same site is also {@code AW1060}: the redirect removes the very
 * operation the wrap would hold a handle to. That comparison is between declarations rather than
 * between resolved instructions — the target class, the {@code method} text, the point, its target
 * text, its ordinal and its slice — so two declarations differing in any of those are not reported
 * even where they turn out to match the same instruction.
 *
 * <p>Among wraps, the outermost is the one whose weave declares the highest
 * {@code @Weave(priority)}. Ties are broken by weave class name, then handler name, then handler
 * descriptor, so two builds of the same inputs produce the same nesting and a weave added later
 * cannot silently reorder the ones already there. Only the outermost declaration emits anything at
 * the site; the inner levels are bound into the
 * {@link de.splatgames.aether.weaver.api.callback.Operation} that outermost handler receives.
 *
 * <p>A handler therefore cannot assume it sees the target's own operation. Calling
 * {@link de.splatgames.aether.weaver.api.callback.Operation#call(Object...)} reaches the next level
 * in, which may be another weave's handler, and only the innermost call performs the operation
 * itself.
 *
 * <h2>How many matches are required</h2>
 *
 * <p>One declaration may match several instructions. {@link #require()} is the fewest that count as
 * success and {@link #allow()} the most; falling short is reported as {@code AW1043} and exceeding
 * is {@code AW1044}. Neither default reads the way it looks. An {@link #allow()} of {@code 0}
 * imposes no upper bound rather than forbidding every match. A {@link #require()} of {@code 0} is a
 * sentinel rather than a value: a class file records only the elements that were written, so an
 * omitted {@link #require()} becomes the injector's own default of one match, while a {@code 0}
 * written out requires none.
 *
 * <p>A declaration naming a {@link #group()} is accounted differently: its matches are added to the
 * group's total and neither its own {@link #require()} nor its {@link #allow()} is checked, so that
 * several declarations can answer for one another where any one of them alone would fail.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     // Ledger.charge calls: Receipt r = this.gateway.send(amount);
 *     // The operation is an instance call, so its inputs are the receiver and then the argument.
 *     @Wrap(method = "charge(java.math.BigDecimal)",
 *           at = @At(value = Point.INVOKE, target = "Gateway.send"),
 *           require = 1)
 *     private static Receipt aroundSend(Gateway gateway,
 *                                       BigDecimal amount,
 *                                       Operation<Receipt> op) {
 *         if (amount.signum() <= 0) {
 *             return Receipt.rejected();          // the operation is never performed
 *         }
 *         // The next level in, which is not necessarily the target's own call.
 *         return op.call(gateway, amount);
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Redirect
 * @see Inject
 * @see Point
 * @see de.splatgames.aether.weaver.api.callback.Operation
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Wrap {

    /**
     * The method to weave into, written as a selector.
     *
     * <p>This names the method that performs the operation, not the operation itself, which is
     * {@link At#target()}. A selector that resolves to no method is reported as {@code AW1020} and
     * one that resolves to more than one as {@code AW1021}; naming the parameter types
     * disambiguates an overload, and the {@code desc:} form pins one exactly. An inherited method is
     * not a declared one, so a method the target only inherits has to be wrapped where it is
     * declared.
     *
     * @return the target method selector
     */
    String method();

    /**
     * The operation to wrap.
     *
     * <p>One point, not an array: a handler's signature describes one operation, and two points
     * naming operations of different shapes could not both fit it. Only {@link Point#INVOKE},
     * {@link Point#FIELD} and {@link Point#NEW} name an operation; anything else is
     * {@code AW1061}, and a {@link At#shift()} is {@code AW1102}.
     *
     * @return the injection point
     */
    At at();

    /**
     * Narrows the search to one or more regions of the target rather than its whole body.
     *
     * <p>The point selects the slice it searches by {@link At#slice()}, matched against
     * {@link Slice#id()}; a point that names none searches the slice declared without an id, and a
     * reference matching no slice searches the whole method without reporting anything. An ordinal
     * counts within the narrowed region, not within the method, so adding a slice to an otherwise
     * unchanged declaration can change which instruction it matches.
     *
     * @return the slices to search, or an empty array to search the whole method
     */
    Slice[] slice() default {};

    /**
     * Distinguishes this declaration from others in diagnostics.
     *
     * <p>Left empty, an identifier is derived from the handler and the kind of injection: the weave
     * class, the handler's name and descriptor, and the suffix {@code #wrap}. The identifier is
     * what an accounting diagnostic prints to say which declaration it is about, so a readable one
     * is worth writing where several declarations share a handler's shape.
     *
     * @return the identifier, or an empty string to let one be derived
     */
    String id() default "";

    /**
     * The fewest matches that count as success.
     *
     * <p>An omitted {@code require} is not the same as {@code require = 0}. Written nowhere, the
     * engine reads it as one, so a declaration that matches nothing is an error; written as
     * {@code 0}, the declaration is deliberately optional and matching nothing is accepted. The
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
     * <p>Unlike {@link #require()} this has no sentinel behaviour: written or omitted, {@code 0}
     * means the same thing.
     *
     * <p>Not checked when {@link #group()} is set.
     *
     * @return the maximum number of matches; {@code 0} imposes no upper bound rather than
     *         permitting none
     */
    int allow() default 0;

    /**
     * Accounts this declaration's matches against a named {@link Group} rather than on its own, so
     * that several declarations can answer for one another.
     *
     * <p>Matched literally against the name a {@link Group} on the weave class declares, but nothing
     * enforces that a {@link Group} by this name exists. A grouped declaration's own
     * {@link #require()} and {@link #allow()} are never checked, and a name matching no declared
     * {@link Group} is not accounted anywhere either: its matches are added to a total that no
     * {@link Group} bound checks, so a misspelt name leaves the declaration checked by nothing rather
     * than refused. Because a grouped declaration is normally written with {@code require = 0}, the
     * mistake produces no diagnostic at all.
     *
     * @return the group name, or an empty string to be accounted alone
     */
    String group() default "";
}
