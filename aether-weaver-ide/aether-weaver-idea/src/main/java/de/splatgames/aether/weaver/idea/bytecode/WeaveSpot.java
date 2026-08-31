package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.Point;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One place a handler could be attached, as it is offered to the author.
 *
 * <p>A spot carries both halves of the offer: what would be written into the annotation
 * ({@link #point()}, {@link #operation()} and {@link #slice()}) and what the author is told about
 * it ({@link #what()}, {@link #why()} and {@link #confidence()}). The second half exists because
 * several spots are usually offered at once and the difference between them is not visible in the
 * annotation they would produce.
 *
 * <p>{@link #operation()} is {@code null} exactly for a positional point, where there is no
 * instruction to name. {@link #slice()} is set only on the spot reached through
 * {@link #narrowed()}.
 *
 * @param point      the injection point the annotation would name
 * @param operation  the operation the point would target, or {@code null} for a position such as
 *                   {@link Point#HEAD}
 * @param slice      the region the ordinal is counted in, or {@code null} when the ordinal counts
 *                   across the whole method
 * @param line       the line the spot refers to: the one the operation was compiled from, or the
 *                   caret's own line for a position offered because the caret stands on it, and
 *                   {@code 0} for a position offered regardless of where the caret is
 * @param matches    how many operations share this one's selector, which is what makes the ordinal
 *                   necessary; counted across the whole method when {@link #slice()} is
 *                   {@code null}. A spot reached through {@link #narrowed()} carries {@code 1}
 *                   unconditionally, as the count inside its slice; a positional spot, which has no
 *                   selector to count matches of, carries {@code 1} as well
 * @param confidence how the spot was arrived at
 * @param what       the offer, phrased for a list the author chooses from
 * @param why        why this spot is being offered, phrased to be read next to {@code what}
 * @param narrowed   the same spot with its ordinal counted inside a slice, or {@code null} when
 *                   nothing would be gained by narrowing it
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record WeaveSpot(@NotNull Point point,
                        @Nullable TargetOperations.Operation operation,
                        @Nullable TargetOperations.Bounds slice,
                        int line,
                        int matches,
                        @NotNull Confidence confidence,
                        @NotNull String what,
                        @NotNull String why,
                        @Nullable WeaveSpot narrowed) {

    /**
     * How firmly a spot was tied to what the author is pointing at.
     *
     * <p>Declared in the order the offers are made, which is also the order a reader should read
     * them as: an exact answer first, then something the caret's line makes plausible, then the
     * positions that are available in any method.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Confidence {

        /** The instruction an anchor read from the source agreed with, or the caret's own return. */
        EXACT,

        /** Not the caret's own expression, but compiled from the caret's line. */
        SAME_LINE,

        /** Compiled from another line, offered because the caret's line yielded nothing. */
        NEAREST,

        /**
         * Named from the source alone, with no class file consulted; its operation carries no
         * instruction index and no ordinal.
         */
        FROM_SOURCE,

        /** A position in the method rather than an operation, and therefore always available. */
        POSITION
    }

    /**
     * Renders the offer for a list the author chooses from.
     *
     * <p>The line is part of the label rather than a separate column, because a spot offered for a
     * line other than the caret's has to say so before it is accepted.
     *
     * @return {@link #what()}, followed by the line in parentheses when there is one
     */
    @Contract(pure = true)
    @NotNull
    public String label() {
        return this.line > 0 ? this.what + "  (line " + this.line + ')' : this.what;
    }

    /**
     * Reports whether a narrower form of this spot is available.
     *
     * @return {@code true} when {@link #narrowed()} is present
     */
    @Contract(pure = true)
    public boolean isNarrowable() {
        return this.narrowed != null;
    }
}
