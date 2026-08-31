package de.splatgames.aether.weaver.engine.observe;

import de.splatgames.aether.weaver.api.spi.StatisticsView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.LongAdder;

/**
 * The counters one weaver keeps over one plan.
 *
 * <p>Every count but one is a {@link LongAdder}: in an agent the weaver is entered on whichever
 * thread happens to be loading a class, so those counters are written from many threads and read
 * from almost none, which is the shape {@link LongAdder} is for. {@link #plannedTargets} is fixed
 * once at construction and read but never added to, so it is a plain {@code long} instead.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Statistics {

    /**
     * How many targets the plan names, fixed when the weaver is built.
     *
     * <p>The number the other counts are read against: a plan naming more targets than were ever
     * offered is a weave that did not apply, and nothing else in the run says so.
     */
    private final long plannedTargets;

    /** Classes offered to the weaver, whether the plan named them or not. */
    private final LongAdder seen = new LongAdder();

    /** Classes the weaver handed back changed. */
    private final LongAdder woven = new LongAdder();

    /** Plan entries applied, summed over every woven class. */
    private final LongAdder entries = new LongAdder();

    /** Classes whose woven form the verifier refused, and whose original was handed back instead. */
    private final LongAdder failures = new LongAdder();

    /** Nanoseconds spent on classes the plan named; a class it did not name is never timed. */
    private final LongAdder nanos = new LongAdder();

    /**
     * Creates counters for a plan of the given size.
     *
     * @param plannedTargets the number of targets the plan names
     * @throws IllegalArgumentException if {@code plannedTargets} is negative
     */
    public Statistics(final long plannedTargets) {
        if (plannedTargets < 0) {
            throw new IllegalArgumentException(
                    "plannedTargets must not be negative, got: " + plannedTargets);
        }
        this.plannedTargets = plannedTargets;
    }

    /**
     * Counts a class offered to the weaver.
     *
     * <p>Counted before the plan is consulted, so this includes every class an agent passes through
     * untouched.
     */
    public void seen() {
        this.seen.increment();
    }

    /**
     * Counts a woven class together with the entries applied to it.
     *
     * <p>Both in one call because they are one fact: a count of classes alone cannot tell a plan
     * that applied from one that half applied.
     *
     * @param applied the number of plan entries applied to the class
     */
    public void woven(final int applied) {
        this.woven.increment();
        this.entries.add(applied);
    }

    /**
     * Counts a class whose woven form was refused.
     *
     * <p>A refusal is not also a weave: the caller has handed the original bytes back, so
     * {@link #woven(int)} is not reached for the same class.
     */
    public void failed() {
        this.failures.increment();
    }

    /**
     * Adds to the total time spent weaving.
     *
     * <p>An elapsed time of zero or less is dropped rather than added, so a clock reading that went
     * backwards cannot take time off a total that is otherwise only ever added to.
     *
     * @param elapsed the nanoseconds the class took
     */
    public void spent(final long elapsed) {
        if (elapsed > 0) {
            this.nanos.add(elapsed);
        }
    }

    /**
     * Reads the counters into a value that does not move afterwards.
     *
     * <p>The five adders are read one after another rather than under a lock, so a snapshot taken
     * during weaving may show counts from slightly different moments; {@code plannedTargets} is
     * fixed at construction and carries no such risk.
     *
     * @return the counts as they stand
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public StatisticsView snapshot() {
        return new Snapshot(this.seen.sum(), this.woven.sum(), this.entries.sum(),
                this.plannedTargets, this.failures.sum(), this.nanos.sum());
    }

    /**
     * Renders a snapshot of the counters.
     *
     * @return the same single line {@link #snapshot()} renders
     */
    @Override
    @NotNull
    public String toString() {
        return snapshot().toString();
    }

    /**
     * An immutable reading of the counters.
     *
     * @param classesSeen      the classes offered to the weaver
     * @param classesWoven     the classes handed back changed
     * @param entriesApplied   the plan entries applied across them
     * @param plannedTargets   the targets the plan names
     * @param failures         the classes whose woven form the verifier refused
     * @param weavingTimeNanos the time spent on classes the plan named
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Snapshot(long classesSeen,
                            long classesWoven,
                            long entriesApplied,
                            long plannedTargets,
                            long failures,
                            long weavingTimeNanos) implements StatisticsView {

        /**
         * Renders every count on one line, for a log that has room for one.
         *
         * @return the counts, with the time in whole milliseconds; a run that took less than a
         *         millisecond therefore reads {@code time=0ms}
         */
        @Override
        @NotNull
        public String toString() {
            return "seen=" + this.classesSeen + " woven=" + this.classesWoven
                    + " entries=" + this.entriesApplied + " planned=" + this.plannedTargets
                    + " failures=" + this.failures
                    + " time=" + this.weavingTimeNanos / 1_000_000 + "ms";
        }
    }
}
