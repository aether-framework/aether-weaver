/**
 * Turns one {@code @At} into the positions in a body that it names.
 *
 * <p>A declaration says where it wants to be woven in four independent parts — a slice, a point, an
 * ordinal and a shift — and this package is where those become element indices. It answers the
 * question and emits nothing; the answer goes back to
 * {@link de.splatgames.aether.weaver.engine.inject.WeavingPipeline}, which built the
 * {@link de.splatgames.aether.weaver.engine.inject.point.PointResolver} it asked and owns the rewrite.
 *
 * <h2>Five stages, in one order</h2>
 *
 * <p>The order is the whole of what {@link de.splatgames.aether.weaver.engine.inject.point.PointResolver}
 * contributes, and every consequence a weave author trips over follows from it: the slice narrows the
 * search, the point finds matches inside the narrowed region, the ordinal picks one of them, the shift
 * moves it, and the safety checks drop what cannot be injected at. Each stage sees the output of the
 * one before, which is why adding a slice renumbers every ordinal in the declaration and why a shift
 * is measured against the slice rather than against the body.
 *
 * <p>A stage that refuses returns nothing at all rather than a partial answer, and the refusal is
 * scoped to the one {@code @At} being resolved: a declaration with several has each resolved
 * separately and their positions merged by the caller, so one losing all of its positions still leaves
 * the others to be woven.
 *
 * <h2>Two coordinate systems</h2>
 *
 * <p>A point returns indices into whatever body it was handed — the slice, when there is one — and
 * marks each position with the kind that says what its index means. Every injector emits
 * <em>before</em> the index it is given, so {@code AFTER_ELEMENT} is translated by adding one element,
 * once, here rather than by every injector separately.
 * {@link de.splatgames.aether.weaver.engine.inject.point.PointResolver#matchedIndexOf} is the way back,
 * and it is the only correct one: a caller walking the instruction stream to ask whether a declaration
 * selected a given instruction has to compare in the point's coordinate, because comparing a resolved
 * index directly finds nothing for an after-the-element point and finds nothing silently.
 *
 * <h2>The points themselves</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints} holds one implementation per
 * {@code Point} constant in a static map, shared by every caller and every weaving thread, which is
 * safe because a point keeps nothing across a call — everything it works on arrives as an argument.
 * They reach the registry through
 * {@link de.splatgames.aether.weaver.engine.inject.CorePlugin}, like any contributed point.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.inject.point.Targets} decides whether a declaration's
 * target names a particular member, type or instruction. Two schemes live there and which one runs
 * depends on whether the target was parsed: a target carrying a selector is matched structurally,
 * component by component, and one carrying only text is matched by text rules. An absent target
 * matches everything, which is the answer for a point whose target is optional. An owner is compared
 * leniently — a simple name matches any package, so {@code Gateway.send} matches
 * {@code com.acme.Gateway.send} — while a member name is compared exactly apart from the wildcard, and
 * an owner written in descriptor form matches nothing but that descriptor.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.inject.point.ModelViews} adapts a parsed class to the
 * read-only views a point is given, so that a point never sees the class-file API's machinery. It
 * copies every list in its constructor and never calls back into the model — but copying a list does
 * not copy what it holds, and the retained elements are the class-file API's own lazily inflated
 * objects, so a caller handing one view to several threads is relying on a guarantee this package does
 * not provide.
 *
 * <h2>Finding a position is not the same as being able to use it</h2>
 *
 * <p>The four stages that locate a position answer only the first question. {@code SiteSafety} answers
 * the second, and each of its checks turns a failure with no useful moment of discovery into a
 * diagnostic: {@code AW1026} where a non-static handler's position falls at or before a
 * constructor's own {@code super()} call, so that {@code this} does not yet exist; {@code AW1105}
 * where the position falls between a {@code new} and the constructor call that completes it, where the
 * stack holds a reference to an object that is not there yet; and {@code AW1130} for a position in
 * code nothing can reach. Such a position is dropped rather than woven, so it never runs, and no other
 * check reports it.
 *
 * <p>A refused position is dropped from the list rather than failing the resolution, so a declaration
 * matching four calls of which one is unreachable is woven three times, reports once, and is accounted
 * as three.
 *
 * <p>Those checks run for {@code @Inject} only. A redirect and a wrap stand in for an operation and
 * therefore need one, so a position on the far side of an instruction is refused for them as
 * {@code AW1061} instead; any other kind passes through untouched.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>{@code AW1101} where no injection point is registered under the identifier written.
 *   <li>{@code AW1043} where a point that requires a target was given none, where a point that
 *       forbids one was given one, and — from the point itself — where the search matched nothing. The
 *       last is the substance of this package's diagnostics: it lists every candidate of the kind that
 *       was searched for, capped at ten, so that a selector and the body it failed against can be read
 *       side by side. A body with no candidate of that kind at all says so instead of listing nothing.
 *   <li>{@code AW1102} where the point refuses the shift the declaration wrote. The point is asked
 *       with whatever was written, {@code NONE} included.
 *   <li>{@code AW1110} where the ordinal is past the number of matches.
 *   <li>{@code AW1120} and {@code AW1121} where a slice's first or last bound locates nothing, and
 *       {@code AW1122} where the last bound resolves before the first. An unlocatable bound is refused
 *       rather than widened, because a slice that quietly became the whole method would change which
 *       instruction every ordinal in the declaration selects. An {@code @At} naming a slice the
 *       declaration does not declare is not refused at all: the search runs over the whole body, as
 *       though no slice had been named.
 *   <li>{@code AW1112} where a {@code BY} shift declares a large offset. It is reported beside the
 *       result rather than instead of it, so the position is still used; {@code AW1111} is the one
 *       that refuses, where the shift moves a position outside the range it was found in, and it
 *       discards every position of that {@code @At} rather than the one that moved.
 *   <li>{@code AW1103}, raised in
 *       {@link de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints} and nowhere else, where a
 *       selector also names something reached through an {@code invokedynamic} — a lambda body, a
 *       method reference, a string concatenation — which an invocation point does not match. It exists
 *       because that omission has no other symptom: ordinary calls matched, the injection succeeded,
 *       the accounting is satisfied, and a place the author named is nonetheless not woven.
 * </ul>
 *
 * <p>One refusal is deliberately silent. A slice bound whose own ordinal is past its own matches
 * answers "no slice" with nothing reported, and the resolver passes that answer on unreported in turn.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.inject.point;
