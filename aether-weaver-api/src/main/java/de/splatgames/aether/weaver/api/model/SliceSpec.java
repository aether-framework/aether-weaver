package de.splatgames.aether.weaver.api.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A named region of the target method, so that an injection point searches part of a body instead
 * of all of it.
 *
 * <p>This is the parsed form of {@link de.splatgames.aether.weaver.api.Slice}. A slice is declared
 * on the injection declaration, alongside the points it applies to, and is joined to a point by
 * identifier rather than by position: a {@link PointSpec} carries the {@link PointSpec#slice()} it
 * wants and {@link InjectorSpec#sliceFor(PointSpec)} looks it up.
 *
 * <h2>The bounds</h2>
 *
 * <p>{@link #from()} and {@link #to()} are ordinary injection points used as positions rather than
 * as matches, and each must resolve to exactly one. That is why the constructor refuses a bound
 * whose {@link PointSpec#matchesAll()} is true: an unnumbered bound would resolve to every match
 * and there would be no answer to which one the region starts at. When the annotation is read, both
 * bounds default their ordinal to {@code 0} — the first match — rather than to the {@code -1} an
 * {@link de.splatgames.aether.weaver.api.At} uses everywhere else.
 *
 * <p>The region runs from the instruction {@link #from()} resolves to, inclusive, up to the one
 * {@link #to()} resolves to, exclusive. An instruction a bound names is therefore inside the region
 * when it is the lower bound and outside it when it is the upper one.
 *
 * <p>Which of a bound's components are consulted depends on the point. The point identifier
 * always says what kind of position to look for, and {@link PointSpec#ordinal()} always picks one
 * of the matches. Which component says which instruction that is depends on whether the bound has
 * a parsed selector: {@link PointSpec#target()} decides it when {@link PointSpec#hasSelector()} is
 * {@code true}, and {@link PointSpec#rawTarget()} otherwise — the case for a
 * {@link de.splatgames.aether.weaver.api.Point#NEW} bound or for a contributed point. In addition,
 * {@link PointSpec#access()} narrows a {@link de.splatgames.aether.weaver.api.Point#FIELD} bound.
 * A {@link de.splatgames.aether.weaver.api.Point#THROW} bound consults neither
 * {@link PointSpec#target()} nor {@link PointSpec#rawTarget()}: every {@code throw} in the region
 * is a candidate regardless of what the bound's target names, and {@link PointSpec#ordinal()}
 * alone picks among them. {@link PointSpec#shift()}, {@link PointSpec#by()} and
 * {@link PointSpec#slice()} are carried on the bound and never read: a bound locates a region
 * rather than searching one, and the position it resolves to is the position the region starts or
 * stops at.
 *
 * <p>When the annotation omits them, {@link #from()} is {@code @At(Point.HEAD)} and {@link #to()}
 * is {@code @At(Point.TAIL)}. An all-default slice is consequently not the same as no slice at all:
 * it runs from the method's first instruction up to but not including the final {@code return}, so
 * a {@link de.splatgames.aether.weaver.api.Point#RETURN} search inside it misses that last return.
 * Omit the slice entirely to search the whole method.
 *
 * <h2>What a slice changes</h2>
 *
 * <p>The search runs over the region only, and every instruction index a point produces is
 * translated back into the whole body afterwards, so a slice is invisible to everything downstream
 * except in two respects.
 *
 * <p>An ordinal counts <em>within the region</em>. Adding a slice to an otherwise unchanged
 * declaration therefore changes which instruction {@link PointSpec#ordinal()} selects, and this is
 * the interaction most likely to be overlooked.
 *
 * <p>A shift may not leave the region. {@link PointSpec#shift()} is applied after the ordinal, and
 * a site moved outside the range it was found in is reported as {@code AW1111} and that point
 * matches nothing; widen the slice or drop the shift.
 *
 * <h2>Failure</h2>
 *
 * <p>A bound that matches nothing is refused rather than ignored, because a slice that cannot be
 * located would silently widen to the whole method: {@code AW1120} for {@link #from()} and
 * {@code AW1121} for {@link #to()}. The same two codes cover a bound naming an injection point that
 * is not registered. A {@link #to()} resolving before {@link #from()} is {@code AW1122}.
 *
 * <h2>Identity, and the slice that is silently absent</h2>
 *
 * <p>{@link #matches(String)} compares identifiers with {@link String#equals(Object)} — no
 * trimming, no case folding. The empty identifier is a real identifier and denotes the unnamed
 * slice, which is what a point with no {@link PointSpec#slice()} of its own asks for; that is what
 * lets the common case of one slice and one point work with no names on either side.
 *
 * <p>Two slices of one declaration may not share an identifier, and the constructor of
 * {@link InjectorSpec} refuses that outright, because the lookup would be ambiguous. Two
 * declarations may use the same identifier independently, because a point only ever looks among the
 * slices of its own declaration. A point naming
 * an identifier that no slice declares is not refused: the lookup finds nothing and the point
 * searches the whole method. A misspelt reference therefore silently widens the search rather than
 * failing, and the symptom is a declaration matching more positions than expected — possibly
 * reported as {@code AW1044}, and otherwise not reported at all.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Inject(method = "process(java.util.List)",
 *         slice = @Slice(id = "afterValidation",
 *                        from = @At(value = Point.INVOKE, target = "#validate")),
 *         at = @At(value = Point.INVOKE, target = "#persist", ordinal = 0,
 *                  slice = "afterValidation"),
 *         require = 1)
 * private static void onPersist(Callback cb) { ... }
 * }</pre>
 *
 * <p>{@code ordinal = 0} here means the first {@code persist} call after the {@code validate} call,
 * not the first in the method.
 *
 * @param id   the identifier points refer to this slice by; the empty string declares the unnamed
 *             slice
 * @param from the region's first position, which must resolve to exactly one instruction
 * @param to   the position the region ends before, which must resolve to exactly one instruction
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Slice
 */
public record SliceSpec(String id, PointSpec from, PointSpec to) {

    /**
     * Checks that both bounds name a single position.
     *
     * <p>The refusal is an unchecked exception rather than a diagnostic. Reading a weave class is
     * otherwise diagnostic-driven, so a slice that cannot be constructed does not merely drop the
     * weave that declared it.
     *
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if either bound has no ordinal, and so would resolve to
     *                                  every match rather than to one position
     */
    public SliceSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.matchesAll() || to.matchesAll()) {
            throw new IllegalArgumentException(
                    "a slice bound must resolve to exactly one position, so both bounds need an "
                            + "ordinal; the parser defaults them to 0");
        }
    }

    /**
     * Reports whether this is the unnamed slice.
     *
     * @return {@code true} when {@link #id()} is the empty string
     */
    @Contract(pure = true)
    public boolean isUnnamed() {
        return this.id.isEmpty();
    }

    /**
     * Reports whether a point's slice reference names this slice.
     *
     * <p>An exact string comparison, and the empty reference matches the unnamed slice rather than
     * matching nothing.
     *
     * @param reference the reference to compare, as written on the point; must not be {@code null}
     * @return {@code true} when the reference equals {@link #id()}
     * @throws NullPointerException if {@code reference} is {@code null}
     */
    @Contract(pure = true)
    public boolean matches(@NotNull final String reference) {
        return this.id.equals(Objects.requireNonNull(reference, "reference"));
    }
}
