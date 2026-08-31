package de.splatgames.aether.weaver.runtime.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The safety rules a run has been told to relax.
 *
 * <p>Set by the {@code aether.weaver.policy.allowSigned} and {@code aether.weaver.policy.allowPackage}
 * configuration keys. Unlike the rest of a {@link ConfigLayer}, a relaxation cannot be withdrawn by
 * a layer above it: {@link #merge(PolicyConfig)} takes the union rather than the higher value,
 * because {@link #STRICT} is also what a layer holds when its source said nothing about policy,
 * and treating that as a revocation would let any silent layer undo a deliberate exception.
 * Withdrawing a relaxation means removing the key that granted it.
 *
 * <p>A configuration whose policy is not {@link #isStrict() strict} is named in
 * {@link WeaverConfig#summary()} as {@code POLICY RELAXED}, which is what the agent prints at
 * startup.
 *
 * @param allowSigned   whether a class from a signed artefact may be woven;
 *                      {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} refuses one
 *                      with {@code AW3002} while this is {@code false}, and defines it unwoven
 * @param allowPackages the package names collected from {@code aether.weaver.policy.allowPackage};
 *                      a non-empty set is a relaxation for the purposes of {@link #isStrict()}
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record PolicyConfig(boolean allowSigned,
                           @NotNull @Unmodifiable Set<String> allowPackages) {

    /** Nothing relaxed. This is what a layer carries when its source said nothing about policy. */
    public static final PolicyConfig STRICT = new PolicyConfig(false, Set.of());

    /**
     * Takes a defensive, unmodifiable copy of the package names.
     *
     * @throws NullPointerException if {@code allowPackages} is {@code null} or holds a {@code null}
     */
    public PolicyConfig {
        allowPackages = Set.copyOf(Objects.requireNonNull(allowPackages, "allowPackages"));
    }

    /**
     * Combines this policy with one from a higher-precedence layer by adding the two together.
     *
     * <p>{@code allowSigned} is the disjunction and the package names are the union, so the result
     * relaxes at least as much as either input and never less.
     *
     * @param higher the policy from the layer above; must not be {@code null}
     * @return a new policy relaxed by everything either input relaxed
     * @throws NullPointerException if {@code higher} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public PolicyConfig merge(@NotNull final PolicyConfig higher) {
        Objects.requireNonNull(higher, "higher");
        final Set<String> packages = new LinkedHashSet<>(this.allowPackages);
        packages.addAll(higher.allowPackages);
        return new PolicyConfig(this.allowSigned || higher.allowSigned, packages);
    }

    /**
     * Whether nothing has been relaxed.
     *
     * @return {@code true} when signed artefacts are refused and no package was named
     */
    @Contract(pure = true)
    public boolean isStrict() {
        return !this.allowSigned && this.allowPackages.isEmpty();
    }
}
