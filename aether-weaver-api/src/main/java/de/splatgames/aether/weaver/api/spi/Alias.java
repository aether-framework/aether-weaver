package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A retired identifier, the current identifier that stands in its place, and the version the two
 * parted company in.
 *
 * <p>An alias is how a plugin renames an injection point or an injector kind without invalidating
 * the weaves that already name the old spelling. A factory declares its aliases through
 * {@link InjectionPointFactory#aliases()} or {@link InjectorFactory#aliases()}, and the engine
 * registers them beside the identifiers themselves. A declaration naming {@link #deprecated()}
 * then resolves to the registration made for {@link #replacement()} — the same object, not a
 * second one — so the spelling an author happened to write changes nothing about what is woven.
 *
 * <h2>What an alias may say</h2>
 *
 * <p>An alias renames an identifier; it cannot create one. Both of its identifiers are checked
 * against what was actually registered when the registry is built, and an alias that fails either
 * check is dropped — but only one of the two failures leaves {@link #deprecated()} unresolvable,
 * because a lookup checks the registered identifiers before it ever consults the alias map:
 *
 * <ul>
 *   <li><b>{@link #replacement()} must be a registered identifier.</b> An alias pointing at
 *       something no factory registered is reported as {@code AW3121}, and the diagnostic lists
 *       the identifiers that were available. The alias is dropped and {@link #deprecated()} is not
 *       registered under anything else, so it becomes unresolvable; a declaration naming it as an
 *       injection point is refused separately as {@code AW1101}. Register the replacement, or
 *       correct the alias.
 *   <li><b>{@link #deprecated()} must not itself be registered.</b> An identifier is either
 *       current or retired, and declaring it as both is reported as {@code AW3111}. The alias is
 *       dropped here too, but {@link #deprecated()} still resolves — to the registration made under
 *       that same identifier — so nothing about lookups changes; only the retirement never takes
 *       effect.
 *   <li><b>{@link #deprecated()} must be claimed once.</b> Two aliases with the same
 *       {@link #deprecated()} and different {@link #replacement()} values are reported as
 *       {@code AW3111}. Declaring the identical alias twice is not a collision: aliases compare by
 *       component, and the second declaration replaces the first with an equal value.
 * </ul>
 *
 * <p>These checks run once, while the plugin registry is assembled, and go to the driver's
 * diagnostic listener rather than to the plugin that made the declaration. Both codes are errors,
 * so a build-time driver fails on them; and a declaration naming the identifier the dropped alias
 * was to have provided is refused separately when it is resolved, an injection point as
 * {@code AW1101}.
 *
 * <h2>What a build shows when an alias is used</h2>
 *
 * <p>Resolving an identifier through an alias produces {@code AW3120}, a warning whose message is
 * {@link #describe()}, reported to the listener the lookup is made with. The weaver's own injection
 * point and injector lookups are made with a listener that discards, so a weave written with a
 * retired spelling is woven exactly like one written with the current spelling and says nothing
 * about it in the build output. Nothing at all is reported for an alias that is only declared.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePoints implements InjectionPointFactory {
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
 *         // Called with either spelling, so this must not switch on the current one alone.
 *         return new AfterLoggingPoint();
 *     }
 * }
 * }</pre>
 *
 * <p>A weave written as {@code @At(custom = "acme:AFTER_LOG")} is woven exactly as
 * {@code @At(custom = "acme:AFTER_LOGGING")} would be, and the point is asked for by the spelling
 * the author wrote. {@link #describe()} reads
 * {@code 'acme:AFTER_LOG' is deprecated since 0.2.0; use 'acme:AFTER_LOGGING'}.
 *
 * @param deprecated  the identifier no longer to be written; never blank
 * @param replacement the identifier that stands in its place; never blank and never equal to
 *                    {@code deprecated}
 * @param since       the version from which {@code deprecated} counts as deprecated, reproduced as
 *                    written; never blank
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectionPointFactory#aliases()
 * @see InjectorFactory#aliases()
 */
public record Alias(@NotNull String deprecated,
                    @NotNull String replacement,
                    @NotNull String since) {

    /**
     * Checks that all three components carry text and that the alias points somewhere else.
     *
     * <p>A self-referential alias is refused here rather than left to the registry, because it
     * would resolve to nothing while looking like a rename that simply had not taken effect.
     * Whether the two identifiers exist is a question about a whole registry and is answered when
     * one is built.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if any component is empty or contains only whitespace, or
     *                                  if {@code deprecated} equals {@code replacement}
     */
    public Alias {
        Objects.requireNonNull(deprecated, "deprecated");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(since, "since");
        if (deprecated.isBlank() || replacement.isBlank() || since.isBlank()) {
            throw new IllegalArgumentException("no component of an alias may be blank");
        }
        if (deprecated.equals(replacement)) {
            throw new IllegalArgumentException(
                    "an alias must not point at itself: " + deprecated);
        }
    }

    /**
     * Returns the rename as one line of deprecation notice.
     *
     * <p>The form is {@code 'old' is deprecated since 1.2.3; use 'new'}, with the two identifiers in
     * single quotes and the version as it was given. It is the message of the {@code AW3120}
     * warning a registry raises when a lookup resolves through this alias, used whole rather than
     * assembled around.
     *
     * @return the deprecation notice, quoting both identifiers and the version
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        return '\'' + this.deprecated + "' is deprecated since " + this.since
                + "; use '" + this.replacement + '\'';
    }
}
