package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A named region of a target method, so that a point searches part of the body instead of all of
 * it.
 *
 * <p>A slice is written on the injection declaration — {@link Inject#slice()},
 * {@link Redirect#slice()} or {@link Wrap#slice()} — and is used by an {@link At} that names it
 * through {@link At#slice()}. It changes two things about that point: which instructions can match
 * it at all, and what {@link At#ordinal()} counts. A declaration that matches the right kind of
 * instruction in the wrong place is what a slice exists to fix, and renumbering the ordinal is the
 * side effect that surprises most often.
 *
 * <p>Like {@link At}, a slice means nothing on its own and declares an empty {@link Target @Target},
 * so it cannot be written on a program element.
 *
 * <h2>The region</h2>
 *
 * <p>Both bounds are ordinary {@link At} declarations, resolved against the whole method before
 * anything is narrowed. Each must resolve to exactly one position, which is why a bound's ordinal
 * defaults to {@code 0} — the first match — rather than to the {@code -1} an {@link At} uses
 * elsewhere to mean every match.
 *
 * <p>The region runs from the element {@link #from()} resolved to, <b>inclusive</b>, up to the
 * element {@link #to()} resolved to, <b>exclusive</b>. An instruction that a bound itself names is
 * therefore inside the region when it is the lower bound and outside it when it is the upper one.
 *
 * <p>Everything the point does happens inside that window. It finds only matches within it, an
 * ordinal counts only those matches, and a {@link At#shift()} that would move the selection out of
 * the window is reported as {@code AW1111} rather than clamped, leaving the declaration with
 * nothing matched.
 *
 * <h2>What a bound may be</h2>
 *
 * <p>Four of an {@link At}'s elements decide what a bound finds. {@link At#value()} or
 * {@link At#custom()} chooses the kind of position, {@link At#target()} says what it matches,
 * {@link At#access()} narrows a {@link Point#FIELD} bound to one kind of access, and
 * {@link At#ordinal()} picks one of the matches. The target is read with the same grammar as
 * anywhere else — the field grammar for {@link Point#FIELD}, the method grammar for
 * {@link Point#INVOKE}, {@link Point#INVOKE_AFTER} and {@link Point#CONSTANT} — so
 * {@code "#begin"}, {@code "com.acme.Target.begin()"} and
 * {@code "desc:com/acme/Target.begin()V"} select the same position.
 *
 * <p>The remaining three are parsed and then not consulted. {@link At#shift()} and {@link At#by()}
 * do not move a bound: the position a bound resolves to is the position the region starts or stops
 * at, and a bound that needs a neighbouring instruction wants a different point. {@link At#slice()}
 * on a bound is not read either, because a bound locates a region rather than searching one.
 *
 * <p>Writing {@code ordinal = -1} on a bound is refused when the weave class is read, because a
 * bound that keeps every match is not a position — but not with a diagnostic. The refusal is an
 * unchecked {@link IllegalArgumentException} thrown while the slice's model is constructed, and
 * nothing along the discovery path catches it: the weave being read is not merely dropped, every
 * other weave discovery had not yet reached is abandoned along with it.
 *
 * <h2>An empty {@code @Slice} is not the whole method</h2>
 *
 * <p>{@link #from()} defaults to {@code @At(Point.HEAD)} and {@link #to()} to
 * {@code @At(Point.TAIL)}, and both defaults resolve like any other bound.
 * {@link Point#HEAD} resolves to the method's first instruction — the position after the
 * constructor's own {@code super(...)} or {@code this(...)} call in a constructor — and
 * {@link Point#TAIL} to the method's last return instruction, which the region then excludes.
 *
 * <p>So a declaration that writes {@code @Slice()} with both bounds left at their defaults is not
 * the same as one that declares no slice at all. A declaration with no slice searches the entire
 * element list; a declaration with an all-default slice searches from the first instruction up to
 * but not including the final {@code return}, and a {@link Point#RETURN} search inside it therefore
 * misses that last return. Leave the slice off entirely to search the whole method.
 *
 * <h2>Naming, and what a name that matches nothing does</h2>
 *
 * <p>A point selects its slice by comparing {@link At#slice()} to {@link #id()} literally. The
 * empty default on both sides is what makes the common case work with no names: one slice declared
 * without an id and one point that names none find each other.
 *
 * <p>A reference that matches no declared slice is <b>not</b> an error and produces no diagnostic.
 * The point searches the whole method instead, and the first visible sign of the mistake is an
 * ordinal counting from somewhere other than the author expected. A misspelt {@link At#slice()} and
 * a misspelt {@link #id()} both land here.
 *
 * <p>Two slices with the same id on one declaration are refused when the weave class is read: a
 * reference to that id would have two answers. As with {@code ordinal = -1}, the refusal is an
 * unchecked {@link IllegalArgumentException} rather than a diagnostic, and it aborts discovery of
 * every weave not yet read rather than only this one. Two declarations may of course use the same
 * id independently, because a point only ever looks among the slices of its own declaration.
 *
 * <h2>When a bound cannot be located</h2>
 *
 * <p>A slice that cannot be located is refused rather than allowed to widen silently to the whole
 * method, which would weave somewhere the author never named.
 *
 * <ul>
 *   <li><b>{@link #from()} matches nothing</b> — {@code AW1120}.
 *   <li><b>{@link #to()} matches nothing</b> — {@code AW1121}.
 *   <li><b>{@link #to()} resolves before {@link #from()}</b> — {@code AW1122}. Both bounds resolved,
 *       and the region between them runs backwards.
 *   <li><b>A bound names an injection point that is not registered</b> — {@code AW1120} or
 *       {@code AW1121}, according to which bound it was.
 * </ul>
 *
 * <p>In each of these the point resolves to no position at all, and {@code AW1120}, {@code AW1121}
 * and {@code AW1122} are themselves errors that fail the build regardless of the declaration's
 * {@link Inject#require()} — including {@code require = 0}. {@link Inject#require()} decides only
 * whether the separate {@code AW1043}, reported because the declaration then matched nothing, joins
 * them.
 *
 * <p>One case is quieter than the rest. A bound whose ordinal is at or beyond the number of matches
 * it found reports nothing of its own — no {@code AW1110}, which belongs to the declaration's point
 * rather than to a bound — and the slice simply does not resolve. The only diagnostic is the
 * {@code AW1043} that follows from the declaration matching nothing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Gateway.class)
 * public final class GatewayAudit {
 *
 *     // Gateway.send calls Socket.write several times: once before the handshake and twice
 *     // after it. Without the slice, ordinal = 0 would name the write before the handshake.
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
 * <p>The slice's {@code to} is left out, so the region runs from the {@code Socket.connect} call to
 * just short of the method's last {@code return}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see At
 * @see Point
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Slice {

    /**
     * The name an {@link At} uses to select this slice.
     *
     * <p>Compared literally against {@link At#slice()}. The empty default declares the unnamed
     * slice, which is what a point that names none selects, so a declaration carrying exactly one
     * slice needs no id at all.
     *
     * <p>An id that no point refers to costs nothing: an unused slice is parsed, kept on the
     * declaration and never consulted. Two slices sharing an id on one declaration are refused when
     * the weave class is read, as an unchecked exception rather than a diagnostic — see the
     * class-level notes on naming.
     *
     * @return the slice's name, or an empty string to declare the unnamed slice
     */
    String id() default "";

    /**
     * Where the region begins, inclusive.
     *
     * <p>Resolved against the whole method, and required to name exactly one position: the bound's
     * {@link At#ordinal()} therefore defaults to {@code 0} rather than to {@link At}'s usual
     * {@code -1}, and writing {@code -1} here is refused when the weave class is read, as an
     * unchecked exception rather than a diagnostic — see the class-level notes on the region.
     *
     * <p>The default of {@code @At(Point.HEAD)} resolves to the method's first instruction, or in a
     * constructor to the position after its own {@code super(...)} or {@code this(...)} call. A
     * bound that matches nothing is reported as {@code AW1120} and the point then matches nothing.
     *
     * @return the point at which the region starts
     */
    At from() default @At(Point.HEAD);

    /**
     * Where the region ends, exclusive.
     *
     * <p>Resolved against the whole method under the same rule as {@link #from()}: exactly one
     * position, with the bound's ordinal defaulting to {@code 0}. The instruction this resolves to
     * is the first one <em>outside</em> the region, so a point can never match it.
     *
     * <p>The default of {@code @At(Point.TAIL)} resolves to the method's last return instruction,
     * which the region then excludes. A bound that matches nothing is reported as {@code AW1121},
     * and one that resolves before {@link #from()} as {@code AW1122}.
     *
     * @return the point at which the region stops
     */
    At to() default @At(Point.TAIL);
}
