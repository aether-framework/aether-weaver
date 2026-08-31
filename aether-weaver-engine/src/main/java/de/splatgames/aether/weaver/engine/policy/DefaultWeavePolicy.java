package de.splatgames.aether.weaver.engine.policy;

import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.WeavePolicy;
import de.splatgames.aether.weaver.api.spi.WeaveTarget;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The gate a class passes before the engine changes it, and the policy a weaver uses when none was
 * supplied.
 *
 * <p>Only two of its refusals can be reopened, and each in its own way:
 * {@link Builder#allowPackage(String)} names a single package under a JDK prefix and never a
 * subtree, while {@link Builder#allowSigned()} takes no argument at all, because signedness is a
 * property of the artefact a class came from rather than of a package. Everything else this policy
 * refuses, it refuses under every configuration.
 *
 * <p>Immutable once built, so one instance answers for every thread a parallel-capable class loader
 * weaves on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeavePolicy#and(WeavePolicy)
 */
public final class DefaultWeavePolicy implements WeavePolicy {

    /**
     * The oldest class file major version this policy accepts.
     *
     * <p>A target below it is refused as {@code AW2003}.
     */
    public static final int MINIMUM_MAJOR_VERSION = 50;

    /** The prefix no override reaches, checked before {@link #JDK_PREFIXES} is consulted. */
    private static final String ALWAYS_DENIED_PREFIX = "java.";

    /** The framework's own prefix, which no configuration reopens. */
    private static final String OWN_PREFIX = "de.splatgames.aether.weaver.";

    /** The prefixes closed by default; each package under one is reopened on its own. */
    private static final List<String> JDK_PREFIXES =
            List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    /** The packages reopened by name, matched exactly rather than as prefixes. */
    private final Set<String> allowedPackages;

    /** Whether a target from a signed code source is permitted. */
    private final boolean allowSigned;

    /**
     * Copies the builder's state, so that a builder reused afterwards cannot change this policy.
     *
     * @param builder the builder holding the overrides
     */
    private DefaultWeavePolicy(@NotNull final Builder builder) {
        this.allowedPackages = Set.copyOf(builder.allowedPackages);
        this.allowSigned = builder.allowSigned;
    }

    /**
     * Returns the policy with no override set.
     *
     * @return a policy that reopens no package and refuses signed artefacts
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static DefaultWeavePolicy standard() {
        return builder().build();
    }

    /**
     * Returns a builder for a policy with overrides.
     *
     * @return a fresh builder
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Decides whether the given class may be woven, refusing at the first rule that applies.
     *
     * <p>The order is what decides which code a class refused by several rules is reported under,
     * so it is fixed: a declared weave class is {@code AW1087}, a class of the framework itself is
     * {@code AW3003}, {@code java.*} is {@code AW3001}, a class file older than
     * {@link #MINIMUM_MAJOR_VERSION} is {@code AW2003}, a signed artefact without the override is
     * {@code AW3002}, and any other JDK prefix is {@code AW3001} again unless that exact package
     * was reopened. The reason carried by each denial is what tells the user what to do instead.
     *
     * <p>The weave-class rule and the signed rule read components the engine's own gate cannot fill
     * in: {@code Weaver} builds its {@link WeaveTarget} from the parsed class file alone and passes
     * {@code false} for both {@link WeaveTarget#declaredWeaveClass()} and
     * {@link WeaveTarget#signed()}, so {@code AW1087} and {@code AW3002} are raised here only for a
     * caller that invokes the policy itself and knows those answers.
     *
     * @param target the class about to be woven; must not be {@code null}
     * @return a {@link Decision.Deny} carrying the code and reason of the first rule that refused,
     *         or {@link Decision#allow()} when none did
     * @throws NullPointerException if {@code target} is {@code null}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public Decision decide(@NotNull final WeaveTarget target) {
        Objects.requireNonNull(target, "target");
        final String binaryName = target.binaryName();

        if (target.declaredWeaveClass()) {
            return new Decision.Deny(DiagnosticCode.WEAVE_TARGETS_WEAVE,
                    binaryName + " is a declared weave class. A weave is a declaration folded into "
                            + "its own targets; it is never loaded as itself, so weaving it would "
                            + "modify something that does not exist at runtime");
        }
        if (binaryName.startsWith(OWN_PREFIX)) {
            return new Decision.Deny(DiagnosticCode.POLICY_DENIED_SELF_WEAVE,
                    "Aether Weaver does not weave itself, under any configuration. A framework "
                            + "that can modify its own policy gate, verifier or stamper has no "
                            + "guarantees left to make");
        }
        if (binaryName.startsWith(ALWAYS_DENIED_PREFIX)) {
            return new Decision.Deny(DiagnosticCode.POLICY_DENIED_JDK_PACKAGE,
                    "java.* cannot be woven under any configuration. Its classes are loaded before "
                            + "any transformer can be installed, so an apparent success would be an "
                            + "accident of load ordering rather than a working weave");
        }
        if (target.majorVersion() < MINIMUM_MAJOR_VERSION) {
            return new Decision.Deny(DiagnosticCode.CLASS_FILE_VERSION_TOO_OLD,
                    binaryName + " is class file version " + target.majorVersion()
                            + ", older than " + MINIMUM_MAJOR_VERSION + ". Its stack map frames are "
                            + "absent or inferred, which the engine's transforms assume are present");
        }
        if (target.signed() && !this.allowSigned) {
            return new Decision.Deny(DiagnosticCode.POLICY_DENIED_SIGNED_ARTEFACT,
                    binaryName + " came from a signed code source. Modifying it invalidates the "
                            + "signature, which is a supply-chain event rather than a weaving "
                            + "detail");
        }
        final String jdkPrefix = jdkPrefixOf(binaryName);
        if (jdkPrefix != null && !isExplicitlyAllowed(target.packageName())) {
            return new Decision.Deny(DiagnosticCode.POLICY_DENIED_JDK_PACKAGE,
                    binaryName + " is in " + jdkPrefix + "*, which is not woven by default. "
                            + "Reopen exactly the package you need with "
                            + "aether.weaver.policy.allowPackage=" + target.packageName());
        }
        return Decision.allow();
    }

    /**
     * Returns the packages that were reopened by name.
     *
     * @return a fresh set sorted by name, so that a report of the overrides reads the same on every
     *         run; changing it does not change this policy
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public Set<String> allowedPackages() {
        return new TreeSet<>(this.allowedPackages);
    }

    /**
     * Returns whether a target from a signed code source is permitted.
     *
     * @return {@code true} when the signed override was set
     */
    @Contract(pure = true)
    public boolean allowsSigned() {
        return this.allowSigned;
    }

    /**
     * Returns whether this policy differs from {@link #standard()} in any way.
     *
     * @return {@code true} when a package was reopened or signed artefacts were permitted
     */
    @Contract(pure = true)
    public boolean hasOverrides() {
        return !this.allowedPackages.isEmpty() || this.allowSigned;
    }

    /**
     * Returns the overrides, which is all that distinguishes one instance from another.
     *
     * @return the reopened packages in name order and the signed override
     */
    @Override
    @NotNull
    public String toString() {
        return "DefaultWeavePolicy[allowedPackages=" + allowedPackages()
                + ", allowSigned=" + this.allowSigned + ']';
    }

    /**
     * Returns the JDK prefix the given name starts with.
     *
     * <p>{@code java.} is in {@link #JDK_PREFIXES} and is never the answer in practice, because
     * {@link #decide(WeaveTarget)} refuses that prefix outright before reaching this method.
     *
     * @param binaryName the class name to test; must not be {@code null}
     * @return the matching prefix, or {@code null} when the name is under none of them
     */
    @Contract(pure = true)
    private static String jdkPrefixOf(@NotNull final String binaryName) {
        for (final String prefix : JDK_PREFIXES) {
            if (binaryName.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Returns whether this exact package was reopened.
     *
     * <p>A set membership test rather than a prefix test, so reopening {@code com.sun.crypto} says
     * nothing about {@code com.sun.crypto.provider}.
     *
     * @param packageName the package of the target; must not be {@code null}
     * @return {@code true} when the package was named as an override
     */
    @Contract(pure = true)
    private boolean isExplicitlyAllowed(@NotNull final String packageName) {
        return this.allowedPackages.contains(packageName);
    }

    /**
     * Collects the overrides a policy is built with.
     *
     * <p>Rejects an override it cannot honour at the point it is named rather than when a class is
     * decided, so a configuration mistake surfaces where the setting was written.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Builder {

        /** The reopened packages, sorted so that the built policy reports them in name order. */
        private final Set<String> allowedPackages = new TreeSet<>();

        /** Whether signed artefacts are to be permitted. */
        private boolean allowSigned;

        /** A fresh builder reopens no package and does not permit signed artefacts. */
        private Builder() {
            // Created through DefaultWeavePolicy.builder().
        }

        /**
         * Reopens exactly one package that a JDK prefix rule would otherwise close.
         *
         * @param packageName the package in source spelling, without a wildcard; must not be
         *                    {@code null}
         * @return this builder
         * @throws NullPointerException     if {@code packageName} is {@code null}
         * @throws IllegalArgumentException if the name is blank, contains {@code *}, or is
         *                                  {@code java} or a package under it
         */
        @Contract("_ -> this")
        @NotNull
        public Builder allowPackage(@NotNull final String packageName) {
            Objects.requireNonNull(packageName, "packageName");
            if (packageName.isBlank()) {
                throw new IllegalArgumentException("a reopened package must be named");
            }
            if (packageName.indexOf('*') >= 0) {
                throw new IllegalArgumentException(
                        "an override names one package, never a wildcard: " + packageName);
            }
            if (packageName.equals("java") || packageName.startsWith(ALWAYS_DENIED_PREFIX)) {
                throw new IllegalArgumentException(
                        "java.* cannot be reopened; its classes load before any transformer can "
                                + "be installed, so this would not work even if it were permitted");
            }
            this.allowedPackages.add(packageName);
            return this;
        }

        /**
         * Permits targets that came from a signed code source.
         *
         * <p>The one override with no scope: it applies to every artefact rather than to a named
         * one, because the built policy is told only whether a target was signed.
         *
         * @return this builder
         */
        @Contract("-> this")
        @NotNull
        public Builder allowSigned() {
            this.allowSigned = true;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return a policy holding the overrides named so far
         */
        @Contract(value = " -> new", pure = true)
        @NotNull
        public DefaultWeavePolicy build() {
            return new DefaultWeavePolicy(this);
        }
    }
}
