package de.splatgames.aether.weaver.api.model;

import de.splatgames.aether.weaver.api.select.MemberSelector;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/**
 * One injection declaration, read off a handler and ready to be planned.
 *
 * <p>This is what {@link de.splatgames.aether.weaver.api.Inject},
 * {@link de.splatgames.aether.weaver.api.Redirect} and
 * {@link de.splatgames.aether.weaver.api.Wrap} become once a weave class has been read, and it is
 * the unit everything downstream works in: one specification names one handler, one target method
 * and one or more positions inside it. A handler carrying two {@code @Inject} annotations produces
 * two specifications, and a repeated annotation is flattened back into its occurrences so that a
 * handler with one annotation and a handler with several are not structurally different.
 *
 * <p>Nothing here has been resolved against a target. The selector has been parsed but not matched,
 * the points name searches that have not been run, and the counts are the declaration's own claim
 * about how many positions it expects. Everything the specification says can still turn out to be
 * false of a particular target, and every way it can is reported with a diagnostic rather than an
 * exception.
 *
 * <h2>Finding the target method</h2>
 *
 * <p>{@link #rawMethod()} is the selector as the author wrote it and {@link #method()} is that text
 * parsed. The parsed form is what a target's methods are matched against; the raw text is what
 * every diagnostic quotes, so that a message names what was written rather than a rendering of it.
 *
 * <ul>
 *   <li>No method matches — {@code AW1020}, listing the target's methods.
 *   <li>More than one matches — {@code AW1021}. Add the parameter types, or use the {@code desc:}
 *       form to pin one exactly.
 *   <li>The method matches but has no body — {@code AW1023}.
 *   <li>The method is {@code native} — {@code AW1025}. Inject into the Java method that calls it,
 *       or redirect the call site.
 *   <li>The method is compiler-generated — {@code AW1024}. The injection would work; what it would
 *       not do is survive a recompilation that changes the generated shape.
 * </ul>
 *
 * <p>A weave may name several targets, in which case one specification is planned once per target
 * and each resolution is independent.
 *
 * <h2>Points and slices</h2>
 *
 * <p>{@link #points()} is never empty, and a declaration that names none is refused when the weave
 * class is read, reported as {@code AW1043}. Each point is searched separately and their results
 * are then pooled: two points of one specification that resolve to the same instruction count once,
 * not twice, and the handler is emitted there once.
 *
 * <p>{@link #slices()} holds the regions those points may narrow to. They are matched to points by
 * identifier through {@link #sliceFor(PointSpec)}, and two slices of one specification may not
 * share an identifier because the lookup would have two answers.
 *
 * <h2>How many matches count as success</h2>
 *
 * <p>{@link #require()} is the fewest matches that count as success and {@link #allow()} the most.
 * Neither reads the way it looks.
 *
 * <p>{@link #allow()} of {@code 0} imposes no upper bound rather than permitting no match. An upper
 * bound exists so that a target gaining a second matching call is an error rather than a silent
 * doubling of whatever the handler does; exceeding it is reported as {@code AW1044}, and the remedy
 * is an ordinal or a slice.
 *
 * <p>{@link #require()} of {@code 0} means no match is required — but a specification parsed from a
 * class file only ever carries {@code 0} when the author wrote it explicitly. A class file records
 * only the elements that were written, so an omitted {@code require} is distinguishable from an
 * explicit {@code 0}, and the omitted one becomes {@code 1}. Matching fewer than required is
 * reported as {@code AW1043}. The annotation processor's compile-time reading of the same
 * annotation does not make this distinction: an omitted {@code require} and an explicit {@code 0}
 * are both read as {@code 0} and recorded as such in the manifest it emits. Callers building a
 * specification directly get no such treatment either: the value passed to the constructor is the
 * value used.
 *
 * <p>A specification naming a {@link #group()} is accounted differently. Its matches are added to
 * that group's total and its own {@link #require()} and {@link #allow()} are not checked at all, so
 * that several declarations can answer for one another where any one of them alone would fail. A
 * group name that the weave class does not declare leaves the specification unaccounted entirely,
 * with no diagnostic — see {@link GroupSpec}.
 *
 * <h2>Identity</h2>
 *
 * <p>{@link #id()} is never blank. When the annotation leaves it empty the weave class reader
 * derives one as the handler's {@link HandlerRef#describe()}, a {@code #}, and the kind's
 * identifier, for example
 * {@code com.acme.LedgerWeave.onCharge(Ljava/math/BigDecimal;)V#inject}. It is what diagnostics
 * name the injection by and what an explain report keys resolutions on; it is not required to be
 * unique and nothing enforces that it is.
 *
 * <h2>Capturing the matched call's result</h2>
 *
 * <p>{@link #capturesResult()} says the handler's first parameter is annotated
 * {@link de.splatgames.aether.weaver.api.Result} and therefore receives what the matched call
 * produced. It belongs at {@link de.splatgames.aether.weaver.api.Point#INVOKE_AFTER} of a call that
 * returns something; a position that does not follow a call, or that follows one returning
 * {@code void}, is reported as {@code AW1104}. The eleven-argument constructor leaves it
 * {@code false}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * InjectorSpec spec = new InjectorSpec(
 *         InjectorKind.INJECT,
 *         new HandlerRef(ClassDesc.of("com.acme.LedgerWeave"), "onCharge",
 *                 MethodTypeDesc.ofDescriptor("()V"), Set.of(AccessFlag.STATIC)),
 *         "charge(java.math.BigDecimal)",
 *         MemberSelector.parse("charge(java.math.BigDecimal)", MemberKind.METHOD),
 *         List.of(PointSpec.builtIn(Point.HEAD).build()),
 *         List.of(),
 *         "audit-charge",
 *         1,                 // require: at least one match
 *         0,                 // allow: no upper bound
 *         "",                // accounted on its own rather than in a group
 *         List.of());
 *
 * spec.isBounded();      // false
 * spec.isInAGroup();     // false
 * spec.accepts(3);       // true
 * spec.accepts(0);       // false
 * }</pre>
 *
 * @param kind           what the declaration does at the position it matched
 * @param handler        the method control is handed to
 * @param rawMethod      the target-method selector as written; never blank
 * @param method         that selector parsed
 * @param points         the positions to search for; never empty, held as an unmodifiable copy
 * @param slices         the regions those points may narrow to, with distinct identifiers, held as
 *                       an unmodifiable copy
 * @param id             the name diagnostics refer to this injection by; never blank
 * @param require        the fewest matches that count as success; not checked when {@code group} is
 *                       set
 * @param allow          the most matches that count as success, or {@code 0} for no upper bound
 * @param group          the group this declaration is accounted in, or the empty string to be
 *                       accounted alone
 * @param locals         the handler parameters bound to the target's local variables, held as an
 *                       unmodifiable copy
 * @param capturesResult whether the handler's first parameter takes the matched call's result
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Inject
 * @see de.splatgames.aether.weaver.api.spi.PlanEntryView
 */
public record InjectorSpec(InjectorKind kind,
                           HandlerRef handler,
                           String rawMethod,
                           MemberSelector method,
                           @Unmodifiable List<PointSpec> points,
                           @Unmodifiable List<SliceSpec> slices,
                           String id,
                           int require,
                           int allow,
                           String group,
                           @Unmodifiable List<LocalSpec> locals,
                           boolean capturesResult) {

    /**
     * Creates a specification whose handler does not capture the matched call's result.
     *
     * <p>Equivalent to the canonical constructor with {@code capturesResult} set to {@code false},
     * and validates identically.
     *
     * @param kind      what the declaration does at the position it matched
     * @param handler   the method control is handed to
     * @param rawMethod the target-method selector as written
     * @param method    that selector parsed
     * @param points    the positions to search for; must not be empty
     * @param slices    the regions those points may narrow to
     * @param id        the name diagnostics refer to this injection by
     * @param require   the fewest matches that count as success
     * @param allow     the most matches that count as success, or {@code 0} for no upper bound
     * @param group     the group this declaration is accounted in, or the empty string
     * @param locals    the handler parameters bound to the target's local variables
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException under the same conditions as the canonical constructor
     */
    public InjectorSpec(final InjectorKind kind,
                        final HandlerRef handler,
                        final String rawMethod,
                        final MemberSelector method,
                        @Unmodifiable final List<PointSpec> points,
                        @Unmodifiable final List<SliceSpec> slices,
                        final String id,
                        final int require,
                        final int allow,
                        final String group,
                        @Unmodifiable final List<LocalSpec> locals) {
        this(kind, handler, rawMethod, method, points, slices, id, require, allow, group, locals,
                false);
    }

    /**
     * Checks the declaration and takes unmodifiable copies of the three lists.
     *
     * <p>Only what can be decided without a target is decided here. A bound that no count could
     * satisfy is refused outright, because reporting it later would name a target that has nothing
     * to do with the mistake; a bound that is merely not met by a particular target is a
     * diagnostic.
     *
     * @throws NullPointerException     if any reference argument is {@code null}, or if any of the
     *                                  three lists holds a {@code null} element
     * @throws IllegalArgumentException if {@code rawMethod} or {@code id} is blank, if
     *                                  {@code points} is empty, if {@code require} or {@code allow}
     *                                  is negative, if {@code allow} is non-zero and below
     *                                  {@code require}, or if two slices share an identifier
     */
    public InjectorSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(rawMethod, "rawMethod");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        if (rawMethod.isBlank()) {
            throw new IllegalArgumentException("the target-method selector must not be blank");
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException("an injection id must not be blank");
        }
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        slices = List.copyOf(Objects.requireNonNull(slices, "slices"));
        locals = List.copyOf(Objects.requireNonNull(locals, "locals"));
        if (points.isEmpty()) {
            throw new IllegalArgumentException(
                    "injection \"" + id + "\" declares no injection point");
        }
        if (require < 0 || allow < 0) {
            throw new IllegalArgumentException(
                    "injection \"" + id + "\" has a negative bound: require=" + require
                            + ", allow=" + allow);
        }
        if (allow != 0 && allow < require) {
            throw new IllegalArgumentException(
                    "injection \"" + id + "\" can never be satisfied: require=" + require
                            + " > allow=" + allow);
        }
        final long distinct = slices.stream().map(SliceSpec::id).distinct().count();
        if (distinct != slices.size()) {
            throw new IllegalArgumentException(
                    "injection \"" + id + "\" declares two slices with the same id, so a query "
                            + "referring to it would be ambiguous");
        }
    }

    /**
     * Returns the slice a point of this declaration searches.
     *
     * <p>The first slice whose {@link SliceSpec#id()} equals the point's {@link PointSpec#slice()},
     * compared literally. A point that names no slice therefore finds the unnamed slice, which is
     * what makes one slice and one point work with no identifiers on either side.
     *
     * <p>{@code null} means the point searches the whole method. That is the answer both when the
     * declaration has no slices at all and when the point names one that does not exist, and the
     * two are not distinguished: a misspelt reference silently widens the search rather than
     * failing, and the first visible sign is an ordinal counting from somewhere unexpected.
     *
     * @param point the point whose slice reference to look up; must not be {@code null}
     * @return the slice to search, or {@code null} to search the whole method
     * @throws NullPointerException if {@code point} is {@code null}
     */
    @Contract(pure = true)
    public @Nullable SliceSpec sliceFor(@NotNull final PointSpec point) {
        final String reference = Objects.requireNonNull(point, "point").slice();
        for (final SliceSpec slice : this.slices) {
            if (slice.matches(reference)) {
                return slice;
            }
        }
        return null;
    }

    /**
     * Reports whether an upper bound on matches was declared.
     *
     * @return {@code true} when {@link #allow()} is not {@code 0}
     */
    @Contract(pure = true)
    public boolean isBounded() {
        return this.allow != 0;
    }

    /**
     * Reports whether this declaration is accounted as part of a group.
     *
     * <p>When {@code true}, {@link #require()} and {@link #allow()} are not checked for this
     * declaration; the group's total is checked instead.
     *
     * @return {@code true} when {@link #group()} is not the empty string
     */
    @Contract(pure = true)
    public boolean isInAGroup() {
        return !this.group.isEmpty();
    }

    /**
     * Reports whether a match count satisfies this declaration's own bounds.
     *
     * <p>Ignores {@link #group()}: this is the answer for a declaration accounted on its own, and a
     * grouped declaration's count is offered to {@link GroupSpec#accepts(int)} instead.
     *
     * @param matched the number of distinct positions this declaration matched
     * @return {@code true} when the count is at least {@link #require()} and, unless
     *         {@link #allow()} is {@code 0}, at most {@link #allow()}
     */
    @Contract(pure = true)
    public boolean accepts(final int matched) {
        return matched >= this.require && (this.allow == 0 || matched <= this.allow);
    }
}
