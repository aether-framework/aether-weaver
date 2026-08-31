package de.splatgames.aether.weaver.api;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Calls a handler at matched positions inside a target method, leaving the target's own code
 * in place.
 *
 * <p>An injection adds a call and nothing else: the instruction it attached to still runs,
 * the target's control flow is unchanged, and the handler influences the target only through
 * what it is handed. Where {@link Redirect} substitutes an operation and {@link Wrap}
 * surrounds one, an injection stands beside it.
 *
 * <p>Two things a handler can do beyond observing. It can end the target method early by
 * taking a {@link de.splatgames.aether.weaver.api.callback.Callback} — or a
 * {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback}, which also supplies
 * the value the target then returns — and it can read and write the target's local variables
 * through {@link Local}.
 *
 * <h2>The handler's shape</h2>
 *
 * <p>A handler is a method of the weave class satisfying all of the following. Most of these
 * are checked by the annotation processor at compile time and again by the engine before any
 * bytes are written, so a violation fails the build rather than surfacing at run time; where
 * only one side checks a rule, the bullet below says which.
 *
 * <ul>
 *   <li><b>It returns {@code void}.</b> Reported as {@code AW1041} otherwise. The injected
 *       call is a statement in the middle of the target's own code, so a returned value would
 *       have nowhere to go; to change what the target returns, take a
 *       {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback} and cancel with a
 *       value.
 *   <li><b>Its parameters are a prefix of the target method's own arguments</b>, in
 *       declaration order and matched by erased type. Reported as {@code AW1040}. The
 *       injected call pushes the target's arguments in order, so a handler may take the first
 *       n of them and nothing else; a parameter has no identity in a compiled method beyond
 *       its position, so there is nothing to name a subset with. A value that is a local
 *       rather than an argument is captured with {@link Local}.
 *   <li><b>A callback, if any, follows that prefix</b>, and the captures follow the callback.
 *       The full order is: target arguments, then one
 *       {@link de.splatgames.aether.weaver.api.callback.Callback} or
 *       {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback}, then every
 *       {@link Local}. A capture outside the trailing run is reported as {@code AW1040}, but
 *       only by the engine: the annotation processor's own parameter check skips every
 *       {@code @Local} parameter regardless of where it sits, so a capture out of place is not
 *       caught until the engine runs.
 *   <li><b>The callback matches what the target returns.</b> A
 *       {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback} on a {@code void}
 *       method, or one whose type argument is not the target's boxed return type, is
 *       {@code AW1071} on both sides. A plain
 *       {@link de.splatgames.aether.weaver.api.callback.Callback} on a method that returns a
 *       value is {@code AW1070} — cancelling it would leave the method with nothing to return —
 *       but that half of the rule is checked by the engine alone; the annotation processor does
 *       not report it.
 *   <li><b>It is reachable from the woven class.</b> An instance handler is callable only
 *       once it is a method of the class calling it, which happens when the weave is an
 *       instance weave and is dissolved into its target. A handler that is not static and
 *       whose weave does not dissolve into the target is reported as {@code AW1005}, as is an
 *       instance handler bound to a {@code static} target method, which has no receiver to be
 *       called on. For a {@code @Weave(kind = Kind.STATIC)} weave the call is an ordinary
 *       cross-class invocation, so the handler and its class must be public or share the
 *       target's package, and a private handler is never reachable: {@code AW1042}, checked by
 *       the annotation processor only — there is no corresponding check in the engine, so this
 *       one surfaces as an {@code IllegalAccessError} at the injected call's first execution
 *       under a build that skips the processor.
 * </ul>
 *
 * <h2>Where the handler is called</h2>
 *
 * <p>{@link #at()} is an array, and every {@link At} in it is resolved independently against
 * the same target method. The positions they find are pooled, so one declaration with two
 * points that match three positions between them is woven three times and counts as three
 * matches for {@link #require()} and {@link #allow()}. An empty array is reported as
 * {@code AW1043}.
 *
 * <p>{@link Point} states, per constant, what is matched and what is not. Positions nothing
 * may be woven at are refused rather than emitted: before a constructor's own
 * {@code super(...)} call for an instance handler ({@code AW1026}), between a {@code new} and
 * its constructor call ({@code AW1105}), and code nothing can reach ({@code AW1130}).
 *
 * <h2>What the target method may be</h2>
 *
 * <p>{@link #method()} must resolve to exactly one method that has a body. A selector that
 * finds none is {@code AW1020} and one that finds several is {@code AW1021} — including a
 * {@code *} wildcard that matches several, at weave time: the engine's own resolution does not
 * distinguish a wildcard from an ordinary name and never consults {@link #allow()}. The
 * annotation processor does make that distinction at compile time: there, a wildcard matching
 * several methods is {@code AW1022} rather than {@code AW1021}, and only when {@link #allow()}
 * is left at its default. Setting {@link #allow()} silences the processor's {@code AW1022} but
 * has no effect on the engine's later {@code AW1021}, so a {@code *} selector that resolves to
 * more than one method still fails the build regardless of {@link #allow()}. A method with no
 * body cannot be injected into: a
 * {@code native} one is {@code AW1025}, an {@code abstract} one is {@code AW1023}, and a
 * compiler-generated or bridge method is {@code AW1024} — the injection would work, but it
 * would not survive a recompilation that changes the generated shape.
 *
 * <h2>Interactions</h2>
 *
 * <p>Several injections may attach to one position. They are emitted in the plan's order, which
 * sorts by the weave's {@code @Weave(priority)} descending, then weave class name, then handler
 * name, then handler descriptor. These four do not always distinguish two declarations — two
 * {@link Inject} annotations on the same handler method, as in the example below, compare equal
 * by all four — so the sort is a preorder rather than a total one. The build is still
 * deterministic between runs because the sort used to apply it is stable, so declarations that
 * compare equal keep the relative order they were read in; the four fields above are what
 * decides the order between declarations that differ, not what breaks every tie. The
 * highest-priority handler's call comes first, and a weave added later cannot silently reorder
 * declarations that already have a lower priority than it.
 *
 * <p>An injection may share a position with a {@link Redirect} or a {@link Wrap} that
 * replaces the operation there. Both still apply: the handler's call is emitted and the
 * operation is gone, which is what each of them asked for.
 *
 * <p>Injected code that lands inside one of the target's own {@code try} ranges splits that
 * range around the call, so the target's {@code catch} blocks no longer cover the handler.
 * This is reported as {@code AW1131}, an informational message: nothing needs doing, but a
 * handler that throws is not caught by code written for the target's own failures.
 *
 * <p>Attaching to two constructors of one target that call one another is reported as
 * {@code AW1027}, a warning. A single {@code new} runs every constructor in the chain, so the
 * handler is called once for each — right for a handler that observes, wrong for one that
 * counts.
 *
 * <p>Enough injected calls can push a method past the class file format's limit of 65535
 * bytes of code, which is reported as {@code AW4003}. The handler's own body costs nothing
 * there; only the call does.
 *
 * <h2>How many matches are required</h2>
 *
 * <p>One declaration may match several positions. {@link #require()} is the fewest that count
 * as success and {@link #allow()} the most; falling short is {@code AW1043} and exceeding is
 * {@code AW1044}. Both are checked after every point of the declaration has been resolved,
 * against the total.
 *
 * <p>A declaration naming a {@link #group()} is accounted through the group instead: its
 * matches are added to the group's total and neither its own {@link #require()} nor its
 * {@link #allow()} is checked.
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
 *             callback.cancel(Receipt.rejected());   // charge() returns without running
 *         }
 *     }
 *
 *     @Inject(method = "charge(java.math.BigDecimal)", at = @At(Point.RETURN))
 *     @Inject(method = "refund(java.math.BigDecimal)", at = @At(Point.RETURN))
 *     private void onEitherExit() {
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see At
 * @see Point
 * @see Local
 * @see Redirect
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Inject.Container.class)
public @interface Inject {

    /**
     * The method to weave into, written as a selector.
     *
     * <p>A selector that resolves to no method is reported as {@code AW1020} and one that
     * resolves to more than one as {@code AW1021}; naming the parameter types disambiguates
     * an overload, and the {@code desc:} form pins one exactly. An inherited method is not a
     * declared one, so a method the target only inherits has to be woven where it is
     * declared.
     *
     * @return the target method selector
     */
    String method();

    /**
     * Where inside the target the handler is called.
     *
     * <p>Each entry is resolved on its own and their matches are pooled. An empty array is
     * reported as {@code AW1043}: a declaration with no point attaches to nothing.
     *
     * @return the injection points, at least one
     */
    At[] at();

    /**
     * Narrows the search to one or more regions of the target rather than its whole body.
     *
     * <p>A point selects the slice it searches by {@link At#slice()}, matched against
     * {@link Slice#id()}; a point that names none searches the slice declared without an id,
     * and a reference matching no slice searches the whole method. An ordinal in an
     * {@link At} counts within the narrowed region, not within the method, so adding a slice
     * to an otherwise unchanged declaration can change which instruction it matches.
     *
     * @return the slices to search, or an empty array to search the whole method
     */
    Slice[] slice() default {};

    /**
     * Distinguishes this declaration from others in diagnostics.
     *
     * <p>Left empty, an identifier is derived from the handler and the kind of injection:
     * the weave class, the handler's name and descriptor, and the suffix {@code #inject}.
     *
     * @return the identifier, or an empty string to let one be derived
     */
    String id() default "";

    /**
     * The fewest matches that count as success.
     *
     * <p>An omitted {@code require} is not the same as {@code require = 0}. Written nowhere,
     * the engine reads it as one, so a declaration that matches nothing is an error; written
     * as {@code 0}, the declaration is deliberately optional and matching nothing is
     * accepted. The class file records only the elements that were written, which is what
     * makes the two distinguishable.
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
     * <p>The name must be one a {@link Group} on the weave class declares. A grouped
     * declaration's own {@link #require()} and {@link #allow()} are not checked at all, which
     * is why one is normally written with {@code require = 0}.
     *
     * @return the group name, or an empty string to be accounted alone
     */
    String group() default "";

    /**
     * Holds the repetitions of {@link Inject} on one handler method.
     *
     * <p>Written by the compiler when a method carries more than one {@link Inject}, and read
     * by the engine, which flattens it back into the individual declarations so that a
     * handler with one and a handler with three are modelled the same way. There is no reason
     * to write it by hand.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @ApiStatus.Internal
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Container {

        /**
         * The injections the handler declares, in declaration order.
         *
         * @return the repeated annotations
         */
        Inject[] value();
    }
}
