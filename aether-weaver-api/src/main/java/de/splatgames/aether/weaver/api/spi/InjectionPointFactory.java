package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * Declares which {@code @At} identifiers a plugin adds, and builds the point behind each one.
 *
 * <p>A plugin registers its factories from {@link WeaverPlugin#contribute(PluginContext)} through
 * {@link PluginContext#points(InjectionPointFactory...)}. What is registered is the identifier, not
 * the point: {@link #ids()} is read while the registry is assembled, and {@link #create} is called
 * later, each time a declaration naming one of those identifiers has to be resolved.
 *
 * <p>Implemented by plugins and called by the engine.
 *
 * <h2>Every identifier belongs to the namespace</h2>
 *
 * <p>{@link #namespace()} must be the namespace of the plugin doing the registering, and every
 * entry of {@link #ids()} must be {@code namespace:} followed by something. The two checks are
 * separate and both are fatal for the whole factory rather than for the offending identifier:
 *
 * <ul>
 *   <li>A {@link #namespace()} that is not the registering plugin's own is reported as
 *       {@code AW3110}, and none of the factory's identifiers is registered.
 *   <li>An identifier outside that namespace is reported as {@code AW3110} listing each offender,
 *       and again none of the factory's identifiers is registered. Prefix each with the namespace;
 *       an identifier that does not name its owner cannot be attributed in a diagnostic.
 *   <li>An identifier that is already registered is reported as {@code AW3111} naming both owners,
 *       and the second registration is dropped. Registrations are not deduplicated, so this is
 *       reported even when the same factory instance is passed to
 *       {@link PluginContext#points(InjectionPointFactory...)} twice; a namespace has exactly one
 *       owning plugin, but the two conflicting registrations are not necessarily two distinct
 *       factories.
 * </ul>
 *
 * <p>The unqualified spellings belong to the framework. {@code HEAD} and {@code RETURN} contain no
 * colon precisely because the built-in namespace is the empty one, and it is reserved: a plugin
 * that claims it is refused before any of this is reached.
 *
 * <h2>What happens to an identifier that was registered</h2>
 *
 * <p>A declaration writing {@code @At(custom = "acme:AFTER_LOGGING")} finds the factory registered
 * under that identifier and asks it for a point, once per resolution. An identifier nothing registered is
 * reported as {@code AW1101} when a declaration names it, which is also what a user sees when the
 * plugin is missing from the classpath.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #namespace()}, {@link #ids()} and {@link #aliases()} are read while the weaver is being
 * built, on the thread building it, and each may be asked more than once. {@link #create} is called
 * during weaving, which under the load-time driver is the thread loading a class — and that loader
 * is parallel-capable, so a factory shared by one weaver can be asked for points on several threads
 * at once.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePoints implements InjectionPointFactory {
 *
 *     private static final InjectionPoint AFTER_LOGGING = new AfterLoggingPoint();
 *
 *     @Override
 *     public String namespace() {
 *         return "acme";
 *     }
 *
 *     @Override
 *     public Set<String> ids() {
 *         return Set.of("acme:AFTER_LOGGING");
 *     }
 *
 *     @Override
 *     public Set<Alias> aliases() {
 *         return Set.of(new Alias("acme:AFTER_LOG", "acme:AFTER_LOGGING", "0.2.0"));
 *     }
 *
 *     @Override
 *     public InjectionPoint create(String id) {
 *         return AFTER_LOGGING;   // asked for either spelling; both mean this point
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectionPoint
 * @see PluginContext#points(InjectionPointFactory...)
 */
@ApiStatus.OverrideOnly
public interface InjectionPointFactory {

    /**
     * Returns the namespace this factory contributes to.
     *
     * <p>Checked against the namespace of the plugin registering it, and a disagreement costs the
     * factory every one of its identifiers with {@code AW3110}.
     *
     * @return the namespace, matching the registering plugin's own
     */
    @Contract(pure = true)
    @NotNull
    String namespace();

    /**
     * Returns every identifier this factory serves.
     *
     * <p>Each must be inside {@link #namespace()}, written {@code namespace:NAME}. The set is read
     * while the registry is assembled, and its identifiers are registered in ascending order so
     * that two runs report the same problems in the same sequence.
     *
     * @return the identifiers, never {@code null} and not modifiable; an empty set registers
     *         nothing
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    Set<String> ids();

    /**
     * Returns the retired identifiers that still resolve to a current one.
     *
     * <p>Each alias renames one of {@link #ids()}; the rules an alias must satisfy, and what a
     * build reports when one is used, are on {@link Alias}. Declaring an alias costs nothing until
     * someone writes the retired spelling.
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
     * Returns the point that answers for one identifier.
     *
     * <p>Called once for every point that has to be resolved — once per {@code @At} of a
     * declaration per target method, and once more for each slice bound naming one of these
     * identifiers. Nothing caches the result, so a factory that builds an expensive object here
     * builds it repeatedly; a stateless point is normally a constant the factory hands out again
     * and again.
     *
     * <p>The identifier is the one the declaration wrote, which is the retired spelling when it
     * resolved through one of {@link #aliases()}. A factory that switches on the identifier must
     * therefore accept its own aliases as well as its {@link #ids()}, or it will refuse the very
     * spellings its aliases promised.
     *
     * @param id one of {@link #ids()}, or the deprecated side of one of {@link #aliases()}
     * @return the point for that identifier
     */
    @Contract(pure = true)
    @NotNull
    InjectionPoint create(@NotNull String id);
}
