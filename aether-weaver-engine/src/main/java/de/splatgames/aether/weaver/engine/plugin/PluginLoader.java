package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.ConfigView;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.SelectorResolver;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Admits plugins and collects what they contribute into a {@link PluginRegistry}.
 *
 * <p>Everything that decides whether a plugin runs is here, and each decision is made before the
 * plugin is asked to contribute. A plugin runs with the engine's privileges and a plugin from the
 * wrong SPI generation fails as a {@link LinkageError} from inside class loading, where it is far
 * harder to attribute — so a refused plugin is refused with a diagnostic and never called again.
 *
 * <p>The candidates are sorted by namespace and then by provider class name before any of them is
 * examined. Service loading follows the classpath and the file system, which differ between
 * machines, and that order would otherwise reach the registry, the plan fingerprint and every woven
 * class.
 *
 * <p>The gates call plugin code through {@link PluginIsolation}, so a plugin whose {@code id()}
 * throws while being examined is reported as {@code AW3114} and dropped rather than taking the run
 * down with it. Some call sites read {@code id()} directly instead of through the guard: the
 * comparator that sorts the candidates runs before any gate does, while the sites reached only after
 * a gate has already called {@code id()} successfully need no guard of their own.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PluginLoader {

    /** The oldest SPI generation this engine still admits. */
    public static final int MINIMUM_SUPPORTED_API_LEVEL = 1;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private PluginLoader() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the permission predicate that refuses nothing.
     *
     * @return a predicate accepting every plugin
     */
    @Contract(pure = true)
    @NotNull
    public static Predicate<PluginId> acceptAll() {
        return id -> true;
    }

    /**
     * Discovers plugins through {@link ServiceLoader} and loads what is permitted.
     *
     * @param loader        the class loader to search; must not be {@code null}
     * @param permitted     which namespaces may load; must not be {@code null}
     * @param configuration supplies each plugin's configuration; must not be {@code null}
     * @param listener      the sink for everything reported while loading; must not be {@code null}
     * @return the registry of what loaded
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    public static PluginRegistry discover(@NotNull final ClassLoader loader,
                                          @NotNull final Predicate<PluginId> permitted,
                                          @NotNull final Function<PluginId, ConfigView> configuration,
                                          @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(listener, "listener");
        return load(instantiate(loader, listener), permitted, configuration, listener);
    }

    /**
     * Loads plugins the caller already has, with no built-in plugin among them.
     *
     * <p>With no core, no {@link WeaverPlugin} contributes the built-in injection points and
     * injector kinds; the candidates still reach {@link #contribute} and register whatever kinds and
     * points their own factories offer.
     *
     * @param candidates    the plugins to consider; must not be {@code null}
     * @param permitted     which namespaces may load; must not be {@code null}
     * @param configuration supplies each plugin's configuration; must not be {@code null}
     * @param listener      the sink for everything reported while loading; must not be {@code null}
     * @return the registry of what loaded
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    public static PluginRegistry load(@NotNull final List<WeaverPlugin> candidates,
                                      @NotNull final Predicate<PluginId> permitted,
                                      @NotNull final Function<PluginId, ConfigView> configuration,
                                      @NotNull final DiagnosticListener listener) {
        return load(null, candidates, permitted, configuration, listener);
    }

    /**
     * Loads the built-in plugin and the candidates, and lets each contribute.
     *
     * <p>The core goes first and by a shorter path. It is exempt from {@code permitted} and from the
     * rule against claiming the built-in namespace, which it is the owner of; it is not exempt from
     * the version check, so a stale core cannot slip through either.
     *
     * <p>Every candidate passes four gates in order, each of which reports and drops the plugin
     * without touching the others:
     *
     * <ul>
     *   <li>It has an identity and does not claim the built-in namespace. {@code AW3114} when
     *       {@code id()} throws, {@code AW3101} when the namespace is the empty one. The literal
     *       {@code aether} cannot arrive here at all, because {@code PluginId} refuses it at
     *       construction.
     *   <li>It is permitted. {@code AW3119} otherwise, for a plugin that was handed in directly as
     *       much as for one that was discovered.
     *   <li>Its SPI generation is one this engine speaks. {@code AW3112} for a plugin from the
     *       future, {@code AW3113} for one older than {@link #MINIMUM_SUPPORTED_API_LEVEL}, and
     *       {@code AW3114} when {@code apiLevel()} itself throws.
     *   <li>Its namespace is unclaimed. {@code AW3111} otherwise, and the plugin that claimed it
     *       first is the one that keeps it.
     * </ul>
     *
     * @param core          the built-in plugin, or {@code null} for a registry without one
     * @param candidates    the plugins to consider; must not be {@code null}, and no element may be
     *                      {@code null}
     * @param permitted     which namespaces may load; must not be {@code null}
     * @param configuration supplies each plugin's configuration; a {@code null} answer is read as an
     *                      empty configuration; must not be {@code null}
     * @param listener      the sink for everything reported while loading; must not be {@code null}
     * @return the registry of what loaded and contributed
     * @throws NullPointerException if any argument other than {@code core} is {@code null}, or if
     *                              {@code candidates} holds a {@code null}
     */
    @NotNull
    public static PluginRegistry load(@Nullable final WeaverPlugin core,
                                      @NotNull final List<WeaverPlugin> candidates,
                                      @NotNull final Predicate<PluginId> permitted,
                                      @NotNull final Function<PluginId, ConfigView> configuration,
                                      @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(permitted, "permitted");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(listener, "listener");

        final List<WeaverPlugin> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator
                .comparing((final WeaverPlugin p) -> p.id().namespace())
                .thenComparing(p -> p.getClass().getName()));

        final Map<String, WeaverPlugin> byNamespace = new LinkedHashMap<>();
        final List<WeaverPlugin> accepted = new ArrayList<>();
        if (core != null) {
            // The built-in plugin is exempt from the allowlist and owns the built-in namespace.
            // It is still version-checked, so a stale core cannot slip through either.
            if (isCompatible(core, listener) && claimsFreeNamespace(core, byNamespace, listener)) {
                accepted.add(core);
            }
        }
        for (final WeaverPlugin plugin : ordered) {
            Objects.requireNonNull(plugin, "plugin");
            if (!ownsAPermittedNamespace(plugin, listener)
                    || !isPermitted(plugin, permitted, listener)
                    || !isCompatible(plugin, listener)
                    || !claimsFreeNamespace(plugin, byNamespace, listener)) {
                continue;
            }
            accepted.add(plugin);
        }

        return contribute(accepted, configuration, listener);
    }

    /**
     * Instantiates the plugins on a class loader without admitting or loading any of them.
     *
     * <p>This is how a caller obtains candidates it can add its own to before calling
     * {@link #load(WeaverPlugin, List, Predicate, Function, DiagnosticListener)}.
     *
     * @param loader   the class loader to search; must not be {@code null}
     * @param listener the sink for an instantiation failure; must not be {@code null}
     * @return the plugins that could be constructed
     * @throws NullPointerException if either argument is {@code null}
     */
    @NotNull
    public static List<WeaverPlugin> discovered(@NotNull final ClassLoader loader,
                                                @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(listener, "listener");
        return instantiate(loader, listener);
    }

    /**
     * Constructs every declared plugin, containing each constructor separately.
     *
     * <p>The provider stream is taken first and in full: a malformed service declaration makes
     * {@link ServiceLoader} throw before anything can be constructed, and there is then nothing to
     * salvage, so that case reports {@code AW3114} against the declaration and yields no plugins at
     * all. Once the providers are in hand, each is constructed on its own, so one plugin whose
     * constructor throws costs only itself.
     *
     * @param loader   the class loader to search; must not be {@code null}
     * @param listener the sink for an instantiation failure; must not be {@code null}
     * @return the plugins that could be constructed, in service order
     */
    @NotNull
    private static List<WeaverPlugin> instantiate(@NotNull final ClassLoader loader,
                                                  @NotNull final DiagnosticListener listener) {
        final List<WeaverPlugin> found = new ArrayList<>();
        final List<ServiceLoader.Provider<WeaverPlugin>> providers;
        try {
            providers = ServiceLoader.load(WeaverPlugin.class, loader).stream().toList();
        } catch (final ServiceConfigurationError malformed) {
            // A service file naming a class that does not exist or does not implement the
            // interface. Nothing is loadable, and the file itself is what has to be fixed.
            listener.report(Diagnostic.builder(DiagnosticCode.PLUGIN_INSTANTIATION_FAILED)
                    .message("a plugin service declaration could not be read")
                    .detail(malformed.getClass().getName() + ": " + malformed.getMessage())
                    .remedy("check every META-INF/services/"
                            + WeaverPlugin.class.getName()
                            + " on the classpath: each line must name a public class with a "
                            + "public no-argument constructor that implements WeaverPlugin")
                    .build());
            return List.of();
        }

        for (final ServiceLoader.Provider<WeaverPlugin> provider : providers) {
            // provider.type() does not construct anything, so a failing constructor can still be
            // attributed to a named class.
            final String who = provider.type().getName();
            PluginIsolation.call(who, PluginIsolation.Phase.INSTANTIATION, listener, provider::get)
                    .ifPresent(found::add);
        }
        return found;
    }

    /**
     * Refuses a plugin that has no identity or claims the built-in namespace, as {@code AW3101}.
     *
     * <p>The namespace refused here is the empty one, which is the framework's own: an unqualified
     * identifier such as {@code HEAD} belongs to it, and a plugin owning that namespace could shadow
     * a built-in point. The reserved spelling {@code aether} is refused earlier and elsewhere, by
     * {@code PluginId}'s own constructor.
     *
     * @param plugin   the plugin to check; must not be {@code null}
     * @param listener the sink for the diagnostic; must not be {@code null}
     * @return whether the plugin may go on to the next gate
     */
    private static boolean ownsAPermittedNamespace(@NotNull final WeaverPlugin plugin,
                                                   @NotNull final DiagnosticListener listener) {
        final Optional<PluginId> id = identify(plugin, listener);
        if (id.isEmpty()) {
            return false;
        }
        if (!id.get().namespace().isEmpty()) {
            return true;
        }
        listener.report(Diagnostic.builder(DiagnosticCode.PLUGIN_NAMESPACE_RESERVED)
                .message(plugin.getClass().getName()
                        + " claims the built-in namespace, which only Aether Weaver may use")
                .remedy("give the plugin its own namespace; unqualified identifiers such as "
                        + "'HEAD' and 'RETURN' belong to the framework, and a plugin claiming "
                        + "them could shadow a built-in point")
                .build());
        return false;
    }

    /**
     * Refuses a plugin the configuration does not admit, as {@code AW3119}.
     *
     * <p>Applied to every candidate, whether it was discovered or handed to the loader directly.
     * Only the built-in plugin skips this gate, and only because it never enters this loop.
     *
     * @param plugin    the plugin to check; must not be {@code null}
     * @param permitted the permission predicate; must not be {@code null}
     * @param listener  the sink for the diagnostic; must not be {@code null}
     * @return whether the plugin may go on to the next gate
     */
    private static boolean isPermitted(@NotNull final WeaverPlugin plugin,
                                       @NotNull final Predicate<PluginId> permitted,
                                       @NotNull final DiagnosticListener listener) {
        final Optional<PluginId> id = identify(plugin, listener);
        if (id.isEmpty()) {
            return false;
        }
        if (permitted.test(id.get())) {
            return true;
        }
        listener.report(Diagnostic.builder(DiagnosticCode.PLUGIN_NOT_ALLOWED)
                .message(id.get().describe() + " was discovered but is not permitted")
                .detail("provider: " + plugin.getClass().getName())
                .remedy("add '" + id.get().namespace() + "' to aether.weaver.plugins.allow, or "
                        + "remove the jar from the classpath — a plugin runs with full privileges, "
                        + "so refusing an unreviewed one is the safe default")
                .build());
        return false;
    }

    /**
     * Refuses a plugin built against an SPI generation this engine does not speak.
     *
     * <p>{@code AW3112} for a level above {@link WeaverApi#LEVEL} and {@code AW3113} for one below
     * {@link #MINIMUM_SUPPORTED_API_LEVEL}. The level is asked for through {@link PluginIsolation},
     * so an {@code apiLevel()} that throws is {@code AW3114} and also refuses the plugin: an engine
     * that cannot establish the generation cannot admit the plugin either.
     *
     * @param plugin   the plugin to check; must not be {@code null}
     * @param listener the sink for the diagnostic; must not be {@code null}
     * @return whether the plugin may go on to the next gate
     */
    private static boolean isCompatible(@NotNull final WeaverPlugin plugin,
                                        @NotNull final DiagnosticListener listener) {
        final Optional<PluginId> id = identify(plugin, listener);
        if (id.isEmpty()) {
            return false;
        }
        final Optional<Integer> declared = PluginIsolation.call(
                id.get().describe(), PluginIsolation.Phase.INSTANTIATION, listener,
                plugin::apiLevel);
        if (declared.isEmpty()) {
            return false;
        }
        final int level = declared.get();
        if (level > WeaverApi.LEVEL) {
            listener.report(Diagnostic.builder(DiagnosticCode.PLUGIN_API_LEVEL_TOO_NEW)
                    .message(id.get().describe() + " requires a newer Aether Weaver")
                    .detail("plugin was built against SPI level: " + level)
                    .detail("this engine provides SPI level:      " + WeaverApi.LEVEL)
                    .remedy("upgrade Aether Weaver, or use a build of "
                            + id.get().namespace() + " made for SPI level " + WeaverApi.LEVEL)
                    .build());
            return false;
        }
        if (level < MINIMUM_SUPPORTED_API_LEVEL) {
            listener.report(Diagnostic.builder(DiagnosticCode.PLUGIN_API_LEVEL_TOO_OLD)
                    .message(id.get().describe() + " was built against an SPI generation that is "
                            + "no longer supported")
                    .detail("plugin was built against SPI level: " + level)
                    .detail("oldest supported SPI level:         " + MINIMUM_SUPPORTED_API_LEVEL)
                    .remedy("upgrade " + id.get().namespace() + ", or pin an older Aether Weaver. "
                            + "Loading it anyway would fail later with a LinkageError thrown from "
                            + "inside class loading, where it is far harder to attribute")
                    .build());
            return false;
        }
        return true;
    }

    /**
     * Claims the plugin's namespace, refusing a second claimant as {@code AW3111}.
     *
     * <p>The first plugin to claim a namespace keeps it, which is why the candidates are sorted
     * before this runs: with two jars offering one namespace, the survivor has to be the same one on
     * every machine. {@code id()} is read here without the isolation guard, which is safe because
     * every path into this method has already had {@link #identify} or {@link #isCompatible} call
     * {@code id()} successfully.
     *
     * @param plugin      the plugin to admit; must not be {@code null}
     * @param byNamespace the namespaces claimed so far, updated here; must not be {@code null}
     * @param listener    the sink for the diagnostic; must not be {@code null}
     * @return whether the plugin may be accepted
     */
    private static boolean claimsFreeNamespace(@NotNull final WeaverPlugin plugin,
                                               @NotNull final Map<String, WeaverPlugin> byNamespace,
                                               @NotNull final DiagnosticListener listener) {
        final PluginId id = plugin.id();
        final WeaverPlugin previous = byNamespace.putIfAbsent(id.namespace(), plugin);
        if (previous == null) {
            return true;
        }
        listener.report(Diagnostic.builder(DiagnosticCode.PLUGIN_NAMESPACE_COLLISION)
                .message("two plugins claim the namespace '" + id.namespace() + '\'')
                .detail("loaded:  " + previous.id().describe()
                        + "  [" + previous.getClass().getName() + ']')
                .detail("refused: " + id.describe() + "  [" + plugin.getClass().getName() + ']')
                .remedy("a namespace has exactly one owner. Remove one of the two jars, or ask "
                        + "its author to rename — without a unique owner, an identifier such as '"
                        + id.namespace() + ":SOMETHING' has two meanings and neither can be "
                        + "attributed")
                .build());
        return false;
    }

    /**
     * Lets each accepted plugin contribute, and freezes the result.
     *
     * <p>A plugin that throws while contributing keeps nothing: its context is dropped whole, so
     * even what it registered before throwing does not reach the registry. A plugin that returns
     * normally is recorded as a contributor even if it registered nothing at all, since it is still
     * part of what the run consists of and therefore part of the fingerprint.
     *
     * <p>The identifiers a factory offers are registered in sorted order rather than in whatever
     * order the factory lists them, so the diagnostics a bad contribution produces come out in the
     * same sequence on every machine.
     *
     * @param accepted      the plugins that passed the gates; must not be {@code null}
     * @param configuration supplies each plugin's configuration, with {@code null} read as empty;
     *                      must not be {@code null}
     * @param listener      the sink for everything reported while contributing; must not be
     *                      {@code null}
     * @return the registry
     */
    @NotNull
    private static PluginRegistry contribute(@NotNull final List<WeaverPlugin> accepted,
                                             @NotNull final Function<PluginId, ConfigView> configuration,
                                             @NotNull final DiagnosticListener listener) {
        final NamespacedRegistry.Builder<InjectorFactory> injectors =
                NamespacedRegistry.builder("injector kind");
        final NamespacedRegistry.Builder<InjectionPointFactory> points =
                NamespacedRegistry.builder("injection point");
        final List<SelectorResolver> resolvers = new ArrayList<>();
        final List<DiagnosticListener> sinks = new ArrayList<>();
        final Map<String, String> metadata = new TreeMap<>();
        final List<WeaverPlugin> contributors = new ArrayList<>();
        final List<WeaverPlugin> applyObservers = new ArrayList<>();

        for (final WeaverPlugin plugin : accepted) {
            final PluginId id = plugin.id();
            final ConfigView config = Objects.requireNonNullElseGet(
                    configuration.apply(id), ConfigView::empty);
            final Reporter reporter = listener::report;
            final DefaultPluginContext context =
                    new DefaultPluginContext(id, config, reporter, listener);

            final boolean contributed = PluginIsolation.run(
                    id.describe(), PluginIsolation.Phase.CONTRIBUTE, listener,
                    () -> plugin.contribute(context));
            if (!contributed) {
                continue;
            }

            // Inside the guard, and not after it. contribute() returning does not mean the
            // plugin has stopped running: kinds(), ids() and aliases() are its code too, and
            // reading them outside the guard that covered contribute() let a throw from any of
            // them leave WeaverBuilder.build with no diagnostic.
            final boolean registered = PluginIsolation.run(
                    id.describe(), PluginIsolation.Phase.CONTRIBUTE, listener, () -> {
                        for (final InjectorFactory factory : context.injectorFactories()) {
                            factory.kinds().stream()
                                    .map(kind -> kind.id())
                                    .sorted()
                                    .forEach(kindId ->
                                            injectors.register(id.namespace(), kindId, factory));
                            factory.aliases()
                                    .forEach(alias -> injectors.alias(id.namespace(), alias));
                        }
                        for (final InjectionPointFactory factory : context.pointFactories()) {
                            factory.ids().stream().sorted()
                                    .forEach(pointId ->
                                            points.register(id.namespace(), pointId, factory));
                            factory.aliases()
                                    .forEach(alias -> points.alias(id.namespace(), alias));
                        }
                    });
            if (!registered) {
                continue;
            }
            resolvers.addAll(context.selectorResolvers());
            sinks.addAll(context.diagnosticSinks());
            metadata.putAll(context.contributedMetadata());

            contributors.add(plugin);
            if (context.observesApply()) {
                applyObservers.add(plugin);
            }
        }

        return new PluginRegistry(contributors, applyObservers,
                injectors.build(listener), points.build(listener),
                resolvers, sinks, metadata);
    }

    /**
     * Asks a plugin for its identity, containing a throw as {@code AW3114}.
     *
     * <p>Called by each gate rather than once, because there is nowhere to keep the answer before a
     * plugin has been accepted. A plugin whose {@code id()} throws is therefore reported once per
     * gate that reaches it, which is at most once: the first gate to see an empty answer drops the
     * plugin.
     *
     * <p>The plugin is named by its class here, since a plugin that cannot say who it is has no
     * other name to be reported under.
     *
     * @param plugin   the plugin to ask; must not be {@code null}
     * @param listener the sink for the diagnostic; must not be {@code null}
     * @return the identity, or empty when {@code id()} threw
     */
    @NotNull
    private static Optional<PluginId> identify(@NotNull final WeaverPlugin plugin,
                                               @NotNull final DiagnosticListener listener) {
        return PluginIsolation.call(plugin.getClass().getName(),
                PluginIsolation.Phase.INSTANTIATION, listener, plugin::id);
    }
}
