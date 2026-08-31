package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * Everything a weaver has decided to do, before any class has been read.
 *
 * <p>A plan is built once, when the weaver is built: the weave classes are parsed, each declaration
 * is paired with each of the targets its weave names, the resulting entries are ordered by priority
 * and its tie-breakers, and conflicts between them are reported. Nothing in a plan has been matched
 * against a target's bytes — a selector that names no method and a point that finds nothing are
 * discovered later, when the class is actually offered — so a plan describes intent, not outcome.
 *
 * <p>A plugin receives one through {@link PluginEvent.Prepared}, which is published to every loaded
 * plugin immediately after planning and before any class has been offered to the weaver. That is the
 * one opportunity to inspect the whole plan; a plugin that wants to react per class observes
 * {@link PluginEvent.ClassWoven} instead.
 *
 * <h2>Targets and entries are not the same set</h2>
 *
 * <p>{@link #targets()} is not the key set of {@link #entriesFor(String)}. A weave that only merges
 * members into its target declares no injection at all, so it contributes a target with no entries;
 * asking {@link #entriesFor(String)} about such a class returns an empty list even though the class
 * will be rewritten. A plugin deciding whether a class is interesting has to consult both, which is
 * exactly what the weaver's own fast path does.
 *
 * <h2>The fingerprint</h2>
 *
 * <p>{@link #fingerprint()} identifies the plan as a whole and is written into every class the
 * weaver stamps, so that a second driver over the same artefacts can tell that this plan has already
 * been applied and skip it rather than doubling every injection. Build-time weaving followed by a
 * load-time driver is the ordinary case for that check.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Override
 * public void observe(PluginEvent event) {
 *     if (!(event instanceof PluginEvent.Prepared prepared)) {
 *         return;
 *     }
 *     PlanView plan = prepared.plan();
 *     for (String target : plan.targets()) {
 *         List<PlanEntryView> here = plan.entriesFor(target);
 *         System.out.println(target + ": " + here.size() + " modifications");
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PlanEntryView
 * @see PluginEvent.Prepared
 */
@ApiStatus.NonExtendable
public interface PlanView {

    /**
     * Returns every entry of the plan, in the plan's order.
     *
     * <p>The order is {@link PlanEntryView#priority()} descending, then the declaring weave class
     * name, then the handler name, then the handler descriptor — see {@link PlanEntryView} for where
     * this order is partial rather than total. It is established before anything else looks at the
     * entries, so every stage that reads them — conflict detection, the fingerprint, the report, the
     * sequence in which emitters are asked — sees this sequence.
     *
     * @return the entries, never {@code null} and not modifiable; empty for a weaver built with no
     *         weaves, and for one whose weaves declare only structural members
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<PlanEntryView> entries();

    /**
     * Returns the entries planned for one class, in the plan's order.
     *
     * <p>The key is the class's internal name — slashes, no leading {@code L} and no trailing
     * semicolon, as in {@code com/acme/Ledger}. A name nothing is planned for yields an empty list
     * rather than an error, which is what makes this the weaver's first gate: two map lookups and no
     * bytes fetched for the overwhelming majority of classes a load-time driver sees.
     *
     * <p>An empty answer does not mean the class is left alone. A class named only by a weave that
     * merges members appears in {@link #targets()} and has no entries here.
     *
     * @param internalName the class's internal name, with slashes
     * @return the entries planned for that class, never {@code null} and not modifiable; empty when
     *         nothing is planned for it
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<PlanEntryView> entriesFor(@NotNull String internalName);

    /**
     * Returns every class this plan touches, by internal name.
     *
     * <p>The union of the classes {@link #entries()} name and the classes a weave dissolves into,
     * so a purely structural weave's target is listed here and has no entries. Each name appears
     * once. The classes carrying entries come first, in the order those entries appear in
     * {@link #entries()}, and the structural-only targets follow.
     *
     * @return the target class names, never {@code null} and not modifiable
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<String> targets();

    /**
     * Returns the identity of this plan as 64 lowercase hexadecimal characters.
     *
     * <p>A SHA-256 digest over a canonical rendering of everything that decides what the weaver will
     * do: a format tag, each entry in plan order — its target, kind, priority and tie-breakers,
     * selector, match counts, group and every point specification with its target, ordinal, shift,
     * by, access and slice — then each dissolving weave's members with their names, descriptors,
     * flags and dispositions, and finally the plugins the weaver was built with, the injector kinds
     * and injection point identifiers they registered, and the metadata they contributed.
     *
     * <p>Two consequences follow, and both are the point of it. Rerunning a build over classes this
     * plan already stamped weaves nothing a second time. And adding a plugin, changing a plugin's
     * version, or changing one character of one selector produces a different fingerprint: under the
     * default build-time driver, a class already stamped under the old fingerprint is then refused
     * with {@code AW2201} rather than mistaken for already woven, and under a load-time driver it is
     * rewoven instead, after a warning, {@code AW2202}.
     *
     * @return the fingerprint, always 64 lowercase hexadecimal characters
     */
    @Contract(pure = true)
    @NotNull
    String fingerprint();

    /**
     * Returns the number of entries.
     *
     * <p>Modifications, not classes: a weave naming three targets and declaring two handlers counts
     * as six. {@link #targets()} is what counts classes.
     *
     * @return the size of {@link #entries()}
     */
    @Contract(pure = true)
    default int size() {
        return entries().size();
    }

    /**
     * Reports whether the plan holds no entries.
     *
     * <p>Not the same as "nothing will be woven": a plan whose weaves only merge members has no
     * entries and still has targets.
     *
     * @return {@code true} when {@link #entries()} is empty
     */
    @Contract(pure = true)
    default boolean isEmpty() {
        return entries().isEmpty();
    }
}
