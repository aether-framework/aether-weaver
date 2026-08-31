package de.splatgames.aether.weaver.api;

/**
 * The stage a weave declares itself to belong to.
 *
 * <p>A weave names one of these through {@link Weave#phase()}, which defaults to
 * {@link #DEFAULT}. The value travels with the weave: the engine records it on the parsed
 * weave, a generated manifest carries it as the {@code phase} field of the weave entry, and
 * the runtime configuration accepts a {@code phase} key whose value is the lower-case name
 * of one of these constants. A configuration that says nothing resolves to {@link #DEFAULT}.
 *
 * <p>The value is carried rather than acted upon. No stage of planning, conflict detection
 * or injection selects weaves by phase, so declaring {@link #EARLY} does not on its own
 * change whether or when a weave is applied; what orders two declarations meeting at one
 * place is {@link Weave#priority()}, and what decides whether a weave applies at all is its
 * target list and its {@link Require} setting.
 *
 * <h2>Order</h2>
 *
 * <p>The constants are declared earliest first, so {@link #EARLY} precedes {@link #DEFAULT}
 * in {@link Enum#ordinal()} order and the two compare that way through
 * {@link Comparable#compareTo(Object)}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(value = Ledger.class, phase = Phase.EARLY)
 * public final class LedgerAudit {
 *     // handlers
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Weave#phase()
 */
public enum Phase {

    /**
     * The earlier of the two stages.
     *
     * <p>Written to a manifest as {@code "EARLY"} and spelled {@code early} in a
     * configuration file.
     */
    EARLY,

    /**
     * The stage a weave belongs to when {@link Weave#phase()} is not written.
     *
     * <p>This is also what an unset {@code phase} configuration key resolves to. Written to
     * a manifest as {@code "DEFAULT"} and spelled {@code default} in a configuration file.
     */
    DEFAULT
}
