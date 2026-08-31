package de.splatgames.aether.weaver.engine.stamp;

import de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the {@code AetherWeave} attribute back off a class, to answer whether it has been woven and
 * by which plan.
 *
 * <p>Every overload taking a {@link ClassModel} can only see the attribute when that model was
 * parsed with the attribute mapper installed, which is what
 * {@link WeaveAttribute#classFileWithMapper(java.lang.classfile.ClassFile.Option...)} does. A model
 * from a plain {@code ClassFile.of()} keeps the attribute in the class file but exposes it as an
 * unknown one, so the lookup finds nothing and every class looks unwoven — including one this
 * weaver stamped moments earlier. The {@code byte[]} overloads parse with the mapper themselves and
 * have no such precondition, which is why the weaver's skip gate uses them.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Provenance {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private Provenance() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns whether the class already carries this exact plan's stamp.
     *
     * <p>A different plan's fingerprint answers {@code false}, so it is a test for "this work is
     * already done" rather than for "something has been done here".
     *
     * @param model       the class, parsed with the attribute mapper installed; must not be
     *                    {@code null}
     * @param fingerprint the plan's fingerprint; must not be {@code null}
     * @return {@code true} when the stamped fingerprint equals the one given
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    public static boolean wovenBy(@NotNull final ClassModel model,
                                  @NotNull final String fingerprint) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return fingerprintOf(model).filter(fingerprint::equals).isPresent();
    }

    /**
     * Returns whether the class already carries this exact plan's stamp, parsing the bytes with the
     * mapper installed.
     *
     * @param bytes       the class as it stands; must not be {@code null}
     * @param fingerprint the plan's fingerprint; must not be {@code null}
     * @return {@code true} when the stamped fingerprint equals the one given
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    public static boolean wovenBy(final byte @NotNull [] bytes,
                                  @NotNull final String fingerprint) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return WeaveAttribute.readFrom(bytes)
                .map(WeaveAttribute::fingerprint)
                .filter(fingerprint::equals)
                .isPresent();
    }

    /**
     * Returns whether the class carries a stamp from any plan.
     *
     * @param model the class, parsed with the attribute mapper installed; must not be {@code null}
     * @return {@code true} when the attribute is present
     * @throws NullPointerException if {@code model} is {@code null}
     */
    @Contract(pure = true)
    public static boolean isWoven(@NotNull final ClassModel model) {
        return fingerprintOf(model).isPresent();
    }

    /**
     * Returns the fingerprint of the plan that wove the class.
     *
     * @param model the class, parsed with the attribute mapper installed; must not be {@code null}
     * @return the fingerprint, or empty when the class carries no attribute
     * @throws NullPointerException if {@code model} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static Optional<String> fingerprintOf(@NotNull final ClassModel model) {
        Objects.requireNonNull(model, "model");
        return WeaveAttribute.readFrom(model).map(WeaveAttribute::fingerprint);
    }

    /**
     * Reconstructs what was done to a class, from the attribute alone.
     *
     * <p>The record that comes back is not the one that was stamped: the attribute carries no
     * plugin coordinates and no plugin metadata, so both come back empty, and the weave names are
     * recovered as the distinct owners of the entries rather than read from a list of their own.
     * The two flag bits the attribute does carry are decoded into the record's booleans.
     *
     * @param bytes the class as it stands; must not be {@code null}
     * @return the record, or empty when the class carries no attribute
     * @throws NullPointerException     if {@code bytes} is {@code null}
     * @throws IllegalArgumentException if the attribute holds a blank weaver version, fingerprint
     *                                  or entry component, which the record refuses and the
     *                                  attribute does not
     */
    @Contract(pure = true)
    @NotNull
    public static Optional<WeaveRecord> recordOf(final byte @NotNull [] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return WeaveAttribute.readFrom(bytes).map(attribute -> {
            final List<WeaveRecord.Entry> entries = attribute.entries().stream()
                    .map(entry -> new WeaveRecord.Entry(entry.weaveClass(), entry.kind(),
                            entry.handler(), entry.target()))
                    .toList();
            final List<String> weaves = entries.stream()
                    .map(WeaveRecord.Entry::weave)
                    .distinct()
                    .toList();
            return new WeaveRecord(attribute.weaverVersion(), attribute.fingerprint(),
                    weaves, List.of(), Map.of(), entries,
                    attribute.usedPolicyOverride(),
                    (attribute.flags() & WeaveRecord.FLAG_STRUCTURAL) != 0);
        });
    }
}
