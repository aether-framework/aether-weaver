package de.splatgames.aether.weaver.idea.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.idea.bytecode.SourceAnchor;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offers weave spots for a target that has never been compiled.
 *
 * <p>The fallback for {@link de.splatgames.aether.weaver.idea.bytecode.SpotFinder}, which reads the target's class
 * file: in a project that has not been built there is none, and without this the only answer to any caret would be
 * the three positional points. Every spot produced here carries
 * {@link de.splatgames.aether.weaver.idea.bytecode.WeaveSpot.Confidence#FROM_SOURCE} and can be told apart from a
 * verified one by that alone.
 *
 * <h2>What a source-only spot deliberately lacks</h2>
 *
 * <ul>
 *   <li><b>An ordinal, and an instruction index.</b> Both are written {@code -1}. An ordinal counted in source order
 *       is a claim about a compiled method nobody has read: the compiler emits calls no source shows and numbers
 *       them its own way, so a number invented here would bind a handler to whatever happened to land on it.
 *   <li><b>A slice.</b> A slice's bounds are two further queries needing ordinals of their own, and nothing here can
 *       verify one.
 *   <li><b>A redirect shape.</b> The descriptor a redirect handler must mirror comes from reading the instruction,
 *       so no spot offered here is redirectable.
 * </ul>
 *
 * <p>What replaces the ordinal is the match count: how many expressions in the method name the same thing. One match
 * needs no ordinal, and several are reported as several, with the row saying that the handler will run at every one.
 * The alternative would be to pick one, which is a guess about which instruction the caret meant.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class SourceSpots {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private SourceSpots() {
        throw new AssertionError("no instances");
    }

    /**
     * Offers the spots the source alone supports for one caret.
     *
     * <p>Three passes, in the order the offers should be read. The anchors the caret stands in come first, marked as
     * read at or around the caret. Then the expressions of the caret's own line, or of the nearest line that has
     * any, marked as read near it — a caret on {@code if (}, on a brace or on the whitespace before a statement
     * stands in no expression at all, and without this pass it is answered with nothing. The three positional points
     * come last and are always present.
     *
     * <p>A spot is offered once per point, selector and line; the same expression reached by both passes is not
     * offered twice.
     *
     * @param target   the method the caret is in; must not be {@code null}
     * @param reading  what the editor read at the caret; must not be {@code null}
     * @param document the document the target belongs to; must not be {@code null}
     * @param spelling how each selector is written; must not be {@code null}
     * @return the spots to offer, never empty because the positional ones are always added
     */
    @Unmodifiable
    @NotNull
    public static List<WeaveSpot> at(@NotNull final PsiMethod target,
                                     @NotNull final SpotFinder.Reading reading,
                                     @NotNull final Document document,
                                     @NotNull final TargetOperations.Spelling spelling) {
        final List<WeaveSpot> spots = new ArrayList<>();
        final Set<String> taken = new LinkedHashSet<>();
        for (final SourceAnchor anchor : reading.anchors()) {
            add(spots, taken, target, anchor, document, spelling, true);
        }
        // A caret is not always inside an expression. It sits on `if (`, on a brace, on the
        // whitespace before a statement — which is where a reader's cursor spends most of its life.
        // The bytecode side has always answered that with the caret's line; without the same
        // fallback here the source side answered it with nothing, and "nothing" is three positional
        // points and a dialog that looks like it only knows HEAD.
        for (final SourceAnchor anchor : aroundTheCaret(target, reading.caretLine(), document)) {
            add(spots, taken, target, anchor, document, spelling, false);
        }
        spots.addAll(SpotFinder.positions());
        return List.copyOf(spots);
    }

    /**
     * Returns the anchors of the caret's line, or of the nearest line that has any.
     *
     * <p>Nearness is measured forwards first: with expressions both above and below, the ones below win, which is
     * what a caret on a blank line before a statement is pointing at. A caret past the last expression falls back to
     * the line above it.
     *
     * @param target    the method to search; must not be {@code null}
     * @param caretLine the one-based line the caret is on
     * @param document  the document supplying the line numbers; must not be {@code null}
     * @return the anchors of one line, and an empty list when the method body holds no expression an anchor can be
     *         read from
     */
    @NotNull
    private static List<SourceAnchor> aroundTheCaret(@NotNull final PsiMethod target,
                                                     final int caretLine,
                                                     @NotNull final Document document) {
        final List<SourceAnchor> everything = nameableIn(target, document);
        final List<SourceAnchor> onTheLine = new ArrayList<>();
        for (final SourceAnchor candidate : everything) {
            if (candidate.covers(caretLine)) {
                onTheLine.add(candidate);
            }
        }
        if (!onTheLine.isEmpty()) {
            return onTheLine;
        }
        final int nearest = nearestLineTo(everything, caretLine);
        final List<SourceAnchor> found = new ArrayList<>();
        for (final SourceAnchor candidate : everything) {
            if (candidate.firstLine() == nearest) {
                found.add(candidate);
            }
        }
        return found;
    }

    /**
     * Returns the line closest to the caret that carries an anchor.
     *
     * <p>Closest in the sense of first below, then last above; the distance is not compared, so a statement one line
     * below the caret and one twenty lines above it are decided by direction alone.
     *
     * @param everything the anchors of the whole method; must not be {@code null}
     * @param caretLine  the one-based line the caret is on
     * @return the first line below the caret that carries an anchor, the last one above it when there is none below,
     *         and {@code 0} when neither exists
     */
    private static int nearestLineTo(@NotNull final List<SourceAnchor> everything,
                                     final int caretLine) {
        int after = Integer.MAX_VALUE;
        int before = 0;
        for (final SourceAnchor candidate : everything) {
            final int line = candidate.firstLine();
            if (line > caretLine && line < after) {
                after = line;
            }
            if (line < caretLine && line > before) {
                before = line;
            }
        }
        return after == Integer.MAX_VALUE ? before : after;
    }

    /**
     * Returns an anchor for every expression in the method an injection point could name.
     *
     * <p>Four expression kinds are collected and each is offered to {@code CaretAnchors}, which drops the ones that
     * name nothing — a reference to a local, or a literal outside the constant grammar. Every anchor carries depth
     * {@code 0}, since none of them was reached by walking out from the caret.
     *
     * @param target   the method to search; must not be {@code null}
     * @param document the document supplying the line numbers; must not be {@code null}
     * @return the anchors, and an empty list for a method with no body
     */
    @NotNull
    private static List<SourceAnchor> nameableIn(@NotNull final PsiMethod target,
                                                 @NotNull final Document document) {
        final PsiCodeBlock body = target.getBody();
        final List<SourceAnchor> found = new ArrayList<>();
        if (body == null) {
            return found;
        }
        for (final PsiElement candidate : PsiTreeUtil.findChildrenOfAnyType(body,
                PsiMethodCallExpression.class, PsiNewExpression.class, PsiReferenceExpression.class,
                PsiLiteralExpression.class)) {
            final SourceAnchor anchor = CaretAnchors.describe(candidate, document);
            if (anchor != null) {
                found.add(anchor);
            }
        }
        return found;
    }

    /**
     * Offers every point one anchor supports, unless the same offer was made already.
     *
     * <p>An anchor that no selector can be written for — a return, or a call the editor could not resolve far enough
     * — contributes nothing rather than an offer that would not compile into a working annotation. Identity is the
     * point, the selector and the line together, so the same call reached first as an anchor and again by the line
     * pass is offered once, keeping the wording of the first pass.
     *
     * @param spots      the offers accumulated so far; must not be {@code null}
     * @param taken      the identities already offered; must not be {@code null}
     * @param target     the method being woven, searched to count the matches; must not be {@code null}
     * @param anchor     the anchor to offer; must not be {@code null}
     * @param document   the document supplying the line numbers; must not be {@code null}
     * @param spelling   how the selector is written; must not be {@code null}
     * @param atTheCaret whether the anchor came from the caret's own surroundings rather than from a nearby line
     */
    private static void add(@NotNull final List<WeaveSpot> spots,
                            @NotNull final Set<String> taken,
                            @NotNull final PsiMethod target,
                            @NotNull final SourceAnchor anchor,
                            @NotNull final Document document,
                            @NotNull final TargetOperations.Spelling spelling,
                            final boolean atTheCaret) {
        final String selector = TargetOperations.selectorFor(anchor, spelling);
        if (selector == null) {
            return;
        }
        final int matches = countIn(target, anchor, document);
        for (final Point point : SpotFinder.pointsFor(anchor.kind())) {
            if (taken.add(point.name() + '|' + selector + '|' + anchor.firstLine())) {
                spots.add(spotFor(point, anchor, selector, matches, atTheCaret));
            }
        }
    }

    /**
     * Builds one offer for a point and an anchor.
     *
     * <p>The operation is a stand-in rather than something read from a class file: it carries the selector as both
     * its target and its label, {@code -1} for the ordinal and the index, and no redirect descriptor.
     *
     * @param point      the injection point being offered; must not be {@code null}
     * @param anchor     the anchor it was read from; must not be {@code null}
     * @param selector   the selector the annotation would carry; must not be {@code null}
     * @param matches    how many expressions of the method name the same thing
     * @param atTheCaret whether the anchor came from the caret's own surroundings
     * @return the offer, with the anchor's first line and no narrowed form
     */
    @NotNull
    private static WeaveSpot spotFor(@NotNull final Point point,
                                     @NotNull final SourceAnchor anchor,
                                     @NotNull final String selector,
                                     final int matches,
                                     final boolean atTheCaret) {
        final TargetOperations.Operation operation =
                new TargetOperations.Operation(point, selector, -1, -1, selector, null);
        return new WeaveSpot(point, operation, null, anchor.firstLine(), matches,
                WeaveSpot.Confidence.FROM_SOURCE, SpotFinder.whatOf(point, operation),
                whyFor(anchor, matches, atTheCaret), null);
    }

    /**
     * Phrases why an offer is being made.
     *
     * <p>Says where it was read — at the caret, around it for an enclosing expression, near it for one taken from a
     * neighbouring line — and what the match count means for the annotation being written: one match binds
     * unambiguously and a build that later finds none fails rather than binding elsewhere, while several mean the
     * handler runs at every one, since without a class file they cannot be told apart.
     *
     * @param anchor     the anchor the offer was read from; must not be {@code null}
     * @param matches    how many expressions of the method name the same thing
     * @param atTheCaret whether the anchor came from the caret's own surroundings
     * @return the wording, to be read next to the offer itself
     */
    @NotNull
    private static String whyFor(@NotNull final SourceAnchor anchor,
                                 final int matches,
                                 final boolean atTheCaret) {
        final String where = !atTheCaret
                ? "read from the source near the caret"
                : anchor.depth() > 0
                        ? "read from the source around the caret"
                        : "read from the source at the caret";
        return matches == 1
                ? where + "; the only one this method shows, and the build fails rather than "
                        + "binding elsewhere"
                : where + "; one of " + matches + " this method shows — without a class file they "
                        + "cannot be told apart, so the handler runs at every one";
    }

    /**
     * Counts the expressions of the method that name the same thing as the anchor.
     *
     * <p>This is what stands in for an ordinal: the number of instructions the written selector will match. Counted
     * over the whole body rather than the caret's line, because that is the region the selector will be resolved
     * over.
     *
     * @param target   the method to search; must not be {@code null}
     * @param anchor   the anchor to count; must not be {@code null}
     * @param document the document supplying the line numbers; must not be {@code null}
     * @return the number of matching expressions, and {@code 1} for a method with no body or one whose own anchor
     *         could not be re-derived, since an offer claiming zero matches would be an offer to write a selector
     *         that binds to nothing
     */
    private static int countIn(@NotNull final PsiMethod target,
                               @NotNull final SourceAnchor anchor,
                               @NotNull final Document document) {
        final PsiCodeBlock body = target.getBody();
        if (body == null) {
            return 1;
        }
        int found = 0;
        for (final PsiElement candidate : PsiTreeUtil.findChildrenOfAnyType(body,
                PsiMethodCallExpression.class, PsiNewExpression.class,
                PsiReferenceExpression.class, PsiLiteralExpression.class)) {
            if (namesTheSameThing(CaretAnchors.describe(candidate, document), anchor)) {
                found++;
            }
        }
        return Math.max(found, 1);
    }

    /**
     * Reports whether two anchors would be matched by one selector.
     *
     * <p>Compares what a selector can express — kind, owner, name, descriptor, constant — and neither the lines nor
     * the occurrence, which are exactly what distinguishes two expressions a selector cannot tell apart.
     *
     * @param candidate the anchor read from another expression, or {@code null} when it named nothing
     * @param anchor    the anchor being counted; must not be {@code null}
     * @return {@code true} when both name the same member, type or value
     */
    private static boolean namesTheSameThing(@Nullable final SourceAnchor candidate,
                                             @NotNull final SourceAnchor anchor) {
        return candidate != null
                && candidate.kind() == anchor.kind()
                && java.util.Objects.equals(candidate.owner(), anchor.owner())
                && java.util.Objects.equals(candidate.name(), anchor.name())
                && java.util.Objects.equals(candidate.descriptor(), anchor.descriptor())
                && java.util.Objects.equals(candidate.constant(), anchor.constant());
    }
}
