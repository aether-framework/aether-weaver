package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/**
 * Something the weaver has finished doing, delivered to
 * {@link WeaverPlugin#observe(PluginEvent)}.
 *
 * <p>Four events exist, and they are the four records nested below. Each is a statement of fact
 * about work already completed: an observer is told what happened and cannot change it. Nothing an
 * observer returns is read, and the bytes of a woven class are never offered to it.
 *
 * <h2>When each one arrives</h2>
 *
 * <ol>
 *   <li>{@link PluginsLoaded}, once, as soon as the plugin registry has been assembled and before
 *       anything has been planned. Every loaded plugin already knows its own identity from
 *       {@link PluginContext#self()}; this is how it learns who else is present.
 *   <li>{@link Prepared}, once, immediately after planning and before any class has been offered to
 *       the weaver. The one opportunity to see the whole plan.
 *   <li>{@link ClassWoven}, once for each class that was actually rewritten, stamped and handed
 *       back. Delivered only to plugins that called {@link PluginContext#observeApply()}.
 *   <li>{@link WeavingFinished}, once, when the driver declares the run over. A driver is not
 *       obliged to do so, and a load-time driver — where there is no last class — does not; the
 *       build-time Maven goal does, after the last directory has been rewritten.
 * </ol>
 *
 * <p>{@link ClassWoven} is the only one restricted to opted-in plugins. The other three go to every
 * plugin the registry accepted, which excludes any plugin whose
 * {@link WeaverPlugin#contribute(PluginContext)} threw: such a plugin is dropped from the registry
 * and hears nothing at all.
 *
 * <h2>Threading, and what a throw costs</h2>
 *
 * <p>{@link PluginsLoaded} and {@link Prepared} arrive on the thread building the weaver.
 * {@link ClassWoven} arrives on the thread that wove the class, which under the load-time driver is
 * the thread loading it, and that loader is parallel-capable — so an observer that keeps state has
 * to be prepared for concurrent delivery. {@link WeavingFinished} arrives on whichever thread the
 * driver ended the run on.
 *
 * <p>An observer that throws is reported as {@code AW3118} and weaving continues. It is the one
 * plugin failure that is a warning rather than an error, because an observer cannot change the woven
 * bytes and nothing was miswoven because of it. Observers are notified in registry order and a throw
 * from one does not stop the next from being told. A {@link VirtualMachineError} is rethrown rather
 * than reported.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Override
 * public void observe(PluginEvent event) {
 *     switch (event) {
 *         case PluginEvent.PluginsLoaded loaded ->
 *                 System.out.println(loaded.plugins().size() + " plugins");
 *         case PluginEvent.Prepared prepared ->
 *                 System.out.println(prepared.plan().size() + " modifications planned");
 *         case PluginEvent.ClassWoven woven ->
 *                 System.out.println("wove " + woven.internalName());
 *         case PluginEvent.WeavingFinished finished ->
 *                 System.out.println(finished.statistics().classesWoven() + " classes");
 *         default -> { }
 *     }
 * }
 * }</pre>
 *
 * <p>The interface is not sealed, so a {@code switch} over it needs a default branch and an
 * implementation must tolerate an event it does not recognise.
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaverPlugin#observe(PluginEvent)
 * @see PluginContext#observeApply()
 */
public interface PluginEvent {

    /**
     * The plugin registry has been assembled.
     *
     * <p>Published once, before planning, to every plugin that was accepted. The list names all of
     * them, including the framework's own built-in plugin — whose
     * {@link PluginId#namespace()} is the empty string — and including the plugin being told. A
     * plugin that was discovered and then refused, whether by the allowlist, by the API level gate,
     * by a namespace collision or by a throw from its own {@code contribute}, is not listed and is
     * not told.
     *
     * <p>The order is the registry's: the built-in plugin first, then the rest by namespace and then
     * by implementation class name.
     *
     * @param plugins the plugins that were loaded, in registry order; held as an unmodifiable copy
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record PluginsLoaded(@NotNull @Unmodifiable List<PluginId> plugins) implements PluginEvent {

        /**
         * Copies the list so that the event cannot change under an observer holding it.
         *
         * @throws NullPointerException if {@code plugins} is {@code null} or holds a {@code null}
         *                              element
         */
        public PluginsLoaded {
            plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        }
    }

    /**
     * Planning has finished and nothing has been woven yet.
     *
     * <p>Published once, to every loaded plugin, immediately after the plan is built and before the
     * first class is offered to the weaver. Conflicts between declarations have already been
     * detected and reported by the time this arrives, so a plan carrying entries that were reported
     * as conflicting is still delivered whole.
     *
     * <p>This is the only event carrying the plan, and the plan is what tells an observer which
     * classes the run intends to touch and what it intends to do to each of them.
     *
     * @param plan the plan, as it will be applied
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Prepared(@NotNull PlanView plan) implements PluginEvent {

        /**
         * Checks that a plan was given.
         *
         * @throws NullPointerException if {@code plan} is {@code null}
         */
        public Prepared {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * One class has been rewritten, verified and stamped.
     *
     * <p>Published to the plugins that called {@link PluginContext#observeApply()}, once per class the
     * weaver actually rewrites, verifies and stamps — whether that rewrite came from one or more plan
     * entries or, as {@link #entriesApplied()} then reports with {@code 0}, only from a dissolving
     * structural weave with no entries of its own. A class the weaver was offered and left alone
     * produces nothing, and neither does one whose rewrite the verifier refused, nor a class whose
     * only change is an extension call site rewritten with no plan entry of its own, so the count of
     * these events is not the count of classes whose bytes changed.
     *
     * @param internalName   the class's internal name, with slashes, as in {@code com/acme/Ledger}
     * @param entriesApplied how many plan entries were planned for this class, counted from
     *                       {@link PlanView#entriesFor(String)} before weaving began — so a
     *                       declaration the weaver went on to refuse is still counted here, and a
     *                       class rewritten only by a structural weave reports {@code 0}; never
     *                       negative
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record ClassWoven(@NotNull String internalName, int entriesApplied) implements PluginEvent {

        /**
         * Checks that a name was given and that the count is not negative.
         *
         * @throws NullPointerException     if {@code internalName} is {@code null}
         * @throws IllegalArgumentException if {@code entriesApplied} is negative
         */
        public ClassWoven {
            Objects.requireNonNull(internalName, "internalName");
            if (entriesApplied < 0) {
                throw new IllegalArgumentException(
                        "entriesApplied must not be negative, got: " + entriesApplied);
            }
        }
    }

    /**
     * The driver has declared the run over.
     *
     * <p>Published to every loaded plugin when the driver asks the weaver to finish, which is a
     * decision the driver takes rather than something the weaver detects. A build-time run ends
     * exactly once, after the last artefact has been rewritten; a load-time run has no last class
     * and no such moment, so a plugin must not treat this event as guaranteed.
     *
     * <p>The statistics are a snapshot taken at that moment. Because a driver may weave after
     * finishing — nothing prevents it — the snapshot is what was true when the driver asked, not a
     * final total the weaver enforces.
     *
     * @param statistics the counts as they stood when the run was declared over
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record WeavingFinished(@NotNull StatisticsView statistics) implements PluginEvent {

        /**
         * Checks that statistics were given.
         *
         * @throws NullPointerException if {@code statistics} is {@code null}
         */
        public WeavingFinished {
            Objects.requireNonNull(statistics, "statistics");
        }
    }
}
