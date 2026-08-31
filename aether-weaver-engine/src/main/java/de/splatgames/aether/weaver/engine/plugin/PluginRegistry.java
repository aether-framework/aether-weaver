package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.PluginEvent;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.SelectorResolver;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the plugins contributed, frozen once loading is over.
 *
 * <p>{@link PluginLoader} builds one for a real run; the only other instance is the shared empty
 * registry built below. Everything it holds is immutable from then on: the plugins that survived
 * the gates, the identifiers they registered, the resolvers and listeners they added, and their
 * metadata. The weaver reads it on every class it weaves, so nothing here may change once weaving has
 * begun — a registry that grew a point halfway through a run would make the plan's fingerprint
 * describe a set of contributions that no longer exists.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PluginRegistry {

    /** The registry of a run with no plugins; immutable, so one instance serves every caller. */
    private static final PluginRegistry EMPTY = new PluginRegistry(
            List.of(), List.of(),
            NamespacedRegistry.<InjectorFactory>builder("injector kind")
                    .build(DiagnosticListener.NOOP),
            NamespacedRegistry.<InjectionPointFactory>builder("injection point")
                    .build(DiagnosticListener.NOOP),
            List.of(), List.of(), Map.of());

    /** The plugins that loaded and contributed, in the order the loader accepted them. */
    private final List<WeaverPlugin> plugins;

    /** The subset of {@link #plugins} that asked to hear about each woven class. */
    private final List<WeaverPlugin> applyObservers;

    /** The injector kinds, built-in and contributed. */
    private final NamespacedRegistry<InjectorFactory> injectors;

    /** The injection points, built-in and contributed. */
    private final NamespacedRegistry<InjectionPointFactory> points;

    /** The contributed selector resolvers, in contribution order. */
    private final List<SelectorResolver> selectorResolvers;

    /** The contributed diagnostic sinks, in contribution order. */
    private final List<DiagnosticListener> diagnosticListeners;

    /**
     * The contributed metadata, sorted by key; folded into the plan fingerprint and written into a
     * woven class's {@code Woven} annotation when {@code wovenDetail} requests it.
     */
    private final Map<String, String> metadata;

    /**
     * Freezes what the loader collected.
     *
     * <p>Package-private: the invariants that make this safe to read from the weaving path — a
     * namespace with one owner, an API level that was checked — are established by
     * {@link PluginLoader} and cannot be checked here.
     *
     * @param plugins             the accepted plugins; must not be {@code null}
     * @param applyObservers      those among them that opted into apply events; must not be
     *                            {@code null}
     * @param injectors           the injector kind registry; must not be {@code null}
     * @param points              the injection point registry; must not be {@code null}
     * @param selectorResolvers   the contributed selector resolvers; must not be {@code null}
     * @param diagnosticListeners the contributed diagnostic sinks; must not be {@code null}
     * @param metadata            the contributed metadata, re-sorted by key here; must not be
     *                            {@code null}
     * @throws NullPointerException if any argument is {@code null} or holds a {@code null}
     */
    PluginRegistry(@NotNull final List<WeaverPlugin> plugins,
                   @NotNull final List<WeaverPlugin> applyObservers,
                   @NotNull final NamespacedRegistry<InjectorFactory> injectors,
                   @NotNull final NamespacedRegistry<InjectionPointFactory> points,
                   @NotNull final List<SelectorResolver> selectorResolvers,
                   @NotNull final List<DiagnosticListener> diagnosticListeners,
                   @NotNull final Map<String, String> metadata) {
        this.plugins = List.copyOf(plugins);
        this.applyObservers = List.copyOf(applyObservers);
        this.injectors = Objects.requireNonNull(injectors, "injectors");
        this.points = Objects.requireNonNull(points, "points");
        this.selectorResolvers = List.copyOf(selectorResolvers);
        this.diagnosticListeners = List.copyOf(diagnosticListeners);
        this.metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
    }

    /**
     * Returns the registry of a run with no plugins.
     *
     * <p>Its two identifier registries are empty as well, so a weaver built on it resolves no point
     * and no injector kind at all.
     *
     * @return the shared empty registry
     */
    @Contract(pure = true)
    @NotNull
    public static PluginRegistry empty() {
        return EMPTY;
    }

    /**
     * Returns the identity of each loaded plugin.
     *
     * <p>Asks every plugin for its {@code id()} again on each call and builds a new list, which
     * matters because this is called once per class the weaver stamps as well as when the
     * fingerprint is built. Unlike the loader's calls, this one is outside
     * {@link PluginIsolation}: a plugin whose {@code id()} throws here throws through the weaving.
     *
     * @return the plugin ids, in the order the plugins were accepted
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<PluginId> plugins() {
        final List<PluginId> ids = new ArrayList<>(this.plugins.size());
        for (final WeaverPlugin plugin : this.plugins) {
            ids.add(plugin.id());
        }
        return List.copyOf(ids);
    }

    /**
     * Returns the injector kinds this run understands.
     *
     * @return the injector kind registry
     */
    @Contract(pure = true)
    @NotNull
    public NamespacedRegistry<InjectorFactory> injectors() {
        return this.injectors;
    }

    /**
     * Returns the injection points this run understands.
     *
     * @return the injection point registry
     */
    @Contract(pure = true)
    @NotNull
    public NamespacedRegistry<InjectionPointFactory> points() {
        return this.points;
    }

    /**
     * Returns the contributed selector resolvers.
     *
     * @return the resolvers, in contribution order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<SelectorResolver> selectorResolvers() {
        return this.selectorResolvers;
    }

    /**
     * Returns the diagnostic sinks the plugins added.
     *
     * @return the listeners, in contribution order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<DiagnosticListener> diagnosticListeners() {
        return this.diagnosticListeners;
    }

    /**
     * Returns the contributed metadata, keyed by namespaced key.
     *
     * <p>Sorted, because it is folded into the plan fingerprint and, when {@code wovenDetail}
     * requests it, written into a woven class's {@code Woven} annotation: an iteration order that
     * depended on contribution order would make two identical builds disagree.
     *
     * @return the metadata
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public Map<String, String> metadata() {
        return this.metadata;
    }

    /**
     * Reports whether any plugin asked to hear about each woven class.
     *
     * <p>These are the audience {@link #publish} picks for a {@link PluginEvent.ClassWoven}; the
     * other events go to every plugin.
     *
     * @return whether there is an apply observer
     */
    @Contract(pure = true)
    public boolean hasApplyObservers() {
        return !this.applyObservers.isEmpty();
    }

    /**
     * Reports whether any plugin loaded.
     *
     * @return whether the registry holds no plugin
     */
    @Contract(pure = true)
    public boolean isEmpty() {
        return this.plugins.isEmpty();
    }

    /**
     * Delivers an event to the plugins.
     *
     * <p>A {@link PluginEvent.ClassWoven} goes only to the plugins that opted in, since it is
     * published once per woven class; every other event goes to all of them. Each delivery is
     * isolated on its own, so one observer that throws is reported as {@code AW3118} and the
     * remaining plugins still hear the event. A plugin that throws stays registered and is offered
     * the next event as well.
     *
     * <p>{@code listener} is where such a failure is reported. It is not the audience: an event
     * reaches plugins, never a {@link DiagnosticListener}.
     *
     * @param event    the event to deliver; must not be {@code null}
     * @param listener the sink for a failing observer's diagnostic; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void publish(@NotNull final PluginEvent event,
                        @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(listener, "listener");

        final List<WeaverPlugin> audience =
                event instanceof PluginEvent.ClassWoven ? this.applyObservers : this.plugins;
        for (final WeaverPlugin plugin : audience) {
            PluginIsolation.run(plugin.id().describe(), PluginIsolation.Phase.OBSERVE, listener,
                    () -> plugin.observe(event));
        }
    }

    /**
     * Renders the loaded plugins as namespace-to-version pairs.
     *
     * <p>Two plugins cannot share a namespace, so nothing is lost by keying on it.
     *
     * @return the summary rendering
     */
    @Override
    @NotNull
    public String toString() {
        final Map<String, String> coordinates = new LinkedHashMap<>();
        for (final WeaverPlugin plugin : this.plugins) {
            coordinates.put(plugin.id().namespace(), plugin.id().version());
        }
        return "PluginRegistry" + coordinates;
    }
}
