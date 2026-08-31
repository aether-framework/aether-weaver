package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What one configuration source said, with {@code null} kept for everything it did not mention.
 *
 * <p>A layer is deliberately not a {@link WeaverConfig}. Substituting a default for a component the
 * source never mentioned would make silence indistinguishable from a deliberate setting, and a
 * higher layer's silence would then quietly undo the layer beneath it. The defaults are applied
 * once, at the end, by {@link #resolve()}.
 *
 * <p>{@link ConfigParser} builds one layer per source and {@link ConfigLayers} keeps the ordered
 * stack of them; {@link #merge(ConfigLayer)} states which of two layers wins, and does not treat
 * every component the same way.
 *
 * @param enabled           whether weaving happens at all, or {@code null}
 * @param verification      what to do with woven bytes the verifier refuses, or {@code null}
 * @param onError           what to do with a class whose weaving threw, or {@code null}
 * @param dumpDirectory     where to write the original and woven bytes, or {@code null}. Also
 *                          {@code null} for {@code aether.weaver.dump=off}, which therefore reads
 *                          as silence and lets a directory set by a lower layer survive
 *                          {@link #merge(ConfigLayer)}
 * @param explain           whether to build an explain report, or {@code null}
 * @param tags              which weaves to keep, or {@code null}
 * @param phase             the stage named by {@code aether.weaver.phase}, or {@code null}
 * @param weaveOverrides    what was said about individual weaves, keyed by binary name; empty
 *                          rather than {@code null} when nothing was said
 * @param injectorOverrides what was said about individual injections, keyed by name; empty rather
 *                          than {@code null} when nothing was said
 * @param policy            the safety rules relaxed by this source; {@link PolicyConfig#STRICT}
 *                          rather than {@code null} when nothing was said
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record ConfigLayer(@Nullable Boolean enabled,
                          @Nullable VerificationPolicy verification,
                          @Nullable ErrorPolicy onError,
                          @Nullable Path dumpDirectory,
                          @Nullable Boolean explain,
                          @Nullable TagFilter tags,
                          @Nullable Phase phase,
                          @NotNull @Unmodifiable Map<String, WeaveOverride> weaveOverrides,
                          @NotNull @Unmodifiable Map<String, InjectorOverride> injectorOverrides,
                          @NotNull PolicyConfig policy) {

    /**
     * A layer that says nothing, and the identity of {@link #merge(ConfigLayer)} from either side.
     *
     * <p>{@link ConfigLayers} folds from this, and {@link WeaverConfig#defaults()} is its
     * resolution.
     */
    public static final ConfigLayer EMPTY = new ConfigLayer(
            null, null, null, null, null, null, null, Map.of(), Map.of(), PolicyConfig.STRICT);

    /**
     * Copies both override maps and checks that they and the policy are present.
     *
     * <p>The nullable components are left exactly as given: {@code null} is how a layer says that
     * its source did not mention the setting.
     *
     * @throws NullPointerException if either map or the policy is {@code null}, or if a map holds a
     *                              {@code null}
     */
    public ConfigLayer {
        weaveOverrides = Map.copyOf(Objects.requireNonNull(weaveOverrides, "weaveOverrides"));
        injectorOverrides =
                Map.copyOf(Objects.requireNonNull(injectorOverrides, "injectorOverrides"));
        Objects.requireNonNull(policy, "policy");
    }

    /**
     * Combines this layer with one of higher precedence.
     *
     * <p>Components do not all combine the same way, and the difference is what a deployment turns
     * on.
     *
     * <ul>
     *   <li>Each scalar is taken from {@code higher} unless {@code higher} left it {@code null}.
     *       That includes {@code tags}: a layer that mentions either tag key replaces the whole
     *       filter of the layers below rather than adding to it.
     *   <li>The two override maps are merged key by key, and where both layers name the same weave
     *       or injection the two overrides are themselves merged component-wise. A layer naming one
     *       weave therefore leaves everything said about the others intact.
     *   <li>The policies are added together by {@link PolicyConfig#merge(PolicyConfig)}, so a
     *       relaxation granted below cannot be withdrawn here.
     * </ul>
     *
     * <p>The operation is associative, so folding a stack of layers gives the same result however
     * the caller groups it.
     *
     * @param higher the layer that wins wherever it says anything; must not be {@code null}
     * @return a new layer, both inputs being unchanged
     * @throws NullPointerException if {@code higher} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public ConfigLayer merge(@NotNull final ConfigLayer higher) {
        Objects.requireNonNull(higher, "higher");
        return new ConfigLayer(
                higher.enabled != null ? higher.enabled : this.enabled,
                higher.verification != null ? higher.verification : this.verification,
                higher.onError != null ? higher.onError : this.onError,
                higher.dumpDirectory != null ? higher.dumpDirectory : this.dumpDirectory,
                higher.explain != null ? higher.explain : this.explain,
                higher.tags != null ? higher.tags : this.tags,
                higher.phase != null ? higher.phase : this.phase,
                mergedWeaves(higher),
                mergedInjectors(higher),
                this.policy.merge(higher.policy));
    }

    /**
     * Merges the weave overrides of the two layers.
     *
     * <p>{@link Map#merge(Object, Object, java.util.function.BiFunction)} is what keeps a weave
     * named only below from being dropped, and what keeps the two overrides for a weave named in
     * both from replacing one another wholesale.
     *
     * @param higher the layer of higher precedence
     * @return the combined overrides, keyed by weave, in no promised order
     */
    @NotNull
    private Map<String, WeaveOverride> mergedWeaves(@NotNull final ConfigLayer higher) {
        final Map<String, WeaveOverride> merged = new LinkedHashMap<>(this.weaveOverrides);
        higher.weaveOverrides.forEach((weave, override) -> merged.merge(weave, override,
                WeaveOverride::merge));
        return merged;
    }

    /**
     * Merges the injector overrides of the two layers, exactly as
     * {@link #mergedWeaves(ConfigLayer)} does for weaves.
     *
     * @param higher the layer of higher precedence
     * @return the combined overrides, keyed by injection, in no promised order
     */
    @NotNull
    private Map<String, InjectorOverride> mergedInjectors(@NotNull final ConfigLayer higher) {
        final Map<String, InjectorOverride> merged = new LinkedHashMap<>(this.injectorOverrides);
        higher.injectorOverrides.forEach((injection, override) -> merged.merge(injection, override,
                InjectorOverride::merge));
        return merged;
    }

    /**
     * Applies the defaults to everything this layer left unsaid.
     *
     * <p>Unset means on for {@code enabled}, off for {@code explain},
     * {@link VerificationPolicy#STRICT}, {@link ErrorPolicy#FAIL}, {@link TagFilter#ALL},
     * {@link Phase#DEFAULT} and no dump directory. The override maps and the policy are carried
     * over as they are.
     *
     * @return the configuration a run would use
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public WeaverConfig resolve() {
        return new WeaverConfig(
                this.enabled == null || this.enabled,
                this.verification == null ? VerificationPolicy.STRICT : this.verification,
                this.onError == null ? ErrorPolicy.FAIL : this.onError,
                this.dumpDirectory,
                this.explain != null && this.explain,
                this.tags == null ? TagFilter.ALL : this.tags,
                this.phase == null ? Phase.DEFAULT : this.phase,
                this.weaveOverrides,
                this.injectorOverrides,
                this.policy);
    }

    /**
     * Whether this layer contributes nothing to a merge.
     *
     * <p>A source that set {@code aether.weaver.dump=off} and nothing else says nothing by this
     * measure, since that key records no value. So does one whose policy is
     * {@link PolicyConfig#STRICT}, which is what an explicit
     * {@code aether.weaver.policy.allowSigned=false} produces.
     *
     * @return {@code true} when every scalar is unset, both override maps are empty and no policy
     *         is relaxed
     */
    @Contract(pure = true)
    public boolean saysNothing() {
        return this.enabled == null && this.verification == null && this.onError == null
                && this.dumpDirectory == null && this.explain == null && this.tags == null
                && this.phase == null
                && this.weaveOverrides.isEmpty() && this.injectorOverrides.isEmpty()
                && this.policy.isStrict();
    }

    /**
     * Returns a builder for a layer.
     *
     * @return a new builder, which starts out saying nothing
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Collects one source's settings, leaving anything not set on it {@code null}.
     *
     * <p>Not thread-safe. {@link #build()} may be called more than once and returns an independent
     * layer each time, which {@link ConfigParser} relies on: it builds part-way through a source to
     * read back the tag filter accumulated so far.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Builder {

        /** Whether weaving happens at all, or {@code null} while unset. */
        private @Nullable Boolean enabled;

        /** What to do with woven bytes the verifier refuses, or {@code null} while unset. */
        private @Nullable VerificationPolicy verification;

        /** What to do with a class whose weaving threw, or {@code null} while unset. */
        private @Nullable ErrorPolicy onError;

        /** Where to write the original and woven bytes, or {@code null} while unset. */
        private @Nullable Path dumpDirectory;

        /** Whether to build an explain report, or {@code null} while unset. */
        private @Nullable Boolean explain;

        /** Which weaves to keep, or {@code null} while unset. */
        private @Nullable TagFilter tags;

        /** The stage this source named, or {@code null} while unset. */
        private @Nullable Phase phase;

        /** What was said about individual weaves, keyed by binary name, in the order first named. */
        private final Map<String, WeaveOverride> weaves = new LinkedHashMap<>();

        /** What was said about individual injections, keyed by name, in the order first named. */
        private final Map<String, InjectorOverride> injectors = new LinkedHashMap<>();

        /** The relaxations this source granted; strict until {@link #policy(PolicyConfig)} says otherwise. */
        private PolicyConfig policy = PolicyConfig.STRICT;

        /**
         * Creates a builder that says nothing, reachable through {@link ConfigLayer#builder()}.
         */
        Builder() {
            // Created through ConfigLayer.builder().
        }

        /**
         * Says whether weaving happens at all.
         *
         * @param enabled {@code false} to weave nothing
         * @return this builder
         */
        @Contract("_ -> this")
        @NotNull
        public Builder enabled(final boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Says what to do with woven bytes the verifier refuses.
         *
         * @param verification the policy; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code verification} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder verification(@NotNull final VerificationPolicy verification) {
            this.verification = Objects.requireNonNull(verification, "verification");
            return this;
        }

        /**
         * Says what to do with a class whose weaving threw.
         *
         * @param onError the policy; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code onError} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder onError(@NotNull final ErrorPolicy onError) {
            this.onError = Objects.requireNonNull(onError, "onError");
            return this;
        }

        /**
         * Says where to write the original and woven bytes.
         *
         * <p>The directory is recorded as given and is neither resolved nor created here.
         *
         * @param directory the directory to write to; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code directory} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder dumpDirectory(@NotNull final Path directory) {
            this.dumpDirectory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /**
         * Says whether to build an explain report.
         *
         * @param explain {@code true} to build one
         * @return this builder
         */
        @Contract("_ -> this")
        @NotNull
        public Builder explain(final boolean explain) {
            this.explain = explain;
            return this;
        }

        /**
         * Says which weaves to keep, replacing any filter set on this builder before.
         *
         * @param tags the filter; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code tags} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder tags(@NotNull final TagFilter tags) {
            this.tags = Objects.requireNonNull(tags, "tags");
            return this;
        }

        /**
         * Names the stage this source asked for.
         *
         * @param phase the stage; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code phase} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder phase(@NotNull final Phase phase) {
            this.phase = Objects.requireNonNull(phase, "phase");
            return this;
        }

        /**
         * Says something about one weave class.
         *
         * <p>Accumulates rather than replaces: calling this twice for the same weave merges the two
         * overrides, so the {@code enabled} and {@code priority} keys of one source can arrive
         * separately without either losing the other.
         *
         * @param weave    the weave class's binary name; must not be {@code null}
         * @param override what this source said about it; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if either argument is {@code null}
         */
        @Contract("_, _ -> this")
        @NotNull
        public Builder weave(@NotNull final String weave, @NotNull final WeaveOverride override) {
            Objects.requireNonNull(weave, "weave");
            this.weaves.merge(weave, Objects.requireNonNull(override, "override"),
                    WeaveOverride::merge);
            return this;
        }

        /**
         * Says something about one injection.
         *
         * <p>Accumulates in the same way as {@link #weave(String, WeaveOverride)}.
         *
         * @param injection the injection's name; must not be {@code null}
         * @param override  what this source said about it; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if either argument is {@code null}
         */
        @Contract("_, _ -> this")
        @NotNull
        public Builder injector(@NotNull final String injection,
                                @NotNull final InjectorOverride override) {
            Objects.requireNonNull(injection, "injection");
            this.injectors.merge(injection, Objects.requireNonNull(override, "override"),
                    InjectorOverride::merge);
            return this;
        }

        /**
         * Sets the relaxations this source granted, replacing any set on this builder before.
         *
         * @param policy the relaxations; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code policy} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder policy(@NotNull final PolicyConfig policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Returns the layer described so far.
         *
         * <p>The builder is not spent: it may be added to and built again, and the layer already
         * returned is unaffected because the constructor copies both maps.
         *
         * @return a new layer
         */
        @Contract(value = " -> new", pure = true)
        @NotNull
        public ConfigLayer build() {
            return new ConfigLayer(this.enabled, this.verification, this.onError,
                    this.dumpDirectory, this.explain, this.tags, this.phase, this.weaves,
                    this.injectors, this.policy);
        }
    }
}
