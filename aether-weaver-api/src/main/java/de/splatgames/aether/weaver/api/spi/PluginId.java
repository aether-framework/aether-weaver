package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who a plugin is: the namespace it owns, a name for a human, and its version.
 *
 * <p>A plugin returns one from {@link WeaverPlugin#id()}, and it is the first thing the engine asks
 * for. The namespace is the load-bearing component — everything a plugin contributes is qualified
 * with it, and the engine uses it to attribute a diagnostic to the party responsible. The other two
 * exist so that a build log names something a reader recognises and so that two builds with
 * different versions of one plugin are distinguishable.
 *
 * <h2>The namespace</h2>
 *
 * <p>A namespace matches {@code [a-z][a-z0-9-]*}: it begins with a lowercase letter and continues
 * with lowercase letters, digits and hyphens. No case folding, trimming or normalisation is applied,
 * so {@code Acme} and {@code acme_tools} are refused rather than corrected, and the canonical
 * constructor throws {@link IllegalArgumentException} for either.
 *
 * <p>Two namespaces are special and neither is available to a plugin:
 *
 * <ul>
 *   <li>{@link #RESERVED_NAMESPACE}, the literal {@code aether}, is refused by the constructor. A
 *       {@link PluginId} naming it cannot be built at all.
 *   <li>{@link #BUILT_IN_NAMESPACE}, the empty string, is accepted by the constructor and refused by
 *       the loader. It belongs to the framework's own plugin, which is what makes {@code HEAD},
 *       {@code RETURN} and {@code inject} spell without a colon. A discovered plugin claiming it is
 *       reported as {@code AW3101} and contributes nothing.
 * </ul>
 *
 * <p>A namespace has exactly one owner per weaver. Two plugins declaring the same one is reported as
 * {@code AW3111}, naming both, and the second is refused; which of the two is second is decided by
 * the load order, which sorts by namespace and then by implementation class name. Two builds of the
 * same classpath therefore refuse the same one.
 *
 * <h2>Where each component ends up</h2>
 *
 * <p>{@link #namespace()} prefixes every injector kind, every {@code @At} identifier, every plugin
 * diagnostic code and every metadata key the plugin contributes.
 *
 * <p>{@link #coordinate()} — {@code namespace:version} — is written into the {@code @Woven}
 * annotation of a class this weaver weaves, sorted alongside the coordinates of the other loaded
 * plugins, and is what {@code WovenInfo.plugins()} reads back. A plugin is listed there whether or
 * not it contributed anything to that particular class. Nothing is written when the annotation
 * itself is suppressed with {@code Woven.Detail.NONE}; the default, {@code Woven.Detail.SUMMARY},
 * always carries it. The namespace and version also feed {@link PlanView#fingerprint()}, so changing
 * a plugin's version changes the plan's identity: under the default build-time driver, a class
 * already stamped under the old fingerprint is then refused with {@code AW2201} rather than
 * rewoven, and under a load-time driver it is rewoven instead, after a warning, {@code AW2202}.
 *
 * <p>{@link #describe()} is what a diagnostic quotes when it needs to name the plugin as a whole.
 * Confirmed sites include the allowlist refusal {@code AW3119}, the API level gates {@code AW3112}
 * and {@code AW3113}, the load-time contribution failure {@code AW3115}, the observer failure
 * {@code AW3118}, the namespace violation {@code AW3110}, and the namespace collision {@code AW3111},
 * which quotes both identities involved. {@code AW3116} and {@code AW3117} instead quote the
 * {@code @At} identifier or the contributed injector kinds, not a {@link PluginId}, and
 * {@code AW3114} quotes {@link #describe()} only for its API level check; its remaining failures
 * often name a class instead, except where no plugin class could be identified at all.
 *
 * <h2>Equality</h2>
 *
 * <p>Componentwise, as a record. Two identities agreeing on the namespace and differing in version
 * are unequal and still collide at load time, because collision is decided on the namespace alone.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePlugin implements WeaverPlugin {
 *
 *     private static final PluginId ID = new PluginId("acme", "Acme Tracing", "1.0.0");
 *
 *     @Override
 *     public PluginId id() {
 *         return ID;   // asked for repeatedly; a constant costs nothing
 *     }
 *
 *     @Override
 *     public int apiLevel() {
 *         return WeaverApi.LEVEL;
 *     }
 * }
 * }</pre>
 *
 * <p>That identity describes as {@code Acme Tracing (acme 1.0.0)} and coordinates as
 * {@code acme:1.0.0}.
 *
 * @param namespace   the namespace this plugin owns; empty for the framework's own plugin, and
 *                    otherwise matching {@code [a-z][a-z0-9-]*}
 * @param displayName the name to print for a human; never blank, and otherwise unconstrained
 * @param version     the plugin's version, reproduced as written; never blank, and never parsed or
 *                    compared as a version by anything here
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaverPlugin#id()
 * @see PluginContext#self()
 */
public record PluginId(@NotNull String namespace,
                       @NotNull String displayName,
                       @NotNull String version) {

    /**
     * The namespace no plugin may claim, refused by the canonical constructor.
     *
     * <p>Distinct from {@link #BUILT_IN_NAMESPACE}: the framework's own plugin does not use this
     * name either. It is held back so that {@code aether:} can never appear as an identifier
     * belonging to a third party, whatever a future release decides to do with it.
     */
    public static final String RESERVED_NAMESPACE = "aether";

    /**
     * The namespace the framework's own plugin owns: the empty string.
     *
     * <p>This is why the built-in spellings carry no colon — {@code HEAD} rather than
     * {@code aether:HEAD}, {@code inject} rather than {@code aether:inject} — and why an identifier
     * containing a colon is by construction not a built-in one. The canonical constructor accepts an
     * identity naming it, since the framework's own plugin is built that way; the loader refuses any
     * discovered plugin that does, as {@code AW3101}.
     */
    public static final String BUILT_IN_NAMESPACE = "";

    /** The shape a non-empty namespace must have: a lowercase letter, then lowercase, digits and hyphens. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9-]*");

    /**
     * Checks the namespace against its grammar and the two other components for text.
     *
     * <p>The checks run in order: shape first, then the reserved name, then the display name, then
     * the version. {@code aether} satisfies the shape and is caught by the second check, so it is the
     * reserved-namespace message rather than the malformed one that a caller sees.
     *
     * <p>An empty namespace skips the shape check entirely, which is the only reason
     * {@link #BUILT_IN_NAMESPACE} is constructible. Whether a namespace is <em>available</em> is a
     * question about a whole weaver and is answered when one is loaded, as {@code AW3101} for the
     * built-in namespace and {@code AW3111} for one another plugin already owns.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code namespace} is neither empty nor a match for
     *                                  {@code [a-z][a-z0-9-]*}, if it is {@code aether}, or if
     *                                  {@code displayName} or {@code version} is empty or contains
     *                                  only whitespace
     */
    public PluginId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(version, "version");
        if (!namespace.isEmpty() && !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "a plugin namespace must match " + NAMESPACE.pattern() + ", got: " + namespace);
        }
        if (RESERVED_NAMESPACE.equals(namespace)) {
            throw new IllegalArgumentException(
                    "the namespace '" + RESERVED_NAMESPACE + "' is reserved for Aether Weaver");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /**
     * Returns the identity as one line for a human.
     *
     * <p>The form is {@code displayName (namespace version)}, with a single space between the
     * namespace and the version. The framework's own plugin has an empty namespace, so its rendering
     * has nothing before that space, leaving a single space after the opening parenthesis where a
     * third-party plugin's rendering carries its namespace.
     *
     * <p>This is the text every plugin diagnostic names the plugin by, and it is what the weaver's
     * explanation of a run prints for each loaded plugin.
     *
     * <p>The built-in plugin holds {@link #BUILT_IN_NAMESPACE}, which is the empty string, so its
     * namespace is left out rather than rendered as the leading space it would otherwise be.
     *
     * @return the identity rendered for a human
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        return this.namespace.isEmpty()
                ? this.displayName + " (" + this.version + ')'
                : this.displayName + " (" + this.namespace + ' ' + this.version + ')';
    }

    /**
     * Returns the identity as {@code namespace:version}.
     *
     * <p>The machine-readable form, and the one written into every woven class. The display name is
     * deliberately absent: it is prose, and this string is compared and sorted. The framework's own
     * plugin coordinates as {@code :0.1.0}, with nothing before the colon.
     *
     * @return the coordinate, {@code namespace} followed by a colon and {@code version}; the
     *         version is reproduced as written and may itself contain a colon, so the result is not
     *         guaranteed to contain exactly one
     */
    @Contract(pure = true)
    @NotNull
    public String coordinate() {
        return this.namespace + ':' + this.version;
    }

    /**
     * Returns {@link #coordinate()}.
     *
     * <p>The short form rather than the record's generated rendering, so that an identity
     * interpolated into a message reads as {@code acme:1.0.0}. {@link #describe()} is what a message
     * addressed to a human should use instead.
     *
     * @return the coordinate
     */
    @Override
    @NotNull
    public String toString() {
        return coordinate();
    }
}
