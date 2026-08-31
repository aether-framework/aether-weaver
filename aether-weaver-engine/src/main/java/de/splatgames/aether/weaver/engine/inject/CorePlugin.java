package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.spi.Alias;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;
import java.util.Set;

/**
 * Contributes the injection points and injectors the engine ships with, as an ordinary plugin.
 *
 * <p>The framework's own contribution goes through the same {@link WeaverPlugin} interface a third
 * party implements, and is installed by the weaver builder into the same registry as whatever was
 * discovered. Registration, lookup and namespacing are therefore one code path rather than two, and
 * the built-in points exercise it on every run.
 *
 * <p>The one thing it does differently is its namespace. It claims
 * {@link PluginId#BUILT_IN_NAMESPACE}, the empty string, which no discovered plugin may take, so
 * {@code HEAD} and {@code inject} are written unqualified while a contributed identifier carries
 * its owner's prefix.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable, and so is everything it contributes.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CorePlugin implements WeaverPlugin {

    /** Identifies the plugin. The version is a literal here and is not read from the build. */
    private static final PluginId ID = new PluginId(
            PluginId.BUILT_IN_NAMESPACE, "Aether Weaver", "0.1.0");

    /**
     * Creates the plugin.
     *
     * <p>Public and no-argument because a plugin is instantiated by whoever installs it; this one is
     * constructed by the weaver builder rather than discovered, since a weaver without it would have
     * no points and no injectors at all.
     */
    public CorePlugin() {
        // Nothing to initialise.
    }

    /**
     * Returns the built-in plugin's identity.
     *
     * @return the identifier, whose namespace is {@link PluginId#BUILT_IN_NAMESPACE}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public PluginId id() {
        return ID;
    }

    /**
     * Returns the API level this plugin was compiled against.
     *
     * <p>{@link WeaverApi#LEVEL} is a compile-time constant, so this is not a delegation: the value
     * returned is the one folded in when the engine was compiled against the API it ships with.
     *
     * @return {@link WeaverApi#LEVEL}
     */
    @Contract(pure = true)
    @Override
    public int apiLevel() {
        return WeaverApi.LEVEL;
    }

    /**
     * Registers the built-in points and injectors.
     *
     * <p>One factory each, both created here. Neither holds state, so nothing depends on whether an
     * instance is shared with another weaver or not.
     *
     * @param ctx where to register; must not be {@code null}
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    @Override
    public void contribute(@NotNull final PluginContext ctx) {
        Objects.requireNonNull(ctx, "ctx")
                .points(new CorePoints())
                .injectors(new CoreInjectors());
    }

    /**
     * Returns the plugin as a diagnostic names it.
     *
     * @return the plugin's description, as {@link PluginId#describe()} renders it
     */
    @Override
    @NotNull
    public String toString() {
        return ID.describe();
    }

    /**
     * Supplies the three injectors the engine implements.
     *
     * <p>A new injector for every declaration, which costs nothing here because all three are
     * stateless: everything an injector needs arrives as an argument.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class CoreInjectors implements InjectorFactory {

        /**
         * Creates the factory.
         */
        public CoreInjectors() {
            // Nothing to initialise.
        }

        /**
         * Returns the namespace these kinds are registered under.
         *
         * @return {@link PluginId#BUILT_IN_NAMESPACE}, the namespace {@link #kinds()} are
         *         registered under
         */
        @Contract(pure = true)
        @Override
        @NotNull
        public String namespace() {
            return PluginId.BUILT_IN_NAMESPACE;
        }

        /**
         * Returns the kinds this factory answers for.
         *
         * <p>Three of them, and they are the kinds that rewrite a method body. The identifiers named
         * by {@link InjectorKind#MERGE}, {@link InjectorKind#ACCESSOR} and
         * {@link InjectorKind#INVOKER} are absent, and {@link #create(InjectorKind)} throws for each
         * of them.
         *
         * @return {@link InjectorKind#INJECT}, {@link InjectorKind#REDIRECT} and
         *         {@link InjectorKind#WRAP}
         */
        @Contract(pure = true)
        @Override
        @Unmodifiable
        @NotNull
        public Set<InjectorKind> kinds() {
            return Set.of(InjectorKind.INJECT, InjectorKind.REDIRECT, InjectorKind.WRAP);
        }

        /**
         * Returns a new injector for one kind.
         *
         * <p>The comparison is by value rather than by identity, because a kind arriving here has
         * been through the registry and need not be one of the constants this class named.
         *
         * @param kind the kind to build an injector for; must not be {@code null}
         * @return a new injector
         * @throws NullPointerException     if {@code kind} is {@code null}
         * @throws IllegalArgumentException if {@code kind} is not one of {@link #kinds()}
         */
        @Contract(pure = true)
        @Override
        @NotNull
        public Injector create(@NotNull final InjectorKind kind) {
            Objects.requireNonNull(kind, "kind");
            if (InjectorKind.INJECT.equals(kind)) {
                return new InjectInjector();
            }
            if (InjectorKind.REDIRECT.equals(kind)) {
                return new RedirectInjector();
            }
            if (InjectorKind.WRAP.equals(kind)) {
                return new WrapInjector();
            }
            throw new IllegalArgumentException("no built-in injector implements " + kind.id());
        }

        /**
         * Returns the factory with the kinds it serves.
         *
         * @return a description naming the registered kinds
         */
        @Override
        @NotNull
        public String toString() {
            return "CoreInjectors" + kinds();
        }
    }

    /**
     * Supplies the injection points the engine implements.
     *
     * <p>Unlike the injectors, the points are handed out rather than built: every identifier is
     * answered with the entry {@link BuiltInPoints} holds in its static map.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class CorePoints implements InjectionPointFactory {

        /**
         * Creates the factory.
         */
        public CorePoints() {
            // Nothing to initialise.
        }

        /**
         * Returns the namespace these points are registered under.
         *
         * @return {@link PluginId#BUILT_IN_NAMESPACE}, which is what lets {@code HEAD} and its
         *         siblings be written without a prefix
         */
        @Contract(pure = true)
        @Override
        @NotNull
        public String namespace() {
            return PluginId.BUILT_IN_NAMESPACE;
        }

        /**
         * Returns the identifiers of the built-in points.
         *
         * <p>The key set of the map {@code create} reads, so the two cannot drift apart: an
         * identifier that is listed is an identifier that resolves.
         *
         * @return the built-in point identifiers
         */
        @Contract(pure = true)
        @Override
        @Unmodifiable
        @NotNull
        public Set<String> ids() {
            return BuiltInPoints.all().keySet();
        }

        /**
         * Returns no aliases.
         *
         * <p>No built-in identifier has been retired, so every built-in spelling is one of
         * {@link #ids()}.
         *
         * @return an empty set
         */
        @Contract(pure = true)
        @Override
        @Unmodifiable
        @NotNull
        public Set<Alias> aliases() {
            return Set.of();
        }

        /**
         * Returns the point registered for one identifier.
         *
         * <p>The same instance every time, and the same instance for two weaving threads, since the
         * points are shared out of a static map. That is safe because they hold no per-run state:
         * everything a point works on arrives as an argument to {@code find}.
         *
         * @param id the identifier to resolve; must not be {@code null}
         * @return the point registered under that identifier
         * @throws NullPointerException     if {@code id} is {@code null}
         * @throws IllegalArgumentException if no built-in point carries that identifier
         */
        @Contract(pure = true)
        @Override
        @NotNull
        public InjectionPoint create(@NotNull final String id) {
            final InjectionPoint point = BuiltInPoints.all()
                    .get(Objects.requireNonNull(id, "id"));
            if (point == null) {
                throw new IllegalArgumentException("no built-in injection point named " + id);
            }
            return point;
        }

        /**
         * Returns the factory with the identifiers it serves.
         *
         * @return a description naming the registered point identifiers
         */
        @Override
        @NotNull
        public String toString() {
            return "CorePoints" + ids();
        }
    }
}
