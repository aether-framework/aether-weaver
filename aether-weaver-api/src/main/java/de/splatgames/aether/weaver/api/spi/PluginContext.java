package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The one handle a plugin has for registering everything it adds to a weaver.
 *
 * <p>A context is created per plugin and handed to {@link WeaverPlugin#contribute(PluginContext)}.
 * Everything a plugin contributes is contributed through it, and every registration method returns
 * the context so that a whole contribution reads as one statement.
 *
 * <h2>It is alive only during the call</h2>
 *
 * <p>The engine reads what was registered the moment {@link WeaverPlugin#contribute(PluginContext)}
 * returns, and never looks again. A context retained in a field and used later accumulates
 * registrations that nothing will read. There is no way to unregister, and no second opportunity.
 *
 * <p>If {@link WeaverPlugin#contribute(PluginContext)} throws, the throw is reported as
 * {@code AW3115} and the plugin contributes nothing at all — not even what it registered before the
 * throw, since the whole context is discarded. Such a plugin is also dropped from the registry, so
 * it receives no {@link PluginEvent} either. A plugin that finds a problem reports it through
 * {@link #diagnostics()} and returns.
 *
 * <p>{@link #metadata(String, String)} is the one method here that refuses a value by throwing
 * rather than by reporting, and its {@link IllegalArgumentException} costs the plugin its whole
 * contribution for exactly that reason. Check the bounds before calling it. The registration methods
 * throw only {@link NullPointerException}, and only for an argument that is {@code null}.
 *
 * <h2>The namespace runs through all of it</h2>
 *
 * <p>{@link #injectors(InjectorFactory...)} and {@link #points(InjectionPointFactory...)} check
 * every factory against {@link #self()}: the factory's own declared namespace must be the plugin's,
 * and every identifier it offers must be prefixed with it. A violation is reported as
 * {@code AW3110} and costs that factory every one of its identifiers — the factory is dropped rather
 * than partly registered — while the other factories in the same call are unaffected.
 * {@link #metadata(String, String)} prefixes its key automatically instead of refusing it.
 *
 * <p>{@link #selectorResolvers(SelectorResolver...)} and
 * {@link #diagnosticListeners(DiagnosticListener...)} apply no namespace check; they take what they
 * are given.
 *
 * <h2>Threading</h2>
 *
 * <p>The whole contribution happens on the thread building the weaver, one plugin at a time. A
 * context is never shared between plugins and never used concurrently.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePlugin implements WeaverPlugin {
 *
 *     @Override
 *     public PluginId id() {
 *         return new PluginId("acme", "Acme Tracing", "1.0.0");
 *     }
 *
 *     @Override
 *     public int apiLevel() {
 *         return WeaverApi.LEVEL;
 *     }
 *
 *     @Override
 *     public void contribute(PluginContext ctx) {
 *         ctx.points(new AcmePoints())
 *            .injectors(new AcmeInjectors())
 *            .metadata("mode", ctx.configuration().get("mode", "default"))
 *            .observeApply();
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaverPlugin
 * @see PluginId
 */
@ApiStatus.NonExtendable
public interface PluginContext {

    /**
     * Returns the identity of the plugin this context belongs to.
     *
     * <p>The value {@link WeaverPlugin#id()} returned. Its {@link PluginId#namespace()} is what
     * every factory registered here is checked against and what
     * {@link #metadata(String, String)} prefixes keys with.
     *
     * @return the plugin's own identity
     */
    @Contract(pure = true)
    @NotNull
    PluginId self();

    /**
     * Returns the configuration the driver supplied for this plugin.
     *
     * <p>Scoped to this plugin: a driver that offers configuration at all decides per plugin what it
     * sees. The view is built once and never refreshed, and a driver with nothing to offer supplies
     * an empty one — which is what a weaver built through {@code Weaver.builder()} does for every
     * plugin, so a plugin must work with its defaults alone.
     *
     * @return the configuration, empty rather than {@code null} when the driver offered none
     */
    @Contract(pure = true)
    @NotNull
    ConfigView configuration();

    /**
     * Returns where to report a problem found while contributing.
     *
     * <p>Reports go straight to the listener the driver installed on the weaver, alongside every
     * diagnostic the run produces. This is what a plugin uses instead of throwing: a thrown exception
     * costs the plugin its whole contribution and arrives as {@code AW3115} with no code, no location
     * and no remedy of the plugin's own.
     *
     * <p>Reporting an error here does not stop the plugin from being loaded and does not abandon its
     * contribution. What an error means for the build is the driver's decision.
     *
     * @return the reporter for this run
     */
    @Contract(pure = true)
    @NotNull
    Reporter diagnostics();

    /**
     * Registers injector factories, adding the kinds they declare.
     *
     * <p>Each factory is checked before it is accepted: {@link InjectorFactory#namespace()} must
     * equal {@link #self()}'s namespace, and every {@link InjectorFactory#kinds()} identifier must be
     * that namespace followed by a colon and a name. A factory failing either check is reported as
     * {@code AW3110} and dropped whole; the remaining arguments of the same call are still
     * registered. A kind another factory already claimed is reported as {@code AW3111} when the
     * registry is assembled, and the later registration is the one dropped.
     *
     * <p>Calling this several times accumulates. Passing the same factory twice registers it twice
     * and therefore reports {@code AW3111} for each of its kinds.
     *
     * @param factories the factories to register; neither the array nor any element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code factories} or any element is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    PluginContext injectors(@NotNull InjectorFactory... factories);

    /**
     * Registers injection point factories, adding the {@code @At} identifiers they declare.
     *
     * <p>Checked exactly as {@link #injectors(InjectorFactory...)} is, against
     * {@link InjectionPointFactory#namespace()} and {@link InjectionPointFactory#ids()}, with the
     * same two codes: {@code AW3110} for a factory outside the plugin's namespace, and
     * {@code AW3111} for an identifier a second contributor claims.
     *
     * @param factories the factories to register; neither the array nor any element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code factories} or any element is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    PluginContext points(@NotNull InjectionPointFactory... factories);

    /**
     * Registers selector resolvers.
     *
     * <p>No namespace check applies and no diagnostic is raised: whatever is passed is collected.
     * The resolvers are held on the registry the weaver was built with, and nothing in the engine
     * reads that list back, so registering a resolver changes nothing about which members a selector
     * matches.
     *
     * @param resolvers the resolvers to register; neither the array nor any element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code resolvers} or any element is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    PluginContext selectorResolvers(@NotNull SelectorResolver... resolvers);

    /**
     * Registers additional diagnostic listeners.
     *
     * <p>No namespace check applies and no diagnostic is raised: whatever is passed is collected.
     * The listeners are held on the registry the weaver was built with, and nothing in the engine
     * reads that list back; the weaver reports through the listener its driver installed, so a
     * listener registered here is not fed the run's diagnostics. A plugin that wants a record of what
     * it reports collects it as it reports.
     *
     * @param listeners the listeners to register; neither the array nor any element may be
     *                  {@code null}
     * @return this context
     * @throws NullPointerException if {@code listeners} or any element is {@code null}
     */
    @Contract("_ -> this")
    @NotNull
    PluginContext diagnosticListeners(@NotNull DiagnosticListener... listeners);

    /**
     * Records one key and value to be written into every class this weaver weaves.
     *
     * <p>The key is prefixed with the plugin's namespace and a colon, so {@code metadata("mode",
     * "strict")} from a plugin whose namespace is {@code acme} is recorded as {@code acme:mode}.
     * Entries are kept sorted by that namespaced key. They feed the {@code @Woven} annotation the
     * weaver can attach, feed {@link PlanView#fingerprint()}, and are printed, every key and value,
     * in the run's own explanation of what it did. They do not reach the {@code AetherWeave}
     * attribute every woven class carries, which records no plugin metadata at all. Changing one
     * value changes the plan's identity: under the default build-time driver, a class already
     * stamped under the old fingerprint is then refused with {@code AW2201} rather than rewoven, and
     * under a load-time driver it is rewoven instead, after a warning, {@code AW2202}.
     *
     * <p>Writing the same key twice replaces the earlier value rather than adding an entry.
     *
     * <p>Because this is written into every woven class, it is bounded, and the bounds are enforced
     * by throwing rather than by reporting. A throw from inside
     * {@link WeaverPlugin#contribute(PluginContext)} costs the plugin its entire contribution and is
     * reported as {@code AW3115}, so a plugin deriving a value from configuration checks its length
     * before passing it here.
     *
     * @param key   the key, without a namespace prefix; must not be blank and must be at most 128
     *              characters
     * @param value the value; may be empty and must be at most 128 characters
     * @return this context
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code key} is blank, if either argument is longer than
     *                                  128 characters, or if the key is new and this plugin has
     *                                  already recorded 8 entries
     */
    @Contract("_, _ -> this")
    @NotNull
    PluginContext metadata(@NotNull String key, @NotNull String value);

    /**
     * Asks to be told about each class that is woven.
     *
     * <p>{@link PluginEvent.ClassWoven} is delivered only to the plugins that called this; the other
     * events go to every loaded plugin whether they called it or not. It is opt-in because it fires
     * once per woven class rather than once per run, on the thread doing the weaving, and under a
     * load-time driver that is inside class loading.
     *
     * <p>Calling it more than once is the same as calling it once, and there is no way to withdraw.
     *
     * @return this context
     */
    @Contract("-> this")
    @NotNull
    PluginContext observeApply();
}
