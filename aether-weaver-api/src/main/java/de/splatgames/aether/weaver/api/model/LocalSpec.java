package de.splatgames.aether.weaver.api.model;

import org.jetbrains.annotations.Contract;

import java.util.Objects;

/**
 * One handler parameter bound to a local variable of the target method rather than to an argument
 * of the matched operation.
 *
 * <p>This is the parsed form of {@link de.splatgames.aether.weaver.api.Local}, read from the weave
 * class's runtime-visible parameter annotations. One instance exists per annotated parameter;
 * parameters carrying no {@code @Local} produce none, so {@link InjectorSpec#locals()} is usually
 * shorter than the handler's parameter list and is not indexed by position — {@link #parameter()}
 * carries the position instead.
 *
 * <h2>Which variable is meant</h2>
 *
 * <p>Three of the four components are alternative ways of saying which variable, and they are not
 * combined. {@link #strategy()} picks exactly one of them, in a fixed order of precedence, and the
 * losers are ignored without a diagnostic:
 *
 * <ol>
 *   <li>{@link #index()} of {@code 0} or more selects {@link Strategy#BY_SLOT}. A slot is named
 *       outright and nothing else is consulted, not even a {@link #name()} written beside it.
 *   <li>Otherwise a non-empty {@link #name()} selects {@link Strategy#BY_NAME}.
 *   <li>Otherwise an {@link #ordinal()} of {@code 0} or more selects {@link Strategy#BY_ORDINAL}.
 *   <li>Otherwise {@link Strategy#BY_TYPE}, which is what an unadorned {@code @Local} means.
 * </ol>
 *
 * <p>The two sentinels are therefore not interchangeable with the values they neighbour.
 * {@link #index()} and {@link #ordinal()} are {@code -1} when unspecified, and {@code 0} is a real
 * slot and a real ordinal. {@link #name()} is the empty string when unspecified; a variable cannot
 * be named the empty string, so there is no ambiguity there.
 *
 * <h2>What each strategy needs from the target</h2>
 *
 * <p>{@link Strategy#BY_SLOT} works on any target. It reads the slot at the injection point, and
 * checks the declared type only when a local variable table is present to check against — a slot
 * holding an incompatible type is reported as {@code AW1050}.
 *
 * <p>The other three resolve against the target's {@code LocalVariableTable}, and a target compiled
 * without one is reported as {@code AW1052}. Recompile the target with {@code -g}, or read its
 * bytecode and capture by {@link #index()}; a slot is never inferred from the method's shape,
 * because a wrong slot reads a different value rather than failing.
 *
 * <p>Every strategy considers only variables live at the injection point, not every variable the
 * method declares. A variable declared later, or one whose scope has already closed, does not
 * match. {@link Strategy#BY_NAME} reports {@code AW1050} when no live variable has that name.
 * {@link Strategy#BY_ORDINAL} counts the live variables of the parameter's type in slot order from
 * zero and reports {@code AW1050} when the ordinal is past the end. {@link Strategy#BY_TYPE}
 * reports {@code AW1050} when none is live and {@code AW1051} when more than one is — two
 * candidates and a coin flip is not resolution, so say which with a name or an ordinal.
 *
 * <h2>Writing back</h2>
 *
 * <p>{@link #mutable()} says the handler intends to assign to the variable, and it changes what the
 * parameter's declared type must be. A Java parameter is passed by value, so the handler must
 * receive a carrier: {@link de.splatgames.aether.weaver.api.callback.LocalRef} for a reference type
 * and one of {@link de.splatgames.aether.weaver.api.callback.LocalIntRef} and its siblings for a
 * primitive. The two halves are checked against each other and disagreeing either way is refused:
 *
 * <ul>
 *   <li>{@code mutable = true} on a parameter that is not a carrier is {@code AW1053}. Declare the
 *       parameter as the carrier type.
 *   <li>A carrier parameter without {@code mutable = true} is {@code AW1054}. Add
 *       {@code mutable = true} if the handler means to write, or declare the parameter as the
 *       variable's own type if it only reads.
 * </ul>
 *
 * <p>When the parameter is a carrier, it is what the carrier <em>holds</em> that is matched against
 * the target's variable, not the carrier type itself: a {@code LocalIntRef} parameter resolves as
 * an {@code int}. That holds only for the primitive carriers. A
 * {@link de.splatgames.aether.weaver.api.callback.LocalRef} is generic, and its type argument is
 * erased before the match is made, so a {@code LocalRef<T>} parameter always resolves against
 * {@code Object} regardless of {@code T}.
 *
 * <h2>Ordering</h2>
 *
 * <p>Captures are resolved in ascending {@link #parameter()} order regardless of the order they
 * appear in {@link InjectorSpec#locals()}, so the diagnostics for a handler with several
 * unresolvable captures arrive in the order the parameters are written. All of them are reported:
 * a handler capturing three locals none of which resolve produces three messages rather than one
 * and a mystery.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Inject(method = "process(java.util.List)", at = @At(Point.RETURN))
 * private static void onProcessed(Callback cb,
 *                                 @Local(name = "accepted") int accepted,
 *                                 @Local(mutable = true) LocalDoubleRef total) {
 *     total.set(total.get() + accepted);   // written back into the target's variable
 * }
 * }</pre>
 *
 * <p>The captures follow the {@code Callback}, which is what {@code HandlerBinding} requires: every
 * {@code @Local} parameter occupies one of the handler's last {@link InjectorSpec#locals()} positions,
 * and a {@code Callback}, when present, is the last parameter of the part in front of them.
 * {@code accepted} is {@link Strategy#BY_NAME} and {@code total} is {@link Strategy#BY_TYPE}, the
 * latter resolving against the {@code double} the {@code LocalDoubleRef} holds, since a primitive
 * carrier resolves against the type it holds rather than against {@code Object}. Adding
 * {@code index = 4} to {@code accepted} would silently make it {@link Strategy#BY_SLOT} and ignore
 * the name.
 *
 * @param parameter the zero-based position of the annotated parameter in the handler's declared
 *                  parameter list; never negative
 * @param name      the variable's name, or the empty string when none was written
 * @param index     the variable's slot, or {@code -1} when none was written
 * @param ordinal   the zero-based ordinal among the live variables of the parameter's type, or
 *                  {@code -1} when none was written
 * @param mutable   whether the handler writes back to the variable, which requires the parameter to
 *                  be a {@code LocalRef} carrier
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Local
 */
public record LocalSpec(int parameter, String name, int index, int ordinal, boolean mutable) {

    /**
     * Checks the parameter position and the two sentinels.
     *
     * <p>Only the shape is checked. Whether the named variable exists, and whether it is live where
     * the handler attaches, are questions about one target method at one position and are answered
     * when the capture is resolved.
     *
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code parameter} is negative, or if {@code index} or
     *                                  {@code ordinal} is below {@code -1}
     */
    public LocalSpec {
        Objects.requireNonNull(name, "name");
        if (parameter < 0) {
            throw new IllegalArgumentException(
                    "a handler parameter index is never negative, but was " + parameter);
        }
        if (index < -1) {
            throw new IllegalArgumentException(
                    "a local slot is -1 when unspecified or a zero-based index, but was " + index);
        }
        if (ordinal < -1) {
            throw new IllegalArgumentException(
                    "a local ordinal is -1 when unspecified or a zero-based index, but was "
                            + ordinal);
        }
    }

    /**
     * Returns the single way this capture is resolved.
     *
     * <p>Derived rather than stored, from the first of {@link #index()}, {@link #name()} and
     * {@link #ordinal()} that carries a value. Components that lose the precedence are not
     * consulted anywhere else either, so this is the whole answer.
     *
     * @return the strategy, never {@code null}; {@link Strategy#BY_TYPE} when nothing was written
     */
    @Contract(pure = true)
    public Strategy strategy() {
        if (this.index >= 0) {
            return Strategy.BY_SLOT;
        }
        if (!this.name.isEmpty()) {
            return Strategy.BY_NAME;
        }
        return this.ordinal >= 0 ? Strategy.BY_ORDINAL : Strategy.BY_TYPE;
    }

    /**
     * How a capture picks the target's variable.
     *
     * <p>The constants are declared in order of precedence, which is the order
     * {@link LocalSpec#strategy()} tests them in.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Strategy {

        /**
         * The variable is the one occupying {@link LocalSpec#index()} at the injection point.
         *
         * <p>The only strategy that does not need the target to carry a {@code LocalVariableTable}.
         * Where one is present, the slot's recorded type is checked against the parameter's and a
         * mismatch is reported as {@code AW1050}; where none is present, the slot is used as
         * written and the parameter's own type decides how it is loaded.
         *
         * <p>Slots are assigned by the compiler and are reused once a scope ends, so a slot that is
         * right today is right only for that build of the target.
         */
        BY_SLOT,

        /**
         * The variable is the live one named {@link LocalSpec#name()}.
         *
         * <p>Reported as {@code AW1050} when no variable of that name is live at the injection
         * point, and again when the one found holds a type the parameter cannot accept.
         */
        BY_NAME,

        /**
         * The variable is the {@link LocalSpec#ordinal()}-th live one of the parameter's type.
         *
         * <p>Counted in slot order from zero, over the live variables of that type only. An ordinal
         * past the end is reported as {@code AW1050}.
         */
        BY_ORDINAL,

        /**
         * The variable is the only live one of the parameter's type.
         *
         * <p>What an unadorned {@code @Local} means. Reported as {@code AW1050} when none is live
         * and as {@code AW1051} when several are; in the latter case name one with
         * {@link de.splatgames.aether.weaver.api.Local#name()} or
         * {@link de.splatgames.aether.weaver.api.Local#ordinal()}.
         */
        BY_TYPE
    }
}
