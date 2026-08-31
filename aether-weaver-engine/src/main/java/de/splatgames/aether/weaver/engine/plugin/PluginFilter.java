package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.spi.PluginId;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns configured namespace lists into the predicate {@link PluginLoader} admits plugins by.
 *
 * <p>A plugin runs with the engine's own privileges, so the predicate answers one question and
 * answers it from the configuration alone: may a plugin of this namespace load here. Reading a
 * deployment's settings and deciding what "unconfigured" means happens here rather than in the
 * loader, which only calls {@code test}.
 *
 * <p>The three keys are named as constants but not read here — a caller passes the values it found.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PluginFilter {

    /** The setting that switches plugin discovery off entirely. */
    public static final String KEY_ENABLED = "aether.weaver.plugins.enabled";

    /** The setting naming the only namespaces that may load, comma-separated. */
    public static final String KEY_ALLOW = "aether.weaver.plugins.allow";

    /** The setting naming namespaces that may not load, comma-separated. */
    public static final String KEY_DENY = "aether.weaver.plugins.deny";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private PluginFilter() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds the predicate three configured values describe.
     *
     * <p>The three interact in a fixed way, so that reading a deployment's settings answers what can
     * load without having to reason about combinations. {@code enabled} wins over both lists; a
     * denied namespace is refused even when the allowlist names it; and an allowlist with any entry
     * is exhaustive rather than a set of exceptions, so a namespace it does not name is refused.
     * Both lists absent, empty or blank means unconfigured, which permits everything.
     *
     * <p>Only the exact text {@code false}, trimmed and compared without regard to case, switches
     * discovery off. {@code Boolean.parseBoolean} would read every misspelling as {@code false} and
     * turn a typo into a deployment that silently loads no plugins.
     *
     * @param enabled the value of {@link #KEY_ENABLED}, or {@code null} when unset
     * @param allow   the value of {@link #KEY_ALLOW}, or {@code null} when unset
     * @param deny    the value of {@link #KEY_DENY}, or {@code null} when unset
     * @return the predicate; it accepts everything when nothing is configured
     */
    @Contract(pure = true)
    @NotNull
    public static Predicate<PluginId> from(@Nullable final String enabled,
                                           @Nullable final String allow,
                                           @Nullable final String deny) {
        if (enabled != null && "false".equalsIgnoreCase(enabled.trim())) {
            return id -> false;
        }
        final Set<String> allowed = namespaces(allow);
        final Set<String> denied = namespaces(deny);
        if (allowed.isEmpty() && denied.isEmpty()) {
            return PluginLoader.acceptAll();
        }
        return id -> !denied.contains(id.namespace())
                && (allowed.isEmpty() || allowed.contains(id.namespace()));
    }

    /**
     * Builds a predicate admitting only the named namespaces.
     *
     * <p>Naming none is unconfigured rather than "permit nothing", which matches how an empty
     * {@link #KEY_ALLOW} reads; {@link #none()} is how a caller says nothing may load.
     *
     * @param namespaces the namespaces to admit; must not be {@code null}
     * @return the predicate
     * @throws NullPointerException if {@code namespaces} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static Predicate<PluginId> allowOnly(final String @NotNull ... namespaces) {
        final Set<String> allowed = new LinkedHashSet<>(Arrays.asList(namespaces));
        return allowed.isEmpty()
                ? PluginLoader.acceptAll()
                : id -> allowed.contains(id.namespace());
    }

    /**
     * Builds a predicate admitting nothing.
     *
     * <p>A plugin the loader was handed directly is refused by this as well; the built-in plugin is
     * exempt because {@link PluginLoader} never consults the predicate for it.
     *
     * @return the predicate
     */
    @Contract(pure = true)
    @NotNull
    public static Predicate<PluginId> none() {
        return id -> false;
    }

    /**
     * Splits a comma-separated setting into namespaces.
     *
     * <p>Tokens are trimmed and empty ones dropped, so trailing commas and spaces around a name are
     * as harmless as they look in a properties file.
     *
     * @param value the configured text, or {@code null} when unset
     * @return the namespaces, empty when the value is {@code null}, blank or all separators
     */
    @Contract(pure = true)
    @NotNull
    private static Set<String> namespaces(@Nullable final String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        final Set<String> namespaces = new LinkedHashSet<>();
        for (final String token : value.split(",")) {
            final String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                namespaces.add(trimmed);
            }
        }
        return Set.copyOf(namespaces);
    }
}
