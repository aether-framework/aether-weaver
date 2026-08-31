package de.splatgames.aether.weaver.runtime.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What one configuration layer said about a single weave class, addressed by binary name.
 *
 * <p>Written as {@code aether.weaver.weave[<binary name>].enabled=true|false} and
 * {@code aether.weaver.weave[<binary name>].priority=<integer>}. The name between the brackets
 * becomes a map key verbatim and is compared by string equality against the weave's binary name,
 * so a misspelt name is not an error and produces no diagnostic — the override simply never
 * applies.
 *
 * <p>The two components are independent. Setting only {@code priority} leaves the tag filter
 * deciding whether the weave runs at all, and setting only {@code enabled} leaves
 * {@link WeaverConfig#priorityOf(String)} empty.
 *
 * <p>{@code null} in a component means this layer said nothing about it, which is what lets a
 * lower-precedence layer keep deciding; see {@link #merge(WeaveOverride)}.
 *
 * @param enabled  whether the weave is applied, or {@code null} when this layer did not say
 * @param priority the priority recorded for the weave and returned by
 *                 {@link WeaverConfig#priorityOf(String)}, or {@code null} when this layer did not
 *                 say
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record WeaveOverride(@Nullable Boolean enabled, @Nullable Integer priority) {

    /** An override that says nothing, and therefore leaves every decision to the layers below. */
    public static final WeaveOverride NOTHING = new WeaveOverride(null, null);

    /**
     * Combines this override with one from a higher-precedence layer.
     *
     * <p>Component by component, so a higher layer that names only one of them keeps the other as
     * this layer left it.
     *
     * @param higher the override from the layer that wins where it speaks; must not be {@code null}
     * @return a new override taking each component from {@code higher} unless it is {@code null}
     * @throws NullPointerException if {@code higher} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public WeaveOverride merge(@NotNull final WeaveOverride higher) {
        return new WeaveOverride(
                higher.enabled != null ? higher.enabled : this.enabled,
                higher.priority != null ? higher.priority : this.priority);
    }

    /**
     * Whether this override leaves everything to the layers below.
     *
     * @return {@code true} when neither component was set
     */
    @Contract(pure = true)
    public boolean saysNothing() {
        return this.enabled == null && this.priority == null;
    }
}
