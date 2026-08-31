package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticId;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import javax.annotation.processing.Messager;
import java.util.Objects;

/**
 * Turns this project's diagnostics into messages a compiler prints.
 *
 * <p>Every check in the annotation processor reports through one of these, so this is where an
 * {@code AW####} code, its details and its remedy become the block of text a user reads in a build
 * log or an IDE's problem view. Two translations happen here and nowhere else: a
 * {@link Severity} becomes a {@link javax.tools.Diagnostic.Kind}, which is what decides whether the
 * compilation still succeeds, and the diagnostic's parts become one string.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Not safe to share. The error count is an ordinary {@code int} field, updated on every report
 * without synchronisation; one reporter belongs to one processor instance, which the host compiler
 * drives from a single thread.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class MessagerReporter {

    /** The compiler's message sink, supplied by the processing environment. */
    private final Messager messager;

    /** How many reported diagnostics carried {@link Severity#ERROR}. */
    private int errors;

    /**
     * Wraps a messager obtained from the processing environment.
     *
     * @param messager the messager to print through; must not be {@code null}
     * @throws NullPointerException if {@code messager} is {@code null}
     */
    public MessagerReporter(@NotNull final Messager messager) {
        this.messager = Objects.requireNonNull(messager, "messager");
    }

    /**
     * Prints one diagnostic at a place in the source.
     *
     * <p>An {@link Severity#ERROR} raises the count reported by {@link #errors()} and reaches the
     * compiler as {@link javax.tools.Diagnostic.Kind#ERROR}, which fails the compilation; every
     * other severity leaves it succeeding under a default {@code javac} invocation. A
     * {@link Severity#WARNING} is routed to {@code javac}'s {@code Kind.WARNING}, so a build run
     * with {@code -Werror} fails on it too. Nothing is deduplicated: a check invoked once per
     * target prints once per target, and keeping a weave-level fact to one message is the caller's
     * job rather than this one's.
     *
     * @param diagnostic the diagnostic to print; must not be {@code null}
     * @param anchor     where to print it; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void report(@NotNull final Diagnostic diagnostic, @NotNull final Anchor anchor) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(anchor, "anchor");
        if (diagnostic.severity() == Severity.ERROR) {
            this.errors++;
        }
        anchor.print(this.messager, kindOf(diagnostic.severity()), render(diagnostic));
    }

    /**
     * Prints one diagnostic with no position at all.
     *
     * <p>For a condition that belongs to the compilation rather than to any declaration in it, such
     * as the manifest failing to be written. The message appears in the build log without a file,
     * a line or a caret, so the text has to identify itself.
     *
     * <p>This is the overload a
     * {@link de.splatgames.aether.weaver.api.spi.Reporter} method reference binds to, that
     * interface declaring exactly one abstract method taking a diagnostic alone.
     *
     * @param diagnostic the diagnostic to print; must not be {@code null}
     * @throws NullPointerException if {@code diagnostic} is {@code null}
     */
    public void report(@NotNull final Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (diagnostic.severity() == Severity.ERROR) {
            this.errors++;
        }
        this.messager.printMessage(kindOf(diagnostic.severity()), render(diagnostic));
    }

    /**
     * Reports how many diagnostics of severity {@link Severity#ERROR} have been printed.
     *
     * <p>Counts what this reporter was given, which is not the same as what failed the build: a
     * compilation also fails on errors the compiler itself raises, and on errors reported through
     * a different reporter.
     *
     * @return the number of errors printed so far, across both {@code report} overloads
     */
    @Contract(pure = true)
    public int errors() {
        return this.errors;
    }

    /**
     * Renders a diagnostic as the block of text a build log shows.
     *
     * <p>The code and the message on the first line, then each detail on its own line indented by
     * four spaces, then the remedy on a line beginning {@code "  remedy: "} when there is one.
     * Lines are joined with {@link System#lineSeparator()}. The severity does not appear, because
     * the compiler prefixes its own word for the kind.
     *
     * <p>{@link Diagnostic#location()} is not rendered, which is the one way this differs from
     * {@link Diagnostic#format()}. Position at compile time comes from the {@link Anchor} the
     * message is printed against; no check in this package sets a location on the diagnostic
     * itself.
     *
     * @param diagnostic the diagnostic to render; must not be {@code null}
     * @return one line, plus one per detail, plus one for the remedy
     * @throws NullPointerException if {@code diagnostic} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String render(@NotNull final Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        final DiagnosticId id = diagnostic.code();
        final StringBuilder text = new StringBuilder(128)
                .append(id.code()).append(' ').append(diagnostic.message());
        for (final String detail : diagnostic.details()) {
            text.append(System.lineSeparator()).append("    ").append(detail);
        }
        final String remedy = diagnostic.remedy().orElse(null);
        if (remedy != null) {
            text.append(System.lineSeparator()).append("  remedy: ").append(remedy);
        }
        return text.toString();
    }

    /**
     * Maps a severity onto the kind the compiler understands.
     *
     * <p>{@link Severity#ERROR} is the only kind that fails a compilation on a default invocation.
     * {@link Severity#WARNING} maps to {@link javax.tools.Diagnostic.Kind#WARNING}, which under
     * {@code -Werror} fails the build as well. {@link Severity#DEBUG} maps to
     * {@link javax.tools.Diagnostic.Kind#OTHER}, which {@code javac} prints exactly as it prints a
     * note, so debug and informational output are indistinguishable in a {@code javac} build log.
     *
     * @param severity the severity to map; must not be {@code null}
     * @return the corresponding compiler diagnostic kind
     * @throws NullPointerException if {@code severity} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static javax.tools.Diagnostic.Kind kindOf(@NotNull final Severity severity) {
        return switch (Objects.requireNonNull(severity, "severity")) {
            case ERROR -> javax.tools.Diagnostic.Kind.ERROR;
            case WARNING -> javax.tools.Diagnostic.Kind.WARNING;
            case INFO -> javax.tools.Diagnostic.Kind.NOTE;
            case DEBUG -> javax.tools.Diagnostic.Kind.OTHER;
        };
    }

    /**
     * Describes the reporter by its error count.
     *
     * <p>The wrapped {@link Messager} does not appear.
     *
     * @return a string of the form {@code MessagerReporter[errors=0]}
     */
    @Override
    @NotNull
    public String toString() {
        return "MessagerReporter[errors=" + this.errors + ']';
    }
}
