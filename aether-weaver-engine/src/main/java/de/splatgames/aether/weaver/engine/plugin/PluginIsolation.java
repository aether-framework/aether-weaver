package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * The boundary every call into plugin code crosses.
 *
 * <p>A plugin is third-party code running inside a build or a class loader, and a throw from it must
 * become a diagnostic naming the plugin rather than a stack trace naming this framework. Each entry
 * point catches {@link Throwable}, turns it into the {@linkplain Phase phase's} diagnostic and
 * answers "did not succeed" — a checked exception included, which is why the two functional
 * interfaces below are declared {@code throws Throwable} rather than reusing
 * {@link java.util.function.Supplier}.
 *
 * <p>{@link VirtualMachineError} is the one exception this boundary does not swallow: it is
 * re-thrown untouched rather than turned into a diagnostic.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PluginIsolation {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private PluginIsolation() {
        throw new AssertionError("no instances");
    }

    /**
     * Runs an action that returns nothing.
     *
     * @param who      how the plugin is named in the diagnostic; must not be {@code null}
     * @param phase    what the plugin was doing, which decides the code and the remedy; must not be
     *                 {@code null}
     * @param listener the sink for the diagnostic; must not be {@code null}
     * @param action   the plugin call; must not be {@code null}
     * @return whether the action returned normally
     * @throws NullPointerException if any argument is {@code null}
     * @throws VirtualMachineError  if the action throws one
     */
    public static boolean run(@NotNull final String who,
                              @NotNull final Phase phase,
                              @NotNull final DiagnosticListener listener,
                              @NotNull final ThrowingRunnable action) {
        Objects.requireNonNull(action, "action");
        return call(who, phase, listener, () -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    /**
     * Runs an action that produces a value.
     *
     * <p>An empty result does not distinguish a plugin that failed from one that returned
     * {@code null}: the first is reported and the second is not, so a plugin declining to answer
     * costs no diagnostic.
     *
     * @param <T>      the result type
     * @param who      how the plugin is named in the diagnostic; must not be {@code null}
     * @param phase    what the plugin was doing, which decides the code and the remedy; must not be
     *                 {@code null}
     * @param listener the sink for the diagnostic; must not be {@code null}
     * @param action   the plugin call; must not be {@code null}
     * @return the result, or empty when the action threw or answered {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws VirtualMachineError  if the action throws one
     */
    @NotNull
    public static <T> Optional<T> call(@NotNull final String who,
                                       @NotNull final Phase phase,
                                       @NotNull final DiagnosticListener listener,
                                       @NotNull final ThrowingSupplier<T> action) {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(action, "action");

        try {
            return Optional.ofNullable(action.get());
        } catch (final VirtualMachineError fatal) {
            // The JVM itself is compromised. Reporting "a plugin threw" would be a false account
            // of what is happening, and continuing would bury the cause under its consequences.
            throw fatal;
        } catch (final Throwable thrown) {
            listener.report(describe(who, phase, thrown));
            return Optional.empty();
        }
    }

    /**
     * Builds the diagnostic for a throw.
     *
     * <p>The exception's type and message, and the frame it was thrown from, are the whole of what
     * the plugin's author can act on, so the trace is reduced to its first element rather than
     * printed. A {@link LinkageError} takes a remedy of its own instead of the phase's: it says
     * almost nothing about what the plugin was doing and almost everything about which
     * {@code aether-weaver-api} it was compiled against.
     *
     * @param who    how the plugin is named; must not be {@code null}
     * @param phase  what the plugin was doing; must not be {@code null}
     * @param thrown what it threw; must not be {@code null}
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic describe(@NotNull final String who,
                                       @NotNull final Phase phase,
                                       @NotNull final Throwable thrown) {
        final Diagnostic.Builder builder = Diagnostic.builder(phase.code())
                .message(who + " threw while " + phase.describe())
                .detail(thrown.getClass().getName()
                        + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage()));

        final StackTraceElement[] trace = thrown.getStackTrace();
        if (trace.length > 0) {
            builder.detail("at " + trace[0]);
        }
        if (thrown instanceof LinkageError) {
            builder.remedy("this is what a plugin built against a different SPI generation looks "
                    + "like. Check that " + who + " returns WeaverApi.LEVEL from apiLevel() rather "
                    + "than a literal, and that no second copy of aether-weaver-api is on the "
                    + "classpath");
        } else {
            builder.remedy(phase.remedy());
        }
        return builder.build();
    }

    /**
     * What a plugin was doing when it threw, and therefore how the failure is reported.
     *
     * <p>Each constant carries its own diagnostic code, so a build log distinguishes a plugin that
     * could not be constructed from one that failed while weaving. Only {@link #OBSERVE} maps to a
     * warning; the other four map to errors, and {@link #isFatal()} answers along the same line.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Phase {

        /**
         * Constructing the plugin, or asking it for its identity or its API level.
         *
         * <p>Reported as {@code AW3114}. {@link PluginLoader} uses this phase for the service
         * provider call and for {@code id()} and {@code apiLevel()}, all of which happen before the
         * plugin has been admitted to anything.
         */
        INSTANTIATION(DiagnosticCode.PLUGIN_INSTANTIATION_FAILED, "being instantiated",
                "the plugin's constructor or static initialiser failed; it contributes nothing"),

        /**
         * Registering factories, resolvers, listeners and metadata into the context.
         *
         * <p>Reported as {@code AW3115}. A plugin that throws here contributes nothing at all, even
         * what it registered before throwing, because the loader drops the whole context.
         */
        CONTRIBUTE(DiagnosticCode.PLUGIN_CONTRIBUTE_FAILED, "registering its contributions",
                "report problems through PluginContext.diagnostics() instead of throwing"),

        /**
         * Resolving a contributed injection point against a target method.
         *
         * <p>Reported as {@code AW3116}. That point contributes no site, while the rest of the
         * declaration and the rest of the run go on. The name in the message is the point's
         * identifier rather than a plugin's, since the identifier is what the user wrote.
         */
        PLANNING(DiagnosticCode.PLUGIN_PLANNING_FAILED, "planning",
                "an injector or injection point must report a user's mistake through the "
                        + "Reporter and return an empty result, never throw"),

        /**
         * Emitting bytes through a contributed injector.
         *
         * <p>Reported as {@code AW3117}. The whole class rewrite sits inside the guard, so the
         * class is left as it was rather than half-woven, and the name in the message is the list of
         * contributed kinds involved.
         */
        APPLY(DiagnosticCode.PLUGIN_APPLY_FAILED, "weaving a class",
                "the class was left unmodified; emission must be total and deterministic"),

        /**
         * Receiving an event through {@code observe}.
         *
         * <p>Reported as {@code AW3118}, a warning rather than an error, and the plugin stays
         * registered. An observer cannot change what was written, so a build that fails for one
         * would be failing for something that changed nothing.
         */
        OBSERVE(DiagnosticCode.PLUGIN_OBSERVER_FAILED, "observing",
                "an observer cannot change the woven bytes, so weaving continued; fix the "
                        + "observer, but nothing was miswoven because of it");

        /** The code a failure in this phase is reported under. */
        private final DiagnosticCode code;

        /** What the plugin was doing, phrased to follow "threw while". */
        private final String description;

        /** What the plugin's author should do, used unless the throwable is a {@link LinkageError}. */
        private final String remedy;

        /**
         * Creates a phase.
         *
         * @param code        the diagnostic code; must not be {@code null}
         * @param description what the plugin was doing; must not be {@code null}
         * @param remedy      the advice for this phase; must not be {@code null}
         */
        Phase(@NotNull final DiagnosticCode code,
              @NotNull final String description,
              @NotNull final String remedy) {
            this.code = code;
            this.description = description;
            this.remedy = remedy;
        }

        /**
         * Returns the code a failure in this phase is reported under.
         *
         * @return the diagnostic code
         */
        @Contract(pure = true)
        @NotNull
        public DiagnosticCode code() {
            return this.code;
        }

        /**
         * Returns what the plugin was doing, as the message's trailing clause.
         *
         * @return the description
         */
        @Contract(pure = true)
        @NotNull
        public String describe() {
            return this.description;
        }

        /**
         * Returns the advice for a failure in this phase.
         *
         * @return the remedy
         */
        @Contract(pure = true)
        @NotNull
        public String remedy() {
            return this.remedy;
        }

        /**
         * Reports whether a failure in this phase can have changed what is written.
         *
         * <p>True for every phase but {@link #OBSERVE}, which matches the severities the codes
         * declare.
         *
         * @return whether the phase is fatal
         */
        @Contract(pure = true)
        public boolean isFatal() {
            return this != OBSERVE;
        }
    }

    /**
     * An action returning nothing that may throw anything.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ThrowingRunnable {

        /**
         * Performs the action.
         *
         * @throws Throwable if the plugin throws
         */
        void run() throws Throwable;
    }

    /**
     * An action returning a value that may throw anything.
     *
     * @param <T> the result type
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        /**
         * Produces the value.
         *
         * @return the value, or {@code null} to decline without failing
         * @throws Throwable if the plugin throws
         */
        T get() throws Throwable;
    }
}
