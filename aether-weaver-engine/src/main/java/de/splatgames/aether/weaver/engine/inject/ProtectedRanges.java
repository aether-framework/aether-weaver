package de.splatgames.aether.weaver.engine.inject;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.LabelTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Works out how a target's exception handlers have to be re-cut so that injected code sits outside
 * the ranges they protect.
 *
 * <p>Leaving an injected handler call inside the target's own {@code try} makes the target's
 * {@code catch} answer for a failure that did not come from the target, which is invisible where
 * the target catches broadly: a handler that threw and one that returned look the same to the
 * caller. The cut is what {@code AW1131} reports.
 *
 * <p>One property of the cut comes from what the class file will accept:
 * {@link de.splatgames.aether.weaver.engine.verify.StructuralCheck} refuses a range whose start is
 * not before its end, so a piece covering no instruction is dropped rather than emitted. A second,
 * unrelated property comes from what this element list can resolve: a handler whose {@code tryStart}
 * or {@code tryEnd} label has no {@link LabelTarget} here is left with no pieces at all, which is the
 * instruction to re-emit it exactly as it was — nothing about what the class file will accept is
 * involved in that case.
 *
 * <p>Instances are immutable and hold indices into one body, so they are meaningful only against
 * the element list they were computed from.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ProtectedRanges {

    /**
     * The pieces each exception handler's range breaks into, one entry per {@link ExceptionCatch}
     * of the body in the order it holds them, and empty for a handler that is not cut.
     */
    private final List<List<Piece>> pieces;

    /** Every injection index some handler's range is cut around. */
    private final Set<Integer> splitAt;

    /**
     * Stores what {@link #of(List, Set)} computed.
     *
     * @param pieces  the pieces per handler ordinal; must not be {@code null}
     * @param splitAt the indices ranges are cut around; must not be {@code null}
     */
    private ProtectedRanges(@NotNull final List<List<Piece>> pieces,
                            @NotNull final Set<Integer> splitAt) {
        this.pieces = pieces;
        this.splitAt = splitAt;
    }

    /**
     * One surviving stretch of a handler's protected range, named by the injections that bound it.
     *
     * <p>A {@code null} bound means the handler's own label rather than an injection, so a range
     * cut once yields {@code Piece(null, i)} and {@code Piece(i, null)}: everything up to the
     * injection, and everything after it.
     *
     * @param from the injection this piece resumes after, or {@code null} for the handler's own
     *             {@code tryStart}
     * @param to   the injection this piece pauses at, or {@code null} for the handler's own
     *             {@code tryEnd}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Piece(@Nullable Integer from, @Nullable Integer to) {
    }

    /**
     * Computes the cut for every exception handler in one body.
     *
     * <p>Labels are resolved against this body's own {@link LabelTarget} elements, so a handler
     * whose bounds are not both bound here is recorded with no pieces and survives untouched; this
     * asks nothing of a body shape the caller cannot see.
     *
     * @param elements   the body, in the order the class file holds it; must not be {@code null}
     * @param insertions the element indices code is being injected at; must not be {@code null}
     * @return the cut, holding nothing at all when {@code insertions} is empty
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    static ProtectedRanges of(@NotNull final List<CodeElement> elements,
                              @NotNull final Set<Integer> insertions) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(insertions, "insertions");

        final List<List<Piece>> pieces = new ArrayList<>();
        final Set<Integer> splitAt = new LinkedHashSet<>();
        if (insertions.isEmpty()) {
            return new ProtectedRanges(List.of(), Set.of());
        }

        final Map<Label, Integer> bound = boundLabels(elements);
        for (final CodeElement element : elements) {
            if (!(element instanceof final ExceptionCatch handler)) {
                continue;
            }
            final Integer start = bound.get(handler.tryStart());
            final Integer end = bound.get(handler.tryEnd());
            if (start == null || end == null) {
                // A bound this body does not show. Nothing here can reason about it, so the handler
                // survives exactly as it was — no worse than before, and never guessed at.
                pieces.add(List.of());
                continue;
            }
            pieces.add(piecesOf(elements, insertions, start, end, splitAt));
        }
        return new ProtectedRanges(List.copyOf(pieces), Set.copyOf(splitAt));
    }

    /**
     * Cuts one handler's range around the injections that fall inside it.
     *
     * <p>The bounds are half-open, so an injection at {@code end} belongs to whatever follows the
     * range rather than to it. A piece is kept only where it covers an instruction, which is what
     * keeps an injection at the very first instruction of a {@code try} from producing an empty
     * range ahead of itself.
     *
     * @param elements   the body; must not be {@code null}
     * @param insertions every injection index in the method; must not be {@code null}
     * @param start      the element index the handler's {@code tryStart} is bound to
     * @param end        the element index its {@code tryEnd} is bound to
     * @param splitAt    collects the injections this handler was cut around; must not be
     *                   {@code null}
     * @return the surviving pieces, empty when no injection falls inside the range
     */
    @NotNull
    private static List<Piece> piecesOf(@NotNull final List<CodeElement> elements,
                                        @NotNull final Set<Integer> insertions,
                                        final int start,
                                        final int end,
                                        @NotNull final Set<Integer> splitAt) {
        final List<Integer> inside = insertions.stream()
                .filter(index -> index >= start && index < end)
                .sorted()
                .toList();
        if (inside.isEmpty()) {
            return List.of();
        }

        final List<Piece> pieces = new ArrayList<>(inside.size() + 1);
        int from = start;
        Integer after = null;
        for (final int injection : inside) {
            if (holdsAnInstruction(elements, from, injection)) {
                pieces.add(new Piece(after, injection));
            }
            from = injection;
            after = injection;
        }
        if (holdsAnInstruction(elements, from, end)) {
            pieces.add(new Piece(after, null));
        }
        splitAt.addAll(inside);
        return List.copyOf(pieces);
    }

    /**
     * Reports whether the half-open span holds an instruction rather than only labels and metadata.
     *
     * <p>Both ends are clamped into the body, so a caller may name a bound outside it.
     *
     * @param elements the body; must not be {@code null}
     * @param from     the first index to consider
     * @param to       the index to stop before
     * @return {@code true} when at least one {@link Instruction} lies in the span
     */
    @Contract(pure = true)
    private static boolean holdsAnInstruction(@NotNull final List<CodeElement> elements,
                                              final int from, final int to) {
        for (int index = Math.max(from, 0); index < Math.min(to, elements.size()); index++) {
            if (elements.get(index) instanceof Instruction) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indexes the labels this body binds, so an exception handler's bounds can be turned into
     * element positions.
     *
     * @param elements the body; must not be {@code null}
     * @return each bound label mapped to the index of its {@link LabelTarget}
     */
    @Contract(pure = true)
    @NotNull
    private static Map<Label, Integer> boundLabels(@NotNull final List<CodeElement> elements) {
        final Map<Label, Integer> bound = new HashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index) instanceof final LabelTarget target) {
                bound.put(target.label(), index);
            }
        }
        return bound;
    }

    /**
     * Reports whether any range is cut at all.
     *
     * <p>The caller withholds the whole exception table from emission on this answer, so a body
     * with nothing to cut keeps its handlers on their original path.
     *
     * @return {@code true} when at least one injection falls inside a protected range
     */
    @Contract(pure = true)
    boolean splits() {
        return !this.splitAt.isEmpty();
    }

    /**
     * Returns the injection indices ranges are cut around.
     *
     * <p>These are the positions at which the caller has to bind a pause label before the injected
     * code and a resume label after it.
     *
     * @return the indices ranges are cut around
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    Set<Integer> splitAt() {
        return this.splitAt;
    }

    /**
     * Counts the handlers whose range is actually cut, for the wording of {@code AW1131}.
     *
     * @return how many handlers have at least one piece
     */
    @Contract(pure = true)
    int splitHandlers() {
        return (int) this.pieces.stream().filter(handler -> !handler.isEmpty()).count();
    }

    /**
     * Returns the pieces of one handler's range.
     *
     * <p>The ordinal counts {@link ExceptionCatch} elements of the body in order, which is the
     * order a caller that collects them as it walks the body already has them in.
     *
     * @param ordinal the handler's position among the body's exception handlers
     * @return its pieces, or an empty list for a handler that is not cut and for an ordinal this
     *         cut knows nothing about
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    List<Piece> piecesOf(final int ordinal) {
        return ordinal >= 0 && ordinal < this.pieces.size() ? this.pieces.get(ordinal) : List.of();
    }
}
