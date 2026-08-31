package de.splatgames.aether.weaver.engine.dump;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Produces a unified diff of two texts, comparing them by a caller-supplied key.
 *
 * <p>The key is the reason this exists rather than a library call: weaving shifts bytecode offsets
 * and constant-pool indices, so {@link Disassembly#key(String)} normalises both away before two
 * lines are compared. Lines are matched on {@code normalise(line)} and printed as they were
 * written, so the output is unnormalised even though the comparison is not.
 *
 * <p>The algorithm is a longest common subsequence over a full table, which costs one {@code int}
 * per pair of lines. Two guards keep that affordable: a common prefix and suffix are trimmed away
 * first, and a pair whose remaining halves would exceed {@code MAX_CELLS} cells is refused.
 *
 * <p>Stateless, though not every method is pure: {@link #hunk} appends into the {@code lines} list
 * its caller owns rather than returning a new one.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class TextDiff {

    /** Unchanged lines kept on each side of a change, and the gap at which two hunks stay apart. */
    private static final int CONTEXT = 3;

    /**
     * The limit on the product of the two trimmed lengths, checked before {@link #edits(List, List)}
     * is called.
     *
     * <p>Not the size of the table {@link #edits(List, List)} actually allocates: that table is
     * {@code (before.size() + 1) * (after.size() + 1)} cells, one larger in each dimension than the
     * trimmed lengths this limit bounds. The trim removes a common prefix and a common suffix only,
     * so two long texts that differ only near one end are trimmed down to a small pair and pass
     * regardless of their original length; a pair whose remaining interior still exceeds this limit
     * is refused with a line of explanation instead of the {@code int[][]}.
     */
    private static final long MAX_CELLS = 4_000_000L;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private TextDiff() {
        throw new AssertionError("no instances");
    }

    /**
     * Compares two texts and returns the differing regions as unified-diff hunks.
     *
     * <p>The trim of the common prefix and suffix is an optimisation for the table, not a decision
     * about what to show: the edits it skipped are put back into whole-file coordinates before
     * rendering, so a hunk header names a line number a reader can find in either text and the
     * context around a change at the very first or very last line is still printed.
     *
     * <p>The result is empty when the two texts agree under {@code normalise}, which is not the
     * same as being identical. A refusal on size is returned as a single explanatory line, so a
     * caller that prints whatever comes back needs no special case for it.
     *
     * @param before    the original text, one entry per line; must not be {@code null}
     * @param after     the changed text, one entry per line; must not be {@code null}
     * @param normalise what to compare each line by; must not be {@code null}
     * @return the hunks, empty when nothing differs, or one line saying the pair was too large
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public static List<String> unified(@NotNull final List<String> before,
                                @NotNull final List<String> after,
                                @NotNull final Function<String, String> normalise) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(normalise, "normalise");

        final List<String> beforeKeys = before.stream().map(normalise).toList();
        final List<String> afterKeys = after.stream().map(normalise).toList();

        int head = 0;
        while (head < beforeKeys.size() && head < afterKeys.size()
                && beforeKeys.get(head).equals(afterKeys.get(head))) {
            head++;
        }
        int tail = 0;
        while (tail < beforeKeys.size() - head && tail < afterKeys.size() - head
                && beforeKeys.get(beforeKeys.size() - 1 - tail)
                .equals(afterKeys.get(afterKeys.size() - 1 - tail))) {
            tail++;
        }

        final int beforeLength = beforeKeys.size() - head - tail;
        final int afterLength = afterKeys.size() - head - tail;
        if (beforeLength == 0 && afterLength == 0) {
            return List.of();
        }
        if ((long) beforeLength * afterLength > MAX_CELLS) {
            return List.of("the two disassemblies differ too widely to diff line by line ("
                    + beforeLength + " against " + afterLength + " lines); "
                    + "both class files were written and can be compared with javap directly");
        }

        // The trim is an optimisation for the table, not a decision about what to show. The
        // edits are put back into whole-file coordinates before rendering, because the lines the
        // trim removed are exactly the context a reader needs to place the change.
        final List<Edit> edits = new ArrayList<>(before.size() + after.size());
        for (int i = 0; i < head; i++) {
            edits.add(new Edit(Edit.Kind.SAME, i, i));
        }
        for (final Edit edit : edits(beforeKeys.subList(head, head + beforeLength),
                afterKeys.subList(head, head + afterLength))) {
            edits.add(new Edit(edit.kind(),
                    edit.before() < 0 ? -1 : head + edit.before(),
                    edit.after() < 0 ? -1 : head + edit.after()));
        }
        for (int i = 0; i < tail; i++) {
            edits.add(new Edit(Edit.Kind.SAME,
                    head + beforeLength + i, head + afterLength + i));
        }
        return render(before, after, edits);
    }

    /**
     * Walks the longest common subsequence of two key lists into a sequence of edits.
     *
     * <p>The table is filled backwards so that the walk forwards can read a decision off it
     * directly. Where the two continuations are of equal length the removal is taken first, which
     * is what makes a replacement print as its removals followed by its additions rather than
     * interleaved.
     *
     * @param before the original keys
     * @param after  the changed keys
     * @return the edits, in the order the lines appear, with indices relative to these two lists
     */
    @Contract(pure = true)
    @NotNull
    private static List<Edit> edits(@NotNull final List<String> before,
                                    @NotNull final List<String> after) {
        // The classic longest-common-subsequence table. Built over the trimmed region only, which
        // for a woven class is the handful of lines around the injection.
        final int[][] lengths = new int[before.size() + 1][after.size() + 1];
        for (int i = before.size() - 1; i >= 0; i--) {
            for (int j = after.size() - 1; j >= 0; j--) {
                lengths[i][j] = before.get(i).equals(after.get(j))
                        ? lengths[i + 1][j + 1] + 1
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }

        final List<Edit> edits = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < before.size() && j < after.size()) {
            if (before.get(i).equals(after.get(j))) {
                edits.add(new Edit(Edit.Kind.SAME, i++, j++));
            } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
                edits.add(new Edit(Edit.Kind.REMOVED, i++, -1));
            } else {
                edits.add(new Edit(Edit.Kind.ADDED, -1, j++));
            }
        }
        while (i < before.size()) {
            edits.add(new Edit(Edit.Kind.REMOVED, i++, -1));
        }
        while (j < after.size()) {
            edits.add(new Edit(Edit.Kind.ADDED, -1, j++));
        }
        return edits;
    }

    /**
     * Turns a sequence of edits into hunks, skipping the runs that did not change.
     *
     * <p>A hunk keeps growing until {@code 2 * CONTEXT} consecutive unchanged edits follow, so two
     * changes closer together than their context windows are shown as one hunk rather than as two
     * that would print the same lines twice.
     *
     * @param before the original lines
     * @param after  the changed lines
     * @param edits  the edits, in whole-file coordinates
     * @return the rendered hunks
     */
    @Contract(pure = true)
    @NotNull
    private static List<String> render(@NotNull final List<String> before,
                                       @NotNull final List<String> after,
                                       @NotNull final List<Edit> edits) {
        final List<String> lines = new ArrayList<>();
        int index = 0;
        while (index < edits.size()) {
            if (edits.get(index).kind() == Edit.Kind.SAME) {
                index++;
                continue;
            }
            int end = index;
            while (end < edits.size() && !allSame(edits, end, CONTEXT * 2)) {
                end++;
            }
            final int from = Math.max(0, index - CONTEXT);
            final int to = Math.min(edits.size(), end + CONTEXT);
            hunk(lines, before, after, edits, from, to);
            index = to;
        }
        return List.copyOf(lines);
    }

    /**
     * Reports whether a window of edits is unchanged throughout.
     *
     * <p>A window running past the end counts as unchanged over what is left, so the last hunk of a
     * text closes rather than extending to the final line.
     *
     * @param edits the edits to inspect
     * @param from  the first index of the window
     * @param count how many to inspect
     * @return {@code true} when every edit in range is unchanged
     */
    @Contract(pure = true)
    private static boolean allSame(@NotNull final List<Edit> edits, final int from,
                                   final int count) {
        for (int i = from; i < Math.min(edits.size(), from + count); i++) {
            if (edits.get(i).kind() != Edit.Kind.SAME) {
                return false;
            }
        }
        return true;
    }

    /**
     * Appends one hunk: its header, then its lines with a one-character prefix each.
     *
     * <p>The counts in the header include the context lines, since they too are present on both
     * sides. A hunk that adds lines where the original had none has no first line to name on that
     * side, and its start is reported as {@code 1}.
     *
     * @param lines  the buffer to append to
     * @param before the original lines
     * @param after  the changed lines
     * @param edits  the edits, in whole-file coordinates
     * @param from   the first edit of this hunk
     * @param to     one past the last edit of this hunk
     */
    private static void hunk(@NotNull final List<String> lines,
                             @NotNull final List<String> before,
                             @NotNull final List<String> after,
                             @NotNull final List<Edit> edits,
                             final int from, final int to) {
        int removed = 0;
        int added = 0;
        int firstBefore = -1;
        int firstAfter = -1;
        for (int i = from; i < to; i++) {
            final Edit edit = edits.get(i);
            if (edit.before() >= 0) {
                removed++;
                firstBefore = firstBefore < 0 ? edit.before() : firstBefore;
            }
            if (edit.after() >= 0) {
                added++;
                firstAfter = firstAfter < 0 ? edit.after() : firstAfter;
            }
        }
        // Line numbers are one-based and count from the untrimmed text. A hunk header that
        // referred to the trimmed region would name lines nobody can find in either file.
        lines.add("@@ -" + (Math.max(firstBefore, 0) + 1) + ',' + removed
                + " +" + (Math.max(firstAfter, 0) + 1) + ',' + added + " @@");
        for (int i = from; i < to; i++) {
            final Edit edit = edits.get(i);
            switch (edit.kind()) {
                case SAME -> lines.add("  " + before.get(edit.before()));
                case REMOVED -> lines.add("- " + before.get(edit.before()));
                case ADDED -> lines.add("+ " + after.get(edit.after()));
            }
        }
    }

    /**
     * One line's fate, and where it sits on each side.
     *
     * <p>An index of {@code -1} means the line is absent from that side: a removal has no
     * {@code after} and an addition has no {@code before}. The sentinel is what lets the two
     * coordinate systems be carried in one record, and it is why the rendering asks about the kind
     * before it uses either number.
     *
     * @param kind   what happened to the line
     * @param before its index in the original text, or {@code -1}
     * @param after  its index in the changed text, or {@code -1}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Edit(@NotNull Kind kind, int before, int after) {

        /**
         * What an edit does to a line.
         *
         * @author Erik Pförtner
         * @since 0.1.0
         */
        enum Kind {

            /** Present on both sides, under the comparison key. */
            SAME,

            /** Present in the original only. */
            REMOVED,

            /** Present in the changed text only. */
            ADDED
        }
    }
}
