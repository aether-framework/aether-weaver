package de.splatgames.aether.weaver.api;

/**
 * The built-in injection points, each naming a kind of position inside a target method.
 *
 * <p>A point is chosen with {@link At#value()} and answers one question: which instructions
 * of the target method a declaration attaches to. It does not say what happens there — that
 * is {@link Inject}, {@link Redirect} or {@link Wrap} — and it does not say which method is
 * searched, which is the declaration's own {@code method} selector.
 *
 * <h2>Positions and operations</h2>
 *
 * <p>The constants fall into two groups, and the group decides which annotations may name
 * them.
 *
 * <ul>
 *   <li><b>Operations</b> — {@link #INVOKE}, {@link #FIELD} and {@link #NEW}. Each names
 *       something the target <em>does</em>: a call, a field access, an instantiation. These
 *       three, and only these three, may be named by {@link Redirect} and {@link Wrap},
 *       which stand in for the operation. {@link Inject} may name them as well, and then
 *       adds code beside the operation rather than replacing it.
 *   <li><b>Positions</b> — {@link #HEAD}, {@link #RETURN}, {@link #TAIL},
 *       {@link #INVOKE_AFTER}, {@link #CONSTANT} and {@link #THROW}. Each names a place in
 *       the instruction sequence with no operation of its own to take over, so only
 *       {@link Inject} may use them. A {@link Redirect} or {@link Wrap} naming one is
 *       reported as {@code AW1061} — by the annotation processor at compile time and again
 *       by the engine before any bytes are written — and the fix is to use {@link Inject},
 *       or to point at the call, field access or instantiation that was meant.
 * </ul>
 *
 * <h2>What each point requires of {@link At#target()}</h2>
 *
 * <p>Three answers, and giving the wrong one is reported as {@code AW1043}.
 *
 * <ul>
 *   <li><b>Required</b>: {@link #INVOKE}, {@link #INVOKE_AFTER}, {@link #FIELD},
 *       {@link #NEW}. A declaration without a target is refused rather than matching
 *       everything.
 *   <li><b>Forbidden</b>: {@link #HEAD}, {@link #RETURN}, {@link #TAIL}. These locate a
 *       position and have nothing to match against, so a target given alongside them is
 *       refused.
 *   <li><b>Optional</b>: {@link #CONSTANT}, {@link #THROW}.
 * </ul>
 *
 * <h2>How a point becomes a set of sites</h2>
 *
 * <p>Resolution runs in a fixed order, and each step sees the output of the one before it:
 * the slice named by {@link At#slice()} narrows the search window, the point finds every
 * position of its kind inside that window, {@link At#ordinal()} selects one of them counted
 * within the window, {@link At#shift()} moves the selection, and finally positions that
 * cannot be woven are refused. The last step is where {@code AW1026},
 * {@code AW1105} and {@code AW1130} come from; they are described on the constants that can
 * resolve to such a place.
 *
 * <h2>Shifting</h2>
 *
 * <p>Every constant except {@link #HEAD} accepts {@link At#shift()}. {@link #HEAD} refuses
 * it with {@code AW1102}, and so does any point named by a {@link Redirect} or a
 * {@link Wrap}, whatever the constant.
 *
 * <h2>Points that are not on this enum</h2>
 *
 * <p>A plugin may contribute further points, which are named as text through
 * {@link At#custom()} rather than by a constant and always carry a {@code namespace:NAME}
 * spelling. A name that no registered point answers to is reported as {@code AW1101}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Gateway.class)
 * public final class GatewayAudit {
 *
 *     // Before the target's own code, once per call.
 *     @Inject(method = "send(Payment)", at = @At(Point.HEAD))
 *     private void onEntry() {
 *     }
 *
 *     // Before the second call to Socket.write in the same method.
 *     @Inject(method = "send(Payment)",
 *             at = @At(value = Point.INVOKE, target = "Socket.write", ordinal = 1))
 *     private void beforeSecondWrite() {
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see At
 * @see Inject
 * @see Redirect
 */
public enum Point {

    /**
     * The start of the target method's body.
     *
     * <p>Resolves to exactly one position, which is the method's first instruction — except
     * in a constructor, where it is the position immediately after the constructor's own
     * {@code super(...)} or {@code this(...)} call. That call is found by scanning for the
     * first constructor invocation that does not belong to an instantiation inside the
     * argument list, so a constructor whose arguments themselves construct objects still
     * gets the position the reader expects. Injecting before that call is what
     * {@code AW1026} refuses for an instance handler, because {@code this} does not exist
     * yet; putting {@link #HEAD} after it is why the constant needs no such diagnostic.
     *
     * <p>{@link At#target()} is forbidden here and {@link At#shift()} is refused with
     * {@code AW1102}: a position one instruction either side of the method entry is not a
     * thing this point can promise.
     *
     * <p>Usable by {@link Inject} only. {@link Redirect} and {@link Wrap} report
     * {@code AW1061}.
     */
    HEAD,

    /**
     * Every point at which the target method returns.
     *
     * <p>Matches each return instruction in the body, whatever its type, and the injected
     * code is emitted immediately before it. A method with three {@code return} statements
     * therefore produces three sites, and {@link At#ordinal()} picks one of them; a method
     * that only throws produces none, which the declaration's own {@code require} then
     * reports as {@code AW1043}.
     *
     * <p>An exceptional exit is not a return, so an {@code athrow} is not matched here;
     * {@link #THROW} is what names those.
     *
     * <p>This constant and {@link #TAIL} are the two positions at which the target's return
     * value exists. A handler that calls
     * {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback#value()} at any
     * other point is reported as {@code AW1072}, because the value it would read has not
     * been computed.
     *
     * <p>{@link At#target()} is forbidden here. Usable by {@link Inject} only;
     * {@link Redirect} and {@link Wrap} report {@code AW1061}.
     */
    RETURN,

    /**
     * The last point at which the target method returns.
     *
     * <p>Finds the same instructions as {@link #RETURN} and keeps only the last of them in
     * body order, so it resolves to at most one position. A method that never returns
     * normally produces no site at all, which the declaration's {@code require} reports as
     * {@code AW1043}.
     *
     * <p>The last return in body order is not necessarily the last one executed, and it is
     * not the same thing as "after everything else has run": a method whose final statement
     * is a loop may compile with its return in the middle of the body.
     *
     * <p>Like {@link #RETURN}, this is a position at which the target's return value exists,
     * so {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback#value()} may be
     * read here.
     *
     * <p>{@link At#target()} is forbidden here. Usable by {@link Inject} only;
     * {@link Redirect} and {@link Wrap} report {@code AW1061}.
     */
    TAIL,

    /**
     * A method call the target makes, matched immediately before the call happens.
     *
     * <p>Matches the four ordinary call instructions — virtual, interface, static and
     * special — that satisfy {@link At#target()}, which is required and is parsed as a
     * method selector. The injected code runs with the call's receiver and arguments already
     * on the stack and the call not yet performed.
     *
     * <p>An {@code invokedynamic} is not an ordinary call and is never matched. A lambda, a
     * method reference and string concatenation all compile to one, so the method behind
     * them is invoked by the JVM rather than by the target and has to be woven where it is
     * declared. Where the target text would have named something reached through such an
     * instruction, {@code AW1103} says so and lists what was skipped; it is informational,
     * and the ordinary calls that did match are still woven.
     *
     * <p>When nothing matches, the diagnostic lists the calls found in the region a
     * {@link At#slice()} narrowed the search to, or the whole method when there is none, up to
     * ten of them with the rest summarised as a count; this is usually enough to see the
     * spelling that was meant.
     *
     * <p>Usable by {@link Inject}, {@link Redirect} and {@link Wrap}. A redirect or a wrap
     * takes the call over; an inject adds code in front of it and the call still happens.
     */
    INVOKE,

    /**
     * A method call the target makes, matched immediately after the call has happened.
     *
     * <p>Finds exactly the same calls as {@link #INVOKE} and resolves one position later, so
     * the injected code runs with the call's result on the stack. At that position a handler
     * may take the result by annotating its first parameter with
     * {@link de.splatgames.aether.weaver.api.Result}; a call that returns {@code void} has
     * nothing to hand over and is reported as {@code AW1104}. {@link #INVOKE} with
     * {@link At#shift()} set to {@link At.Shift#AFTER} resolves to the same position and works
     * the same way for this purpose — the engine does not tell the two apart once resolution is
     * done — but {@link #INVOKE_AFTER} is the direct way to ask for it.
     *
     * <p>{@link At#target()} is required and is parsed as a method selector, exactly as for
     * {@link #INVOKE}.
     *
     * <p>Usable by {@link Inject} only. There is no operation at the position after a call
     * for a handler to stand in for, so {@link Redirect} and {@link Wrap} report
     * {@code AW1061} — the same code they report for a bare position, and the remedy is to
     * point them at {@link #INVOKE} instead.
     */
    INVOKE_AFTER,

    /**
     * A field the target reads or writes, matched immediately before the access.
     *
     * <p>Matches instance and static reads and writes alike, narrowed by
     * {@link At#access()}: {@link At.Access#GET} and {@link At.Access#PUT} name the instance
     * forms, {@link At.Access#STATIC_GET} and {@link At.Access#STATIC_PUT} the static ones,
     * and {@link At.Access#ANY} — the default — accepts all four. {@link At#target()} is
     * required and is parsed as a field selector, so it may name the owner and the field's
     * type as well as its name.
     *
     * <p>Where nothing matches, the diagnostic lists the field accesses found in the region a
     * {@link At#slice()} narrowed the search to, or the whole method when there is none, up to
     * ten of them with the access kind of each and the rest summarised as a count. This
     * distinguishes the two mistakes that look alike: a misspelled field, and a field that is
     * accessed the other way round from what {@link At#access()} asked for.
     *
     * <p>Usable by {@link Inject}, {@link Redirect} and {@link Wrap}. A redirect replaces
     * the read or the write; an inject adds code in front of it and the access still
     * happens.
     */
    FIELD,

    /**
     * An object the target creates, matched at the {@code new} instruction.
     *
     * <p>Matches the instruction that allocates an object, not the constructor call that
     * follows it, and not an array creation. {@link At#target()} is required and names the
     * created class. It is compared as text rather than parsed as a member selector: the
     * created type's binary name must equal the target, or end with a dot followed by it,
     * so both {@code com.acme.Receipt} and {@code Receipt} name the same class.
     *
     * <p>For {@link Redirect} and {@link Wrap} this point stands for the whole
     * instantiation: the allocation, the duplication that follows it and the constructor
     * call are one operation, the handler produces the object itself, and the code that
     * computes the constructor arguments stays where it is.
     *
     * <p>{@link Inject} attaches in front of the allocation. Nothing may be injected between
     * a {@code new} and its constructor call, because the stack there holds a reference to
     * an object that does not exist yet and the JVM refuses code that touches it; a shift or
     * an {@link At#ordinal()} that lands there is reported as {@code AW1105}.
     */
    NEW,

    /**
     * A constant the target loads, matched immediately before the load.
     *
     * <p>Matches every instruction that pushes a constant: the intrinsic forms such as
     * {@code iconst_1} and {@code aconst_null}, the immediate forms {@code bipush} and
     * {@code sipush}, and {@code ldc} in all of its widths.
     *
     * <p>{@link At#target()} is optional. Omitted, every constant in the search window
     * matches. Written, it is understood in one of two ways.
     *
     * <ul>
     *   <li>A constant of the selector grammar — {@code int:42}, {@code long:42},
     *       {@code float:1.5}, {@code double:1.5}, {@code class:com.acme.Receipt},
     *       {@code string:"total"} or the bare word {@code null} — is compared to the
     *       constant's value. A {@code string:} form only counts as one when its value is
     *       quoted, because {@code string} is also a legal field name.
     *   <li>Anything else is compared as text against the constant's printed value, with
     *       everything up to and including the first colon removed. This is what makes
     *       {@code string:hello} without quotes still match the string {@code hello}.
     * </ul>
     *
     * <p>Usable by {@link Inject} only. A constant is a value rather than an operation, so
     * {@link Redirect} and {@link Wrap} report {@code AW1061}.
     */
    CONSTANT,

    /**
     * A throw the target performs, matched immediately before the exception is thrown.
     *
     * <p>Matches every {@code athrow} in the search window, in body order. The exception
     * itself is on the stack at that position, having already been constructed.
     *
     * <p>{@link At#target()} is accepted here and is not consulted: this point matches every
     * throw whether or not one is written, so narrowing to a particular exception type is
     * done with {@link At#ordinal()} or with a slice rather than with a target. A
     * declaration that names one and expects it to filter matches more sites than it
     * intended, and the extra ones are reported as {@code AW1044} only if
     * {@link Inject#allow()} was set.
     *
     * <p>Usable by {@link Inject} only. {@link Redirect} and {@link Wrap} report
     * {@code AW1061}.
     */
    THROW
}
