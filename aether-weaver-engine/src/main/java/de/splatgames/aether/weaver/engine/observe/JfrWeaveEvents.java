package de.splatgames.aether.weaver.engine.observe;

import jdk.jfr.EventType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Emits {@code ClassWovenEvent} to JDK Flight Recorder.
 *
 * <p>Nothing in the engine names this class in code; {@link WeaveEvents#discover()} names it only as
 * a string and reaches it reflectively, and the weaver holds the result as a {@link WeaveEvents}.
 * Loading and initialising this class does not by itself require {@code jdk.jfr}: what does is
 * running its constructor, which resolves the {@link EventType}, so the module is needed only once
 * {@link WeaveEvents#discover()} actually constructs an instance.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class JfrWeaveEvents implements WeaveEvents {

    /** The registered event type, resolved once so that {@link #enabled()} is a field read and a call. */
    private final EventType type;

    /**
     * Resolves the event type, which registers {@code ClassWovenEvent} with the runtime.
     */
    JfrWeaveEvents() {
        this.type = EventType.getEventType(ClassWovenEvent.class);
    }

    /**
     * Reports whether a recording currently has this event enabled.
     *
     * @return {@code true} only while a recording that enables
     *         {@code de.splatgames.aether.weaver.ClassWoven} is running; a runtime that has JFR but
     *         is recording nothing answers {@code false}
     */
    @Override
    public boolean enabled() {
        return this.type.isEnabled();
    }

    /**
     * Fills in an event and commits it.
     *
     * @param internalName the woven class's internal name; must not be {@code null}
     * @param entries      the number of plan entries applied to it
     * @param fingerprint  the fingerprint of the plan that was applied; must not be {@code null}
     * @param nanos        the time spent weaving this class
     * @throws NullPointerException if {@code internalName} or {@code fingerprint} is {@code null}
     */
    @Override
    public void classWoven(@NotNull final String internalName, final int entries,
                           @NotNull final String fingerprint, final long nanos) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(fingerprint, "fingerprint");

        final ClassWovenEvent event = new ClassWovenEvent();
        // Binary form, because a recording is read by a person and every other tool in a JFR view
        // shows binary names.
        event.wovenClass = internalName.replace('/', '.');
        event.modifications = entries;
        event.fingerprint = fingerprint;
        event.weavingTime = nanos;
        if (event.shouldCommit()) {
            // Asked again after the duration is known: a recording may carry a threshold, and that
            // cannot be evaluated from isEnabled() alone.
            event.commit();
        }
    }

    /**
     * Renders whether this is emitting anything, which is the only state the object has.
     *
     * @return {@code JfrWeaveEvents[recording]} or {@code JfrWeaveEvents[idle]}
     */
    @Override
    @NotNull
    public String toString() {
        return "JfrWeaveEvents[" + (enabled() ? "recording" : "idle") + ']';
    }
}
