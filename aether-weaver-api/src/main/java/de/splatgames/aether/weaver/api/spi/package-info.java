/**
 * The service provider interface: what a plugin implements, what a driver supplies, and the read-only
 * views the engine hands both.
 *
 * <p>A plugin compiles against {@code aether-weaver-api} and needs nothing else: no signature in this
 * package names an engine type. That property is the reason the package exists: an extension coupled
 * to engine internals would break on every upgrade. A plugin also reaches types outside this
 * package — {@link de.splatgames.aether.weaver.api.model.PointSpec},
 * {@link de.splatgames.aether.weaver.api.model.InjectorKind} and
 * {@link de.splatgames.aether.weaver.api.diagnostic.Diagnostic} among them — and what it is handed is
 * not always an interface: {@link CodeView} exposes {@code java.lang.classfile.CodeElement}s
 * directly, and an {@link Injector.Emitter} is given a {@code java.lang.classfile.CodeBuilder} to
 * write into.
 *
 * <h2>The three groups</h2>
 *
 * <p><b>Being a plugin.</b> {@link WeaverPlugin} is the single type a plugin implements and the only
 * one the engine looks for. {@link PluginId} says who it is, {@link WeaverApi} says which generation of
 * this SPI it was compiled against, {@link PluginContext} is where everything it adds is registered,
 * {@link ConfigView} is what the driver told it, and {@link PluginEvent} is what it is told afterwards.
 *
 * <p><b>Contributing behaviour.</b> {@link InjectionPointFactory} registers the identifiers an
 * {@code @At} may name and builds the {@link InjectionPoint} behind each; {@link InjectorFactory}
 * registers the kinds an injection declaration may carry and builds the {@link Injector} behind each.
 * The two halves are separate on purpose: a point decides <em>where</em> and answers with
 * {@link Site}s, an injector decides <em>what</em> and writes instructions, and a contribution that
 * tries to do both has mistaken which half it is in. {@link Alias} retires an identifier without
 * invalidating the weaves that name it, {@link HandlerBinding} is how a handler's arguments reach the
 * stack, and {@link InjectionContext} is what an injector is given while it emits.
 *
 * <p><b>Reading and deciding.</b> {@link TargetView}, {@link MethodView} and {@link CodeView} are the
 * class being woven; {@link PlanView} and {@link PlanEntryView} are what the weaver decided to do
 * before it read any class; {@link StatisticsView} is what it did. {@link WeaveSource} and
 * {@link DiscoveryContext} answer which weave classes exist, {@link ClassSource} supplies bytes without
 * loading anything, and {@link WeavePolicy} with {@link WeaveTarget} is the gate that can refuse a
 * class outright. {@link DiagnosticListener} and {@link Reporter} are where everything says what went
 * wrong.
 *
 * <p>{@link SelectorResolver} is a contributed way of turning a selector into members. At this
 * generation it declares an identity and a priority and no resolution method, and nothing in the
 * engine reads the list a plugin registers into, so registering one changes nothing about which
 * members a selector matches.
 *
 * <h2>The lifecycle</h2>
 *
 * <p>Candidates are sorted by namespace and then by implementation class name before anything begins,
 * so the outcome does not depend on classpath order, and the built-in plugin is accepted first. Then,
 * per candidate:
 *
 * <ol>
 *   <li>{@link WeaverPlugin#id()} is asked for and its namespace is checked against the built-in one —
 *       the empty namespace, which only the framework's own plugin may claim ({@code AW3101}).
 *   <li>The allowlist is consulted ({@code AW3119}). It permits everything unless the program building
 *       the weaver supplies one.
 *   <li>{@link WeaverPlugin#apiLevel()} is compared against {@link WeaverApi#LEVEL} — {@code AW3112}
 *       when it is higher, {@code AW3113} when it is below the oldest generation the engine supports.
 *   <li>The namespace is claimed ({@code AW3111} when it is taken).
 *   <li>{@link WeaverPlugin#contribute(PluginContext)} is called ({@code AW3115} when it throws).
 *   <li>What was registered is read off the context and folded into the registry.
 * </ol>
 *
 * <p>Every step decides on its own: a plugin that fails one is dropped and the others carry on. A
 * refusal always happens before {@link WeaverPlugin#contribute(PluginContext)}, so an incompatible
 * plugin never runs a line of its own contribution code — which is the whole reason the API level is
 * checked rather than discovered from the {@link LinkageError} it would otherwise cause.
 *
 * <p>Afterwards {@link PluginEvent}s are published in acceptance order:
 * {@link PluginEvent.PluginsLoaded} once the registry exists, {@link PluginEvent.Prepared} once the
 * plan has been built, {@link PluginEvent.ClassWoven} after each class that was really changed, and
 * {@link PluginEvent.WeavingFinished} when a driver declares the run over. Only
 * {@link PluginEvent.ClassWoven} is opt-in, through {@link PluginContext#observeApply()}, because it
 * fires once per woven class rather than once per run.
 *
 * <p>A plugin is reached in one of two ways, both driven by whoever builds the weaver: an instance
 * installed directly, or classpath discovery, which is a {@link java.util.ServiceLoader} lookup of
 * {@link WeaverPlugin}. Neither the agent nor the load-time class loader nor the build plugin in this
 * project asks for either, so plugins are reached by a program that assembles the weaver itself.
 *
 * <h2>Everything a plugin adds carries its namespace</h2>
 *
 * <p>An injection point identifier is {@code namespace:NAME}, an injector kind is
 * {@code namespace:kind}, a diagnostic code is {@code namespace:IDENTIFIER}, and a metadata key is
 * prefixed with the namespace on the plugin's behalf. The empty namespace belongs to the framework —
 * which is why {@code @At(Point.HEAD)} spells as {@code HEAD} and not {@code aether:HEAD} — and the
 * literal namespace {@code aether} is reserved and refused by {@link PluginId} itself.
 *
 * <p>{@link PluginContext#injectors(InjectorFactory...)} and
 * {@link PluginContext#points(InjectionPointFactory...)} check every factory against the plugin's own
 * identity: the factory's declared namespace must match, and every identifier it offers must be
 * prefixed with it. A violation is {@code AW3110} and costs that factory <em>all</em> of its
 * identifiers — it is dropped rather than partly registered — while the other factories in the same
 * call are unaffected. An identifier a second contributor claims is {@code AW3111}, and the later
 * registration is the one dropped. {@link PluginContext#selectorResolvers(SelectorResolver...)} and
 * {@link PluginContext#diagnosticListeners(DiagnosticListener...)} apply no namespace check.
 *
 * <p>An identifier that does not name its owner cannot be attributed in a diagnostic and cannot be
 * switched off as a set, which is what the rule exists for.
 *
 * <h2>Report rather than throw</h2>
 *
 * <p>This is the convention every interface here is built around, and the reason a {@link Reporter} is
 * passed to so many methods. An exception carries no code, no location and no remedy, so it cannot be
 * attributed to the declaration that caused it; the engine reports what it can contain as
 * {@code AW3116} or {@code AW3117}, which tells a user that a plugin failed and nothing about what
 * their weave did wrong. A diagnostic says both.
 *
 * <p>The intended way to fail therefore differs per interface, and each of them says so: an
 * {@link InjectionPoint} reports and returns an empty list, an {@link Injector} reports and returns
 * {@link Injector.Emitter#NOTHING}, a {@link WeavePolicy} answers with a
 * {@link WeavePolicy.Decision.Deny} carrying a code and a reason, and a {@link WeaveSource} reports
 * through {@link DiscoveryContext#reporter()} and returns the candidates it did find.
 *
 * <p>Containment is real but partial, and it is not a substitute for reporting. A throw out of
 * {@link WeaverPlugin#apiLevel()}, out of the guarded {@link WeaverPlugin#id()} call, or out of
 * {@link WeaverPlugin#contribute(PluginContext)} is caught and the plugin is dropped; a throw out of
 * {@link WeaverPlugin#observe(PluginEvent)} is {@code AW3118}, a warning, and costs the plugin
 * nothing, because an observer cannot change the woven bytes. A throw out of a contributed
 * {@link InjectionPoint} is {@code AW3116} and costs that one {@code @At} its matches. A throw out of
 * {@link Injector#emitter(InjectionContext)} or the emitter it returned is {@code AW3117} and leaves
 * the class exactly as it was — but only when at least one declaration on that class names a kind
 * carrying a namespace. Elsewhere nothing catches: a throw from {@link WeavePolicy#decide(WeaveTarget)},
 * from {@link WeaveSource#candidates(DiscoveryContext)}, from
 * {@link Injector#validate(PlanEntryView, TargetView, Reporter)} or from
 * {@link InjectorFactory#create(de.splatgames.aether.weaver.api.model.InjectorKind)} leaves the weaver,
 * and what becomes of it is the driver's business. A {@link VirtualMachineError} is rethrown
 * everywhere rather than reported: the JVM itself is in trouble, and reporting "a plugin threw" would
 * misstate what is happening.
 *
 * <p>{@link PluginContext#metadata(String, String)} is the one registration method that refuses a
 * value by throwing rather than by reporting, because what it records is written into every woven
 * class and is therefore bounded. Its {@link IllegalArgumentException} costs the plugin its whole
 * contribution, so a plugin deriving a value from configuration checks the length before calling it.
 *
 * <h2>Threading</h2>
 *
 * <p>Building the weaver happens on one thread: {@link WeaverPlugin#apiLevel()},
 * {@link WeaverPlugin#contribute(PluginContext)} and every factory's declaration methods are called
 * there. Weaving is not necessarily single-threaded. Under the load-time driver the weaver is consulted
 * from inside class loading by a parallel-capable loader, so {@link InjectionPointFactory#create},
 * {@link InjectorFactory#create(de.splatgames.aether.weaver.api.model.InjectorKind)},
 * {@link InjectionPoint#find}, an emitter, a {@link ClassSource}, a {@link WeavePolicy} and a
 * {@link DiagnosticListener} can all be entered on several threads at once, one per class being woven.
 * A factory that hands out one shared instance — the ordinary case — must therefore not keep
 * unsynchronized mutable state across those calls.
 *
 * <p>{@link WeaverPlugin#id()} is asked for repeatedly and most of those calls are unguarded: once per
 * plugin for every class the weaver stamps, and once per plugin in an event's audience for every event
 * published. Return a constant.
 *
 * <h2>Views are snapshots</h2>
 *
 * <p>{@link TargetView}, {@link MethodView} and {@link CodeView} describe the class as it was read,
 * before this weave touched it. Weaving builds a new class rather than editing the parsed one, so
 * nothing another injection is about to add shows up: a handler a merge will fold into the target is
 * not among {@link TargetView#methods()} while the injection expecting it is being planned. Everything
 * is declared and never inherited, and finding an inherited member is a matter of walking
 * {@link TargetView#superclass()} and {@link TargetView#interfaces()} with a {@link ClassSource} of the
 * caller's own — never by loading the class, which would run its static initialiser and fix its shape
 * before the weaver could change it.
 *
 * <p>{@link CodeView} is also the coordinate system every position in this SPI is expressed in. An
 * index counts <em>elements</em> of the {@code Code} attribute — labels, line numbers,
 * local-variable declarations and exception handlers included — so it is neither a bytecode offset nor
 * an ordinal among instructions. Where a declaration names a slice, an {@link InjectionPoint} is handed
 * a view of the slice alone and answers with indices into it; the engine translates them back, which is
 * why an ordinal counts within the slice.
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
 *         return new PluginId("acme", "Acme Weaves", "1.4.0");   // a constant; asked for often
 *     }
 *
 *     @Override
 *     public int apiLevel() {
 *         return WeaverApi.LEVEL;   // folded in at compile time; never write the number
 *     }
 *
 *     @Override
 *     public void contribute(PluginContext ctx) {
 *         ctx.points(new AcmePoints())              // every id is "acme:SOMETHING"
 *            .injectors(new AcmeInjectors())        // every kind is "acme:something"
 *            .metadata("mode", ctx.configuration().get("acme.mode", "default"))
 *            .observeApply();                       // opts in to ClassWoven
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
 * <p>The jar holds {@code META-INF/services/de.splatgames.aether.weaver.api.spi.WeaverPlugin} with the
 * single line {@code com.acme.AcmePlugin}, so that a caller who asks for classpath discovery finds it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.spi;
