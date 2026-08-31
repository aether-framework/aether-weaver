package de.splatgames.aether.weaver.engine.plan;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the ways two declarations can contradict each other, before any class is loaded.
 *
 * <p>Everything here is decided from the declarations alone: no target class is read, and no
 * selector is resolved. That is what makes these checks worth running at planning time — they answer
 * for the whole run at once instead of once per loading class — and it is also their limit. Two
 * declarations that name one call site in different words are not seen as claiming the same site,
 * because the key is built from the text the user wrote.
 *
 * <p>Nothing here stops the plan. Each pass reports what it finds and returns a count, so one run
 * shows everything that is wrong rather than one problem per rebuild.
 *
 * <p>Report order is fixed rather than incidental: a pass that walks the weaves walks them sorted by
 * binary name, and a pass that groups by a key collects into a {@link TreeMap}. See {@link #sorted}
 * for why.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConflictDetector {

    /**
     * Creates a detector.
     *
     * <p>Holds no state; the instance exists so that {@link WeavePlanner} can keep one.
     */
    public ConflictDetector() {
        // Nothing to initialise.
    }

    /**
     * Runs every conflict pass and reports what each one finds.
     *
     * <p>The passes are independent and all of them run: one weave can be named by several of them,
     * and stopping at the first would hide the rest until it was fixed.
     *
     * @param weaves   the parsed weaves; must not be {@code null}
     * @param entries  the flattened plan entries; must not be {@code null}
     * @param listener the sink for the diagnostics; must not be {@code null}
     * @return how many conflicts were reported, counted per diagnostic
     * @throws NullPointerException if any argument is {@code null}
     */
    public int detect(@NotNull final List<WeaveClass> weaves,
                      @NotNull final List<PlanEntry> entries,
                      @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(weaves, "weaves");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(listener, "listener");

        int found = 0;
        found += weavesTargetingWeaves(weaves, listener);
        found += duplicateRedirects(entries, listener);
        found += collidingMergedMembers(weaves, listener);
        found += collidingHandlers(weaves, listener);
        found += shadowsOfLaterAdditions(weaves, listener);
        return found;
    }

    /**
     * Reports {@code AW1087} for a weave whose target is another weave of the same run.
     *
     * <p>A weave is matched by its own type, so a weave that names itself as a target is reported
     * too. One diagnostic is emitted per offending target rather than per weave.
     *
     * @param weaves   the parsed weaves; must not be {@code null}
     * @param listener the sink for the diagnostics; must not be {@code null}
     * @return how many diagnostics were reported
     */
    private static int weavesTargetingWeaves(@NotNull final List<WeaveClass> weaves,
                                             @NotNull final DiagnosticListener listener) {
        final Map<ClassDesc, WeaveClass> byType = new LinkedHashMap<>();
        for (final WeaveClass weave : weaves) {
            byType.put(weave.weaveType(), weave);
        }

        int found = 0;
        for (final WeaveClass weave : sorted(weaves)) {
            for (final TargetRef target : weave.targets()) {
                final WeaveClass targeted = byType.get(target.type());
                if (targeted == null) {
                    continue;
                }
                listener.report(Diagnostic.builder(DiagnosticCode.WEAVE_TARGETS_WEAVE)
                        .message(weave.binaryName() + " targets " + targeted.binaryName()
                                + ", which is itself a weave class")
                        .detail("weave:  " + weave.binaryName())
                        .detail("target: " + targeted.binaryName())
                        .remedy("a weave class is a declaration, not a runtime class — its members "
                                + "are folded into its own targets and it is never loaded as "
                                + "itself. Target the class you actually want to modify")
                        .build());
                found++;
            }
        }
        return found;
    }

    /**
     * Reports {@code AW1060} where two declarations claim one call site and at least one of them
     * removes it.
     *
     * <p>Only a redirect makes the site contested: any number of wraps at one site nest, so a site
     * without a redirect is skipped however many claimants it has. A site with a redirect and a wrap
     * is reported with a different message and remedy from one with two redirects, because the two
     * situations fail for different reasons.
     *
     * <p>A site is identified by {@link #callSiteOf}, which is spelled from the declaration and not
     * from a resolved instruction. Two declarations that reach one instruction through different
     * spellings — a different rendering of the target method, or one naming an ordinal the other
     * leaves open — are two sites here and neither is reported.
     *
     * @param entries  the flattened plan entries; must not be {@code null}
     * @param listener the sink for the diagnostics; must not be {@code null}
     * @return how many diagnostics were reported
     */
    private static int duplicateRedirects(@NotNull final List<PlanEntry> entries,
                                          @NotNull final DiagnosticListener listener) {
        final Map<String, List<PlanEntry>> bySite = new TreeMap<>();
        for (final PlanEntry entry : entries) {
            if (!InjectorKind.REDIRECT.equals(entry.spec().kind())
                    && !InjectorKind.WRAP.equals(entry.spec().kind())) {
                continue;
            }
            for (final PointSpec point : entry.spec().points()) {
                bySite.computeIfAbsent(callSiteOf(entry, point), key -> new ArrayList<>())
                        .add(entry);
            }
        }

        int found = 0;
        for (final Map.Entry<String, List<PlanEntry>> site : bySite.entrySet()) {
            final List<PlanEntry> claimants = site.getValue();
            final long redirects = claimants.stream()
                    .filter(claimant -> InjectorKind.REDIRECT.equals(claimant.spec().kind()))
                    .count();
            if (redirects == 0 || claimants.size() < 2) {
                // Nothing, one thing, or any number of wraps — all of which compose.
                continue;
            }
            final boolean mixed = redirects < claimants.size();
            final Diagnostic.Builder builder = Diagnostic.builder(DiagnosticCode.DUPLICATE_REDIRECT)
                    .message(mixed
                            ? "a @Redirect and a @Wrap claim the same call site"
                            : claimants.size() + " redirects claim the same call site")
                    .detail("site: " + site.getKey());
            for (final PlanEntry claimant : claimants) {
                builder.detail("claimed by: " + claimant.order().describe()
                        + " (" + claimant.spec().kind().id() + ')');
            }
            listener.report(builder
                    .remedy(mixed
                            ? "a redirect removes the operation, and a wrap hands that same "
                            + "operation to its handler — so the wrap would hold a handle to "
                            + "something the woven method no longer does. Make both of them "
                            + "@Wrap, which nests, or narrow one with an ordinal or a slice"
                            : "a call has one callee, so two redirects of it cannot both apply. "
                            + "Make both of them @Wrap, which nests instead of colliding, or "
                            + "narrow one with an ordinal or a slice")
                    .build());
            found++;
        }
        return found;
    }

    /**
     * Reports {@code AW1080} where two dissolving weaves would put a method of one name and
     * descriptor into one target.
     *
     * <p>Only a handler a weave declares itself is at risk, and only if the weave dissolves. See the
     * reported diagnostic's remedy for what keeps other handlers out of reach.
     *
     * <p>The count is of distinct weaves, so one weave that names the same handler from two
     * declarations claims the site twice without conflicting with itself.
     *
     * @param weaves   the parsed weaves; must not be {@code null}
     * @param listener the sink for the diagnostics; must not be {@code null}
     * @return how many diagnostics were reported
     */
    private static int collidingHandlers(@NotNull final List<WeaveClass> weaves,
                                         @NotNull final DiagnosticListener listener) {
        /** One weave's claim on a handler signature in a target. */
        record Claim(WeaveClass weave, String handler) { }

        final Map<String, List<Claim>> byHandler = new TreeMap<>();
        for (final WeaveClass weave : sorted(weaves)) {
            if (!dissolves(weave)) {
                // A static weave's handler stays where it is and is called there, so two of them
                // never meet. Only dissolving moves a handler into the target.
                continue;
            }
            for (final var spec : weave.injectors()) {
                if (!spec.handler().owner().equals(weave.weaveType())) {
                    // A handler declared elsewhere is not the weave's to move.
                    continue;
                }
                final String signature = spec.handler().name()
                        + spec.handler().type().descriptorString();
                for (final TargetRef target : weave.targets()) {
                    byHandler.computeIfAbsent(target.internalName() + '#' + signature,
                            k -> new ArrayList<>()).add(new Claim(weave, signature));
                }
            }
        }

        int found = 0;
        for (final Map.Entry<String, List<Claim>> handler : byHandler.entrySet()) {
            final List<Claim> claims = handler.getValue();
            final long distinctWeaves = claims.stream().map(Claim::weave).distinct().count();
            if (distinctWeaves < 2) {
                continue;
            }
            final Diagnostic.Builder builder =
                    Diagnostic.builder(DiagnosticCode.MERGED_MEMBER_COLLIDES)
                            .message(distinctWeaves + " weaves merge a handler of the same name "
                                    + "and descriptor into one target")
                            .detail("handler: " + handler.getKey());
            claims.stream().map(Claim::weave).distinct().forEach(weave ->
                    builder.detail("declared by: " + weave.binaryName()));
            listener.report(builder
                    .detail("an instance weave is dissolved into its target, so its handlers "
                            + "become methods of that target")
                    .remedy("rename all but one of them, or make the weaves static — a static "
                            + "weave's handler stays where it is and is called there, so two of "
                            + "them never meet")
                    .build());
            found++;
        }
        return found;
    }

    /**
     * Reports whether the weave is folded into its targets rather than staying a class of its own.
     *
     * <p>The same condition {@link WeavePlanner} applies when it sets {@link PlanEntry#dissolved()};
     * the two are written out separately and have to agree, since this pass and the weaving both act
     * on it.
     *
     * @param weave the weave to test; must not be {@code null}
     * @return whether the weave dissolves
     */
    @Contract(pure = true)
    private static boolean dissolves(@NotNull final WeaveClass weave) {
        return weave.kind() == de.splatgames.aether.weaver.api.Weave.Kind.INSTANCE
                && (!weave.members().isEmpty() || weave.injectors().stream()
                .anyMatch(spec -> spec.handler().owner().equals(weave.weaveType())));
    }

    /**
     * Reports {@code AW1080} where two merged members of one name and type would land in one target.
     *
     * <p>A collision is excused only when every claimant is {@code @Unique}; see the reported
     * diagnostic's remedy for why marking only some does not help. The key carries the member's
     * type as well as its name, so a field and a method of one name are not a collision.
     *
     * <p>Unlike {@link #collidingHandlers}, this counts claims rather than distinct weaves: one
     * weave declaring the same member twice is reported against itself.
     *
     * @param weaves   the parsed weaves; must not be {@code null}
     * @param listener the sink for the diagnostics; must not be {@code null}
     * @return how many diagnostics were reported
     */
    private static int collidingMergedMembers(@NotNull final List<WeaveClass> weaves,
                                              @NotNull final DiagnosticListener listener) {
        /** One weave's claim on a merged member of a target. */
        record Claim(WeaveClass weave, WeaveMember.Merged member) { }

        final Map<String, List<Claim>> byMember = new TreeMap<>();
        for (final WeaveClass weave : sorted(weaves)) {
            for (final WeaveMember member : weave.members()) {
                if (!(member instanceof WeaveMember.Merged merged)) {
                    continue;
                }
                for (final TargetRef target : weave.targets()) {
                    final String key = target.internalName() + '#' + merged.name()
                            + ' ' + describeType(merged.type());
                    byMember.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Claim(weave, merged));
                }
            }
        }

        int found = 0;
        for (final Map.Entry<String, List<Claim>> member : byMember.entrySet()) {
            final List<Claim> claims = member.getValue();
            if (claims.size() < 2 || claims.stream().allMatch(c -> c.member().unique())) {
                continue;
            }
            final Diagnostic.Builder builder = Diagnostic.builder(DiagnosticCode.MERGED_MEMBER_COLLIDES)
                    .message(claims.size() + " weaves merge the same member into one target")
                    .detail("member: " + member.getKey());
            for (final Claim claim : claims) {
                builder.detail("merged by: " + claim.weave().binaryName()
                        + (claim.member().unique() ? "  (@Unique)" : ""));
            }
            listener.report(builder
                    .remedy("mark every one of them @Unique so each is mangled to its own private "
                            + "name, or rename all but one. Marking only some does not help: a "
                            + "mangled member and a plainly named one still collide on the plain "
                            + "name")
                    .build());
            found++;
        }
        return found;
    }

    /**
     * Reports {@code AW1034} where a weave shadows a member that another weave merges without
     * running before it.
     *
     * <p>A shadow resolves a name the target is expected to have already, so the weave that adds it
     * has to run first — which means a strictly higher priority; see the reported diagnostic's
     * remedy for why equal priority does not qualify. A weave shadowing a member it merges itself is
     * skipped.
     *
     * <p>Additions are keyed by name alone, without the descriptor, so a shadow is compared against
     * every merged member of that name.
     *
     * @param weaves   the parsed weaves; must not be {@code null}
     * @param listener the sink for the diagnostics; must not be {@code null}
     * @return how many diagnostics were reported
     */
    private static int shadowsOfLaterAdditions(@NotNull final List<WeaveClass> weaves,
                                               @NotNull final DiagnosticListener listener) {
        /** A member one weave adds to a target, and the weave that adds it. */
        record Addition(WeaveClass weave, WeaveMember.Merged member) { }

        final Map<String, List<Addition>> additions = new TreeMap<>();
        for (final WeaveClass weave : sorted(weaves)) {
            for (final WeaveMember member : weave.members()) {
                if (member instanceof WeaveMember.Merged merged) {
                    for (final TargetRef target : weave.targets()) {
                        additions.computeIfAbsent(target.internalName() + '#' + merged.name(),
                                k -> new ArrayList<>()).add(new Addition(weave, merged));
                    }
                }
            }
        }

        int found = 0;
        for (final WeaveClass weave : sorted(weaves)) {
            for (final WeaveMember member : weave.members()) {
                if (!(member instanceof WeaveMember.Shadowed shadowed)) {
                    continue;
                }
                for (final TargetRef target : weave.targets()) {
                    final String key = target.internalName() + '#' + shadowed.targetName();
                    for (final Addition addition : additions.getOrDefault(key, List.of())) {
                        if (addition.weave().equals(weave)
                                || addition.weave().priority() > weave.priority()) {
                            continue;
                        }
                        listener.report(Diagnostic.builder(
                                        DiagnosticCode.SHADOW_OF_LOWER_PRIORITY_MEMBER)
                                .message(weave.binaryName() + " shadows '" + shadowed.targetName()
                                        + "', which " + addition.weave().binaryName()
                                        + " adds at a priority that does not run first")
                                .detail("shadowing weave: " + weave.binaryName()
                                        + "  priority " + weave.priority())
                                .detail("adding weave:    " + addition.weave().binaryName()
                                        + "  priority " + addition.weave().priority())
                                .detail("member:          " + key)
                                .remedy("give the adding weave a strictly higher priority than "
                                        + weave.priority() + ". Equal priority is not enough — the "
                                        + "tie is broken by class name, which is stable but "
                                        + "arbitrary, and a weave that depended on it would be "
                                        + "correct only by coincidence")
                                .build());
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Copies the weaves and sorts them by binary name.
     *
     * <p>Every pass iterates this rather than the list it was given, so the sequence of diagnostics
     * does not depend on the order the weaves were discovered in.
     *
     * @param weaves the weaves to sort; must not be {@code null}
     * @return a sorted copy
     */
    @Contract(pure = true)
    @NotNull
    private static List<WeaveClass> sorted(@NotNull final List<WeaveClass> weaves) {
        final List<WeaveClass> copy = new ArrayList<>(weaves);
        copy.sort(Comparator.comparing(WeaveClass::binaryName));
        return copy;
    }

    /**
     * Names one call site as the declaration spells it.
     *
     * <p>Built from the raw selector text, the point, its raw target, its ordinal and its slice, so
     * two declarations collide here only when they were written the same way. The text is also what
     * the {@code site:} detail of a diagnostic shows.
     *
     * @param entry the entry the site belongs to; must not be {@code null}
     * @param point the point within it; must not be {@code null}
     * @return the site key
     */
    @Contract(pure = true)
    @NotNull
    private static String callSiteOf(@NotNull final PlanEntry entry,
                                     @NotNull final PointSpec point) {
        return entry.targetInternalName() + '#' + entry.spec().rawMethod()
                + " @" + point.point()
                + ' ' + (point.rawTarget() == null ? "*" : point.rawTarget())
                + " ordinal=" + point.ordinal()
                + " slice=" + point.slice();
    }

    /**
     * Renders a member's type for a collision key.
     *
     * <p>{@link WeaveMember#type()} is either a {@link ClassDesc} or a
     * {@link java.lang.constant.MethodTypeDesc}; the first is spelled as its descriptor and anything
     * else through {@link Object#toString()}.
     *
     * @param type the member's type; must not be {@code null}
     * @return the rendering
     */
    @Contract(pure = true)
    @NotNull
    private static String describeType(@NotNull final Object type) {
        return type instanceof ClassDesc field ? field.descriptorString() : type.toString();
    }

    /**
     * Returns the four codes {@link #detect} can report.
     *
     * <p>Listed literally rather than derived, so a pass added here has to be added to this set as
     * well.
     *
     * @return the reportable codes
     */
    @Contract(pure = true)
    @NotNull
    public static Set<DiagnosticCode> reportableCodes() {
        return Set.of(DiagnosticCode.WEAVE_TARGETS_WEAVE,
                DiagnosticCode.DUPLICATE_REDIRECT,
                DiagnosticCode.MERGED_MEMBER_COLLIDES,
                DiagnosticCode.SHADOW_OF_LOWER_PRIORITY_MEMBER);
    }
}
