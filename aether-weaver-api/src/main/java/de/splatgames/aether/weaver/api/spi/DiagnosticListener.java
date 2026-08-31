package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;

import java.util.Objects;

/**
 * The sink every message the weaver produces is handed to.
 *
 * <p>A driver supplies one when it builds a weaver, and everything the run has to say — a selector
 * that matched nothing, a handler whose parameters do not fit, a plugin that threw — arrives here
 * as a {@link Diagnostic}. The engine writes to no stream of its own, so a listener that discards
 * its input produces a silent build, and one that collects its input has the complete account of
 * the run.
 *
 * <h2>A listener records; the driver decides</h2>
 *
 * <p>{@link #report(Diagnostic)} returns nothing, so a listener cannot veto a weave or stop a
 * build. What an error means is the driver's decision, taken from
 * {@link Diagnostic#severity()} after the fact: the Maven plugin collects the diagnostics of a run,
 * logs each at its severity, and fails the goal afterwards when any of them was an error and
 * {@code failOnError} is set. The engine's own reaction to a problem it reported — abandoning one
 * declaration, or leaving a class unwoven — is decided inside the engine and does not depend on
 * what the listener does with the message.
 *
 * <h2>What an implementation may assume</h2>
 *
 * <p>A diagnostic arrives fully built: it carries a code, a severity, a message, and often details
 * and a remedy. {@link Diagnostic#format()} renders the whole of it, which is what a listener
 * printing to a console wants. Most diagnostics are delivered as they are produced, and one mistake
 * is reported once per place it was found: an injection whose captures fail at three of its sites
 * says so three times, each naming what was live there. This is not universal: the plugin registry
 * accumulates every namespace-registration and alias problem it finds while it is being assembled
 * and reports the whole batch only once the registry is built.
 *
 * <p>{@link Reporter} extends this interface and adds a convenience for building a diagnostic from
 * a code and a message. Every reporting handle this SPI hands to an implementation is a
 * {@link Reporter} — {@link InjectionContext#diagnostics()}, {@link PluginContext#diagnostics()},
 * {@link DiscoveryContext#reporter()}, and the one {@link InjectionPoint#find} is called with. A
 * listener is what a driver installs underneath all of them.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * List<Diagnostic> collected = new ArrayList<>();
 * DiagnosticListener listener = ((DiagnosticListener) collected::add)
 *         .andThen(diagnostic -> System.out.println(diagnostic.format()));
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Diagnostic
 * @see Reporter
 */
@FunctionalInterface
public interface DiagnosticListener {

    /**
     * A listener that discards everything.
     *
     * <p>The default for a weaver whose driver installed no listener, and the right argument where
     * a call is made only for its result and its diagnostics have already been reported elsewhere.
     * A run wired to this listener still behaves exactly as one that reports; only the account of
     * it is lost.
     */
    DiagnosticListener NOOP = diagnostic -> {
    };

    /**
     * Records one diagnostic.
     *
     * <p>Called on the thread that produced the diagnostic, which for the load-time driver is the
     * thread loading the class. An implementation that keeps state has to be prepared for that.
     *
     * @param diagnostic the diagnostic to record
     */
    void report(Diagnostic diagnostic);

    /**
     * Returns a listener that reports to this one and then to another.
     *
     * <p>In that order, and without any containment between them: an exception thrown by this
     * listener means the other never sees the diagnostic.
     *
     * @param other the listener to report to afterwards; must not be {@code null}
     * @return a new listener feeding both, this one first
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    default DiagnosticListener andThen(@NotNull final DiagnosticListener other) {
        Objects.requireNonNull(other, "other");
        return diagnostic -> {
            report(diagnostic);
            other.report(diagnostic);
        };
    }
}
