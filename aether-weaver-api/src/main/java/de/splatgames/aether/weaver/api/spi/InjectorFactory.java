package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.model.InjectorKind;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * Declares which injector kinds a plugin adds, and builds the injector behind each one.
 *
 * <p>A plugin registers its factories from {@link WeaverPlugin#contribute(PluginContext)} through
 * {@link PluginContext#injectors(InjectorFactory...)}. What is registered is the kind, not the
 * injector: {@link #kinds()} is read while the registry is assembled, and
 * {@link #create(InjectorKind)} is called later, once for each declaration of that kind that
 * reaches a class being woven.
 *
 * <p>This is the injector half of the two-part extension point. Its sibling
 * {@link InjectionPointFactory} contributes the identifiers an {@code @At} may name; this one
 * contributes the identifiers an {@link de.splatgames.aether.weaver.api.model.InjectorSpec} may
 * carry as its kind. The two are registered separately, checked by the same rules and kept in
 * separate registries, so a plugin may contribute either without the other.
 *
 * <h2>Every kind belongs to the namespace</h2>
 *
 * <p>{@link #namespace()} must be the namespace of the plugin doing the registering, and every
 * {@link InjectorKind#id()} in {@link #kinds()} must be {@code namespace:} followed by something.
 * The two checks are separate and both cost the factory every one of its kinds rather than only the
 * offending one:
 *
 * <ul>
 *   <li>A {@link #namespace()} that is not the registering plugin's own is reported as
 *       {@code AW3110}, and none of the factory's kinds is registered.
 *   <li>A kind outside that namespace is reported as {@code AW3110} listing each offender, and
 *       again none of the factory's kinds is registered. Prefix each with the namespace; an
 *       identifier that does not name its owner cannot be attributed in a diagnostic.
 *   <li>A kind that is already registered is reported as {@code AW3111} naming both owners, and the
 *       second registration is dropped. Registrations are not deduplicated, so this is reported even
 *       when the same factory instance is passed to
 *       {@link PluginContext#injectors(InjectorFactory...)} twice.
 * </ul>
 *
 * <p>{@link InjectorKind#of(String)} refuses an unqualified identifier outright and is the factory a
 * plugin author calls for exactly that reason: the unqualified spellings {@code inject},
 * {@code redirect} and {@code wrap} belong to the framework, whose namespace is the empty one and is
 * reserved. A plugin that claims it is refused as {@code AW3101} before any of this is reached.
 *
 * <h2>What happens to a kind that was registered</h2>
 *
 * <p>A declaration carrying the kind {@code acme:trace} finds the factory registered under that
 * identifier and asks it for an injector, once per class being woven per declaration. A kind nothing
 * registered is reported as {@code AW4090} and the declaration is skipped — but only once that
 * declaration's points have matched a position in the target, since a declaration that matched
 * nothing is dropped before its kind is looked up. {@code AW4090} is also what a user sees when the
 * plugin that owns the kind is missing from the classpath.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #namespace()}, {@link #kinds()} and {@link #aliases()} are read while the weaver is
 * being built, on the thread building it, and each may be asked more than once.
 * {@link #create(InjectorKind)} is called during weaving, which under the load-time driver is the
 * thread loading a class, and that loader is parallel-capable, so a factory shared by one weaver can
 * be asked for injectors on several threads at once.
 *
 * <p>The identifiers that end up registered feed {@link PlanView#fingerprint()}, so adding or
 * removing a plugin gives the same weaves a different plan fingerprint. Under the default build-time
 * driver, a class already stamped under the old fingerprint is refused with {@code AW2201} rather
 * than rewoven; a load-time driver rewrites it instead, after a warning, {@code AW2202}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmeInjectors implements InjectorFactory {
 *
 *     static final InjectorKind TRACE = InjectorKind.of("acme:trace");
 *
 *     private static final Injector INSTANCE = new TraceInjector();
 *
 *     @Override
 *     public String namespace() {
 *         return "acme";
 *     }
 *
 *     @Override
 *     public Set<InjectorKind> kinds() {
 *         return Set.of(TRACE);
 *     }
 *
 *     @Override
 *     public Set<Alias> aliases() {
 *         return Set.of(new Alias("acme:tracing", "acme:trace", "0.2.0"));
 *     }
 *
 *     @Override
 *     public Injector create(InjectorKind kind) {
 *         return INSTANCE;   // asked for either spelling; both mean this injector
 *     }
 * }
 * }</pre>
 *
 * <p>Implemented by plugins and called by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Injector
 * @see PluginContext#injectors(InjectorFactory...)
 * @see InjectionPointFactory
 */
@ApiStatus.OverrideOnly
public interface InjectorFactory {

    /**
     * Returns the namespace this factory contributes to.
     *
     * <p>Checked against the namespace of the plugin registering it, and a disagreement costs the
     * factory every one of its kinds with {@code AW3110}.
     *
     * @return the namespace, matching the registering plugin's own
     */
    @Contract(pure = true)
    @NotNull
    String namespace();

    /**
     * Returns every kind this factory serves.
     *
     * <p>Each must be inside {@link #namespace()}, written {@code namespace:name}. The set is read
     * while the registry is assembled, and its kinds are registered in ascending order of
     * {@link InjectorKind#id()} so that two runs report the same problems in the same sequence.
     *
     * @return the kinds, never {@code null} and not modifiable; an empty set registers nothing
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    Set<InjectorKind> kinds();

    /**
     * Returns the retired kind identifiers that still resolve to a current one.
     *
     * <p>Each alias renames one of {@link #kinds()}; the rules an alias must satisfy, and what a
     * build reports when one is used, are on {@link Alias}. Declaring an alias costs nothing until
     * a declaration carries the retired spelling.
     *
     * @return the aliases, never {@code null} and not modifiable; empty unless overridden
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    default Set<Alias> aliases() {
        return Set.of();
    }

    /**
     * Returns the injector that answers for one kind.
     *
     * <p>Called once for every declaration of that kind that reaches a class being woven — once per
     * plan entry per class, after the declaration's positions have been found and before it is
     * validated. Nothing caches the result, so a factory that builds an expensive object here builds
     * it repeatedly; a stateless injector is normally a constant the factory hands out again and
     * again.
     *
     * <p>The kind is built from the identifier the declaration wrote, which is the retired spelling
     * when it resolved through one of {@link #aliases()}. A factory that switches on the kind must
     * therefore accept its own aliases as well as its {@link #kinds()}, or it will refuse the very
     * spellings its aliases promised. The value handed in is equal to the corresponding member of
     * {@link #kinds()} whenever the declaration wrote a current spelling, since two kinds are equal
     * exactly when their identifiers are.
     *
     * <p>Returning is the only acceptable outcome. Throwing from here is not contained: the exception
     * leaves the weaver with no diagnostic reported, and the class is neither woven nor left alone by
     * any decision the engine took.
     *
     * @param kind one of {@link #kinds()}, or a kind built from the deprecated side of one of
     *             {@link #aliases()}
     * @return the injector for that kind
     */
    @Contract(pure = true)
    @NotNull
    Injector create(@NotNull InjectorKind kind);
}
