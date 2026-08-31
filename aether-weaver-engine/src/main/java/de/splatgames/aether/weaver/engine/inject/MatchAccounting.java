package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Decides whether every declaration that can be checked matched as many positions as it asked for.
 *
 * <p>The verdict is for the whole class rather than for one declaration: {@link WeavingPipeline}
 * returns nothing when this reports failure, so a class in which one injection fell short is left
 * exactly as it arrived rather than woven without that injection.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class MatchAccounting {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private MatchAccounting() {
        throw new AssertionError("no instances");
    }

    /**
     * Checks every declaration's match count against its own bounds, and every group's total
     * against the group's.
     *
     * <p>A declaration naming a group is accounted only through that group: both its own
     * {@code require} and its own {@code allow} are skipped, so the two statements cannot
     * contradict each other — but only when {@code groups} actually declares that name. A group
     * absent from {@code groups} is never read back out of the running totals, so a declaration
     * naming it is not accounted at all: no {@code AW1043}, no {@code AW1044}, nothing. Every other
     * declaration is checked directly, reporting {@code AW1043} below its {@code require} and
     * {@code AW1044} above a non-zero {@code allow}. A group whose total falls outside its bounds
     * is a second use of {@code AW1043}, listing what each of its members contributed.
     *
     * <p>Every failure is reported before the answer is returned, so one run names all of them.
     * A group in {@code groups} that no counted declaration mentioned is checked against a total
     * of zero rather than ignored.
     *
     * @param counts   how many positions each declaration matched; must not be {@code null}
     * @param groups   the groups declared for this run; must not be {@code null}
     * @param reporter where to report an unsatisfied bound; must not be {@code null}
     * @return {@code true} when every declaration and every group is within its bounds
     * @throws NullPointerException if any argument is {@code null}
     */
    public static boolean check(@NotNull final Map<InjectorSpec, Integer> counts,
                                @NotNull final List<GroupSpec> groups,
                                @NotNull final Reporter reporter) {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(reporter, "reporter");

        boolean satisfied = true;
        final Map<String, Integer> groupTotals = new TreeMap<>();

        for (final Map.Entry<InjectorSpec, Integer> entry : counts.entrySet()) {
            final InjectorSpec spec = entry.getKey();
            final int matched = entry.getValue();

            if (spec.isInAGroup()) {
                // The group's total is the statement; the injection's own require is normally 0
                // precisely so that the two do not contradict each other.
                groupTotals.merge(spec.group(), matched, Integer::sum);
                continue;
            }
            if (matched < spec.require()) {
                reporter.report(tooFew(spec, matched));
                satisfied = false;
            } else if (spec.isBounded() && matched > spec.allow()) {
                reporter.report(tooMany(spec, matched));
                satisfied = false;
            }
        }

        for (final GroupSpec group : groups) {
            final int total = groupTotals.getOrDefault(group.name(), 0);
            if (!group.accepts(total)) {
                reporter.report(groupUnsatisfied(group, total, counts));
                satisfied = false;
            }
        }
        return satisfied;
    }

    /**
     * Builds the {@code AW1043} for a declaration that matched fewer positions than it requires.
     *
     * <p>Each of the declaration's points is listed with the target and ordinal it named, because
     * the count alone does not say which of several points found nothing.
     *
     * @param spec    the declaration that fell short; must not be {@code null}
     * @param matched how many positions it matched
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic tooFew(@NotNull final InjectorSpec spec, final int matched) {
        return Diagnostic.builder(DiagnosticCode.NO_INJECTION_POINT_MATCHED)
                .message(spec.handler().describe() + " matched " + matched + " position"
                        + (matched == 1 ? "" : "s") + " in " + spec.rawMethod()
                        + ", and requires at least " + spec.require())
                .detail("injection: " + spec.id())
                .details(spec.points().stream()
                        .map(point -> "point: " + point.point()
                                + (point.hasTarget() ? " target=" + point.rawTarget() : "")
                                + (point.ordinal() >= 0 ? " ordinal=" + point.ordinal() : ""))
                        .toList())
                .remedy("the point's own diagnostic above lists what was found instead. If the "
                        + "target legitimately varies — two library versions, say — declare "
                        + "@Group(name = \"…\", min = 1) across the alternatives instead of "
                        + "requiring each one")
                .build();
    }

    /**
     * Builds the {@code AW1044} for a declaration that matched more positions than it allows.
     *
     * @param spec    the declaration that exceeded its bound; must not be {@code null}
     * @param matched how many positions it matched
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic tooMany(@NotNull final InjectorSpec spec, final int matched) {
        return Diagnostic.builder(DiagnosticCode.TOO_MANY_INJECTION_POINTS)
                .message(spec.handler().describe() + " matched " + matched + " positions in "
                        + spec.rawMethod() + ", and allows at most " + spec.allow())
                .detail("injection: " + spec.id())
                .remedy("narrow it with an ordinal or a slice. An upper bound exists so that a "
                        + "target gaining a second matching call is an error rather than a silent "
                        + "doubling of whatever the handler does")
                .build();
    }

    /**
     * Builds the {@code AW1043} for a group whose total falls outside its bounds.
     *
     * <p>The members are recovered by scanning {@code counts} for declarations naming this group,
     * since the running totals keep no record of who contributed to them, and are sorted so that
     * the same inputs produce the same listing.
     *
     * @param group  the group that was not satisfied; must not be {@code null}
     * @param total  the positions its members matched between them
     * @param counts every declaration's count, searched for this group's members; must not be
     *               {@code null}
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic groupUnsatisfied(@NotNull final GroupSpec group,
                                               final int total,
                                               @NotNull final Map<InjectorSpec, Integer> counts) {
        final List<String> members = new ArrayList<>();
        new LinkedHashMap<>(counts).forEach((spec, matched) -> {
            if (group.name().equals(spec.group())) {
                members.add(spec.handler().describe() + " -> " + matched + " position"
                        + (matched == 1 ? "" : "s"));
            }
        });
        members.sort(null);

        return Diagnostic.builder(DiagnosticCode.NO_INJECTION_POINT_MATCHED)
                .message("group '" + group.name() + "' matched " + total + " position"
                        + (total == 1 ? "" : "s") + " in total, which is outside its bounds")
                .detail("min " + group.min()
                        + (group.max() == 0 ? ", no maximum" : ", max " + group.max()))
                .details(members)
                .remedy("a group says 'at least one of these had to work'. If none did, the target "
                        + "has changed in a way none of the alternatives anticipated")
                .build();
    }
}
