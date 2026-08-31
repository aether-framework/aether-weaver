package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How seriously a {@link Diagnostic} is to be taken, and whether a build may continue past it.
 *
 * <p>Every {@link DiagnosticId} declares one of these as its {@link DiagnosticId#defaultSeverity()},
 * and that is the severity a {@link Diagnostic} carries unless
 * {@link Diagnostic.Builder#severity(Severity)} overrides it for one report. The constants are
 * declared from least to most serious, and that order is the whole of their comparison contract:
 * {@link #isAtLeast(Severity)} compares {@link Enum#ordinal()}, so inserting a constant between two
 * existing ones would change the answer for every threshold in the system.
 *
 * <h2>Suppressibility</h2>
 *
 * <p>{@link #isSuppressible()} is derived rather than declared: it is {@code true} for everything
 * except {@link #ERROR}, and {@code false} for {@link #ERROR}. This is the single place the rule
 * lives — {@link DiagnosticId#isSuppressible()}, {@link DiagnosticCode#isSuppressible()} and
 * {@link Diagnostic#isSuppressible()} all delegate here. Nothing outside those three delegations
 * calls {@link #isSuppressible()} on any of the four types that declare it, and nothing acts on
 * the answer; no driver reads it to filter, silence or threshold a diagnostic.
 *
 * <h2>Choosing one for a plugin code</h2>
 *
 * <p>A {@link PluginDiagnosticId} names its own severity, and the built-in catalogue is the
 * reference for what each level means in practice.
 *
 * <ul>
 *   <li>{@link #ERROR} is for a declaration that cannot be honoured at all, or for output that
 *       would be wrong if it were produced. {@code AW1043} — a handler that matched no injection
 *       point — is an error because weaving would silently do nothing.
 *   <li>{@link #WARNING} is for something that will be done, and that is more often a mistake than
 *       an intention. {@code AW1008} — a weave class that is not final — is a warning because the
 *       weave still applies.
 *   <li>{@link #INFO} is for a decision the framework made that the reader could not otherwise
 *       observe. {@code AW1094} — a {@code @Unique} member renamed around a collision — is an info
 *       because the new name appears in stack traces of the woven class.
 *   <li>{@link #DEBUG} is for detail that is not part of the normal narrative of a build.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // Report everything a build should not ignore, and drop the rest.
 * void onDiagnostic(Diagnostic diagnostic) {
 *     if (diagnostic.severity().isAtLeast(Severity.WARNING)) {
 *         System.err.println(diagnostic.format());
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Diagnostic#severity()
 * @see DiagnosticId#defaultSeverity()
 */
public enum Severity {

    /**
     * Detail that is not part of the normal narrative of a build.
     *
     * <p>The least serious level. Used as a threshold with {@link #isAtLeast(Severity)}, it admits
     * every severity, since every severity is at least as serious as {@link #DEBUG}; used as the
     * severity being tested, it is rejected by any threshold more serious than itself. Suppressible.
     */
    DEBUG,

    /**
     * Something the framework did that the reader would otherwise not learn about.
     *
     * <p>Nothing is wrong and nothing needs doing; the report exists because the decision has an
     * effect that shows up somewhere other than in the build log. Suppressible.
     */
    INFO,

    /**
     * Something that will be done, and that is more often a mistake than an intention.
     *
     * <p>The declaration is honoured. {@link #isSuppressible()} is {@code true}; nothing outside
     * {@link Diagnostic#isSuppressible()} reads it, and nothing acts on the answer.
     */
    WARNING,

    /**
     * A declaration that cannot be honoured, or output that would be wrong if produced.
     *
     * <p>The only level for which {@link #isSuppressible()} is {@code false}. Outside
     * {@link Diagnostic#isSuppressible()}, that method is not consulted by anything under
     * {@code src/main}; a build may still be configured to proceed past an error reported at this
     * level — for instance the Maven plugin's {@code aether.weaver.failOnError=false}, or the
     * weaving class loader's {@code ErrorPolicy.REPORT} — because the alternative to reporting it
     * is emitting a class that is wrong rather than a class that is missing something.
     */
    ERROR;

    /**
     * Reports whether this severity is at least as serious as the given one.
     *
     * <p>Compares {@link Enum#ordinal()}, so the comparison is exactly the declaration order
     * {@link #DEBUG}, {@link #INFO}, {@link #WARNING}, {@link #ERROR}. A severity is always at
     * least as serious as itself.
     *
     * @param other the severity to compare against; must not be {@code null}
     * @return {@code true} when this severity is the same as or more serious than {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Contract(pure = true)
    public boolean isAtLeast(@NotNull final Severity other) {
        return ordinal() >= java.util.Objects.requireNonNull(other, "other").ordinal();
    }

    /**
     * Reports whether a diagnostic at this severity may be silenced.
     *
     * <p>True for every constant except {@link #ERROR}. This is the definition the rest of the
     * package delegates to rather than repeats.
     *
     * @return {@code true} for {@link #DEBUG}, {@link #INFO} and {@link #WARNING}, {@code false}
     *         for {@link #ERROR}
     */
    @Contract(pure = true)
    public boolean isSuppressible() {
        return this != ERROR;
    }
}
