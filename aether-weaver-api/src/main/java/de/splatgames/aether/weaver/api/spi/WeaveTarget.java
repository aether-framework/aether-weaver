package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The facts a {@link WeavePolicy} decides on: which class is about to be changed, how old its class
 * file is, where it came from, and whether it is a weave rather than a target.
 *
 * <p>Deliberately small and deliberately not a class model. A policy runs on the class-loading path
 * of every driver, once per class the plan matched, and is expected to answer from a name and three
 * facts rather than from a parsed class file. Anything a policy needs beyond this has to be
 * something the caller can supply cheaply, or the gate stops being cheap.
 *
 * <h2>Which components are filled in, and by whom</h2>
 *
 * <p>The engine builds the target for its own policy gate from the class file it has just parsed:
 * {@link #internalName()} is the name the driver offered the class under and
 * {@link #majorVersion()} is the class file's own major version. It passes {@code false} for both
 * {@link #signed()} and {@link #declaredWeaveClass()}, because neither is knowable from the bytes
 * in front of it — a code source's certificates belong to the loader that read the artefact, and
 * whether a class is a declared weave belongs to discovery.
 *
 * <p>Those two components are therefore for a caller that invokes a policy itself and knows the
 * answers. A rule keyed on {@link #signed()} or {@link #declaredWeaveClass()} is silent when the
 * engine's own gate consults the policy, which is the shape of bug that looks like a policy failing
 * to apply. The drivers in this project make the signed decision separately and before the weaver
 * is asked: the load-time class loader inspects the {@link java.security.CodeSource} of the class
 * it is about to define and reports {@code AW3002} without weaving it, and the build plugin
 * inspects the signature of each dependency jar and reports {@code AW3002}, or {@code AW3020} when
 * the override is set.
 *
 * <h2>Name forms</h2>
 *
 * <p>{@link #internalName()} is the class-file spelling with {@code /} separators and no
 * {@code L...;} wrapper, which is the form every driver names a class in.
 * {@link #binaryName()} and {@link #packageName()} are derived from it and allocate on each call;
 * a policy that tests several prefixes should take the value once.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * final class VendorPolicies {
 *
 *     static final DiagnosticId VENDOR_CODE = new PluginDiagnosticId(
 *             "acme", "VENDOR_CODE", Severity.ERROR, DiagnosticCode.Category.POLICY,
 *             "vendor code is not woven in this build");
 *
 *     static WeavePolicy noVendorCode() {
 *         return candidate -> candidate.packageName().startsWith("com.vendor")
 *                 ? new WeavePolicy.Decision.Deny(VENDOR_CODE,
 *                         candidate.binaryName() + " is vendor code and is not woven here")
 *                 : WeavePolicy.Decision.allow();
 *     }
 *
 *     static void demo() {
 *         WeaveTarget target = new WeaveTarget("com/acme/billing/Ledger", 65, false, false);
 *
 *         target.binaryName();    // "com.acme.billing.Ledger"
 *         target.packageName();   // "com.acme.billing"
 *         target.toString();      // "com/acme/billing/Ledger"
 *     }
 * }
 * }</pre>
 *
 * @param internalName       the class-file name of the class, with {@code /} separators
 * @param majorVersion       the class file's major version; not validated here, and compared
 *                           against a minimum by whichever policy cares
 * @param signed             whether the class came from a signed artefact, as far as the caller
 *                           knows
 * @param declaredWeaveClass whether the class is itself a weave declaration rather than a target
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeavePolicy
 */
public record WeaveTarget(@NotNull String internalName,
                          int majorVersion,
                          boolean signed,
                          boolean declaredWeaveClass) {

    /**
     * Checks that a name is present and not blank.
     *
     * <p>{@link #majorVersion()} is not checked: a class file version this record refused could not
     * be reported as {@code AW2003} by a policy, which is where a version that is too old belongs.
     *
     * @throws NullPointerException     if {@code internalName} is {@code null}
     * @throws IllegalArgumentException if {@code internalName} is blank
     */
    public WeaveTarget {
        Objects.requireNonNull(internalName, "internalName");
        if (internalName.isBlank()) {
            throw new IllegalArgumentException("internalName must not be blank");
        }
    }

    /**
     * Returns the name in its source spelling, with {@code .} between package parts.
     *
     * <p>Only the separators change. A nested class keeps the {@code $} the compiler gave it, so
     * {@code com/acme/Outer$Inner} becomes {@code com.acme.Outer$Inner} rather than
     * {@code com.acme.Outer.Inner}.
     *
     * @return the binary name of the class
     */
    @Contract(pure = true)
    @NotNull
    public String binaryName() {
        return this.internalName.replace('/', '.');
    }

    /**
     * Returns the package this class is in, in source spelling.
     *
     * @return the package name, or the empty string for a class in the default package
     */
    @Contract(pure = true)
    @NotNull
    public String packageName() {
        final int lastSlash = this.internalName.lastIndexOf('/');
        return lastSlash < 0 ? "" : this.internalName.substring(0, lastSlash).replace('/', '.');
    }

    /**
     * Returns the internal name, so that a target reads as a class name in a diagnostic.
     *
     * @return {@link #internalName()}, unchanged and without the other components
     */
    @Override
    @NotNull
    public String toString() {
        return this.internalName;
    }
}
