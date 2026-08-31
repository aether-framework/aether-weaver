package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.engine.explain.ExplainReport;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * The ordered stack of configuration sources, kept so that a run can say who decided what.
 *
 * <p>{@link ConfigLayer#merge(ConfigLayer)} alone would answer what the configuration is and lose
 * where it came from. Holding the layers instead lets {@link #resolve()} answer the first question
 * and {@link #settings()} the second, and both read the same rule: the last layer added that said
 * anything about a setting is the one that decided it. The relaxations in
 * {@link PolicyConfig} are the exception, since they accumulate across layers rather than being
 * settled by one of them; {@code DESCRIBED} states how they are attributed.
 *
 * <p>Precedence is therefore the order of the {@link #add(String, ConfigLayer)} calls, and nothing
 * about a source's name. The agent adds the system properties first and the agent arguments second,
 * so an argument given to that run beats a property set for the whole machine.
 *
 * <p>Instances are immutable; {@link #add(String, ConfigLayer)} returns a new stack.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConfigLayers {

    /** The source reported for a setting no layer mentioned. */
    private static final String DEFAULT = "default";

    /**
     * The settings {@link #settings()} reports, in the order it reports them.
     *
     * <p>The list is fixed, so two runs' reports can be compared line by line. A setting absent from
     * it is still resolved and still in force; it is simply not attributed, which is the case for
     * the per-weave and per-injection overrides and for {@code policy.allowPackage}.
     *
     * <p>Each entry pairs a function reading the raw component of a layer, whose {@code null} means
     * the layer said nothing, with a function rendering the resolved value. The reader for
     * {@code policy.allowSigned} maps {@code false} to {@code null} rather than reading the
     * component directly, because {@link PolicyConfig#STRICT} is what a layer carries when it said
     * nothing at all; the effect is that only a layer switching it on is ever credited with it.
     */
    private static final List<Described> DESCRIBED = List.of(
            new Described("enabled", ConfigLayer::enabled,
                    config -> Boolean.toString(config.enabled())),
            new Described("verification", ConfigLayer::verification,
                    config -> lower(config.verification().name())),
            new Described("onError", ConfigLayer::onError,
                    config -> lower(config.onError().name())),
            new Described("phase", ConfigLayer::phase,
                    config -> lower(config.phase().name())),
            new Described("tags", ConfigLayer::tags,
                    config -> config.tags().toString()),
            new Described("dump", ConfigLayer::dumpDirectory,
                    config -> config.dumpDirectoryIfSet().map(Object::toString).orElse("none")),
            new Described("explain", ConfigLayer::explain,
                    config -> Boolean.toString(config.explain())),
            new Described("policy.allowSigned", layer -> layer.policy().allowSigned() ? true : null,
                    config -> Boolean.toString(config.policy().allowSigned())));

    /** The layers in the order they were added, lowest precedence first. */
    private final List<Named> layers;

    /**
     * Creates a stack holding a copy of the given layers.
     *
     * @param layers the layers, lowest precedence first
     */
    private ConfigLayers(@NotNull final List<Named> layers) {
        this.layers = List.copyOf(layers);
    }

    /**
     * Returns an empty stack, which resolves to {@link WeaverConfig#defaults()} and attributes
     * every setting to {@code default}.
     *
     * @return a new empty stack
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static ConfigLayers of() {
        return new ConfigLayers(List.of());
    }

    /**
     * Returns this stack with one more layer on top of it.
     *
     * <p>The new layer wins over every layer already present, wherever it says anything. The source
     * name is used only in the report, is not checked against anything and is not required to be
     * unique.
     *
     * @param source what to call this layer in a report, in whatever words the driver uses; must
     *               not be {@code null}
     * @param layer  the layer; must not be {@code null}
     * @return a new stack, this one being unchanged
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public ConfigLayers add(@NotNull final String source, @NotNull final ConfigLayer layer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(layer, "layer");

        final List<Named> combined = new ArrayList<>(this.layers);
        combined.add(new Named(source, layer));
        return new ConfigLayers(combined);
    }

    /**
     * Folds the layers and applies the defaults.
     *
     * @return the configuration the run uses, equal to merging the same layers in the same order
     *         onto {@link ConfigLayer#EMPTY} and resolving that
     */
    @Contract(pure = true)
    @NotNull
    public WeaverConfig resolve() {
        return merged().resolve();
    }

    /**
     * Reports each described setting with the value in force and the layer that decided it.
     *
     * <p>The value is read from the resolved configuration, so it is what the run uses rather than
     * what the crediting layer wrote; for a setting no layer mentioned the source is {@code default}
     * and the value is the default.
     *
     * @return one entry per described setting, in a fixed order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<ExplainReport.Setting> settings() {
        final WeaverConfig resolved = resolve();
        final List<ExplainReport.Setting> settings = new ArrayList<>(DESCRIBED.size());
        for (final Described described : DESCRIBED) {
            settings.add(new ExplainReport.Setting(described.name(),
                    described.value().apply(resolved), sourceOf(described)));
        }
        return List.copyOf(settings);
    }

    /**
     * Finds the layer that decided one setting.
     *
     * @param described the setting to attribute
     * @return the name of the highest layer that said anything about it, or {@code default} when
     *         none did
     */
    @Contract(pure = true)
    @NotNull
    private String sourceOf(@NotNull final Described described) {
        // Backwards: the last layer that said anything is the one that won, which is exactly the
        // rule merge() applies. Reading the same rule from both ends is what keeps them agreeing.
        for (int i = this.layers.size() - 1; i >= 0; i--) {
            if (described.reads().apply(this.layers.get(i).layer()) != null) {
                return this.layers.get(i).source();
            }
        }
        return DEFAULT;
    }

    /**
     * Folds the layers into one, lowest precedence first.
     *
     * @return the combined layer, still with {@code null} for anything no layer mentioned
     */
    @Contract(pure = true)
    @NotNull
    private ConfigLayer merged() {
        ConfigLayer merged = ConfigLayer.EMPTY;
        for (final Named named : this.layers) {
            merged = merged.merge(named.layer());
        }
        return merged;
    }

    /**
     * Lower-cases an enum constant's name for the report.
     *
     * <p>{@link Locale#ROOT} rather than the default locale: a Turkish default renders the
     * {@code I} of {@code STRICT} as a dotless letter, so two machines' reports would differ in
     * their letters rather than in their settings. Measured on Temurin 25.0.3, such a value would
     * still be read back by {@link ConfigParser}, which compares without regard to case, so what
     * {@link Locale#ROOT} protects here is the report and not the parse.
     *
     * @param name the constant name
     * @return the name in lower case
     */
    @Contract(pure = true)
    @NotNull
    private static String lower(@NotNull final String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the source names in precedence order.
     *
     * @return a description of this stack
     */
    @Override
    @NotNull
    public String toString() {
        return "ConfigLayers" + this.layers.stream().map(Named::source).toList();
    }

    /**
     * A layer and the name to report it under.
     *
     * @param source what to call the layer in a report
     * @param layer  the layer
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Named(@NotNull String source, @NotNull ConfigLayer layer) {
    }

    /**
     * One setting {@link ConfigLayers#settings()} knows how to report.
     *
     * @param name  the name the report uses, which is not always a configuration key: the two
     *              {@code tags} keys are reported as one entry
     * @param reads reads the setting from an unresolved layer, answering {@code null} when that
     *              layer said nothing about it
     * @param value renders the setting from the resolved configuration
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Described(@NotNull String name,
                             @NotNull Function<ConfigLayer, Object> reads,
                             @NotNull Function<WeaverConfig, String> value) {
    }
}
