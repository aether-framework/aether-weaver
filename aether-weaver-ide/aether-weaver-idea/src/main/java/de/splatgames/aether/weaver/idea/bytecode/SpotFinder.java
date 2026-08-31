package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.spi.MethodView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what the editor read at the caret into the places a handler could be attached.
 *
 * <p>The two halves of the question meet here: a {@link Reading} says what the author is pointing
 * at in the editor's terms, {@link TargetOperations} says what the compiled method actually
 * contains, and this decides which of the second answers the first. Each answer carries a
 * {@link WeaveSpot.Confidence} rather than being silently ranked, because a spot found by agreeing
 * with the caret's own expression and a spot found because it was the nearest thing to the caret
 * are worth different amounts and the annotation they would produce looks the same.
 *
 * <h2>Order</h2>
 *
 * <p>Offers are made in three passes, and the order they are made in is the order they are
 * returned in: the instructions an anchor agreed with, then the instructions around the caret, then
 * the positions that exist in every method. A spot already offered is never offered again, so the
 * first pass that reaches an instruction is the one that gets to describe it — an instruction
 * matched exactly is not offered a second time as merely being on the caret's line.
 *
 * <p>An answer is always produced. The last pass adds {@link Point#HEAD}, {@link Point#RETURN} and
 * {@link Point#TAIL}, which need no match, so an author who asked "here" is never told there is
 * nowhere.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class SpotFinder {

    /**
     * The most spots {@link #at} offers.
     *
     * <p>Applied after the passes have run, so the positional offers can be cut off by it when the
     * caret's surroundings alone filled the list.
     */
    private static final int MOST = 12;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private SpotFinder() {
        throw new AssertionError("no instances");
    }

    /**
     * What the editor read at the caret.
     *
     * <p>The anchors run outwards: the first is the expression the caret is on and each one after
     * it encloses the one before, so a caret inside an argument can still be answered with the call
     * it is an argument to.
     *
     * <p>The region, when there is one, is the nearest loop, {@code try}, {@code switch},
     * {@code synchronized} or {@code if} the caret stands in; a caret in a plain method body, with
     * none of those enclosing it, has no region. It is what makes a slice possible: an ordinal
     * counted inside a region cannot be moved by an edit outside it.
     *
     * @param anchors         what the editor could read, innermost first
     * @param caretLine       the one-based line the caret is on
     * @param regionFirstLine the first line of the enclosing block, or {@code 0} when there is none
     * @param regionLastLine  the last line of the enclosing block, or {@code 0} when there is none
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Reading(@NotNull @Unmodifiable List<SourceAnchor> anchors,
                          int caretLine,
                          int regionFirstLine,
                          int regionLastLine) {

        /**
         * Reports whether the caret stands in a block a slice could be built from.
         *
         * @return {@code true} when both region lines are set and describe a non-empty range
         */
        @Contract(pure = true)
        public boolean hasRegion() {
            return this.regionFirstLine > 0 && this.regionLastLine >= this.regionFirstLine;
        }
    }

    /**
     * Reports the spots worth offering for one caret.
     *
     * <p>Confined to the caret's surroundings: the instructions the anchors agreed with, the
     * instructions of the caret's line or of the nearest line that has any, and the three
     * positions. The result is truncated to at most twelve, so a method with a great many
     * operations does not answer a caret with a list nobody reads.
     *
     * @param compiled the compiled method the caret is in; must not be {@code null}
     * @param reading  what the editor read at the caret; must not be {@code null}
     * @param spelling how each selector is written; must not be {@code null}
     * @return the spots to offer, in the order they were found and never empty
     * @throws NullPointerException if {@code compiled} or {@code reading} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<WeaveSpot> at(@NotNull final MethodView compiled,
                                     @NotNull final Reading reading,
                                     @NotNull final TargetOperations.Spelling spelling) {
        final Search search = new Search(compiled, reading, spelling);
        search.addExact();
        search.addAroundTheCaret();
        search.addPositions();
        return search.found(MOST);
    }

    /**
     * Reports every spot in the method, ordered by how close it is to the caret.
     *
     * <p>The same three passes as {@link #at}, but the second offers the whole method rather than
     * one line, sorted by distance from the caret and, at equal distance, by position in the body.
     * Nothing is truncated, so this is the answer for a list the author scrolls rather than one
     * shown at the caret.
     *
     * @param compiled the compiled method; must not be {@code null}
     * @param reading  what the editor read at the caret; must not be {@code null}
     * @param spelling how each selector is written; must not be {@code null}
     * @return every spot the method offers, the exact matches first and the rest by distance from
     *         the caret; never empty
     * @throws NullPointerException if {@code compiled} or {@code reading} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<WeaveSpot> everywhere(@NotNull final MethodView compiled,
                                             @NotNull final Reading reading,
                                             @NotNull final TargetOperations.Spelling spelling) {
        final Search search = new Search(compiled, reading, spelling);
        search.addExact();
        search.addEverythingInTheMethod();
        search.addPositions();
        return search.found(Integer.MAX_VALUE);
    }

    /**
     * Reports the three spots that exist in every method.
     *
     * <p>Needs no compiled method and no caret, so this is what a caller offers when nothing was
     * compiled: {@link Point#HEAD}, {@link Point#RETURN} and {@link Point#TAIL} depend on no
     * instruction and cannot be wrong about one.
     *
     * @return the three positional spots, each with no operation and no line
     */
    @Unmodifiable
    @NotNull
    public static List<WeaveSpot> positions() {
        final List<WeaveSpot> spots = new ArrayList<>(3);
        for (final Point point : List.of(Point.HEAD, Point.RETURN, Point.TAIL)) {
            spots.add(new WeaveSpot(point, null, null, 0, 1, WeaveSpot.Confidence.POSITION,
                    whatOf(point, null), whyPositional(point, false), null));
        }
        return List.copyOf(spots);
    }

    /**
     * One search, accumulating offers across the passes that make them.
     *
     * <p>Mutable and single-use. The passes have to see what the ones before them offered, both to
     * avoid repeating an instruction and to keep the order they were offered in, which is why this
     * is an object rather than a chain of pure calls.
     *
     * <p>Two caches sit here as well. The constructor reads every operation of the method with its
     * line into {@link #everything}, but {@link #addAroundTheCaret()} enumerates a second time
     * through {@link OperationsAtLine#at}, which does not consult that field; a call to {@link #at}
     * therefore pays for the full enumeration twice. The per-point enumerations in {@link #byPoint}
     * are filled on demand and are genuinely cached, because counting how many instructions share a
     * selector is asked once per offer and resolving is what it costs.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Search {

        /** The compiled method every offer is made about. */
        private final MethodView compiled;

        /** What the editor read at the caret. */
        private final Reading reading;

        /** How each selector is written. */
        private final TargetOperations.Spelling spelling;

        /** Every operation of the method that has a line, read once. */
        private final List<OperationsAtLine.Found> everything;

        /** Enumerations per point, filled on demand and never re-resolved. */
        private final Map<Point, List<TargetOperations.Operation>> byPoint =
                new EnumMap<>(Point.class);

        /**
         * What has already been offered, which is what stops a later pass repeating an earlier
         * one's instruction; insertion-ordered so the set can be reasoned about while debugging.
         */
        private final Set<Placed> taken = new LinkedHashSet<>();

        /** The offers, in the order they were made. */
        private final List<WeaveSpot> spots = new ArrayList<>();

        /**
         * Reads every operation of the method that has a line.
         *
         * @param compiled the compiled method; must not be {@code null}
         * @param reading  what the editor read at the caret; must not be {@code null}
         * @param spelling how each selector is written; must not be {@code null}
         */
        Search(@NotNull final MethodView compiled,
               @NotNull final Reading reading,
               @NotNull final TargetOperations.Spelling spelling) {
            this.compiled = compiled;
            this.reading = reading;
            this.spelling = spelling;
            this.everything = OperationsAtLine.allIn(compiled, spelling);
        }

        /**
         * Offers the instructions the anchors agreed with.
         *
         * <p>Every anchor is tried, innermost first, and each is offered at every point its kind
         * allows, so a call is offered both before and after itself: the two differ in whether the
         * handler sees the result, and that is the author's decision rather than one to be made
         * here. An anchor that names a position rather than an operation is offered as that
         * position, carrying the caret's line so the offer says where it came from.
         */
        void addExact() {
            for (final SourceAnchor anchor : this.reading.anchors()) {
                for (final Point point : pointsFor(anchor.kind())) {
                    if (point == Point.HEAD || point == Point.RETURN || point == Point.TAIL) {
                        addPosition(point, this.reading.caretLine(), WeaveSpot.Confidence.EXACT,
                                whyPositional(point, true));
                        continue;
                    }
                    addOperation(chosenFor(point, anchor), WeaveSpot.Confidence.EXACT, anchor);
                }
            }
        }

        /**
         * Offers the instructions of the caret's line, or of the nearest line that has any.
         *
         * <p>The confidence distinguishes the two: an instruction compiled from the caret's own
         * line is offered as such, and one from another line is offered as the nearest, which is
         * what tells the author the list has moved away from where they were standing.
         */
        void addAroundTheCaret() {
            for (final OperationsAtLine.Found found
                    : OperationsAtLine.at(this.compiled, this.reading.caretLine(), this.spelling)) {
                addOperation(found, found.line() == this.reading.caretLine()
                        ? WeaveSpot.Confidence.SAME_LINE
                        : WeaveSpot.Confidence.NEAREST, null);
            }
        }

        /**
         * Offers every operation of the method, nearest the caret first.
         *
         * <p>Sorted by the absolute distance between the operation's line and the caret's, with
         * ties broken by position in the body. That tie-break does not order every pair: the same
         * element index appears once under {@link Point#INVOKE} and once under
         * {@link Point#INVOKE_AFTER} in {@link #everything}, and that pair compares equal on both
         * keys. The list is nonetheless the same on every run, because {@link List#sort} is a
         * stable sort and leaves such a pair in the order {@link #everything} already held it in.
         */
        void addEverythingInTheMethod() {
            final List<OperationsAtLine.Found> ordered = new ArrayList<>(this.everything);
            ordered.sort((left, right) -> {
                final int byDistance = Integer.compare(
                        Math.abs(left.line() - this.reading.caretLine()),
                        Math.abs(right.line() - this.reading.caretLine()));
                return byDistance != 0
                        ? byDistance
                        : Integer.compare(left.operation().index(), right.operation().index());
            });
            for (final OperationsAtLine.Found found : ordered) {
                addOperation(found, found.line() == this.reading.caretLine()
                        ? WeaveSpot.Confidence.SAME_LINE
                        : WeaveSpot.Confidence.NEAREST, null);
            }
        }

        /**
         * Offers the three positions, so that the list is never empty.
         *
         * <p>A position already offered by {@link #addExact()} is not offered again, and keeps the
         * caret line and the wording it was given there.
         */
        void addPositions() {
            for (final Point point : List.of(Point.HEAD, Point.RETURN, Point.TAIL)) {
                addPosition(point, 0, WeaveSpot.Confidence.POSITION, whyPositional(point, false));
            }
        }

        /**
         * Returns the offers, truncated to a limit.
         *
         * <p>The truncation keeps the front, so what is dropped is what the later passes added:
         * the positions go before an exact match ever does.
         *
         * @param most the most offers to return
         * @return the offers, in the order they were made
         */
        @Unmodifiable
        @NotNull
        List<WeaveSpot> found(final int most) {
            return List.copyOf(this.spots.size() <= most
                    ? this.spots
                    : this.spots.subList(0, most));
        }

        /**
         * Picks the instruction one anchor names, at one point.
         *
         * <p>Only instructions on the anchor's own lines are considered, and they are sorted into
         * three tiers by how much of the anchor they agree with. The best non-empty tier is used
         * whole; a weaker tier is never mixed with a stronger one, so an owner that agrees decides
         * outright over one that only shares a name.
         *
         * <p>The anchor's occurrence then picks within that tier. It is counted in evaluation
         * order, which is the order the compiler emits the calls in rather than the order they read
         * in: {@code send(build(order))} evaluates the inner call first. An occurrence past the end
         * of the tier is clamped rather than dropped, because the author is standing on one of
         * these instructions either way and offering the last of them beats offering nothing.
         *
         * @param point  the point to look for; must not be {@code null}
         * @param anchor what the editor read; must not be {@code null}
         * @return the instruction to offer, or {@code null} when nothing on the anchor's lines
         *         agrees with it
         */
        @Nullable
        private OperationsAtLine.Found chosenFor(@NotNull final Point point,
                                                 @NotNull final SourceAnchor anchor) {
            final List<OperationsAtLine.Found> whole = new ArrayList<>();
            final List<OperationsAtLine.Found> signature = new ArrayList<>();
            final List<OperationsAtLine.Found> named = new ArrayList<>();
            for (final OperationsAtLine.Found found : this.everything) {
                if (found.point() != point || !anchor.covers(found.line())) {
                    continue;
                }
                final TargetOperations.Described described =
                        TargetOperations.describe(this.compiled, found.operation().index());
                switch (tierOf(described, anchor)) {
                    case 3 -> whole.add(found);
                    case 2 -> signature.add(found);
                    case 1 -> named.add(found);
                    default -> {
                        // Not this anchor's instruction.
                    }
                }
            }
            final List<OperationsAtLine.Found> best = !whole.isEmpty()
                    ? whole
                    : !signature.isEmpty() ? signature : named;
            // The occurrence tells two identical calls on one line apart, and it is counted in
            // evaluation order rather than in reading order — `send(build(order))` evaluates the
            // inner call first, and the compiler emits it first. An occurrence past the end of the
            // list is clamped rather than dropped: the author is standing on one of these calls
            // either way, and offering the last of them beats offering nothing.
            return best.isEmpty()
                    ? null
                    : best.get(Math.min(anchor.occurrence(), best.size() - 1));
        }

        /**
         * Offers one instruction, unless it has already been offered at this point.
         *
         * <p>Identity is the point and the instruction's index together, so the same call may be
         * offered once before it and once after it while a second pass reaching it adds nothing.
         *
         * @param found      the instruction to offer, or {@code null} to offer nothing
         * @param confidence how the instruction was arrived at; must not be {@code null}
         * @param anchor     the anchor that named it, or {@code null} when none did
         */
        private void addOperation(@Nullable final OperationsAtLine.Found found,
                                  @NotNull final WeaveSpot.Confidence confidence,
                                  @Nullable final SourceAnchor anchor) {
            if (found == null
                    || !this.taken.add(new Placed(found.point(), found.operation().index()))) {
                return;
            }
            this.spots.add(new WeaveSpot(found.point(), found.operation(), null, found.line(),
                    countOf(found), confidence, whatOf(found.point(), found.operation()),
                    whyOperation(found, confidence, anchor), narrowed(found, confidence, anchor)));
        }

        /**
         * Offers one position, unless that position has already been offered.
         *
         * <p>A position is identified by its point alone, under an index of {@code -1} that no
         * instruction can have, so each of the three is offered at most once however many anchors
         * name it.
         *
         * @param point      the position to offer; must not be {@code null}
         * @param line       the line to show with it, or {@code 0} for an offer that is not tied to
         *                   one
         * @param confidence how the position was arrived at; must not be {@code null}
         * @param why        why it is being offered; must not be {@code null}
         */
        private void addPosition(@NotNull final Point point,
                                 final int line,
                                 @NotNull final WeaveSpot.Confidence confidence,
                                 @NotNull final String why) {
            if (this.taken.add(new Placed(point, -1))) {
                this.spots.add(new WeaveSpot(point, null, null, line, 1, confidence,
                        whatOf(point, null), why, null));
            }
        }

        /**
         * Builds the same offer with its ordinal counted inside the caret's block.
         *
         * <p>Offered only where it buys something: a selector matching one instruction in the whole
         * method needs no slice, and this is refused rather than dressed up as safety by
         * {@link #isAmbiguous(OperationsAtLine.Found)}. Nothing here separately refuses a slice
         * bounding the whole method; that case cannot arise because
         * {@code de.splatgames.aether.weaver.idea.psi.CaretAnchors#regionOf} never names the method
         * body as a region.
         *
         * <p>The narrowed offer is verified like any other: the operation is re-enumerated under
         * the bounds and accepted only when the enumeration still contains this very instruction.
         * Its ordinal is then the one counted inside the region, which is how the engine counts it
         * beside a slice — an absolute ordinal written next to a slice names a different
         * instruction, and that is the mistake a slice exists to avoid.
         *
         * @param found      the instruction being offered; must not be {@code null}
         * @param confidence the confidence the narrowed offer inherits; must not be {@code null}
         * @param anchor     the anchor that named it, or {@code null} when none did
         * @return the narrowed offer, or {@code null} when the caret is in no block, the selector
         *         is unambiguous already, no pair of calls in the block brackets the instruction,
         *         or the bounded enumeration no longer contains it
         */
        @Nullable
        private WeaveSpot narrowed(@NotNull final OperationsAtLine.Found found,
                                   @NotNull final WeaveSpot.Confidence confidence,
                                   @Nullable final SourceAnchor anchor) {
            if (!this.reading.hasRegion() || !isAmbiguous(found)) {
                return null;
            }
            final TargetOperations.Bounds bounds = boundsAround(found.operation().index());
            if (bounds == null) {
                return null;
            }
            for (final TargetOperations.Operation sliced : TargetOperations.of(this.compiled,
                    found.point(), this.spelling, bounds)) {
                if (sliced.index() == found.operation().index()) {
                    return new WeaveSpot(found.point(), sliced, bounds, found.line(), 1, confidence,
                            whatOf(found.point(), sliced),
                            whyOperation(found, confidence, anchor) + "; counted inside "
                                    + bounds.from().label() + " … " + bounds.to().label()
                                    + ", so an edit outside that block cannot move it", null);
                }
            }
            return null;
        }

        /**
         * Reports whether the instruction's selector matches more than one operation.
         *
         * <p>The test for whether narrowing would buy anything: a selector matching once is pinned
         * by itself, and a slice around it would only add something else that could break.
         *
         * @param found the instruction being offered; must not be {@code null}
         * @return {@code true} when at least two operations of this point share the selector
         */
        private boolean isAmbiguous(@NotNull final OperationsAtLine.Found found) {
            int matches = 0;
            for (final TargetOperations.Operation candidate : operationsOf(found.point())) {
                if (candidate.target().equals(found.operation().target())) {
                    matches++;
                }
            }
            return matches > 1;
        }

        /**
         * Finds a pair of calls inside the caret's block that brackets an instruction.
         *
         * <p>Calls are the only bound used, because a call is the operation a selector names most
         * reliably. The opening bound is the first call of the block at or before the instruction
         * and the closing bound the first one after it, so both are inside the block the author can
         * see, and an edit outside it cannot move what the slice counts.
         *
         * @param index the element index the region must bracket
         * @return the bounds, or {@code null} when the block holds no call before the instruction,
         *         none after it, or the two do not straddle it
         */
        @Nullable
        private TargetOperations.Bounds boundsAround(final int index) {
            TargetOperations.Operation from = null;
            TargetOperations.Operation to = null;
            for (final TargetOperations.Operation call : operationsOf(Point.INVOKE)) {
                final int line = lineOf(call);
                if (line < this.reading.regionFirstLine() || line > this.reading.regionLastLine()) {
                    continue;
                }
                if (call.index() <= index) {
                    from = from == null ? call : from;
                } else if (to == null) {
                    to = call;
                }
            }
            return from == null || to == null || from.index() >= to.index()
                    ? null
                    : new TargetOperations.Bounds(from, to);
        }

        /**
         * Returns the method's operations for one point, enumerating them at most once.
         *
         * <p>Each enumeration resolves every selector it proposes, and counting the matches behind
         * an offer asks for the same list once per offer.
         *
         * @param point the point to enumerate for; must not be {@code null}
         * @return the operations, in code order
         */
        @NotNull
        private List<TargetOperations.Operation> operationsOf(@NotNull final Point point) {
            return this.byPoint.computeIfAbsent(point,
                    candidate -> TargetOperations.of(this.compiled, candidate, this.spelling));
        }

        /**
         * Reports the line one operation was compiled from.
         *
         * @param operation the operation; must not be {@code null}
         * @return the line, or {@code 0} when no line entry precedes the instruction, which puts it
         *         outside every region
         */
        private int lineOf(@NotNull final TargetOperations.Operation operation) {
            final List<Integer> lines =
                    CompiledLines.of(this.compiled, List.of(operation.index()));
            return lines.isEmpty() ? 0 : lines.getFirst();
        }

        /**
         * Writes why an instruction is being offered.
         *
         * <p>Two clauses: how the instruction was found, and how it will be pinned. The second is
         * the one that matters when the offer is accepted, because a selector matching several
         * instructions is written down with an ordinal and the author has to be told which of them
         * they are getting.
         *
         * <p>An exact match distinguishes the expression the caret is on from one it sits inside,
         * which is the difference between an argument and the call it is an argument to.
         *
         * @param found      the instruction being offered; must not be {@code null}
         * @param confidence how it was arrived at; must not be {@code null}
         * @param anchor     the anchor that named it, or {@code null} when none did
         * @return the explanation, phrased to be read next to the offer
         */
        @NotNull
        private String whyOperation(@NotNull final OperationsAtLine.Found found,
                                    @NotNull final WeaveSpot.Confidence confidence,
                                    @Nullable final SourceAnchor anchor) {
            final StringBuilder why = new StringBuilder(96);
            why.append(switch (confidence) {
                case EXACT -> anchor != null && anchor.depth() > 0
                        ? "the expression the caret sits inside"
                        : "the expression at the caret";
                case SAME_LINE -> "also on the caret's line";
                case NEAREST -> "nothing was on the caret's line; this is the nearest that has "
                        + "anything";
                // Nothing here produces either: a spot read from the source is built by SourceSpots,
                // which never has an instruction to explain, and a position is never an operation.
                case FROM_SOURCE -> "read from the source rather than from the class file";
                case POSITION -> "a position rather than an operation";
            });
            final int matches = countOf(found);
            if (matches > 1) {
                why.append("; number ").append(found.operation().ordinal() + 1).append(" of ")
                        .append(matches).append(" in the method, pinned by ordinal");
            } else {
                why.append("; the only one in the method");
            }
            return why.toString();
        }

        /**
         * Counts how many operations of this point share the instruction's selector.
         *
         * @param found the instruction being offered; must not be {@code null}
         * @return the number of matches, never below {@code 1}, since the instruction being counted
         *         is itself one of them
         */
        private int countOf(@NotNull final OperationsAtLine.Found found) {
            int matches = 0;
            for (final TargetOperations.Operation candidate : operationsOf(found.point())) {
                if (candidate.target().equals(found.operation().target())) {
                    matches++;
                }
            }
            return Math.max(matches, 1);
        }
    }

    /**
     * The identity of an offer already made.
     *
     * <p>The point belongs in the key because one instruction is offered both before and after
     * itself, and those are two different places to attach a handler.
     *
     * @param point the point the offer was made for
     * @param index the instruction's element index, or {@code -1} for a position, which no
     *              instruction can collide with
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Placed(@NotNull Point point, int index) {
    }

    /**
     * Reports the points an expression of this kind can be woven at.
     *
     * <p>A call yields two, before and after; a return yields both the point that catches every way
     * out and the one that catches only the last.
     *
     * @param kind what the editor read; must not be {@code null}
     * @return the points to offer, in the order they should be offered
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static List<Point> pointsFor(@NotNull final SourceAnchor.Kind kind) {
        return switch (kind) {
            case CALL -> List.of(Point.INVOKE, Point.INVOKE_AFTER);
            case INSTANTIATION -> List.of(Point.NEW);
            case FIELD_ACCESS -> List.of(Point.FIELD);
            case CONSTANT -> List.of(Point.CONSTANT);
            case RETURN -> List.of(Point.RETURN, Point.TAIL);
            case HEAD -> List.of(Point.HEAD);
        };
    }

    /**
     * Scores how well one instruction agrees with one anchor.
     *
     * <p>Three tiers rather than a yes or a no, because the editor's view of a call and the class
     * file's can differ legitimately and a candidate that disagrees about the owner is still a
     * better answer than none.
     *
     * @param described what the instruction says about itself, or {@code null} when it is not a
     *                  kind an anchor can name
     * @param anchor    what the editor read; must not be {@code null}
     * @return {@code 3} for full agreement, {@code 2} or {@code 1} for a partial one, and
     *         {@code 0} for an instruction this anchor does not name
     */
    @Contract(pure = true)
    private static int tierOf(@Nullable final TargetOperations.Described described,
                              @NotNull final SourceAnchor anchor) {
        if (described == null || described.kind() != anchor.kind()) {
            return 0;
        }
        return switch (anchor.kind()) {
            // A constant names nothing but its value, so agreeing on the value is the whole match.
            case CONSTANT -> described.constant().equals(anchor.constant()) ? 3 : 0;
            // A `new` names only a type, so a different type is a different instruction outright.
            case INSTANTIATION -> described.owner().equals(anchor.owner()) ? 3 : 0;
            case CALL, FIELD_ACCESS -> tierOfMember(described, anchor);
            case RETURN, HEAD -> 0;
        };
    }

    /**
     * Scores a call or a field access against an anchor.
     *
     * <p>The name has to agree; nothing else is enough on its own. A descriptor the editor could
     * not determine does not count against a candidate, and one that disagrees drops it to the
     * bottom tier rather than out, because a resolved overload and the instruction can differ over
     * a bridge method while still being the call the author means.
     *
     * @param described what the instruction says about itself; must not be {@code null}
     * @param anchor    what the editor read; must not be {@code null}
     * @return {@code 3} when name, descriptor and owner agree, {@code 2} when the owner is unknown
     *         or differs, {@code 1} when the descriptor differs, and {@code 0} when the name does
     */
    @Contract(pure = true)
    private static int tierOfMember(@NotNull final TargetOperations.Described described,
                                    @NotNull final SourceAnchor anchor) {
        if (anchor.name() == null || !described.name().equals(anchor.name())) {
            return 0;
        }
        // A descriptor the editor could not determine does not count against a candidate; one that
        // disagrees drops it to the bottom tier rather than out, because a resolved overload and the
        // instruction can differ over a bridge method while still being the call the author means.
        final boolean signature =
                anchor.descriptor() == null || described.descriptor().equals(anchor.descriptor());
        if (!signature) {
            return 1;
        }
        return anchor.owner() != null && described.owner().equals(anchor.owner()) ? 3 : 2;
    }

    /**
     * Writes what a spot offers, phrased for a list the author chooses from.
     *
     * <p>Reads as a place rather than as a point name: the three positions describe what they catch
     * instead of naming themselves. {@link Point#INVOKE} and {@link Point#INVOKE_AFTER} are
     * prefixed with whether the handler runs before or after the call; every other operation is
     * prefixed with {@code "at "} instead.
     *
     * @param point     the point being offered; must not be {@code null}
     * @param operation the operation being offered, or {@code null} for a position
     * @return the offer
     * @throws NullPointerException if {@code point} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String whatOf(@NotNull final Point point,
                                 @Nullable final TargetOperations.Operation operation) {
        return switch (point) {
            case HEAD -> "at the head, before the method's own code";
            case RETURN -> "at every return";
            case TAIL -> "at the last return";
            case INVOKE -> "before " + labelOf(operation);
            case INVOKE_AFTER -> "after " + labelOf(operation);
            default -> "at " + labelOf(operation);
        };
    }

    /**
     * Names the operation inside an offer.
     *
     * @param operation the operation, or {@code null} for a position
     * @return the operation's label, or the words used where there is no operation to name
     */
    @Contract(pure = true)
    @NotNull
    private static String labelOf(@Nullable final TargetOperations.Operation operation) {
        return operation == null ? "this position" : operation.label();
    }

    /**
     * Writes why a position is being offered.
     *
     * <p>A position the caret asked for is explained by the caret; one offered because it is always
     * there says so, and says what it does not catch — {@link Point#TAIL} is the last return only,
     * which is not every way out of a method.
     *
     * @param point   the position being offered; must not be {@code null}
     * @param pointed whether the caret is on a return statement, rather than the position being
     *                offered unconditionally
     * @return the explanation, phrased to be read next to the offer
     */
    @Contract(pure = true)
    @NotNull
    private static String whyPositional(@NotNull final Point point, final boolean pointed) {
        if (pointed) {
            return point == Point.RETURN
                    ? "the caret is on a return statement, and this catches every one of them"
                    : "the caret is on a return statement, and this catches the last one only";
        }
        return switch (point) {
            case HEAD -> "always available: the whole method, on the way in";
            case RETURN -> "always available: every way out, including the early ones";
            default -> "always available: the last return only, which is not every way out";
        };
    }
}
