package de.splatgames.aether.weaver.engine.observe;

import org.jetbrains.annotations.NotNull;

/**
 * The weaver's seam to JDK Flight Recorder, kept behind an interface so that no class on the
 * weaving path names {@code jdk.jfr}.
 *
 * <p>A runtime without the {@code jdk.jfr} module is a supported runtime, which is why the
 * implementation that emits events is named only as a string, in {@link #discover()}, and never as
 * a type: no field, cast or {@code new} anywhere in the engine names {@code JfrWeaveEvents}, so
 * loading and linking every other class stays possible without {@code jdk.jfr} present. Only
 * executing that implementation's constructor touches {@code jdk.jfr.Event}, and {@link #discover()}
 * catches the resulting failure there and falls back to {@link #NONE} instead of letting it escape.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public interface WeaveEvents {

    /**
     * The implementation used when nothing can be emitted.
     *
     * <p>A null object rather than a {@code null}, so that a runtime without JFR costs the weaver a
     * call that returns instead of a check on every woven class.
     */
    WeaveEvents NONE = new WeaveEvents() {

        /**
         * Reports that nothing is recording.
         *
         * @return {@code false}, always
         */
        @Override
        public boolean enabled() {
            return false;
        }

        /**
         * Discards the event.
         *
         * @param internalName ignored
         * @param entries      ignored
         * @param fingerprint  ignored
         * @param nanos        ignored
         */
        @Override
        public void classWoven(@NotNull final String internalName, final int entries,
                               @NotNull final String fingerprint, final long nanos) {
            // Nothing records here, and saying so costs one virtual call that returns.
        }
    };

    /**
     * Reports whether an event emitted now would be recorded.
     *
     * <p>Asked before the values are gathered, so that a class nobody is recording is not timed a
     * second time to fill in a field no consumer will read.
     *
     * @return whether {@link #classWoven(String, int, String, long)} would record anything
     */
    boolean enabled();

    /**
     * Records that a class was modified.
     *
     * <p>Called once the weaver has committed to handing back the woven bytes, so a class the
     * verifier refused and handed back unchanged produces no event.
     *
     * @param internalName the woven class's internal name, such as {@code com/acme/Ledger}
     * @param entries      the number of plan entries applied to it
     * @param fingerprint  the fingerprint of the plan that was applied
     * @param nanos        the time spent weaving this class
     */
    void classWoven(@NotNull String internalName, int entries, @NotNull String fingerprint,
                    long nanos);

    /**
     * Returns the implementation this runtime supports.
     *
     * <p>Both the JFR entry point and the implementation class are named as strings rather than as
     * types. Every way that lookup can fail — the class missing, the constructor missing, the
     * construction throwing — ends in {@link #NONE}.
     *
     * @return the Flight Recorder implementation, or {@link #NONE} when it cannot be created
     */
    @NotNull
    static WeaveEvents discover() {
        try {
            Class.forName("jdk.jfr.Event");
            return (WeaveEvents) Class.forName(
                            "de.splatgames.aether.weaver.engine.observe.JfrWeaveEvents")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (final Throwable unavailable) {
            // Reported nowhere. A runtime without JFR is a normal runtime, and a framework that
            // warned about it at every startup would be teaching people to skip its warnings.
            return NONE;
        }
    }
}
