package de.splatgames.aether.weaver.runtime.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What one configuration layer said about a single injection, addressed by name.
 *
 * <p>Written as {@code aether.weaver.injector[<name>].enabled=true|false}. Whatever stands between
 * the brackets becomes a map key verbatim, and
 * {@link WeaverConfig#isInjectionEnabled(String)} compares it by string equality, so a name that
 * does not match the one being asked about is not an error and produces no diagnostic — the
 * override simply never applies.
 *
 * <p>{@code null} in a component means this layer said nothing about it, which is what lets a
 * lower-precedence layer keep deciding; see {@link #merge(InjectorOverride)}.
 *
 * @param enabled whether the injection is enabled, as answered by
 *                {@link WeaverConfig#isInjectionEnabled(String)}, or {@code null} when this layer did
 *                not say
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record InjectorOverride(@Nullable Boolean enabled) {

    /** An override that says nothing, and therefore leaves every decision to the layers below. */
    public static final InjectorOverride NOTHING = new InjectorOverride(null);

    /**
     * Combines this override with one from a higher-precedence layer.
     *
     * @param higher the override from the layer that wins where it speaks; must not be {@code null}
     * @return a new override taking each component from {@code higher} unless it is {@code null}
     * @throws NullPointerException if {@code higher} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public InjectorOverride merge(@NotNull final InjectorOverride higher) {
        return new InjectorOverride(higher.enabled != null ? higher.enabled : this.enabled);
    }

    /**
     * Whether this override leaves everything to the layers below.
     *
     * @return {@code true} when no component was set
     */
    @Contract(pure = true)
    public boolean saysNothing() {
        return this.enabled == null;
    }
}
