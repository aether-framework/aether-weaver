package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticId;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The handle an extension is given for saying that something is wrong.
 *
 * <p>A {@link DiagnosticListener} is what a driver installs; a reporter is what everything inside
 * the run is handed. The two are the same sink — a reporter adds one convenience and nothing else —
 * but the distinction in the signatures is deliberate: a listener is a destination, and a reporter is
 * permission to write to one.
 *
 * <p>Every reporting handle in this SPI is a reporter: {@link PluginContext#diagnostics()} while a
 * plugin contributes, the fourth argument of
 * {@link InjectionPoint#find(MethodView, CodeView, de.splatgames.aether.weaver.api.model.PointSpec,
 * Reporter)} while positions are resolved, the third argument of
 * {@link Injector#validate(PlanEntryView, TargetView, Reporter)}, and
 * {@link InjectionContext#diagnostics()} while an injector emits. The same handle is what
 * {@link de.splatgames.aether.weaver.api.manifest.ManifestReader#read(String, String, Reporter)}
 * takes.
 *
 * <h2>Reporting is the alternative to throwing</h2>
 *
 * <p>This is the whole reason the interface is passed everywhere it is. An exception thrown out of a
 * point or an injector carries no code, no location and no remedy, so it cannot be attributed to the
 * declaration that caused it; the engine contains such a throw where it can and reports it as
 * {@code AW3116} or {@code AW3117}, which tells a user that a plugin failed and nothing about what
 * their weave did wrong. A diagnostic reported here says both.
 *
 * <h2>Reporting does not decide anything by itself</h2>
 *
 * <p>{@link DiagnosticListener#report(Diagnostic)} returns nothing, so nothing an extension reports
 * stops a build on its own. What an error means is the driver's decision, taken from
 * {@link Diagnostic#severity()} afterwards. There is one place where a severity does change what the
 * engine does, and it is a property of that call site rather than of this interface: the reporter
 * handed to {@link Injector#validate(PlanEntryView, TargetView, Reporter)} watches the severities
 * passing through it, and an {@link de.splatgames.aether.weaver.api.diagnostic.Severity#ERROR}
 * abandons the declaration being validated. Everywhere else, an extension that reports an error is
 * expected to stop contributing of its own accord — an injection point returns no sites, an injector
 * returns {@link Injector.Emitter#NOTHING} — because the alternative is a class that is wrong in a
 * way the diagnostic did not describe.
 *
 * <h2>Threading</h2>
 *
 * <p>A reporter is called on the thread that produced the diagnostic. During weaving under the
 * load-time driver that is the thread loading the class, and that loader is parallel-capable, so an
 * implementation that keeps state has to tolerate concurrent calls.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * static final DiagnosticId NO_LOGGING = new PluginDiagnosticId(
 *         "acme", "NO_LOGGING", Severity.WARNING, DiagnosticCode.Category.INJECTION_POINT,
 *         "no logging call was found in the target method");
 *
 * @Override
 * public List<Site> find(MethodView method, CodeView code, PointSpec spec, Reporter reporter) {
 *     List<Site> found = search(code);
 *     if (found.isEmpty()) {
 *         reporter.report(NO_LOGGING, "no SLF4J call in " + method.describe());
 *     }
 *     return found;
 * }
 * }</pre>
 *
 * <p>A plugin defining conditions of its own uses
 * {@link de.splatgames.aether.weaver.api.diagnostic.PluginDiagnosticId} for them rather than reusing
 * a framework code: a code carrying a namespace can be attributed to its owner, and the two code
 * spaces are disjoint by construction.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see DiagnosticListener
 * @see Diagnostic
 */
@FunctionalInterface
public interface Reporter extends DiagnosticListener {

    /**
     * A reporter that discards everything.
     *
     * <p>The right argument where a call is made only for its result and its diagnostics have
     * already been reported elsewhere, or would be reported twice. The engine uses it for exactly
     * that: an injection point asked to locate a slice bound is called with this reporter, because a
     * bound that fails is reported once against the slice rather than once by the point and again by
     * the slice.
     *
     * <p>Distinct from {@link DiagnosticListener#NOOP} as a field, and identical in behaviour. This
     * one is a {@link Reporter} and can therefore be passed where one is required.
     */
    Reporter NOOP = diagnostic -> {
    };

    /**
     * Reports a condition with a code and a message and nothing else.
     *
     * <p>Equivalent to building the diagnostic with {@link Diagnostic#of(DiagnosticId, String)} and
     * passing it to {@link DiagnosticListener#report(Diagnostic)}: the severity is the code's own
     * default, the location is unknown, and there are no details and no remedy. Anything richer than
     * that is built with {@link Diagnostic#builder(DiagnosticId)} and reported through the inherited
     * {@link DiagnosticListener#report(Diagnostic)}, which is the abstract method this interface
     * still has exactly one of.
     *
     * <p>A remedy is worth the extra call. The message says what happened; the remedy is the only
     * part that tells a reader what to write instead, and a diagnostic without one leaves them to
     * work it out from the message.
     *
     * @param code    the condition being reported; must not be {@code null}
     * @param message what happened; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    default void report(@NotNull final DiagnosticId code, @NotNull final String message) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        report(Diagnostic.of(code, message));
    }
}
