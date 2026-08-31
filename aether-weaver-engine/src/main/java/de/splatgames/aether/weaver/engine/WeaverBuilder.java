package de.splatgames.aether.weaver.engine;

import de.splatgames.aether.weaver.api.Woven;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.spi.ConfigView;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.PluginEvent;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.WeavePolicy;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import de.splatgames.aether.weaver.engine.extension.ExtensionIndex;
import de.splatgames.aether.weaver.engine.explain.ExplainReport;
import de.splatgames.aether.weaver.engine.inject.CorePlugin;
import de.splatgames.aether.weaver.engine.merge.WeaveBytes;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.plan.WeavePlan;
import de.splatgames.aether.weaver.engine.plan.WeavePlanner;
import de.splatgames.aether.weaver.engine.plugin.PluginLoader;
import de.splatgames.aether.weaver.engine.plugin.PluginRegistry;
import de.splatgames.aether.weaver.engine.policy.DefaultWeavePolicy;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import de.splatgames.aether.weaver.engine.verify.Verifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Collects everything a {@link Weaver} needs and, in {@link #build()}, performs the one step that
 * cannot be deferred: planning.
 *
 * <p>Planning is where weaves are matched against targets and where a conflict between two of them
 * is reported, so a builder is not a passive holder of settings. Diagnostics reach the listener
 * during {@link #build()} and a weaver is returned regardless — a plan that reported errors is
 * still a plan, and refusing to return one would leave a driver unable to print what went wrong.
 *
 * <p>Every setter overwrites, except {@link #weaves(Collection)} and {@link #plugin(WeaverPlugin)},
 * which append. A builder is not reusable in any meaningful sense: calling {@link #build()} twice
 * plans twice and produces two weavers that share nothing but their inputs.
 *
 * <p>Not thread-safe. The weaver it returns is.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaverBuilder {

    /** The weaves to plan, in the order they were added. */
    private final List<WeaveClass> weaves = new ArrayList<>();

    /** Where a weave class's own bytes come from, which structural weaving needs and injection
     * does not. */
    private WeaveBytes weaveBytes = WeaveBytes.NONE;

    /** Plugins registered by hand, which are offered to the loader ahead of any discovered ones. */
    private final List<WeaverPlugin> plugins = new ArrayList<>();

    /** Where diagnostics go; the default discards them. */
    private DiagnosticListener listener = DiagnosticListener.NOOP;

    /** What may be woven; replaced wholesale rather than composed by {@link #policy(WeavePolicy)}. */
    private WeavePolicy policy = DefaultWeavePolicy.standard();

    /** How a class that fails verification is treated; the default refuses it by throwing. */
    private VerificationPolicy verification = VerificationPolicy.STRICT;

    /** Whether to build an {@link ExplainReport}, which also decides whether diagnostics are
     * counted. */
    private boolean explain;

    /** The loader to search for plugins, or {@code null} to search none. */
    private @Nullable ClassLoader discoveryLoader;

    /** Which registered and discovered plugins may load; the built-in plugin is exempt from it. */
    private Predicate<PluginId> permitted = PluginLoader.acceptAll();

    /** How much a woven class records about itself in its {@code @Woven} annotation. */
    private Woven.Detail detail = Woven.Detail.SUMMARY;

    /** Which driver this weaver serves, which decides how an already-woven class is treated. */
    private Weaver.Driver driver = Weaver.Driver.BUILD;

    /** The extensions to rewrite calls to; empty means the extension pass is skipped entirely. */
    private ExtensionIndex extensions = ExtensionIndex.EMPTY;

    /**
     * Creates a builder with every default in place.
     *
     * <p>Package-private: {@link Weaver#builder()} is the way in.
     */
    WeaverBuilder() {
        // Created through Weaver.builder().
    }

    /**
     * Adds weaves to plan.
     *
     * <p>Appends, so several calls accumulate. Each element is checked for null as it is added,
     * which is why a partially valid collection leaves the ones before the offender in place.
     *
     * @param weaves the weaves to add; must not be {@code null} and must hold no {@code null}
     * @return this builder
     * @throws NullPointerException if the collection or any element is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder weaves(@NotNull final Collection<WeaveClass> weaves) {
        for (final WeaveClass weave : Objects.requireNonNull(weaves, "weaves")) {
            this.weaves.add(Objects.requireNonNull(weave, "weave"));
        }
        return this;
    }

    /**
     * Sets where the bytes of a weave class itself are read from.
     *
     * <p>Needed only by structural weaving, which copies members out of the weave class. The
     * default supplies none, so a run that merges members without this reaches the structural
     * weaver with nothing to copy.
     *
     * @param weaveBytes the source; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code weaveBytes} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder weaveBytes(@NotNull final WeaveBytes weaveBytes) {
        this.weaveBytes = Objects.requireNonNull(weaveBytes, "weaveBytes");
        return this;
    }

    /**
     * Sets the weave class bytes to come from a {@link ClassSource}.
     *
     * <p>Shorthand for {@link #weaveBytes(WeaveBytes)} over {@code WeaveBytes.from(source)}.
     *
     * @param source where class files are read from; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code source} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder classSource(@NotNull final ClassSource source) {
        return weaveBytes(WeaveBytes.from(Objects.requireNonNull(source, "source")));
    }

    /**
     * Replaces the policy that decides which classes may be woven.
     *
     * <p>Replaces rather than adds: the built-in checks are gone unless the argument composes them
     * back in, which is what {@link WeavePolicy#and(WeavePolicy)} is for.
     *
     * @param policy the policy; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code policy} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder policy(@NotNull final WeavePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Sets what happens to a woven class the verifier refuses.
     *
     * @param verification the policy; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code verification} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder verification(@NotNull final VerificationPolicy verification) {
        this.verification = Objects.requireNonNull(verification, "verification");
        return this;
    }

    /**
     * Asks for an {@link ExplainReport}.
     *
     * <p>This also decides whether diagnostics are counted at all: without it the weaver's
     * {@link Weaver#explain()} falls back to the plan's own terse listing, which has no footer to
     * count towards.
     *
     * @param explain whether to build a report
     * @return this builder
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder explain(final boolean explain) {
        this.explain = explain;
        return this;
    }

    /**
     * Sets where diagnostics go, including those reported during {@link #build()}.
     *
     * @param listener the listener; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder diagnostics(@NotNull final DiagnosticListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Registers a plugin instance directly, without discovery.
     *
     * <p>It is still subject to the namespace and version checks, and to
     * {@link #permitPlugins(Predicate)}. The order plugins are added in does not survive: the
     * loader sorts by namespace and class name before contributing any of them.
     *
     * @param plugin the plugin; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code plugin} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder plugin(@NotNull final WeaverPlugin plugin) {
        this.plugins.add(Objects.requireNonNull(plugin, "plugin"));
        return this;
    }

    /**
     * Asks for plugins to be discovered through the given loader as well.
     *
     * @param loader the loader to search; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code loader} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder discoverPlugins(@NotNull final ClassLoader loader) {
        this.discoveryLoader = Objects.requireNonNull(loader, "loader");
        return this;
    }

    /**
     * Restricts which plugins may load.
     *
     * <p>Applies to registered and discovered plugins alike. The built-in plugin is not offered to
     * it, so no predicate can produce a weaver without injectors.
     *
     * @param permitted the predicate; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code permitted} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder permitPlugins(@NotNull final Predicate<PluginId> permitted) {
        this.permitted = Objects.requireNonNull(permitted, "permitted");
        return this;
    }

    /**
     * Joins two plugin lists into a new one, leaving both alone.
     *
     * @param first  the first list
     * @param second the second list
     * @return a new list holding both
     */
    @Contract(pure = true)
    @NotNull
    private static List<WeaverPlugin> concat(@NotNull final List<WeaverPlugin> first,
                                             @NotNull final List<WeaverPlugin> second) {
        final List<WeaverPlugin> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /**
     * Says which driver the weaver serves.
     *
     * <p>The one thing this changes is what happens to a class that already carries somebody else's
     * weave record: {@link Weaver.Driver#BUILD} refuses it, {@link Weaver.Driver#LOAD} warns and
     * weaves it anyway.
     *
     * @param driver the driver; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code driver} is {@code null}
     */
    @NotNull
    public WeaverBuilder driver(@NotNull final Weaver.Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        return this;
    }

    /**
     * Sets how much a woven class records about itself.
     *
     * <p>Only the {@code @Woven} annotation is affected. The {@code AetherWeave} attribute is
     * written whatever this says, and it is the attribute the idempotence gate reads.
     *
     * @param detail the detail level; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code detail} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder wovenDetail(@NotNull final Woven.Detail detail) {
        this.detail = Objects.requireNonNull(detail, "detail");
        return this;
    }

    /**
     * Sets the extensions whose call sites are to be rewritten.
     *
     * <p>An index that is not empty makes the weaver examine every class it is offered, including
     * those the plan names nowhere.
     *
     * @param extensions the index; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code extensions} is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    public WeaverBuilder extensions(@NotNull final ExtensionIndex extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        return this;
    }

    /**
     * Loads the plugins, plans the weaves and returns the weaver.
     *
     * <p>The order matters and is not free to change. The counting listener is installed before
     * anything else, because conflict detection runs inside planning and a footer that counted only
     * what happened afterwards would report no errors for the very run somebody switched
     * {@code explain} on to understand. The report itself cannot exist that early — it needs the
     * plan — so the severities seen before it appears are buffered and replayed into it.
     *
     * <p>Plugins are loaded before planning because {@link WeavePlanner#plan} needs the registry to
     * build the plan's fingerprint: {@code PlanFingerprint.of} folds in the registry's plugin list,
     * its injector and point ids, and its metadata, so the registry has to exist before planning can
     * return. Each plugin is given an empty {@code ConfigView}: nothing at this level knows where a
     * driver's configuration would come from.
     *
     * <p>Two events are published here, {@code PluginsLoaded} and {@code Prepared}; {@code publish}
     * delivers each to every plugin, not to a listener. {@code sink} is passed along only as the
     * destination for a diagnostic raised when a plugin's {@code observe} throws, and with
     * {@code explain(true)} set that destination is the counting listener rather than the caller's
     * own.
     *
     * @return the weaver, whether or not planning reported errors
     */
    @NotNull
    public Weaver build() {
        // Counting starts before planning. Conflict detection runs inside plan(…), so a footer
        // that only counted what happened afterwards would report "Errors: 0" for a run whose
        // conflicts had already been reported — the exact run somebody switched explain on for.
        final Counting counting = this.explain ? new Counting(this.listener) : null;
        final DiagnosticListener sink = counting == null ? this.listener : counting;

        // The built-in plugin is installed here rather than discovered: it owns the built-in
        // namespace, which no discovered plugin may claim, and a weaver without it would have no
        // HEAD, no RETURN and no injectors — a weaver that cannot weave.
        final List<WeaverPlugin> discovered = this.discoveryLoader == null
                ? this.plugins
                : concat(this.plugins, PluginLoader.discovered(this.discoveryLoader, sink));

        final PluginRegistry registry = PluginLoader.load(new CorePlugin(), discovered,
                this.permitted, id -> ConfigView.empty(), sink);

        registry.publish(new PluginEvent.PluginsLoaded(registry.plugins()), sink);

        final WeavePlan plan = new WeavePlanner(sink).plan(this.weaves, registry);
        registry.publish(new PluginEvent.Prepared(plan), sink);

        final List<GroupSpec> groups = new ArrayList<>();
        this.weaves.forEach(weave -> groups.addAll(weave.groups()));

        ExplainReport report = null;
        if (counting != null) {
            report = new ExplainReport(Weaver.VERSION, plan);
            counting.into(report);
        }

        return new Weaver(plan, this.policy,
                new Verifier(this.verification, sink), registry, groups,
                this.detail, this.weaveBytes, this.extensions, sink, this.driver, report);
    }

    /**
     * A listener that tallies severities for the report's footer and passes everything on.
     *
     * <p>It exists because of an ordering problem: diagnostics start arriving before the
     * {@link ExplainReport} they are to be counted into can be created. Until
     * {@link #into(ExplainReport)} is called the severities are held; afterwards they go straight
     * to the report.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Counting implements DiagnosticListener {

        /** Where every diagnostic goes on to, counted or not. */
        private final DiagnosticListener downstream;

        /**
         * Severities seen before the report existed.
         *
         * <p>Only reached while {@link #report} is {@code null}, which is between construction and
         * the single call to {@link #into(ExplainReport)}, on the thread building the weaver.
         */
        private final List<Severity> before = new ArrayList<>();

        /** The report, once it exists; volatile because weaving threads read it. */
        private volatile @Nullable ExplainReport report;

        /**
         * Wraps a listener.
         *
         * @param downstream where diagnostics go on to
         */
        Counting(@NotNull final DiagnosticListener downstream) {
            this.downstream = downstream;
        }

        /**
         * Replays what was buffered into the report and sends everything there from now on.
         *
         * <p>Only the severities are replayed. The diagnostics themselves already went downstream
         * as they arrived, so nothing is reported twice.
         *
         * @param target the report to count into
         */
        void into(@NotNull final ExplainReport target) {
            this.before.forEach(target::note);
            this.before.clear();
            this.report = target;
        }

        /**
         * Counts a diagnostic's severity and passes the diagnostic on unchanged.
         *
         * @param diagnostic the diagnostic
         */
        @Override
        public void report(@NotNull final Diagnostic diagnostic) {
            final ExplainReport target = this.report;
            if (target == null) {
                this.before.add(diagnostic.severity());
            } else {
                target.note(diagnostic.severity());
            }
            this.downstream.report(diagnostic);
        }
    }
}
