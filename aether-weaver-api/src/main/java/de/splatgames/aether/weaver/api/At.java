package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One injection point: where inside a target method a declaration attaches.
 *
 * <p>An {@code @At} is written inside another annotation and means nothing on its own, which
 * is why it declares an empty {@link Target @Target} and cannot be applied to a program
 * element. It appears as {@link Inject#at()}, {@link Redirect#at()}, {@link Wrap#at()} and as
 * either bound of a {@link Slice}.
 *
 * <h2>How the elements combine</h2>
 *
 * <p>Resolution is a pipeline, and each step consumes the result of the one before it. Read
 * the elements in this order, because that is the order in which they take effect.
 *
 * <ol>
 *   <li><b>{@link #slice()}</b> narrows the search to the region of the method named by a
 *       {@link Slice} declared on the same injection declaration. Everything after this step
 *       sees that region and nothing outside it.
 *   <li><b>{@link #value()}, or {@link #custom()} when it is set</b>, finds every position of
 *       its kind inside the region, together with {@link #target()} and — for
 *       {@link Point#FIELD} — {@link #access()}. This produces the matches, in body order.
 *   <li><b>{@link #ordinal()}</b> selects one of those matches by index. The index counts
 *       within the narrowed region, so adding a slice to an otherwise unchanged declaration
 *       can change which instruction it names. This step runs only when the previous one found
 *       at least one match; an ordinal at or beyond the number of matches found is reported as
 *       {@code AW1110}, but a declaration that matches nothing at all is reported earlier, as
 *       {@code AW1043}, and never reaches this step.
 *   <li><b>{@link #shift()}</b>, with {@link #by()} when it is {@link Shift#BY}, moves the
 *       selection along the instruction sequence.
 *   <li>Finally the engine checks whether the resolved position may be woven at, and what it
 *       checks depends on which annotation this {@code @At} belongs to. For {@link Inject} it
 *       refuses a place before a constructor's {@code super(...)} call for an instance handler
 *       ({@code AW1026}), a place between a {@code new} and its constructor call
 *       ({@code AW1105}), and an instruction nothing can reach ({@code AW1130}). For
 *       {@link Redirect} and {@link Wrap}, which stand in for an operation rather than
 *       attaching beside one, this step instead refuses a position that names no operation
 *       ({@code AW1061}); the three checks above do not apply to them.
 * </ol>
 *
 * <p>An {@code @At} that resolves to no position at all is not itself an error; it is the
 * declaration's {@link Inject#require()} that decides, and falling short of it is reported as
 * {@code AW1043}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Gateway.class)
 * public final class GatewayAudit {
 *
 *     @Inject(method = "send(Payment)",
 *             slice = @Slice(id = "afterHandshake",
 *                            from = @At(value = Point.INVOKE, target = "Socket.connect")),
 *             at = @At(value = Point.INVOKE,
 *                      target = "Socket.write",
 *                      ordinal = 0,
 *                      slice = "afterHandshake"),
 *             require = 1)
 *     private void beforeTheFirstWriteAfterTheHandshake() {
 *     }
 * }
 * }</pre>
 *
 * <p>The ordinal is {@code 0} and still does not name the method's first {@code Socket.write}
 * unless that write happens after the handshake: the slice decides what {@code 0} counts
 * from.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Point
 * @see Slice
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface At {

    /**
     * The built-in point to look for.
     *
     * <p>Ignored when {@link #custom()} names a contributed point. Each constant states what
     * it matches, whether it needs a {@link #target()}, and which annotations may name it;
     * naming one that the enclosing annotation may not use is reported as {@code AW1061}.
     *
     * @return the built-in injection point
     */
    Point value() default Point.HEAD;

    /**
     * A contributed injection point, named as text.
     *
     * <p>Set this and {@link #value()} is not consulted at all, including its default of
     * {@link Point#HEAD}. A contributed point is registered by a plugin under a
     * {@code namespace:NAME} identifier and is found by that exact string; a name no
     * registered point answers to is reported as {@code AW1101}, which is also what a
     * misspelling of a built-in name produces. What such a point requires of
     * {@link #target()} and whether it accepts a {@link #shift()} is decided by the plugin
     * that contributes it.
     *
     * @return the contributed point's identifier, or an empty string to use {@link #value()}
     */
    String custom() default "";

    /**
     * What the point matches against, written as a selector.
     *
     * <p>Whether this is required, optional or forbidden depends on the point, and the wrong
     * answer is reported as {@code AW1043}: {@link Point#INVOKE}, {@link Point#INVOKE_AFTER},
     * {@link Point#FIELD} and {@link Point#NEW} require one, {@link Point#HEAD},
     * {@link Point#RETURN} and {@link Point#TAIL} refuse one, and {@link Point#CONSTANT} and
     * {@link Point#THROW} accept either.
     *
     * <p>How the text is read also depends on the point. {@link Point#FIELD} parses it with
     * the field grammar and {@link Point#INVOKE}, {@link Point#INVOKE_AFTER} and
     * {@link Point#CONSTANT} with the method grammar; for {@link Point#NEW},
     * {@link Point#THROW} and a contributed point the text is kept unparsed and compared by
     * the point itself. Text that the grammar cannot parse is reported as {@code AW1015}, a
     * JVM descriptor written without its {@code desc:} prefix as {@code AW1017}, a malformed
     * descriptor as {@code AW1018}, and one missing its return type as {@code AW1019}.
     * {@code AW1016} is not one of them: this text is kept as written and never checked for
     * type arguments, so writing {@code List<String>} here is not reported at all — it is
     * {@link Inject#method()} and its equivalents on {@link Redirect} and {@link Wrap} whose
     * type arguments are accepted and reported as ignored with {@code AW1016}.
     *
     * @return the selector the point matches against, or an empty string for none
     */
    String target() default "";

    /**
     * Which one of the matches to use, counted from zero.
     *
     * <p>Counted within the region named by {@link #slice()}, not within the method, and in
     * body order. The default of {@code -1} keeps every match instead of selecting one, which
     * is what allows a single declaration to weave several positions; defaulting to {@code 0}
     * would silently bind to the first. An ordinal at or beyond the number of matches actually
     * found is reported as {@code AW1110} and the declaration matches nothing; a declaration
     * whose point found no matches at all is reported earlier and separately, as
     * {@code AW1043}, and this element is not consulted for it.
     *
     * <p>As a bound of a {@link Slice} the default is {@code 0} rather than {@code -1}: a
     * range boundary has to be exactly one position.
     *
     * @return the zero-based index of the match to use, or {@code -1} to use every match
     */
    int ordinal() default -1;

    /**
     * Moves the resolved position along the instruction sequence.
     *
     * <p>{@link Point#HEAD} refuses any shift with {@code AW1102}, and so do {@link Redirect}
     * and {@link Wrap} at every point they name: each takes over the operation it matched, and
     * a neighbouring instruction is not something the handler's signature describes. A shift
     * that leaves the region the match was found in
     * is reported as {@code AW1111}, and the declaration then matches nothing rather than
     * being clamped to the edge.
     *
     * <p>The step is one code element, and the element sequence carries the class file's
     * labels and line numbers as well as its instructions, so a shift of one is not
     * necessarily a step of one instruction.
     *
     * @return how to move the resolved position
     */
    Shift shift() default Shift.NONE;

    /**
     * The offset for {@link Shift#BY}.
     *
     * <p>Read only when {@link #shift()} is {@link Shift#BY} and ignored otherwise, including
     * when it is set alongside {@link Shift#BEFORE} or {@link Shift#AFTER}. Negative values
     * move backwards. An absolute value above {@code 4} is reported as {@code AW1112}, a
     * warning rather than an error: a large offset survives no recompilation of the target,
     * and a slice or a different point almost always expresses the same intent.
     *
     * @return the number of code elements to move by
     */
    int by() default 0;

    /**
     * Narrows {@link Point#FIELD} to one kind of field access.
     *
     * <p>Consulted by {@link Point#FIELD} alone; every other point ignores it, and setting it
     * elsewhere is neither an error nor a filter.
     *
     * @return the field access kind to match
     */
    Access access() default Access.ANY;

    /**
     * The {@link Slice} to search, named by its {@link Slice#id()}.
     *
     * <p>The empty default selects a slice declared without an id, which is how a declaration
     * with exactly one slice needs no names at all. A reference that matches no declared
     * slice is not reported: the search runs over the whole method, and the first sign of it
     * is an ordinal counting from somewhere other than the author expected.
     *
     * @return the id of the slice to search, or an empty string for the unnamed one
     */
    String slice() default "";

    /**
     * How far, and in which direction, a resolved position is moved.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum Shift {

        /**
         * No movement: the position the point resolved to is the position that is woven.
         */
        NONE,

        /**
         * One code element earlier.
         */
        BEFORE,

        /**
         * One code element later.
         *
         * <p>For a call this is not conceptually the same as {@link Point#INVOKE_AFTER}: the
         * shift moves past one element of the sequence, while {@link Point#INVOKE_AFTER} names
         * the position after the call as a point in its own right. In practice the two land on
         * the same index, so a handler capturing {@link Result} works equally after
         * {@link Point#INVOKE} shifted {@code AFTER} and after {@link Point#INVOKE_AFTER}
         * itself; nothing downstream distinguishes how that position was reached.
         */
        AFTER,

        /**
         * As many code elements as {@link At#by()} says, forwards for a positive value and
         * backwards for a negative one.
         */
        BY
    }

    /**
     * The kinds of field access {@link Point#FIELD} can be narrowed to.
     *
     * <p>The four specific constants correspond one for one to the JVM's field instructions,
     * so a read of a static field is {@link #STATIC_GET} and never {@link #GET}.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum Access {

        /**
         * Every field access, instance and static, read and write alike.
         */
        ANY,

        /**
         * A read of an instance field.
         */
        GET,

        /**
         * A write of an instance field.
         */
        PUT,

        /**
         * A read of a static field.
         */
        STATIC_GET,

        /**
         * A write of a static field.
         */
        STATIC_PUT
    }
}
