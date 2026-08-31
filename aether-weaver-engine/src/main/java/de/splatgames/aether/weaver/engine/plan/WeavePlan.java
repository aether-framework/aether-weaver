package de.splatgames.aether.weaver.engine.plan;

import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.api.spi.PlanView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the weaver has to know about a run, indexed by the question it asks on every class it
 * sees.
 *
 * <p>That question — does this loading class need anything done to it — is asked once per class the
 * JVM loads and is answered "no" almost every time, so the plan is built once and then only read.
 * Two indexes carry it: {@link #entriesFor(String)} for injections and
 * {@link #structuralFor(String)} for weaves that dissolve into the class. Both are keyed by internal
 * name and both answer with an empty list rather than {@code null}.
 *
 * <p>Every collection is immutable and built in the constructor. The entry order is the one
 * {@link WeavePlanner} established before conflict detection ran, and the per-target lists keep it,
 * so a target's entries appear in the order they are applied.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeavePlan implements PlanView {

    /** Every injection of the run, in application order. */
    private final List<PlanEntry> entries;

    /** {@link #entries} grouped by target internal name, each group keeping the global order. */
    private final Map<String, List<PlanEntryView>> index;

    /** The internal names of every class this plan touches, injected into or dissolved into. */
    private final List<String> targets;

    /** The dissolving weaves per target internal name, empty for a target that has none. */
    private final Map<String, List<WeaveClass>> structural;

    /** Every weave the planner was given, including those that produced no entry. */
    private final List<WeaveClass> weaves;

    /** The digest of the plan, stamped into each woven class. */
    private final PlanFingerprint fingerprint;

    /**
     * Builds the two indexes over an already ordered entry list.
     *
     * <p>Package-private: an entry list that has not been through
     * {@link WeavePlanner#plan(List, de.splatgames.aether.weaver.engine.plugin.PluginRegistry)}
     * would carry an order nothing established.
     *
     * @param entries     the injections, already sorted; must not be {@code null}
     * @param structural  the dissolving weaves per target internal name; must not be {@code null}
     * @param weaves      every weave the planner was given; must not be {@code null}
     * @param fingerprint the digest of the plan; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    WeavePlan(@NotNull final List<PlanEntry> entries,
              @NotNull final Map<String, List<WeaveClass>> structural,
              @NotNull final List<WeaveClass> weaves,
              @NotNull final PlanFingerprint fingerprint) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.structural = Map.copyOf(Objects.requireNonNull(structural, "structural"));
        this.weaves = List.copyOf(Objects.requireNonNull(weaves, "weaves"));
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");

        final Map<String, List<PlanEntryView>> byTarget = new LinkedHashMap<>();
        for (final PlanEntry entry : this.entries) {
            byTarget.computeIfAbsent(entry.targetInternalName(), key -> new ArrayList<>())
                    .add(entry);
        }
        byTarget.replaceAll((key, value) -> List.copyOf(value));
        this.index = Collections.unmodifiableMap(byTarget);

        // A weave with structural members but no injections still has a target, and the fast path
        // has to know about it — otherwise a purely structural weave is silently never applied.
        final java.util.Set<String> named = new java.util.LinkedHashSet<>(byTarget.keySet());
        named.addAll(this.structural.keySet());
        this.targets = List.copyOf(named);
    }

    /**
     * Returns every weave the planner was given, whether or not it produced an entry.
     *
     * @return the weave classes, in the order the planner received them
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<WeaveClass> weaves() {
        return this.weaves;
    }

    /**
     * Returns the weaves that dissolve into the named class.
     *
     * <p>Only a weave that has something to dissolve appears here, so this is the second half of the
     * answer to whether a loading class is touched at all: a class with no entries can still have a
     * member merged into it.
     *
     * @param internalName the class's internal name, as {@code com/acme/Ledger}; must not be
     *                     {@code null}
     * @return the dissolving weaves, or an empty list
     * @throws NullPointerException if {@code internalName} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<WeaveClass> structuralFor(@NotNull final String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        return this.structural.getOrDefault(internalName, List.of());
    }

    /**
     * Returns every injection of the run as the published view type.
     *
     * @return the entries, in application order
     */
    @Contract(pure = true)
    @Override
    @Unmodifiable
    @NotNull
    public List<PlanEntryView> entries() {
        return List.copyOf(this.entries);
    }

    /**
     * Returns the injections that apply to the named class.
     *
     * <p>A lookup in the prebuilt index; a class nothing injects into costs one map lookup and
     * allocates nothing.
     *
     * @param internalName the class's internal name, as {@code com/acme/Ledger}; must not be
     *                     {@code null}
     * @return the entries for that class in application order, or an empty list
     * @throws NullPointerException if {@code internalName} is {@code null}
     */
    @Contract(pure = true)
    @Override
    @Unmodifiable
    @NotNull
    public List<PlanEntryView> entriesFor(@NotNull final String internalName) {
        return this.index.getOrDefault(Objects.requireNonNull(internalName, "internalName"),
                List.of());
    }

    /**
     * Returns the internal names of every class this plan touches.
     *
     * <p>The union of the injection targets and the targets of dissolving weaves, so a class that is
     * only merged into is named here without appearing in {@link #entriesFor(String)}.
     *
     * @return the target internal names
     */
    @Contract(pure = true)
    @Override
    @Unmodifiable
    @NotNull
    public List<String> targets() {
        return this.targets;
    }

    /**
     * Returns the digest of the plan as 64 lowercase hex characters.
     *
     * @return the fingerprint text
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public String fingerprint() {
        return this.fingerprint.value();
    }

    /**
     * Returns the fingerprint itself, for a caller that wants it abbreviated.
     *
     * @return the fingerprint
     */
    @Contract(pure = true)
    @NotNull
    public PlanFingerprint planFingerprint() {
        return this.fingerprint;
    }

    /**
     * Returns the entries as the engine's own type rather than as {@link PlanEntryView}.
     *
     * @return the entries, in application order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<PlanEntry> planEntries() {
        return this.entries;
    }

    /**
     * Renders the plan as the plain listing printed when no explain report was built.
     *
     * <p>Each target's entries are read out of the per-target index, which is keyed by the entries
     * alone. A target that {@link #targets()} carries only because a weave dissolves into it without
     * declaring an injector has no list there, and iterating over it throws
     * {@link NullPointerException}.
     *
     * @return the listing, ending in a count of classes, a count of modifications and the
     *         abbreviated fingerprint
     */
    @Contract(pure = true)
    @NotNull
    public String explain() {
        final StringBuilder sb = new StringBuilder(256);
        for (final String target : this.targets) {
            sb.append(target).append(System.lineSeparator());
            for (final PlanEntryView entry : this.index.get(target)) {
                sb.append("  ← ").append(entry).append(System.lineSeparator());
            }
        }
        sb.append(this.targets.size()).append(" classes, ")
                .append(this.entries.size()).append(" modifications, fingerprint ")
                .append(this.fingerprint.abbreviated());
        return sb.toString();
    }

    /**
     * Returns the two counts and the abbreviated fingerprint.
     *
     * @return the summary rendering
     */
    @Override
    @NotNull
    public String toString() {
        return "WeavePlan[" + this.entries.size() + " entries, " + this.targets.size()
                + " targets, " + this.fingerprint.abbreviated() + ']';
    }
}
