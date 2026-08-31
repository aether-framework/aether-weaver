package de.splatgames.aether.weaver.idea.bytecode;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the editor could read about one expression the caret sits in or on.
 *
 * <p>An anchor is the source half of the search {@link SpotFinder} performs: it says what the
 * author is pointing at in the terms the editor has, and the finder looks for the instruction that
 * agrees with it. Everything but {@link #kind()} may therefore be absent, because the editor
 * cannot always resolve a reference and an unresolved one must not be allowed to exclude a
 * candidate.
 *
 * <p>{@link #owner()} is an internal name with {@code /} separators and {@link #descriptor()} a
 * method descriptor, both in the class file's spelling, so that they can be compared with a
 * {@link TargetOperations.Described} without either side rendering the other's form.
 * {@link #constant()} is a rendered constant selector rather than the literal's own text.
 *
 * @param kind       what sort of expression this is
 * @param owner      the internal name of the class declaring the member, for {@link Kind#CALL},
 *                   {@link Kind#INSTANTIATION} and {@link Kind#FIELD_ACCESS}; {@code null} when it
 *                   could not be resolved or for a kind that names no member
 * @param name       the member's name, or {@code null} when there is none to name
 * @param descriptor the method descriptor, or {@code null} when it could not be determined, which
 *                   is also the case for every field access
 * @param constant   the constant selector the literal renders as, or {@code null} for every other
 *                   kind
 * @param firstLine  the first line of the expression's text
 * @param lastLine   the last line of the expression's text, which differs from {@code firstLine}
 *                   for an expression written across several lines
 * @param occurrence for a {@link Kind#CALL} or a {@link Kind#FIELD_ACCESS}, the zero-based position
 *                   of this expression among the identically named ones ending before it within its
 *                   own lines, which is what tells two calls on one line apart; {@code 0} for every
 *                   other kind, which is not counted against anything
 * @param depth      how many anchors were added on the way out from the caret before this one, so
 *                   {@code 0} is the innermost expression that produced an anchor — the caret's own
 *                   only when the caret element is itself one of the kinds an anchor can be read
 *                   from
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record SourceAnchor(@NotNull Kind kind,
                           @Nullable String owner,
                           @Nullable String name,
                           @Nullable String descriptor,
                           @Nullable String constant,
                           int firstLine,
                           int lastLine,
                           int occurrence,
                           int depth) {

    /**
     * What sort of expression an anchor was read from.
     *
     * <p>The kind decides which points can be offered for it, through
     * {@link SpotFinder#pointsFor(Kind)}, and which components of the anchor mean anything.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Kind {

        /** A method call, named by owner, name and descriptor. */
        CALL,

        /** An object creation, named by the created type alone. */
        INSTANTIATION,

        /** A read of or a write to a field, named by owner and name. */
        FIELD_ACCESS,

        /** A literal, named by the constant selector it renders as and by nothing else. */
        CONSTANT,

        /** A return statement, which names a position in the method rather than an operation. */
        RETURN,

        /** The method's entry, which names a position in the method rather than an operation. */
        HEAD
    }

    /**
     * Reports whether the given line lies within the expression's own lines.
     *
     * <p>The window an instruction search is confined to. An expression written across several
     * lines covers all of them, which is what lets a call whose arguments are wrapped be matched
     * against instructions the compiler attributed to any of its lines.
     *
     * @param line the one-based line to test
     * @return {@code true} when the line is between {@link #firstLine()} and {@link #lastLine()},
     *         both included
     */
    @Contract(pure = true)
    public boolean covers(final int line) {
        return line >= this.firstLine && line <= this.lastLine;
    }
}
