package de.splatgames.aether.weaver.engine.verify;

import org.jetbrains.annotations.Contract;

/**
 * What the engine does with a woven class its verification pass refuses.
 *
 * <p>{@link Verifier} reads a policy through its two questions rather than by comparing constants:
 * {@link #verifies()} decides whether the woven bytes are examined at all, and {@link #isFatal()}
 * decides whether a refusal ends the run or is handed to a diagnostic listener.
 *
 * <p>The names are user-facing. A driver's {@code verification} configuration key is matched
 * against them without regard to case, so a constant renamed here is a configuration value renamed
 * for every user.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public enum VerificationPolicy {

    /**
     * Checks, and refuses a class by throwing.
     *
     * <p>Weaving then fails at the class it went wrong on. Handing the class over instead defers
     * the failure: loading unverifiable bytes through a {@link ClassLoader#defineClass(String,
     * byte[], int, int) ClassLoader}, as a driver does, was measured, on HotSpot 25, to succeed,
     * with the {@code VerifyError} arriving only when the JVM first links the class, and naming
     * that class rather than anything about weaving. This is the default of
     * {@link de.splatgames.aether.weaver.engine.WeaverBuilder}.
     */
    STRICT,

    /**
     * Checks, reports a refusal, and keeps the class as it arrived.
     *
     * <p>The refused bytes are dropped rather than written, so the target goes on working
     * unwoven. The caller is then holding bytes it did not weave, and the returned array alone
     * gives no signal of that; {@link Verifier#check(String, byte[], byte[])} states what does.
     */
    REPORT,

    /**
     * Checks nothing, so the woven class is handed back whatever it contains.
     */
    OFF;

    /**
     * Whether woven bytes are examined at all.
     *
     * @return {@code true} unless this is {@link #OFF}
     */
    @Contract(pure = true)
    public boolean verifies() {
        return this != OFF;
    }

    /**
     * Whether a refusal throws rather than being reported.
     *
     * @return {@code true} only for {@link #STRICT}
     */
    @Contract(pure = true)
    public boolean isFatal() {
        return this == STRICT;
    }
}
