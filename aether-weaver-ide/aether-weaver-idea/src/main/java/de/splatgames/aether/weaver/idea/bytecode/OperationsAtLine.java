package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.spi.MethodView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the operations of a compiled method by the source line they were compiled from.
 *
 * <p>Answers a caret rather than a selector: the question is what an author standing on a given
 * line could attach a handler to, and the answer must not be empty for the ordinary places a caret
 * ends up — a blank line inside a method, or a closing brace. {@link #at} therefore falls forward
 * to the nearest line that has operations instead of reporting nothing.
 *
 * <p>Only the points that name an operation are searched. A position such as
 * {@link Point#HEAD} needs no search because it exists in every method.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class OperationsAtLine {

    /**
     * The points that name an operation, in the order their offers are produced.
     *
     * <p>{@link Point#INVOKE} and {@link Point#INVOKE_AFTER} both appear, so that a call is
     * offered before and after: the two differ in whether the handler can see the result, and
     * leaving one of them out would hand that decision back to the author as an annotation edit.
     */
    private static final List<Point> POINTS =
            List.of(Point.INVOKE, Point.INVOKE_AFTER, Point.FIELD, Point.NEW, Point.CONSTANT);

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private OperationsAtLine() {
        throw new AssertionError("no instances");
    }

    /**
     * One operation of the method, at one point, with the line it was compiled from.
     *
     * @param point     the point that names the operation
     * @param operation the operation itself
     * @param line      the line the operation was compiled from, always above {@code 0} because an
     *                  operation with no line is never reported
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Found(@NotNull Point point,
                        @NotNull TargetOperations.Operation operation,
                        int line) {

        /**
         * Renders the offer for a list the author chooses from.
         *
         * <p>{@link Point#INVOKE_AFTER} is spelled out as a prefix, because the operation's own
         * label reads the same at both points and the difference is the whole choice.
         *
         * @return the operation's label, prefixed for an after-point and followed by the line
         */
        @Contract(pure = true)
        @NotNull
        public String label() {
            return (this.point == Point.INVOKE_AFTER ? "after " : "")
                    + this.operation.label()
                    + "  (line " + this.line + ')';
        }
    }

    /**
     * Reports the operations of one line, or of the line nearest to it that has any.
     *
     * <p>Every result is on the same line as every other, and that line is stated by
     * {@link Found#label()}: the caret's own line when anything was compiled from it, otherwise the
     * first line after it that has operations, and a line before it only when there is none after.
     * A caret on a blank line is therefore answered with the code that follows it rather than with
     * the code it has passed, and a caret past the last operation falls back to that operation.
     *
     * @param compiled the compiled method to search; must not be {@code null}
     * @param line     the one-based line the caret is on
     * @param spelling how each operation's selector is written; must not be {@code null}
     * @return the operations of one line, empty only when the method has no operation with a line
     *         at all
     * @throws NullPointerException if {@code compiled} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Found> at(@NotNull final MethodView compiled,
                                 final int line,
                                 @NotNull final TargetOperations.Spelling spelling) {
        final List<Found> everything = allIn(compiled, spelling);
        if (everything.isEmpty()) {
            return List.of();
        }
        final int nearest = nearestLineTo(everything, line);
        final List<Found> found = new ArrayList<>(2);
        for (final Found candidate : everything) {
            if (candidate.line() == nearest) {
                found.add(candidate);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Reports every operation of the method that can be placed on a line.
     *
     * <p>Grouped by point, in a fixed order rather than by position in the body, so
     * every call of the method is listed before every field access. A caller that wants them in
     * source order sorts them itself.
     *
     * <p>An operation with no preceding line entry is left out rather than placed at a guess. A
     * method compiled without {@code -g} has none, and the result is then empty.
     *
     * @param compiled the compiled method to search; must not be {@code null}
     * @param spelling how each operation's selector is written; must not be {@code null}
     * @return every operation that has a line, grouped by point
     * @throws NullPointerException if {@code compiled} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Found> allIn(@NotNull final MethodView compiled,
                                    @NotNull final TargetOperations.Spelling spelling) {
        final List<Found> found = new ArrayList<>();
        for (final Point point : POINTS) {
            for (final TargetOperations.Operation operation
                    : TargetOperations.of(compiled, point, spelling)) {
                final List<Integer> lines =
                        CompiledLines.of(compiled, List.of(operation.index()));
                // An instruction with no line before it is left out rather than placed at a guess.
                // A method compiled without -g has none of them, and then this feature is simply
                // unavailable for it, which is the honest answer.
                if (!lines.isEmpty()) {
                    found.add(new Found(point, operation, lines.getFirst()));
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Reports which line the offers should be taken from.
     *
     * <p>A line that is present wins outright. Otherwise the first line after the caret wins over
     * every line before it, however far away it is, and a line before it is answered with only
     * when the caret is past everything the method compiled.
     *
     * @param found every operation that has a line; must not be {@code null} and must not be empty
     * @param line  the one-based line the caret is on
     * @return the line to answer with
     * @throws NullPointerException if {@code found} is {@code null}
     */
    @Contract(pure = true)
    private static int nearestLineTo(@NotNull final List<Found> found, final int line) {
        int after = Integer.MAX_VALUE;
        int before = Integer.MIN_VALUE;
        for (final Found candidate : found) {
            final int at = candidate.line();
            if (at == line) {
                return line;
            }
            if (at > line && at < after) {
                after = at;
            }
            if (at < line && at > before) {
                before = at;
            }
        }
        return after == Integer.MAX_VALUE ? before : after;
    }
}
