package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

/**
 * What one weaver did, counted.
 *
 * <p>A view is immutable once obtained: every value it reports is fixed, and a later weave changes
 * the weaver's counters without changing anything already handed out. It is not, though, a
 * consistent snapshot of several counters taken together — each is read independently, without a
 * lock, while other threads may still be weaving — so two values read from one view need not agree
 * with each other the way they would if both had been read at the same instant.
 * {@link #plannedTargets()} is the one exception: it is fixed when the weaver is built, so
 * comparing it against {@link #classesWoven()} is meaningful even though the comparison mixes a
 * value settled once with one still being added to.
 *
 * <p>Instances are supplied by the engine, from the weaver itself and inside
 * {@link PluginEvent.WeavingFinished}. Every counter is summed across whatever threads did the
 * weaving, so a view can be taken at any time and not only at the end of a run.
 *
 * <h2>What the numbers are for</h2>
 *
 * <p>{@link #plannedTargets()} is what the plan asked for and {@link #classesWoven()} is what
 * happened, so the gap between them is the one figure that answers "did the weave apply". At load
 * time a gap is ordinary, because a planned target that the application never loads is never
 * offered. In a build it is not: the build plugin warns when {@link #classesWoven()} is below
 * {@link #plannedTargets()}, since that is a weave that did not reach an artefact about to be
 * published and nothing else in the build would say so.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * StatisticsView statistics = weaver.statistics();
 *
 * long missing = statistics.plannedTargets() - statistics.classesWoven();
 * if (missing > 0) {
 *     log.warn("{} of {} planned targets were never woven",
 *             missing, statistics.plannedTargets());
 * }
 * log.info("{} classes offered, {} woven, {} modifications applied in {} ms",
 *         statistics.classesSeen(), statistics.classesWoven(), statistics.entriesApplied(),
 *         statistics.weavingTimeNanos() / 1_000_000);
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PluginEvent.WeavingFinished
 */
@ApiStatus.NonExtendable
public interface StatisticsView {

    /**
     * Returns how many classes were offered to the weaver.
     *
     * <p>Counted the moment a class arrives, before the plan is consulted and before anything is
     * parsed, so this includes every class the plan does not name. Under an agent that is very
     * nearly every class the application loads.
     *
     * @return the number of classes offered, matched or not
     */
    @Contract(pure = true)
    long classesSeen();

    /**
     * Returns how many classes the weaver changed.
     *
     * <p>Counted once per class, after verification has accepted the woven bytes, and only on the
     * path the plan matched. A class the plan did not name, one the policy refused, one whose
     * modifications all failed to resolve, and one whose woven form the verifier handed back are
     * none of them counted here. Neither is a class changed only by extension rewriting: that path
     * does not run through this counter at all, so a class can really have changed without being
     * counted here.
     *
     * @return the number of classes woven
     */
    @Contract(pure = true)
    long classesWoven();

    /**
     * Returns how many planned modifications were applied.
     *
     * <p>Summed over the classes counted by {@link #classesWoven()}, one for each plan entry that
     * class had. Two weaves injecting into one target count two, where {@link #classesWoven()}
     * counts one; a class woven only by having a weave's members merged into it adds nothing here,
     * because a structural merge is not a plan entry.
     *
     * @return the number of plan entries applied
     */
    @Contract(pure = true)
    long entriesApplied();

    /**
     * Returns how many distinct classes the plan names.
     *
     * <p>Fixed when the weaver is built and never moves. It counts classes rather than
     * modifications: a target that two weaves inject into counts once, and a target named only by a
     * structural merge is counted as well, so that a purely structural weave is not invisible here.
     *
     * @return the number of classes the plan is able to change
     */
    @Contract(pure = true)
    long plannedTargets();

    /**
     * Returns how many classes were woven and then refused by verification.
     *
     * <p>The caller was handed the original class in each of these cases, so a failure here is a
     * class that was left alone rather than a class that was broken. This is counted only under a
     * verification policy that reports, and only on the path the plan matched; under the strict
     * policy, which is the engine's default, a refusal throws out of the weaver instead and nothing
     * reaches this counter. A class changed only by extension rewriting is verified as well, but a
     * refusal on that path is not counted here either: it is not on the path this counter watches.
     *
     * @return the number of classes whose woven form was refused
     */
    @Contract(pure = true)
    long failures();

    /**
     * Returns the time spent weaving, in nanoseconds.
     *
     * <p>Measured only around classes the plan named: a class that matches nothing costs no clock
     * reads at all, which is deliberate, because that is the path an agent takes for very nearly
     * every class an application loads. The intervals of concurrent weaves are added together, so
     * on more than one thread the total can exceed the wall-clock time the run took. An interval
     * that measures as zero or negative is discarded rather than added.
     *
     * @return the summed weaving time in nanoseconds
     */
    @Contract(pure = true)
    long weavingTimeNanos();
}
