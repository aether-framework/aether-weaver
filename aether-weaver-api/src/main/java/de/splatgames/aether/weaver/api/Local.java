package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hands a handler parameter the value of a local variable of the target method.
 *
 * <p>A handler's ordinary parameters are a prefix of the target method's own arguments. A
 * local variable is not an argument and has no position to take, so a parameter that wants
 * one says which one with {@code @Local}. The engine resolves the request at the exact
 * position the declaration matched — against the target method's
 * {@code LocalVariableTable}, unless the slot is named outright — and emits a load of that
 * slot into the call.
 *
 * <p>Captures are supplied to an {@link Inject} handler and to a {@link Redirect} handler.
 * Only the {@link Inject} path writes a mutable capture back into the target's slot after the
 * handler returns.
 *
 * <h2>Where the annotated parameters go</h2>
 *
 * <p>A handler's parameter list is, in order: the prefix of the target's own arguments, then
 * an optional {@link de.splatgames.aether.weaver.api.callback.Callback} or
 * {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback}, then every captured
 * parameter. A {@code @Local} anywhere but in that trailing run is reported as
 * {@code AW1040}. The order is the order the values are pushed in, and getting it wrong is
 * only sometimes a verification error — two captures of the same type can be transposed
 * without the verifier noticing — which is why the rule is checked rather than left to the
 * class file.
 *
 * <h2>Which variable is meant</h2>
 *
 * <p>Four strategies, chosen by which elements are set. They are tried in this order and the
 * first that applies wins, so setting two of them silently ignores the later one.
 *
 * <ol>
 *   <li><b>{@link #index()}</b>, when it is not {@code -1}: the local variable slot, exactly
 *       as the compiler assigned it. This is the only strategy that needs no debug
 *       information.
 *   <li><b>{@link #name()}</b>, when it is not empty: the variable of that name that is live
 *       at the matched position.
 *   <li><b>{@link #ordinal()}</b>, when it is not {@code -1}: the n-th live variable of the
 *       parameter's type, counted in slot order from zero.
 *   <li><b>Neither</b>: the one live variable of the parameter's type. Exactly one must be
 *       live, or the request is refused rather than guessed at.
 * </ol>
 *
 * <p>Everything but {@link #index()} reads the target's {@code LocalVariableTable}, which
 * only exists in a class file compiled with debug information. Without it the request is
 * reported as {@code AW1052}, and the remedies are to recompile the target with {@code -g} or
 * to name the slot with {@link #index()} having read the bytecode. The engine will not infer
 * a slot from the method's shape, because a wrong slot reads a different value rather than
 * failing.
 *
 * <p>Resolution happens once per matched position, not once per declaration. One declaration
 * that matches two positions may read two different slots, and each is resolved against what
 * is live there.
 *
 * <h2>What goes wrong, and what it is called</h2>
 *
 * <ul>
 *   <li>{@code AW1050} — the variable is not there: a name that is not live at this position,
 *       an ordinal beyond the number of live variables of that type, no live variable of the
 *       parameter's type at all, or a slot that holds something else. The message lists the
 *       variables that <em>are</em> live at that position with their types.
 *   <li>{@code AW1051} — resolution by type found more than one candidate. Say which with
 *       {@link #name()} or {@link #ordinal()}.
 *   <li>{@code AW1052} — the target carries no {@code LocalVariableTable}.
 *   <li>{@code AW1053} — {@link #mutable()} is set on a parameter that is not a carrier.
 *   <li>{@code AW1054} — the parameter is a carrier and {@link #mutable()} is not set.
 * </ul>
 *
 * <p>How closely a variable's type must match the parameter's depends on which strategy found
 * it. {@link #name()} and {@link #index()} accept any reference for a reference parameter,
 * exactly like the write-back rule described below for a carrier. {@link #ordinal()} and
 * resolution by type alone are stricter: both draw their candidates from the variables whose
 * type equals the parameter's type exactly, so a {@code CharSequence} parameter resolved this
 * way will not find a variable declared {@code String} — it is reported as {@code AW1050} as
 * though no candidate existed. A primitive parameter matches only the same primitive under
 * every strategy. Declaring the parameter with the variable's own type is what keeps the value
 * usable once it arrives, and is the only choice that works the same way regardless of which
 * strategy resolves it.
 *
 * <h2>Writing the variable back</h2>
 *
 * <p>A Java parameter is passed by value, so a handler that assigns to its own parameter
 * changes its own copy and leaves the target holding the old value. To write a local, declare
 * {@code mutable = true} and give the parameter one of the carrier types:
 * {@link de.splatgames.aether.weaver.api.callback.LocalRef} for a reference and
 * {@link de.splatgames.aether.weaver.api.callback.LocalIntRef} and its siblings for the eight
 * primitives. The engine constructs the carrier around the variable's current value, passes
 * it, and after the handler returns reads it back into the target's own slot — including on
 * the path where the handler cancels through its callback.
 *
 * <p>A carrier is matched by what it holds rather than by its own type: a
 * {@link de.splatgames.aether.weaver.api.callback.LocalIntRef} parameter resolves against an
 * {@code int} variable. The generic
 * {@link de.splatgames.aether.weaver.api.callback.LocalRef} erases to {@code Object}, so
 * resolution by type or by ordinal looks for a variable declared exactly as {@code Object};
 * a reference variable of any other type is captured by {@link #name()} or by
 * {@link #index()}. On the way back the value is cast to the variable's real type, so a
 * handler that stores the wrong one fails with a {@code ClassCastException} at the write-back
 * rather than somewhere later in the target's own code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class LedgerAudit {
 *
 *     // int total is a local of charge(BigDecimal); the handler reads the amount argument,
 *     // may cancel, and adds to the total.
 *     @Inject(method = "charge(java.math.BigDecimal)",
 *             at = @At(value = Point.INVOKE, target = "#post"),
 *             require = 1)
 *     private void beforePost(BigDecimal amount,
 *                             ReturnableCallback<Receipt> callback,
 *                             @Local(name = "total", mutable = true) LocalIntRef total) {
 *         total.set(total.get() + amount.intValue());
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Inject
 * @see Redirect
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Local {

    /**
     * The name of the variable to capture.
     *
     * <p>Ignored when {@link #index()} is set. The variable must be live at the position the
     * declaration matched: one declared later in the method, or one whose scope has already
     * ended, does not match even though the table still lists it. Requires the target to have
     * been compiled with debug information.
     *
     * @return the variable's name, or an empty string to resolve by ordinal or by type
     */
    String name() default "";

    /**
     * The local variable slot to read.
     *
     * <p>Takes precedence over every other element. This is the escape hatch for a target
     * without debug information: the slot is used as given, and the one check made is against
     * the table where there is one, which reports {@code AW1050} when the slot holds a value
     * of a different shape at that position. Slots are assigned by the compiler and are
     * reused once a scope ends, so a slot that is right at one position is not necessarily
     * right at another.
     *
     * @return the zero-based slot, or {@code -1} to resolve by name, ordinal or type
     */
    int index() default -1;

    /**
     * Which variable of the parameter's type to capture, counted from zero.
     *
     * <p>Read only when {@link #index()} is {@code -1} and {@link #name()} is empty. The
     * count runs over the variables of the parameter's type that are live at the matched
     * position, in slot order — which is neither declaration order in the source nor the
     * order the debug table lists them in. Requires the target to have been compiled with
     * debug information.
     *
     * @return the zero-based index among the live variables of that type, or {@code -1} to
     *         resolve by type alone
     */
    int ordinal() default -1;

    /**
     * Whether the handler writes the variable rather than only reading it.
     *
     * <p>Set this and the parameter must be one of the carrier types, or the declaration is
     * reported as {@code AW1053}. Leave it unset and the parameter must not be a carrier, or
     * it is reported as {@code AW1054}: a carrier the handler may not write to is a handle
     * nobody should be holding, and the pair of codes exists so that neither half of the
     * intent can be written on its own.
     *
     * @return whether the captured variable is written back after the handler returns
     */
    boolean mutable() default false;
}
