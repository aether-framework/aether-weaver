/**
 * Decides which contributions a run has, what they are called, and what happens when one of them
 * misbehaves.
 *
 * <p>Everything the engine can be extended with — an injector kind, an injection point, a selector
 * resolver, a diagnostic sink, a piece of metadata — arrives through a
 * {@link de.splatgames.aether.weaver.api.spi.WeaverPlugin}, and this package is the only path in.
 * The framework's own contributions take the same path: {@code CorePlugin} implements the same
 * interface a third party does and is installed into the same registry, so registration, lookup and
 * namespacing are one code path rather than two, exercised on every run by the built-in points.
 *
 * <h2>Admission</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plugin.PluginLoader} decides which plugins run, and
 * every decision is made before a plugin is asked to contribute anything. A plugin runs with the
 * engine's own privileges, and a plugin built against the wrong SPI generation fails as a
 * {@link java.lang.LinkageError} from inside class loading, where it is far harder to attribute — so a
 * refused plugin is refused with a diagnostic and never called again.
 *
 * <p>The candidates are sorted by namespace and then by provider class name before any of them is
 * examined, because service loading follows the classpath and the file system, and that order would
 * otherwise reach the registry, the plan fingerprint and every woven class. The gates that follow, in
 * order, are: the namespace must not be the reserved built-in one ({@code AW3101}); the deployment
 * must permit it ({@code AW3119}); the API level must be one this engine speaks ({@code AW3112} for a
 * plugin newer than the engine, {@code AW3113} for one too old); and the namespace must still be free
 * ({@code AW3111}). The built-in plugin is exempt from the allowlist and owns the built-in namespace,
 * and is still version-checked.
 *
 * <p>Construction is separate and can fail on its own: a service declaration that cannot be read at
 * all is {@code AW3114} and yields no plugins, and a constructor that throws is {@code AW3114} against
 * that plugin alone.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plugin.PluginFilter} turns configured namespace lists
 * into the predicate the loader tests. It answers one question — may a plugin of this namespace load
 * here — from the configuration alone, and reads nothing itself: the three keys it names as constants
 * are looked up by whoever calls it.
 *
 * <h2>Contribution, and namespaces</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plugin.DefaultPluginContext} belongs to one plugin for
 * the duration of one {@code contribute} call. An injector or injection point factory is checked
 * against the plugin's own namespace before it is kept, so a factory reaching for another plugin's
 * identifiers is refused as {@code AW3110} where the offender can still be named; a selector resolver
 * or a diagnostic sink carries no identifier and is kept unconditionally. Refusal is per contribution:
 * a rejected factory costs that factory and nothing else, and the plugin still loads. A plugin that
 * keeps the context and registers into it after {@code contribute} has returned registers into an
 * object nobody reads again.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plugin.NamespacedRegistry} holds the result — two of
 * them per run, one for injector kinds and one for injection points. An identifier is either built-in
 * and unqualified, as {@code HEAD}, or prefixed with its owner's namespace, as
 * {@code acme:AFTER_LOGGING}, and that rule is enforced at registration rather than at use, which is
 * what lets a diagnostic attribute an identifier to whoever contributed it. Retired identifiers live
 * in a second map: a lookup through an alias answers with the replacement's own contribution — the
 * identical object — so the spelling a user wrote can change what is warned about ({@code AW3120}) and
 * not what is woven. Whether it is warned about at all is the caller's choice, since the warning goes
 * to the listener the lookup was given: the two lookups
 * {@link de.splatgames.aether.weaver.engine.Weaver} installs pass
 * {@link de.splatgames.aether.weaver.api.spi.DiagnosticListener#NOOP}, because they run once per point
 * and per injection of every class woven and the warning would repeat that many times. An alias naming
 * a replacement that is not registered is {@code AW3121}, reported when the registry is built and
 * dropping the alias; an identifier that is both registered and declared as an alias is
 * {@code AW3111}.
 *
 * <p>Both maps are sorted, because {@link de.splatgames.aether.weaver.engine.plugin.NamespacedRegistry#ids()}
 * is folded into the plan fingerprint and registration order would put the classpath into it.
 *
 * <h2>Containment</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plugin.PluginIsolation} is the boundary every call into
 * plugin code crosses. A throw from a plugin must become a diagnostic naming the plugin rather than a
 * stack trace naming this framework, so each entry point catches {@link java.lang.Throwable} — a
 * checked exception included, which is why its functional interfaces are declared to throw one — and
 * turns it into the diagnostic of the phase the call belongs to: {@code AW3114} while being
 * instantiated, {@code AW3115} while contributing, {@code AW3116} while planning, {@code AW3117} while
 * weaving a class, {@code AW3118} while observing. {@link java.lang.VirtualMachineError} is the one
 * thing the boundary does not swallow; it is re-thrown untouched.
 *
 * <p>The isolation is used where plugin code is genuinely reached and not as a blanket. A built-in
 * injection point is resolved directly, so a defect in the engine surfaces as itself rather than as
 * {@code AW3116} against a plugin that does not exist, and emission is wrapped only for a class that
 * some contributed kind takes part in.
 *
 * <h2>The frozen result</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.plugin.PluginRegistry} is what remains once loading is
 * over: the plugins that survived the gates, the identifiers they registered, the resolvers and
 * listeners they added, and their metadata, all immutable. The weaver reads it on every class it
 * weaves, and nothing may change once weaving has begun — a registry that grew a point halfway through
 * a run would make the plan's fingerprint describe a set of contributions that no longer exists.
 *
 * <p>Not everything it holds is consulted. The selector resolvers are collected and are asked nothing
 * by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.plugin;
