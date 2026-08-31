package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The configuration one run actually uses, with every question already answered.
 *
 * <p>This is what {@link ConfigLayer#resolve()} produces once the layers have been folded together:
 * where a {@link ConfigLayer} leaves a component {@code null} to mean that its source said nothing,
 * every component here is decided, and only {@link #dumpDirectory()} may still be {@code null}.
 * {@link ConfigParser} names the keys each component comes from, and {@link ConfigLayers} records
 * which layer decided each one.
 *
 * <p>The tag filter and the two override maps are consulted through
 * {@link #isEnabled(String, Set)}, {@link #isInjectionEnabled(String)} and
 * {@link #priorityOf(String)}, which is where the precedence between them is stated.
 *
 * @param enabled           whether weaving happens at all. The agent installs no transformer when
 *                          this is {@code false};
 *                          {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} does not
 *                          consult it, so switching weaving off does not disarm that driver
 * @param verification      what the engine does with woven bytes its verifier refuses
 * @param onError           what a driver does with a class whose weaving threw
 * @param dumpDirectory     where the original and woven bytes are written, or {@code null} to write
 *                          nothing
 * @param explain           whether the engine is asked to build an explain report
 * @param tags              which weaves are kept, judged by their declared tags
 * @param phase             the stage named by {@code aether.weaver.phase}; carried and reported
 *                          rather than acted upon, since nothing here selects weaves by phase
 * @param weaveOverrides    what was said about individual weaves, keyed by binary name
 * @param injectorOverrides what was said about individual injections, keyed by name
 * @param policy            the safety rules that have been relaxed
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record WeaverConfig(boolean enabled,
                           @NotNull VerificationPolicy verification,
                           @NotNull ErrorPolicy onError,
                           @Nullable Path dumpDirectory,
                           boolean explain,
                           @NotNull TagFilter tags,
                           @NotNull Phase phase,
                           @NotNull @Unmodifiable Map<String, WeaveOverride> weaveOverrides,
                           @NotNull @Unmodifiable Map<String, InjectorOverride> injectorOverrides,
                           @NotNull PolicyConfig policy) {

    /**
     * Checks that everything but the dump directory is present, and copies both override maps.
     *
     * @throws NullPointerException if any component other than {@code dumpDirectory} is
     *                              {@code null}, or if either map holds a {@code null}
     */
    public WeaverConfig {
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(onError, "onError");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(phase, "phase");
        weaveOverrides = Map.copyOf(Objects.requireNonNull(weaveOverrides, "weaveOverrides"));
        injectorOverrides =
                Map.copyOf(Objects.requireNonNull(injectorOverrides, "injectorOverrides"));
        Objects.requireNonNull(policy, "policy");
    }

    /**
     * Returns the configuration of a run nobody configured.
     *
     * <p>Weaving is on, verification is {@link VerificationPolicy#STRICT}, a failure to weave is
     * {@link ErrorPolicy#FAIL}, the phase is {@link Phase#DEFAULT}, every weave passes the tag
     * filter, nothing is dumped, no explain report is produced and no policy is relaxed.
     *
     * @return the resolution of an empty layer
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static WeaverConfig defaults() {
        return ConfigLayer.EMPTY.resolve();
    }

    /**
     * Returns the dump directory, if one was configured.
     *
     * @return the directory, or empty when {@code aether.weaver.dump} was unset or {@code off} in
     *         every layer
     */
    @Contract(pure = true)
    @NotNull
    public Optional<Path> dumpDirectoryIfSet() {
        return Optional.ofNullable(this.dumpDirectory);
    }

    /**
     * Decides whether one weave class is applied.
     *
     * <p>An override naming this weave decides it, in whichever direction it points: an operator who
     * named a weave has said something about that weave, and a tag filter is a rule about many. The
     * filter decides instead wherever the override does not: where no override names the weave,
     * where the one that does sets nothing but a priority, and where it sets neither component at
     * all.
     *
     * @param binaryName the weave class's binary name, matched against the override keys by string
     *                   equality; must not be {@code null}
     * @param weaveTags  the tags the weave declares, possibly empty; must not be {@code null}
     * @return {@code true} when the weave is applied
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    public boolean isEnabled(@NotNull final String binaryName, @NotNull final Set<String> weaveTags) {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(weaveTags, "weaveTags");

        final WeaveOverride override = this.weaveOverrides.get(binaryName);
        if (override != null && override.enabled() != null) {
            return override.enabled();
        }
        return this.tags.accepts(weaveTags);
    }

    /**
     * Decides whether one injection is applied.
     *
     * <p>Enabled unless an override registered under exactly this name says otherwise, so a name
     * with no override and a name whose override set nothing both answer {@code true}. Unlike
     * {@link #isEnabled(String, Set)} this consults no tag filter; tags are declared by a weave
     * rather than by an injection.
     *
     * @param injection the injection's name, matched against the override keys by string equality;
     *                  must not be {@code null}
     * @return {@code true} unless an override for this name switched it off
     * @throws NullPointerException if {@code injection} is {@code null}
     */
    @Contract(pure = true)
    public boolean isInjectionEnabled(@NotNull final String injection) {
        Objects.requireNonNull(injection, "injection");
        final InjectorOverride override = this.injectorOverrides.get(injection);
        return override == null || override.enabled() == null || override.enabled();
    }

    /**
     * Returns the priority configured for one weave class.
     *
     * @param binaryName the weave class's binary name; must not be {@code null}
     * @return the configured priority, or empty when no override names this weave and equally when
     *         the one that does set only {@code enabled}
     * @throws NullPointerException if {@code binaryName} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public Optional<Integer> priorityOf(@NotNull final String binaryName) {
        Objects.requireNonNull(binaryName, "binaryName");
        final WeaveOverride override = this.weaveOverrides.get(binaryName);
        return Optional.ofNullable(override).map(WeaveOverride::priority);
    }

    /**
     * Returns the one line a driver prints to say what this run is doing.
     *
     * <p>The state of weaving, the verification policy, the error policy and the phase are always
     * present; the tag filter, the dump directory and the explain flag are added only where they
     * are not at their defaults. Two of them are deliberately shouted: weaving being off reads
     * {@code DISABLED} rather than {@code disabled}, and a policy that is not
     * {@link PolicyConfig#isStrict() strict} appends {@code POLICY RELAXED} and the policy itself.
     * The per-weave and per-injection overrides are not summarised.
     *
     * @return a one-line description of this configuration
     */
    @Contract(pure = true)
    @NotNull
    public String summary() {
        final StringBuilder text = new StringBuilder(96)
                .append(this.enabled ? "enabled" : "DISABLED")
                .append(", verification=").append(this.verification.name().toLowerCase(
                        java.util.Locale.ROOT))
                .append(", onError=").append(this.onError.name().toLowerCase(
                        java.util.Locale.ROOT))
                .append(", phase=").append(this.phase.name().toLowerCase(java.util.Locale.ROOT));
        if (!this.tags.isUnrestricted()) {
            text.append(", tags=").append(this.tags);
        }
        if (this.dumpDirectory != null) {
            text.append(", dump=").append(this.dumpDirectory);
        }
        if (this.explain) {
            text.append(", explain");
        }
        if (!this.policy.isStrict()) {
            // Never omitted when it applies. A relaxed safety rule that leaves no trace in the
            // log is one nobody reviewing the deployment will know was used.
            text.append(", POLICY RELAXED: ").append(this.policy);
        }
        return text.toString();
    }

    /**
     * Returns {@link #summary()} in brackets.
     *
     * @return a description of this configuration
     */
    @Override
    @NotNull
    public String toString() {
        return "WeaverConfig[" + summary() + ']';
    }
}
