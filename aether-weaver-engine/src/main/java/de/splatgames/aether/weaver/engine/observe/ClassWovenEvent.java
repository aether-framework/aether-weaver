package de.splatgames.aether.weaver.engine.observe;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * The Flight Recorder event a woven class produces.
 *
 * <p>Loading this class resolves {@code jdk.jfr.Event} through the superclass, so nothing on the
 * weaving path names it: it is reached only from {@code JfrWeaveEvents}, which
 * {@link WeaveEvents#discover()} itself looks up by name.
 *
 * <p>The annotations are the recording's schema. A consumer reads an event back by the name in
 * {@code @Name} and its values by the field names declared below, neither of which the compiler
 * checks against anything.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Name("de.splatgames.aether.weaver.ClassWoven")
@Label("Class Woven")
@Category({"Java Application", "Aether Weaver"})
@Description("A class was modified by Aether Weaver as it was loaded or built")
@StackTrace(false)
final class ClassWovenEvent extends Event {

    /** The woven class, in binary form rather than the internal form the weaver works in. */
    @Label("Class")
    String wovenClass;

    /** The number of plan entries applied to the class. */
    @Label("Modifications")
    int modifications;

    /** The fingerprint of the plan that was applied, which ties an event to the build that made it. */
    @Label("Plan Fingerprint")
    String fingerprint;

    /**
     * The time spent weaving this class.
     *
     * <p>Declared as a {@link Timespan} so that a JFR view can total and filter it as a duration
     * rather than as an integer that happens to count nanoseconds.
     */
    @Label("Weaving Time")
    @Timespan(Timespan.NANOSECONDS)
    long weavingTime;

    /**
     * Creates an event with every field at its default, for {@code JfrWeaveEvents} to fill in.
     */
    ClassWovenEvent() {
        // JFR requires a no-argument constructor, and populates nothing itself.
    }
}
