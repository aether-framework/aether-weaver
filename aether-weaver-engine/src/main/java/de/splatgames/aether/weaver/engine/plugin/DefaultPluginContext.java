package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.spi.ConfigView;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.SelectorResolver;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The context handed to one plugin's {@code contribute}, and the record of what it registered.
 *
 * <p>A context belongs to a single plugin for the duration of a single call. An injector or
 * injection point factory passes the namespace check before it is kept, so a factory reaching for
 * another plugin's identifiers is refused here rather than at the registry, where the offender
 * would be harder to name. A selector resolver or diagnostic sink carries no identifier and is kept
 * unconditionally. Refusal is per contribution: a rejected factory costs the plugin that factory
 * and nothing else, and the plugin still loads.
 *
 * <p>Nothing here is thread-safe and nothing needs to be; {@link PluginLoader} builds one, passes it
 * to one plugin, and drains it before moving on. A plugin that keeps the context and registers into
 * it after {@code contribute} has returned registers into an object nobody reads again.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class DefaultPluginContext implements PluginContext {

    /** How many metadata entries one plugin may contribute. */
    public static final int MAX_METADATA_ENTRIES = 8;

    /** How long a metadata key or value may be, in characters. */
    public static final int MAX_METADATA_LENGTH = 128;

    /** The plugin this context belongs to; its namespace decides what may be registered. */
    private final PluginId plugin;

    /** The plugin's own configuration. */
    private final ConfigView configuration;

    /** Where the plugin reports its own diagnostics. */
    private final Reporter reporter;

    /** Where this context reports a refused contribution. */
    private final DiagnosticListener listener;

    /** The accepted injector factories, in registration order. */
    private final List<InjectorFactory> injectors = new ArrayList<>();

    /** The accepted injection point factories, in registration order. */
    private final List<InjectionPointFactory> points = new ArrayList<>();

    /** The registered selector resolvers, in registration order. */
    private final List<SelectorResolver> resolvers = new ArrayList<>();

    /** The registered diagnostic sinks, in registration order. */
    private final List<DiagnosticListener> listeners = new ArrayList<>();

    /** The metadata, keyed by the namespaced key and sorted by it. */
    private final Map<String, String> metadata = new TreeMap<>();

    /** Whether the plugin asked to hear about each woven class. */
    private boolean observesApply;

    /**
     * Creates the context for one plugin.
     *
     * @param plugin        the plugin's identity; must not be {@code null}
     * @param configuration the plugin's configuration; must not be {@code null}
     * @param reporter      where the plugin reports its own diagnostics; must not be {@code null}
     * @param listener      where a refused contribution is reported; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultPluginContext(@NotNull final PluginId plugin,
                                @NotNull final ConfigView configuration,
                                @NotNull final Reporter reporter,
                                @NotNull final DiagnosticListener listener) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Returns the identity of the plugin this context belongs to.
     *
     * @return the plugin id
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public PluginId self() {
        return this.plugin;
    }

    /**
     * Returns the plugin's configuration, as the loader's caller supplied it.
     *
     * @return the configuration
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public ConfigView configuration() {
        return this.configuration;
    }

    /**
     * Returns the reporter the plugin should use instead of throwing.
     *
     * @return the reporter
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public Reporter diagnostics() {
        return this.reporter;
    }

    /**
     * Registers injector factories, refusing those that reach outside the plugin's namespace.
     *
     * <p>Two things are checked and either one drops the whole factory with {@code AW3110}: the
     * namespace the factory declares, and every kind identifier it offers. Both matter, because a
     * factory that declares the right namespace can still name a kind belonging to someone else.
     * The factories that pass are kept, so one bad factory among several does not lose the others.
     *
     * @param factories the factories to register; neither the array nor an element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code factories} or any element is {@code null}
     */
    @Override
    @NotNull
    public PluginContext injectors(@NotNull final InjectorFactory... factories) {
        for (final InjectorFactory factory : Objects.requireNonNull(factories, "factories")) {
            Objects.requireNonNull(factory, "factory");
            if (!ownsNamespace(factory.namespace(), factory.getClass().getName())) {
                continue;
            }
            final List<String> foreign = factory.kinds().stream()
                    .map(InjectorKind::id)
                    .filter(id -> !inNamespace(id))
                    .sorted()
                    .toList();
            if (!foreign.isEmpty()) {
                reportForeign("injector kind", factory.getClass().getName(), foreign);
                continue;
            }
            this.injectors.add(factory);
        }
        return this;
    }

    /**
     * Registers injection point factories, refusing those that reach outside the plugin's namespace.
     *
     * <p>Checked exactly as {@link #injectors(InjectorFactory...)} is, against the factory's
     * declared namespace and each point identifier it offers, and refused with {@code AW3110}.
     *
     * @param factories the factories to register; neither the array nor an element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code factories} or any element is {@code null}
     */
    @Override
    @NotNull
    public PluginContext points(@NotNull final InjectionPointFactory... factories) {
        for (final InjectionPointFactory factory : Objects.requireNonNull(factories, "factories")) {
            Objects.requireNonNull(factory, "factory");
            if (!ownsNamespace(factory.namespace(), factory.getClass().getName())) {
                continue;
            }
            final List<String> foreign = factory.ids().stream()
                    .filter(id -> !inNamespace(id))
                    .sorted()
                    .toList();
            if (!foreign.isEmpty()) {
                reportForeign("injection point", factory.getClass().getName(), foreign);
                continue;
            }
            this.points.add(factory);
        }
        return this;
    }

    /**
     * Registers selector resolvers.
     *
     * <p>A resolver carries no identifier, so there is no namespace to check and nothing is refused.
     *
     * @param resolvers the resolvers to register; neither the array nor an element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code resolvers} or any element is {@code null}
     */
    @Override
    @NotNull
    public PluginContext selectorResolvers(@NotNull final SelectorResolver... resolvers) {
        for (final SelectorResolver resolver : Objects.requireNonNull(resolvers, "resolvers")) {
            this.resolvers.add(Objects.requireNonNull(resolver, "resolver"));
        }
        return this;
    }

    /**
     * Registers diagnostic sinks that will see every diagnostic of the run.
     *
     * @param sinks the sinks to register; neither the array nor an element may be {@code null}
     * @return this context
     * @throws NullPointerException if {@code sinks} or any element is {@code null}
     */
    @Override
    @NotNull
    public PluginContext diagnosticListeners(@NotNull final DiagnosticListener... sinks) {
        for (final DiagnosticListener sink : Objects.requireNonNull(sinks, "sinks")) {
            this.listeners.add(Objects.requireNonNull(sink, "sink"));
        }
        return this;
    }

    /**
     * Records one metadata entry under the plugin's namespace.
     *
     * <p>The key is prefixed with the plugin's namespace and a colon, so a plugin cannot name an
     * entry that another plugin's key would collide with, and re-using a key it has already written
     * overwrites that entry rather than counting again against the limit.
     *
     * <p>This is the one place in the context that throws rather than reporting. Metadata can end up
     * written into a woven class's {@code Woven} annotation when {@code wovenDetail} requests it, so
     * the three bounds are size limits on the output rather than advice, and a plugin that exceeds
     * them is failing in a way {@link PluginIsolation} will report as {@code AW3115} along with
     * everything else it contributed.
     *
     * @param key   the key, without a namespace; must not be blank
     * @param value the value
     * @return this context
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the key is blank, if either the key or the value is longer
     *                                  than {@link #MAX_METADATA_LENGTH}, or if a new key would take
     *                                  the plugin past {@link #MAX_METADATA_ENTRIES} entries
     */
    @Override
    @NotNull
    public PluginContext metadata(@NotNull final String key, @NotNull final String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a metadata key must not be blank");
        }
        if (key.length() > MAX_METADATA_LENGTH || value.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("a metadata key and value may each be at most "
                    + MAX_METADATA_LENGTH + " characters; this is written into every woven class");
        }
        final String namespaced = this.plugin.namespace() + ':' + key;
        if (!this.metadata.containsKey(namespaced) && this.metadata.size() >= MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("a plugin may contribute at most "
                    + MAX_METADATA_ENTRIES + " metadata entries; this is written into every woven "
                    + "class, so it is bounded on purpose");
        }
        this.metadata.put(namespaced, value);
        return this;
    }

    /**
     * Records that the plugin wants an event for each woven class.
     *
     * <p>Opting in more than once is the same as opting in once, and there is no way to opt out
     * again.
     *
     * @return this context
     */
    @Override
    @NotNull
    public PluginContext observeApply() {
        this.observesApply = true;
        return this;
    }

    /**
     * Returns the injector factories that passed the namespace checks.
     *
     * @return the factories, in registration order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<InjectorFactory> injectorFactories() {
        return List.copyOf(this.injectors);
    }

    /**
     * Returns the injection point factories that passed the namespace checks.
     *
     * @return the factories, in registration order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<InjectionPointFactory> pointFactories() {
        return List.copyOf(this.points);
    }

    /**
     * Returns the registered selector resolvers.
     *
     * @return the resolvers, in registration order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<SelectorResolver> selectorResolvers() {
        return List.copyOf(this.resolvers);
    }

    /**
     * Returns the registered diagnostic sinks.
     *
     * @return the sinks, in registration order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<DiagnosticListener> diagnosticSinks() {
        return List.copyOf(this.listeners);
    }

    /**
     * Returns the metadata the plugin recorded, keys already namespaced.
     *
     * <p>The result is an immutable copy whose iteration order is unspecified; the sorted order
     * these entries end up in is imposed by {@link PluginRegistry}, which is what the fingerprint
     * and the stamp read.
     *
     * @return the metadata
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public Map<String, String> contributedMetadata() {
        return Map.copyOf(new LinkedHashMap<>(this.metadata));
    }

    /**
     * Reports whether the plugin asked to hear about each woven class.
     *
     * @return whether it opted in
     */
    @Contract(pure = true)
    public boolean observesApply() {
        return this.observesApply;
    }

    /**
     * Checks that a factory declares the plugin's own namespace, reporting {@code AW3110} if not.
     *
     * <p>Compared exactly, so the built-in namespace — the empty string — is claimable only by a
     * plugin whose own namespace is empty.
     *
     * @param declared the namespace the factory declares; must not be {@code null}
     * @param source   the factory's class name, for the diagnostic; must not be {@code null}
     * @return whether the factory may be kept
     */
    private boolean ownsNamespace(@NotNull final String declared, @NotNull final String source) {
        if (this.plugin.namespace().equals(declared)) {
            return true;
        }
        this.listener.report(
                Diagnostic.builder(DiagnosticCode.PLUGIN_CONTRIBUTION_OUTSIDE_NAMESPACE)
                        .message(this.plugin.describe() + " registered a factory declaring the "
                                + "namespace '" + declared + "'")
                        .detail("factory:   " + source)
                        .detail("namespace: " + this.plugin.namespace())
                        .remedy("a plugin may only register contributions in its own namespace; "
                                + "change the factory's namespace() to '"
                                + this.plugin.namespace() + '\'')
                        .build());
        return false;
    }

    /**
     * Reports {@code AW3110} for identifiers a factory offers outside its plugin's namespace.
     *
     * <p>Every offending identifier is listed rather than only the first, so one rebuild is enough
     * to see the whole of what has to be renamed. The caller has sorted them.
     *
     * @param what    what kind of identifier this is, for the message; must not be {@code null}
     * @param source  the factory's class name; must not be {@code null}
     * @param foreign the offending identifiers, sorted and non-empty; must not be {@code null}
     */
    private void reportForeign(@NotNull final String what,
                               @NotNull final String source,
                               @NotNull final List<String> foreign) {
        final Diagnostic.Builder builder =
                Diagnostic.builder(DiagnosticCode.PLUGIN_CONTRIBUTION_OUTSIDE_NAMESPACE)
                        .message(this.plugin.describe() + " registered " + foreign.size() + ' '
                                + what + (foreign.size() == 1 ? "" : "s")
                                + " outside its namespace '" + this.plugin.namespace() + '\'')
                        .detail("factory: " + source);
        for (final String id : foreign) {
            builder.detail("outside: " + id);
        }
        this.listener.report(builder
                .remedy("prefix each with '" + this.plugin.namespace() + ":'; an identifier that "
                        + "does not name its owner cannot be attributed in a diagnostic and "
                        + "cannot be switched off as a set")
                .build());
    }

    /**
     * Reports whether an identifier belongs to this plugin.
     *
     * <p>The framework's own namespace is the empty one, and its identifiers carry no prefix at
     * all; every other plugin must prefix its own namespace and a colon, with something after it.
     *
     * @param id the identifier to test; must not be {@code null}
     * @return whether the plugin may register it
     */
    @Contract(pure = true)
    private boolean inNamespace(@NotNull final String id) {
        if (this.plugin.namespace().isEmpty()) {
            // The built-in namespace owns every unqualified identifier, which is why Point.HEAD
            // spells as "HEAD" and not "aether:HEAD".
            return !id.isEmpty() && id.indexOf(':') < 0;
        }
        final String prefix = this.plugin.namespace() + ':';
        return id.startsWith(prefix) && id.length() > prefix.length();
    }

    /**
     * Returns the plugin's coordinate and how much it has registered so far.
     *
     * @return the summary rendering
     */
    @Override
    @NotNull
    public String toString() {
        return "DefaultPluginContext[" + this.plugin.coordinate() + ", "
                + this.injectors.size() + " injector factories, "
                + this.points.size() + " point factories]";
    }
}
