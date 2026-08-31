package de.splatgames.aether.weaver.runtime.config;

/**
 * What a driver does with a class whose handling threw.
 *
 * <p>Set by the {@code aether.weaver.onError} configuration key, whose value is the name of one of
 * these constants without regard to case, and resolved to {@link #FAIL} when no configuration layer
 * sets it. The constant does not change how a class is woven: nothing hands it to the weaver, and
 * every other read of it only reports it. What it governs is the failure path, which is wider than
 * weaving alone under the agent, whose transformer runs the module-graph expansion and the dump
 * write inside the same {@code try} as the weave, so a throw out of either is handled by this
 * policy for a class that wove cleanly. The class loader reads it only around the weave itself.
 *
 * <p>{@link WeaverConfig#summary()} reports the setting whether or not anything threw, so under the
 * agent two runs differing only in this setting print different closing lines and different explain
 * reports. The class loader prints nothing at all, and neither does the agent on the exits that
 * return before the summary is built.
 *
 * <p>The failure is reported as {@code AW4090} before the policy is consulted, so the diagnostic
 * reaches the listener either way and the policy decides only what happens to the class afterwards.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public enum ErrorPolicy {

    /**
     * Stops the run rather than letting the class through unwoven.
     *
     * <p>What stopping costs is the driver's own decision.
     * {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} throws
     * {@code ClassNotFoundException} at whoever triggered the load, naming
     * {@code aether.weaver.onError=fail}. The agent's transformer cannot throw to any useful
     * effect, because the JVM discards what a transformer throws and carries on with the original
     * bytes, so it prints the failure and halts the JVM.
     */
    FAIL,

    /**
     * Keeps the class as it arrived and lets the run continue.
     *
     * <p>The unwoven bytes are the ones that get defined, so the application runs with that one
     * class unwoven and the reported {@code AW4090} is the only trace of it.
     */
    REPORT
}
