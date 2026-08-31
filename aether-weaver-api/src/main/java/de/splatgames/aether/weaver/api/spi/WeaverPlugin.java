package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The single type a plugin implements, and the only one the engine looks for.
 *
 * <p>Everything a plugin adds — injection points, injector kinds, selector resolvers, diagnostic
 * sinks, metadata written into every woven class — is registered from one call to
 * {@link #contribute(PluginContext)}. Everything a plugin observes arrives at
 * {@link #observe(PluginEvent)}. Both have empty defaults, so a plugin that only contributes
 * overrides the first and a plugin that only watches overrides the second.
 *
 * <p>A plugin compiles against {@code aether-weaver-api} and needs nothing else: no engine class is
 * required on its compile classpath, which is why every type it deals with is in this package and
 * why the views it is handed are interfaces rather than class-file models.
 *
 * <h2>Namespaces</h2>
 *
 * <p>A plugin owns a namespace, declared by its {@link PluginId}, and everything it contributes is
 * named inside it: an injection point is written {@code namespace:NAME} and an injector kind
 * {@code namespace:kind}, while a metadata key is prefixed with the namespace for the plugin, which
 * passes a bare one. The empty namespace belongs to the framework — that is why
 * {@code @At(Point.HEAD)} spells as {@code HEAD} and not {@code aether:HEAD} — and the literal
 * namespace {@code aether} is reserved and refused by {@link PluginId} itself.
 *
 * <ul>
 *   <li>A plugin other than the built-in one whose identity reports the empty namespace is refused
 *       with {@code AW3101}, and contributes nothing.
 *   <li>A factory declaring a namespace other than its plugin's, or registering an identifier
 *       outside its plugin's namespace, is dropped with {@code AW3110}. The plugin still loads and
 *       its other contributions still stand.
 *   <li>Two plugins claiming one namespace is {@code AW3111}. The first to be accepted keeps it and
 *       the second contributes nothing; since acceptance order is fixed, which one loses does not
 *       vary between runs.
 * </ul>
 *
 * <h2>How a plugin is found</h2>
 *
 * <p>Two ways, both driven by whoever builds the weaver. A caller may install an instance directly,
 * or ask for classpath discovery, which is a {@link java.util.ServiceLoader} lookup of this
 * interface: a jar contributes a plugin by holding
 * {@code META-INF/services/de.splatgames.aether.weaver.api.spi.WeaverPlugin} naming a public class
 * with a public no-argument constructor. A service file that names a class which does not exist or
 * does not implement this interface is reported as {@code AW3114} and costs every plugin on that
 * classpath, because the lookup itself fails; a single provider whose constructor or static
 * initialiser throws is reported as {@code AW3114} and costs only that plugin.
 *
 * <p>Both are opt-in, and neither the agent nor the load-time class loader nor the build plugin in
 * this project asks for either, so plugins are reached by a program that builds the weaver itself.
 * That program also supplies the allowlist, which is consulted for every candidate, installed
 * directly or discovered alike, and which permits everything unless it is given. A candidate the
 * allowlist rejects is reported as {@code AW3119} and does not load. A plugin runs with full
 * privileges over the bytes of every class in the application, which is why an allowlist exists at
 * all.
 *
 * <h2>The order things happen in</h2>
 *
 * <p>Candidates are sorted by namespace and then by implementation class name before any of this
 * begins, so the outcome does not depend on classpath order. The built-in plugin is accepted first;
 * it is exempt from the allowlist and from the reserved-namespace rule, the empty namespace being
 * its own, and is checked for its API level like any other, so an engine assembled from mismatched
 * jars is refused rather than half-loaded. Then, per candidate, in this order:
 *
 * <ol>
 *   <li>{@link #id()} is asked for, and the namespace it reports is checked against the built-in
 *       one — the empty namespace — which only the built-in plugin may claim ({@code AW3101}).
 *   <li>The allowlist is consulted ({@code AW3119}).
 *   <li>{@link #apiLevel()} is asked for and compared against {@link WeaverApi#LEVEL}
 *       ({@code AW3112} when it is higher, {@code AW3113} when it is below the oldest generation
 *       the engine supports).
 *   <li>The namespace is claimed ({@code AW3111} when it is taken).
 *   <li>{@link #contribute(PluginContext)} is called ({@code AW3115} when it throws).
 *   <li>What the plugin registered is read off its context and folded into the registry.
 * </ol>
 *
 * <p>Every step decides on its own; a plugin that fails one is dropped and the others carry on. A
 * refusal always happens before {@link #contribute(PluginContext)}, so an incompatible plugin never
 * runs a line of its own contribution code — which is the entire reason the API level is checked
 * rather than discovered from the {@link LinkageError} it would otherwise cause somewhere inside
 * class loading.
 *
 * <p>Afterwards, events are published in acceptance order: {@link PluginEvent.PluginsLoaded} once
 * the registry exists, {@link PluginEvent.Prepared} once the plan has been built,
 * {@link PluginEvent.ClassWoven} after each class that was really changed, and
 * {@link PluginEvent.WeavingFinished} when a caller declares the run over, which the build plugin
 * does at the end of its goal and neither the agent nor the load-time class loader ever does.
 *
 * <h2>Failure containment</h2>
 *
 * <p>A throw out of {@link #apiLevel()}, out of a guarded call to {@link #id()}, or out of
 * {@link #contribute(PluginContext)} is caught, reported against the plugin by name, and the run
 * continues without it — the plugin is dropped and every other plugin is unaffected. A
 * {@link VirtualMachineError} is rethrown instead: the JVM itself is in trouble, and reporting
 * "a plugin threw" would misstate what is happening. A throw out of
 * {@link #observe(PluginEvent)} is caught the same way but costs the plugin nothing; see
 * {@link #observe(PluginEvent)} for what happens instead.
 *
 * <p>{@link #id()} is asked for repeatedly and most of those calls are unguarded: not only while
 * the weaver is built, but afterwards, once per plugin for every class the weaver stamps with the
 * registry's coordinates, and once per plugin in an event's audience for every event that is
 * published. Only the identity check that precedes each of the first three gates is guarded: a
 * plugin whose {@link #id()} throws there is refused with {@code AW3114}. Every other call to it
 * in the engine is unguarded — ordering the candidates before any gate runs, claiming a
 * namespace, and collecting a plugin's contributions are three such places, not the only ones —
 * and a throw from any of them leaves the engine. Return a constant.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #apiLevel()} and {@link #contribute(PluginContext)} are called once, while the weaver
 * is being built, on the thread that builds it. {@link #id()} is called then as well, but not only
 * then. Stamping a woven class asks every plugin for it again on whichever thread is doing that
 * weaving — which, under the load-time class loader's parallel-capable loading, can be more than
 * one thread at once. Publishing an event asks it too, but only of that event's audience, and on
 * the thread that raised the event: {@link PluginEvent.PluginsLoaded} and
 * {@link PluginEvent.Prepared} on the thread that built the weaver, {@link PluginEvent.ClassWoven}
 * on the thread that wove the class, and {@link PluginEvent.WeavingFinished} on whichever thread
 * declares the run over — the Maven build plugin's goal does this on its own thread, once weaving
 * has already finished there. {@link #observe(PluginEvent)} runs on that same thread, for that
 * same reason, right after the {@link #id()} call that precedes it. Returning a constant from
 * {@link #id()}, as required, is what makes all of this safe without an implementation having to
 * reason about which thread is calling it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePlugin implements WeaverPlugin {
 *
 *     // Incremented from whichever thread wove a class, so not a plain long.
 *     private final AtomicLong woven = new AtomicLong();
 *
 *     @Override
 *     public PluginId id() {
 *         return new PluginId("acme", "Acme Weaves", "1.4.0");
 *     }
 *
 *     @Override
 *     public int apiLevel() {
 *         return WeaverApi.LEVEL;
 *     }
 *
 *     @Override
 *     public void contribute(PluginContext ctx) {
 *         ctx.points(new AcmePoints())            // every id is "acme:SOMETHING"
 *            .metadata("build", ctx.configuration().get("acme.build", "unknown"))
 *            .observeApply();                     // opts in to ClassWoven
 *     }
 *
 *     @Override
 *     public void observe(PluginEvent event) {
 *         if (event instanceof PluginEvent.ClassWoven) {
 *             woven.incrementAndGet();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>The jar holds {@code META-INF/services/de.splatgames.aether.weaver.api.spi.WeaverPlugin} with
 * the single line {@code com.acme.AcmePlugin}, so that a caller who asks for classpath discovery
 * finds it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PluginContext
 * @see PluginEvent
 * @see WeaverApi#LEVEL
 */
@ApiStatus.OverrideOnly
public interface WeaverPlugin {

    /**
     * Returns who this plugin is.
     *
     * <p>Asked for repeatedly and expected to answer with the same value every time: it decides the
     * namespace the plugin owns, the order plugins are accepted in, the name every diagnostic about
     * this plugin is attributed to, and the coordinates written into every class the weaver stamps.
     * Returning a constant is the whole of what is required.
     *
     * @return the plugin's identity, never {@code null}
     */
    @Contract(pure = true)
    @NotNull
    PluginId id();

    /**
     * Returns the SPI generation this plugin was compiled against.
     *
     * <p>The only correct body is {@code return WeaverApi.LEVEL;}. The constant is folded into the
     * plugin's own class file at compile time, so the number that comes back is the one the plugin
     * was built against and not the one the engine on the classpath declares — which is exactly the
     * comparison the gate needs to make. A literal written by hand compiles and passes the gate,
     * and stops being true the moment the plugin is rebuilt.
     *
     * <p>Abstract rather than a default method for that reason: a default would be compiled into
     * this interface and would report the engine's level for every plugin, measuring nothing.
     *
     * @return {@link WeaverApi#LEVEL}, as folded in at compile time
     */
    @Contract(pure = true)
    int apiLevel();

    /**
     * Registers everything this plugin adds.
     *
     * <p>Called once, after the plugin has passed every gate, on the thread building the weaver.
     * The context is valid for the duration of the call: what is registered on it is read off
     * afterwards, and a context kept beyond the return registers into something nobody reads.
     *
     * <p>Report a problem through {@link PluginContext#diagnostics()} rather than by throwing. A
     * throw is reported as {@code AW3115} and discards the plugin entirely — including whatever it
     * had already registered before it threw, since the context is not read at all for a plugin
     * that did not return normally, and including its interest in events, since a plugin that did
     * not contribute is not in the audience for any of them.
     *
     * <p>An individual contribution can be rejected without the plugin being lost: a factory
     * outside the plugin's namespace is dropped with {@code AW3110}, and the plugin's other
     * factories still take effect.
     *
     * @param ctx where contributions are registered and problems are reported; never {@code null}
     */
    default void contribute(@NotNull final PluginContext ctx) {
        // Nothing by default: a plugin that only observes contributes nothing.
    }

    /**
     * Hears about something the weaver did.
     *
     * <p>Observation is one-way. An event carries what happened and nothing that can be changed, so
     * an observer cannot influence the bytes, and a throw is a warning — {@code AW3118} — rather
     * than an error: the remaining plugins still hear the event, and nothing was miswoven because
     * of it.
     *
     * <p>{@link PluginEvent.ClassWoven} is delivered only to plugins that asked for it with
     * {@link PluginContext#observeApply()}. Every other event goes to every plugin that
     * contributed. The distinction exists because the weaver is offered very nearly every class an
     * application loads, so an event per woven class is a cost a plugin should have to opt into.
     *
     * <p>Events are not filtered by type: an observer receives all of them and switches on what it
     * cares about.
     *
     * @param event what happened; never {@code null}
     */
    default void observe(@NotNull final PluginEvent event) {
        // Nothing by default: a plugin that only contributes observes nothing.
    }
}
