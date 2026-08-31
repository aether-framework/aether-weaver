package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only configuration handed to a plugin, as string keys and string values.
 *
 * <p>A plugin reaches one through {@link PluginContext#configuration()}, on the context it is
 * handed while it contributes. The view is built once, from what the driver knew when the plugin
 * was loaded, and is never refreshed, so a setting read at the end of a run is the setting that was
 * read at the start of it.
 *
 * <h2>Everything is a string, and everything is optional</h2>
 *
 * <p>There is no schema and no declaration of the keys a plugin understands, so every lookup can
 * miss and every value can be nonsense. The three typed accessors take a fallback for exactly that
 * reason, and none of them reports a diagnostic: a value that cannot be interpreted is treated as
 * absent and the fallback is used. A plugin that wants a malformed value to be visible reads it
 * with {@link #get(String)} and reports the problem itself through
 * {@link PluginContext#diagnostics()}.
 *
 * <p>Whether a driver has any configuration to offer at all is the driver's business. The weaver
 * assembled by {@code Weaver.builder()} supplies an empty view to every plugin, so a plugin must
 * work with its defaults alone.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Override
 * public void contribute(PluginContext ctx) {
 *     ConfigView config = ctx.configuration();
 *     int budget = config.getInt("acme.budget", 32);
 *     boolean verbose = config.getBoolean("acme.verbose", false);
 *     ctx.points(new AcmePoints(budget, verbose));
 * }
 * }</pre>
 *
 * <p>Instances are supplied by the driver; {@link #of(Map)} and {@link #empty()} are the two ways
 * one is made.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PluginContext#configuration()
 */
@ApiStatus.NonExtendable
public interface ConfigView {

    /**
     * Returns a view over a copy of the given entries.
     *
     * <p>The map is copied with {@link Map#copyOf(Map)}, so the view is independent of the map that
     * was passed in and a {@code null} key or value is refused here rather than at the first
     * lookup. The keys of the resulting view are reported in ascending order regardless of the
     * order the map iterated in.
     *
     * @param values the configuration entries; must not be {@code null}, and must hold neither a
     *               {@code null} key nor a {@code null} value
     * @return a new view over a private copy of those entries
     * @throws NullPointerException if {@code values} is {@code null}, or holds a {@code null} key
     *                              or value
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    static ConfigView of(@NotNull final Map<String, String> values) {
        final Map<String, String> copy = Map.copyOf(Objects.requireNonNull(values, "values"));
        return new ConfigView() {

            /**
             * Returns the value held under the key, if there is one.
             *
             * @param key the key to look up; must not be {@code null}
             * @return the value, or an empty {@link Optional} when the copy holds no such key
             * @throws NullPointerException if {@code key} is {@code null}
             */
            @Override
            @NotNull
            public Optional<String> get(@NotNull final String key) {
                return Optional.ofNullable(copy.get(Objects.requireNonNull(key, "key")));
            }

            /**
             * Returns the keys of the copy in ascending order.
             *
             * <p>A new {@link TreeSet} is built on every call; nothing here wraps it as
             * unmodifiable, so the {@link Unmodifiable} annotation on {@link ConfigView#keys()}
             * does not hold for this implementation, and a caller may freely add to or remove from
             * what it gets back without affecting this view.
             *
             * @return a new sorted set of the keys
             */
            @Override
            @NotNull
            public Set<String> keys() {
                return new TreeSet<>(copy.keySet());
            }

            /**
             * Returns the entries in key order, for a diagnostic or a log line.
             *
             * @return {@code ConfigView} followed by the entries, sorted by key
             */
            @Override
            @NotNull
            public String toString() {
                return "ConfigView" + new TreeMap<>(copy);
            }
        };
    }

    /**
     * Returns a view with no entries.
     *
     * <p>Every lookup misses and {@link #keys()} is empty, so every typed accessor answers with its
     * fallback. This is what a plugin sees from a driver that offers no configuration.
     *
     * @return a view holding nothing
     */
    @Contract(pure = true)
    @NotNull
    static ConfigView empty() {
        return of(Map.of());
    }

    /**
     * Returns the raw value held under the key, if there is one.
     *
     * <p>The value is returned exactly as it was supplied, including any surrounding whitespace.
     *
     * @param key the key to look up; must not be {@code null}
     * @return the value, or an empty {@link Optional} when the key is not present
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    Optional<String> get(@NotNull String key);

    /**
     * Returns every key this view holds.
     *
     * <p>Sorted ascending, which makes a plugin that iterates the keys behave the same way on every
     * run. Declared not to be modified — but {@link #of(Map)}, the only implementation in this
     * module, returns a fresh mutable {@link TreeSet} on every call rather than an unmodifiable one,
     * so the {@link Unmodifiable} annotation is not honoured by that implementation and a caller
     * that relies on the returned set rejecting a change is relying on something the code does not
     * do.
     *
     * @return the keys, empty when there are none
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    Set<String> keys();

    /**
     * Returns the value held under the key, or the given fallback when there is none.
     *
     * <p>A key that is present with an empty value yields that empty string rather than the
     * fallback; only absence selects the fallback.
     *
     * @param key      the key to look up; must not be {@code null}
     * @param fallback the value to use when the key is absent; must not be {@code null}
     * @return the value, or {@code fallback}
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    default String get(@NotNull final String key, @NotNull final String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return get(key).orElse(fallback);
    }

    /**
     * Returns the value held under the key as a boolean, or the given fallback.
     *
     * <p>Only {@code true} and {@code false} are recognised, ignoring case. Every other value —
     * {@code yes}, {@code 1}, {@code on}, an empty string, a value with surrounding whitespace —
     * selects the fallback, which is why a fallback of {@code true} is not the same as negating a
     * fallback of {@code false}. Nothing is reported for a value that is not recognised.
     *
     * @param key      the key to look up; must not be {@code null}
     * @param fallback the value to use when the key is absent or unrecognised
     * @return the parsed value, or {@code fallback}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Contract(pure = true)
    default boolean getBoolean(@NotNull final String key, final boolean fallback) {
        return get(key)
                .map(value -> {
                    if ("true".equalsIgnoreCase(value)) {
                        return Boolean.TRUE;
                    }
                    return "false".equalsIgnoreCase(value) ? Boolean.FALSE : null;
                })
                .orElse(fallback);
    }

    /**
     * Returns the value held under the key as an {@code int}, or the given fallback.
     *
     * <p>The value is trimmed and parsed with {@link Integer#valueOf(String)}, so a leading sign is
     * accepted and the radix is always ten. A value that does not parse — a number outside the
     * range of an {@code int} among them — selects the fallback and is not reported, so a
     * mistyped setting shows up as default behaviour rather than as an error.
     *
     * @param key      the key to look up; must not be {@code null}
     * @param fallback the value to use when the key is absent or does not parse
     * @return the parsed value, or {@code fallback}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Contract(pure = true)
    default int getInt(@NotNull final String key, final int fallback) {
        return get(key).map(value -> {
            try {
                return Integer.valueOf(value.trim());
            } catch (final NumberFormatException notAnInt) {
                return null;
            }
        }).orElse(fallback);
    }
}
