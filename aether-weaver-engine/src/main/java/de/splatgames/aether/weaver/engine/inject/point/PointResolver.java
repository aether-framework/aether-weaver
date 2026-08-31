package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns one {@code @At} into the positions in a body that it names.
 *
 * <p>Five stages, in this order, and the order is the whole of what this class contributes: the
 * slice narrows the search, the point finds matches inside it, the ordinal picks one of them, the
 * shift moves it, and the safety checks drop what cannot be injected at. Each stage sees the output
 * of the one before, which is why a slice renumbers ordinals and why a shift is measured against
 * the slice rather than against the body.
 *
 * <p>A stage that refuses returns nothing at all rather than a partial answer. Most refusals report
 * the diagnostic that explains them before returning, but a slice bound whose ordinal is past its
 * own matches is the one exception: {@link #boundOf} answers {@code null} there with nothing
 * reported, and {@link #sliceOf} passes that {@code null} on unreported in turn. A refusal is
 * scoped to the one {@code @At} being resolved here, not to the declaration: a declaration with
 * several {@code @At}s has each resolved separately and its sites merged by the caller, so one
 * {@code @At} losing all of its sites still leaves the others' sites to be woven.
 *
 * <h2>Coordinates</h2>
 *
 * <p>Two coordinate systems meet here and this class is where they are reconciled. A point returns
 * indices into whatever body it was handed — the slice, when there is one — and marks each site
 * with the kind that says what its index means. A resolved site's index is what an injector emits
 * <em>before</em>, so {@code AFTER_ELEMENT} is translated by adding one element, once, here rather
 * than by every injector separately. {@link #matchedIndexOf(Site)} is the way back, for a caller
 * that has a resolved site and wants the instruction it matched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PointResolver {

    /** The offset beyond which {@code shift = BY} is reported as {@code AW1112}. */
    private static final int LARGE_SHIFT = 4;

    /** The registry, answering {@code null} for an identifier nothing registered. */
    private final Function<String, InjectionPoint> points;

    /**
     * Binds the resolver to a point registry.
     *
     * @param points the lookup from identifier to point, answering {@code null} for an unknown
     *               identifier; must not be {@code null}
     * @throws NullPointerException if {@code points} is {@code null}
     */
    public PointResolver(@NotNull final Function<String, InjectionPoint> points) {
        this.points = Objects.requireNonNull(points, "points");
    }

    /**
     * Resolves one point of one declaration against one body.
     *
     * <p>Refuses, returning an empty result, when
     *
     * <ul>
     *   <li>the registry answers {@code null} for the identifier — {@code AW1101};
     *   <li>the point requires a target and none was written, or forbids one and it was written —
     *       both {@code AW1043};
     *   <li>the point refuses the declaration's shift — {@code AW1102};
     *   <li>a slice bound names no registered point or matches nothing — {@code AW1120} for
     *       {@code from} and {@code AW1121} for {@code to} — or the bounds are inverted,
     *       {@code AW1122}. A bound whose ordinal is past its own matches is refused as well, and
     *       is the one refusal here that carries no diagnostic of its own;
     *   <li>the ordinal is past the last match — {@code AW1110};
     *   <li>the shift moves a site out of the range it was found in — {@code AW1111}, which
     *       discards every site this {@code @At} found and not only the one that left.
     * </ul>
     *
     * <p>A large {@code shift = BY} is reported as {@code AW1112} and resolution continues; it is a
     * warning about a declaration that will not survive a recompilation of the target, not a
     * refusal.
     *
     * <p>The point's own diagnostics — {@code AW1043} for a target that matched nothing, and
     * {@code AW1103} for a selector that also named something inside an {@code invokedynamic} —
     * come from the point itself and are passed through untouched.
     *
     * <p>Individual sites are dropped, with the rest of the result kept, where the last stage finds
     * that nothing may be injected at them: {@code AW1026}, {@code AW1105}, {@code AW1130} and,
     * for an injector that stands in for an operation, {@code AW1061}.
     *
     * @param method   the target method; must not be {@code null}
     * @param code     its whole body, which the returned indices refer to; must not be {@code null}
     * @param injector the declaration, which the slices and the handler are read from; must not be
     *                 {@code null}
     * @param spec     the point to resolve, one of the declaration's; must not be {@code null}
     * @param reporter where the diagnostics go; must not be {@code null}
     * @return the usable positions, in body order, or an empty list when the declaration matched
     *         nothing or was refused
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    @Unmodifiable
    public List<Site> resolve(@NotNull final MethodView method,
                              @NotNull final CodeView code,
                              @NotNull final InjectorSpec injector,
                              @NotNull final PointSpec spec,
                              @NotNull final Reporter reporter) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(injector, "injector");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(reporter, "reporter");

        final InjectionPoint point = this.points.apply(spec.point());
        if (point == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.INJECTION_POINT_UNKNOWN)
                    .message("no injection point is registered under '" + spec.point() + '\'')
                    .remedy("check the spelling; a contributed point is always "
                            + "namespace:NAME and needs its plugin on the classpath")
                    .build());
            return List.of();
        }
        if (!checkTargetRequirement(point, spec, reporter)
                || !checkShiftSupport(point, spec, reporter)) {
            return List.of();
        }

        // 1. Slice.
        final Range range = sliceOf(method, code, injector, spec, reporter);
        if (range == null) {
            return List.of();
        }
        final CodeView sliced = range.view(code);

        // 2. Find, and translate indices back into the whole body.
        final List<Site> found = new ArrayList<>();
        for (final Site site : point.find(method, sliced, spec, reporter)) {
            // AFTER_ELEMENT means "the other side of this instruction", and every injector emits
            // BEFORE the index it is handed — so the two are reconciled here, once, rather than by
            // each injector remembering to. Until this translation existed the Kind was produced by
            // every point and read by nothing, which made INVOKE_AFTER emit exactly where INVOKE
            // does: before the call, with the call's result not yet on the stack.
            final int after = site.kind() == Site.Kind.AFTER_ELEMENT ? 1 : 0;
            found.add(new Site(site.index() + range.from() + after, site.kind(), site.element()));
        }
        if (found.isEmpty()) {
            return List.of();
        }

        // 3. Ordinal, counted within the slice.
        final List<Site> selected = applyOrdinal(found, method, spec, reporter);
        if (selected.isEmpty()) {
            return List.of();
        }

        // 4. Shift.
        final List<Site> shifted = applyShift(selected, code, range, spec, reporter);

        // 5. Refuse positions nothing may be injected at, which the first four steps happily find.
        return SiteSafety.usable(shifted, method, code.elements(), injector, spec, reporter);
    }

    /**
     * Returns the index of the element a resolved site matched.
     *
     * <p>The inverse of the translation {@link #resolve} performs, and the only correct way to get
     * from a resolved site back to the instruction the selector named. A caller that walks the
     * instruction stream asking whether a declaration selected a given instruction has to compare
     * in the point's coordinate; comparing a resolved index directly finds nothing for
     * {@code INVOKE_AFTER}, and finds nothing silently, because a method with no matching call
     * answers the same way.
     *
     * <p>Leaves every other kind alone: a {@code BEFORE_ELEMENT} site already names the element it
     * matched, and subtracting from it would name whatever precedes it.
     *
     * @param site the resolved site; must not be {@code null}
     * @return the element index the site was found at
     * @throws NullPointerException if {@code site} is {@code null}
     */
    @Contract(pure = true)
    public static int matchedIndexOf(@NotNull final Site site) {
        Objects.requireNonNull(site, "site");
        return site.kind() == Site.Kind.AFTER_ELEMENT ? site.index() - 1 : site.index();
    }

    /**
     * Checks the declaration's target against what the point does with one.
     *
     * <p>A missing target and a forbidden one are both reported as {@code AW1043}, the code the
     * point itself uses when a target matched nothing. A point whose requirement is optional is
     * never refused here, whether or not it goes on to consult the target.
     *
     * @param point    the resolved point; must not be {@code null}
     * @param spec     the declaration's point; must not be {@code null}
     * @param reporter where the diagnostic goes; must not be {@code null}
     * @return {@code true} to carry on
     */
    private static boolean checkTargetRequirement(@NotNull final InjectionPoint point,
                                                  @NotNull final PointSpec spec,
                                                  @NotNull final Reporter reporter) {
        final InjectionPoint.TargetRequirement requirement = point.targetRequirement();
        if (requirement == InjectionPoint.TargetRequirement.REQUIRED && !spec.hasTarget()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.NO_INJECTION_POINT_MATCHED)
                    .message(spec.point() + " needs a target, and none was given")
                    .remedy("add target = \"…\" naming what to match, for example "
                            + "target = \"Gateway.send(Payment)\" or the name-only \"#send\"")
                    .build());
            return false;
        }
        if (requirement == InjectionPoint.TargetRequirement.FORBIDDEN && spec.hasTarget()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.NO_INJECTION_POINT_MATCHED)
                    .message(spec.point() + " takes no target, but one was given: "
                            + spec.rawTarget())
                    .remedy("remove the target; " + spec.point() + " locates a position rather "
                            + "than matching something")
                    .build());
            return false;
        }
        return true;
    }

    /**
     * Checks the declaration's shift against what the point allows.
     *
     * <p>Asked with whatever the declaration wrote, including {@code NONE}, so a point that refuses
     * everything can never be used.
     *
     * @param point    the resolved point; must not be {@code null}
     * @param spec     the declaration's point; must not be {@code null}
     * @param reporter where the diagnostic goes; must not be {@code null}
     * @return {@code true} to carry on, {@code false} having reported {@code AW1102}
     */
    private static boolean checkShiftSupport(@NotNull final InjectionPoint point,
                                             @NotNull final PointSpec spec,
                                             @NotNull final Reporter reporter) {
        if (point.supportsShift(spec.shift())) {
            return true;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.SHIFT_NOT_SUPPORTED)
                .message(spec.point() + " does not support shift = " + spec.shift())
                .remedy("remove the shift, or use a point that names the position you want "
                        + "directly — a shift that a point refuses would land somewhere the "
                        + "verifier rejects")
                .build());
        return false;
    }

    /**
     * Returns the region of the body the search runs in.
     *
     * <p>The whole body when the declaration names no slice, or names one the declaration does not
     * declare — {@code sliceFor} answers {@code null} for both, and a reference to a slice that is
     * not there widens rather than failing here.
     *
     * <p>An unlocatable bound is refused rather than defaulted, because a slice that quietly widens
     * to the whole method changes which instruction every ordinal in the declaration selects.
     *
     * @param method   the target method; must not be {@code null}
     * @param code     its whole body; must not be {@code null}
     * @param injector the declaration, which the slices are read from; must not be {@code null}
     * @param spec     the point naming the slice; must not be {@code null}
     * @param reporter where the diagnostics go; must not be {@code null}
     * @return the region, or {@code null} having reported {@code AW1120}, {@code AW1121} or
     *         {@code AW1122}
     */
    @Nullable
    private Range sliceOf(@NotNull final MethodView method,
                          @NotNull final CodeView code,
                          @NotNull final InjectorSpec injector,
                          @NotNull final PointSpec spec,
                          @NotNull final Reporter reporter) {
        final SliceSpec slice = injector.sliceFor(spec);
        if (slice == null) {
            return new Range(0, code.size());
        }
        final Integer from = boundOf(method, code, slice.from(), reporter,
                DiagnosticCode.SLICE_FROM_UNRESOLVED, 0);
        final Integer to = boundOf(method, code, slice.to(), reporter,
                DiagnosticCode.SLICE_TO_UNRESOLVED, code.size());
        if (from == null || to == null) {
            return null;
        }
        if (to < from) {
            reporter.report(Diagnostic.builder(DiagnosticCode.SLICE_BOUNDS_INVERTED)
                    .message("slice '" + slice.id() + "' ends before it begins in "
                            + method.describe())
                    .remedy("the 'to' bound must resolve to a position at or after 'from'; check "
                            + "that both name what you think they do")
                    .build());
            return null;
        }
        return new Range(from, to);
    }

    /**
     * Resolves one bound of a slice to a position in the body.
     *
     * <p>A bound is an ordinary point used to name a position, so it has to select exactly one. Its
     * ordinal is what does the selecting, and a bound whose ordinal is negative — which is what
     * {@code matchesAll} reports — is taken as unnarrowed and yields the fallback instead of being
     * resolved at all. In practice this branch is never taken: {@link SliceSpec}'s constructor
     * already refuses a bound with no ordinal, so every bound reaching this method through a
     * {@code sliceOf} call is one that {@code matchesAll} already reports {@code false} for. An
     * author who writes an explicit negative ordinal on a slice bound does not reach this fallback
     * at all — {@code SliceSpec} throws {@link IllegalArgumentException} while the declaration is
     * still being parsed, uncaught, joining the parser's other escapes of the same kind.
     *
     * <p>The bound's own search reports nothing: it is run with {@link Reporter#NOOP}, and a bound
     * that matches nothing is reported once, here, as the slice failing rather than twice as a
     * point failing and then a slice failing.
     *
     * <p>An ordinal past the bound's own matches yields {@code null} without a diagnostic of its
     * own, and the caller treats it as the slice being refused.
     *
     * @param method     the target method; must not be {@code null}
     * @param code       its whole body; must not be {@code null}
     * @param bound      the bound to resolve; must not be {@code null}
     * @param reporter   where the diagnostic goes; must not be {@code null}
     * @param unresolved the code to report, which distinguishes the two bounds; must not be
     *                   {@code null}
     * @param fallback   the position to use when the bound narrows nothing
     * @return the position, or {@code null} when the bound cannot be located
     */
    @Nullable
    private Integer boundOf(@NotNull final MethodView method,
                            @NotNull final CodeView code,
                            @NotNull final PointSpec bound,
                            @NotNull final Reporter reporter,
                            @NotNull final DiagnosticCode unresolved,
                            final int fallback) {
        if (bound.matchesAll()) {
            return fallback;
        }
        final InjectionPoint point = this.points.apply(bound.point());
        if (point == null) {
            reporter.report(Diagnostic.of(unresolved,
                    "no injection point is registered under '" + bound.point() + '\''));
            return null;
        }
        final List<Site> sites = point.find(method, code, bound, Reporter.NOOP);
        if (sites.isEmpty()) {
            reporter.report(Diagnostic.builder(unresolved)
                    .message("a slice bound matched nothing in " + method.describe())
                    .detail("bound: " + bound.point()
                            + (bound.hasTarget() ? " target=" + bound.rawTarget() : ""))
                    .remedy("a slice that cannot be located would silently widen to the whole "
                            + "method, so it is refused instead")
                    .build());
            return null;
        }
        final int ordinal = bound.ordinal() < 0 ? 0 : bound.ordinal();
        return ordinal < sites.size() ? sites.get(ordinal).index() : null;
    }

    /**
     * Narrows the matches to the one the ordinal names.
     *
     * <p>A negative ordinal keeps every match; that is what an {@code @At} without one carries.
     * Counting is within the slice, since the matches were found there.
     *
     * @param found    the matches, in body order; must not be {@code null}
     * @param method   the target method, named in the diagnostic; must not be {@code null}
     * @param spec     the declaration's point; must not be {@code null}
     * @param reporter where the diagnostic goes; must not be {@code null}
     * @return every match, the one selected, or an empty list having reported {@code AW1110}
     */
    @NotNull
    private static List<Site> applyOrdinal(@NotNull final List<Site> found,
                                           @NotNull final MethodView method,
                                           @NotNull final PointSpec spec,
                                           @NotNull final Reporter reporter) {
        final int ordinal = spec.ordinal();
        if (ordinal < 0) {
            return found;
        }
        if (ordinal >= found.size()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.ORDINAL_OUT_OF_RANGE)
                    .message("ordinal " + ordinal + " requested but only " + found.size()
                            + " match" + (found.size() == 1 ? "" : "es") + " found for "
                            + spec.point()
                            + (spec.hasTarget() ? " target=" + spec.rawTarget() : "")
                            + " in " + method.describe())
                    .remedy("ordinals are zero-based and are counted within the slice, so a slice "
                            + "changes the numbering")
                    .build());
            return List.of();
        }
        return List.of(found.get(ordinal));
    }

    /**
     * Moves each site by whole elements.
     *
     * <p>{@code BEFORE} and {@code AFTER} are one element either way and {@code BY} is whatever the
     * declaration wrote, which may be negative. An offset of zero returns the sites unchanged
     * without checking anything, so {@code BY 0} cannot fail.
     *
     * <p>The moved position is checked against the slice it was found in and against the body. A
     * site that leaves discards the whole result rather than only itself: the sites given here all
     * came from resolving one {@code @At}, so one of them leaving means that {@code @At} is wrong,
     * and weaving the rest of its sites would be weaving something the author did not describe. A
     * declaration's other {@code @At}s are resolved separately and are unaffected.
     *
     * <p>An empty slice is not allowed to make every shift fail: the upper bound is taken as at
     * least one element past {@code from}, so a site found at the start of a zero-width range is
     * not immediately out of it.
     *
     * @param sites    the selected sites; must not be {@code null}
     * @param code     the whole body, whose size bounds the result; must not be {@code null}
     * @param range    the region the sites were found in; must not be {@code null}
     * @param spec     the declaration's point; must not be {@code null}
     * @param reporter where the diagnostics go; must not be {@code null}
     * @return the moved sites, or an empty list having reported {@code AW1111}; {@code AW1112} is
     *         reported alongside a large {@code BY} without changing the result
     */
    @NotNull
    private static List<Site> applyShift(@NotNull final List<Site> sites,
                                         @NotNull final CodeView code,
                                         @NotNull final Range range,
                                         @NotNull final PointSpec spec,
                                         @NotNull final Reporter reporter) {
        final int offset = switch (spec.shift()) {
            case NONE -> 0;
            case BEFORE -> -1;
            case AFTER -> 1;
            case BY -> spec.by();
        };
        if (offset == 0) {
            return sites;
        }
        if (spec.shift() == At.Shift.BY && Math.abs(spec.by()) > LARGE_SHIFT) {
            reporter.report(Diagnostic.builder(DiagnosticCode.SHIFT_OFFSET_LARGE)
                    .message("shift = BY " + spec.by() + " is a large offset")
                    .remedy("large offsets almost always mean a slice or a different point would "
                            + "express the intent better, and they break on any recompilation of "
                            + "the target")
                    .build());
        }

        final List<Site> shifted = new ArrayList<>(sites.size());
        for (final Site site : sites) {
            final int moved = site.index() + offset;
            if (moved < range.from() || moved >= Math.max(range.to(), range.from() + 1)
                    || moved >= code.size()) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SHIFT_LEAVES_SLICE)
                        .message("shift " + spec.shift() + " moves a site outside the range it "
                                + "was found in")
                        .detail("site at index " + site.index() + ", shifted to " + moved)
                        .detail("range: [" + range.from() + ", " + range.to() + ')')
                        .remedy("widen the slice, or drop the shift")
                        .build());
                return List.of();
            }
            shifted.add(new Site(moved, site.kind(), site.element()));
        }
        return List.copyOf(shifted);
    }

    /**
     * A half-open region of a body, as element indices.
     *
     * @param from the first element in the region
     * @param to   the first element past it
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Range(int from, int to) {

        /**
         * Returns the body restricted to this region.
         *
         * <p>The whole body is returned as it is when the region covers it, so the common case
         * copies nothing. Otherwise the window is copied, which means a point given a slice cannot
         * see past its bounds even by holding on to the list.
         *
         * <p>The upper bound is clamped to the body's size: a bound may resolve to the position
         * past the last element, which is a legal end and not a legal index.
         *
         * @param code the whole body; must not be {@code null}
         * @return a view over the region
         */
        @Contract(pure = true)
        @NotNull
        CodeView view(@NotNull final CodeView code) {
            if (this.from == 0 && this.to >= code.size()) {
                return code;
            }
            final List<java.lang.classfile.CodeElement> window =
                    List.copyOf(code.elements().subList(this.from, Math.min(this.to, code.size())));
            return () -> window;
        }
    }
}
