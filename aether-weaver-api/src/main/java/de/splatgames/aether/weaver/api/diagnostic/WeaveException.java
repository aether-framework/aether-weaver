package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Thrown when weaving is abandoned, carrying the diagnostics that explain why.
 *
 * <p>Most conditions are reported rather than thrown: a
 * {@link de.splatgames.aether.weaver.api.spi.DiagnosticListener} receives them, the driver decides
 * what to do, and the class is either woven or left alone. This exception is for the cases where
 * continuing would produce a class that must not exist — the verifier refusing woven bytes under a
 * fatal verification policy is the one the engine itself raises, reporting {@code AW4001} for bytes
 * the JVM's verifier rejects and {@code AW4004} for bytes that are structurally malformed before
 * the verifier is even reached.
 *
 * <p>It extends {@link RuntimeException} because it is thrown from inside class transformation,
 * where the call stack belongs to the JVM or to a build plugin and cannot be widened with a checked
 * type. Drivers catch it: the Maven plugin turns it into {@code AW4090} against the class that
 * could not be woven, and the weaving class loader turns it into a {@link ClassNotFoundException}
 * when its configured error policy is {@code ErrorPolicy.FAIL} (the {@code aether.weaver.onError=fail}
 * setting).
 *
 * <h2>Serialisation</h2>
 *
 * <p>The diagnostics are held in a {@code transient} field, and the class declares no
 * {@code readObject}, {@code readObjectNoData} or {@code readResolve} and no field initialiser for
 * it. Default deserialisation therefore restores the field to {@code null} rather than to an empty
 * list, and reads the message and the cause normally. A consumer that needs the detail across a
 * process boundary should serialise {@link #report()} instead of the exception. The class declares
 * a {@code serialVersionUID} of {@code 1}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * try {
 *     byte[] woven = weaver.weave(internalName, original);
 * } catch (WeaveException refused) {
 *     if (refused.hasCode(DiagnosticCode.VERIFICATION_FAILED)) {
 *         log.error(refused.report());     // message, then every diagnostic in full
 *     }
 *     throw refused;
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Diagnostic
 * @see DiagnosticCode
 */
public final class WeaveException extends RuntimeException {

    /** Identifies the serialised form; the diagnostics are not part of it. */
    private static final long serialVersionUID = 1L;

    /**
     * The diagnostics that explain the refusal.
     *
     * <p>{@code transient} because a {@link Diagnostic} is not serialisable. The class defines no
     * {@code readObject} and no field initialiser, so default deserialisation restores this field
     * to {@code null} rather than to an empty list.
     */
    private final transient List<Diagnostic> diagnostics;

    /**
     * Creates an exception with no cause.
     *
     * <p>The diagnostics are copied, so a later change to the list passed in does not affect the
     * exception.
     *
     * @param message     a one-line summary of why weaving was abandoned; must not be {@code null}
     * @param diagnostics the diagnostics that explain it, possibly empty; must not be {@code null}
     *                    and must contain no {@code null}
     * @throws NullPointerException if either argument is {@code null}, or if {@code diagnostics}
     *                              contains {@code null}
     */
    public WeaveException(@NotNull final String message, @NotNull final List<Diagnostic> diagnostics) {
        this(message, diagnostics, null);
    }

    /**
     * Creates an exception with a cause.
     *
     * <p>The diagnostics are copied, so a later change to the list passed in does not affect the
     * exception.
     *
     * @param message     a one-line summary of why weaving was abandoned; must not be {@code null}
     * @param diagnostics the diagnostics that explain it, possibly empty; must not be {@code null}
     *                    and must contain no {@code null}
     * @param cause       the failure this one arose from, or {@code null} when there is none
     * @throws NullPointerException if {@code message} or {@code diagnostics} is {@code null}, or if
     *                              {@code diagnostics} contains {@code null}
     */
    public WeaveException(@NotNull final String message,
                          @NotNull final List<Diagnostic> diagnostics,
                          final @Nullable Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    /**
     * Returns every diagnostic that was collected, at every severity, in the order given.
     *
     * <p>The backing field is {@code transient} and restored to {@code null} by default
     * deserialisation. The {@link NotNull} annotation on this method describes the contract for an
     * instance built through a constructor; it does not hold for an instance restored from a
     * stream, for which this method returns {@code null} instead of the unmodifiable list.
     *
     * @return an unmodifiable list, possibly empty
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<Diagnostic> diagnostics() {
        return this.diagnostics;
    }

    /**
     * Returns only the diagnostics reported at {@link Severity#ERROR}.
     *
     * <p>Filters on {@link Diagnostic#severity()}, which is the severity of the report rather than
     * the code's default, so a warning that one site chose to raise appears here and an error that
     * a site chose to lower does not. A new list is built on every call. For an instance restored
     * from a stream the backing field is {@code null} rather than empty, and this method throws
     * {@link NullPointerException} instead of returning a list.
     *
     * @return an unmodifiable list of the error-severity diagnostics, possibly empty
     * @throws NullPointerException if this instance was restored from a stream
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<Diagnostic> errors() {
        return this.diagnostics.stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .toList();
    }

    /**
     * Reports whether any collected diagnostic carries the given built-in code.
     *
     * <p>Compares with {@code ==}, which is exact for an enum constant and is why the parameter is
     * {@link DiagnosticCode} rather than {@link DiagnosticId}: a plugin's condition cannot be asked
     * for here, and a caller who needs one should search {@link #diagnostics()} on
     * {@link DiagnosticId#code()}. For an instance restored from a stream the backing field is
     * {@code null} rather than empty, and this method throws {@link NullPointerException} for that
     * reason as well as for a {@code null} argument.
     *
     * @param code the code to look for; must not be {@code null}
     * @return {@code true} when at least one diagnostic was reported under that code
     * @throws NullPointerException if {@code code} is {@code null}, or if this instance was restored
     *                              from a stream
     */
    @Contract(pure = true)
    public boolean hasCode(@NotNull final DiagnosticCode code) {
        Objects.requireNonNull(code, "code");
        return this.diagnostics.stream().anyMatch(d -> d.code() == code);
    }

    /**
     * Renders the message followed by every diagnostic in full.
     *
     * <p>The message alone when there are no diagnostics. Otherwise the message, a blank line, and
     * then each diagnostic's {@link Diagnostic#format()} separated by blank lines. Lines are joined
     * with {@link System#lineSeparator()}. For an instance restored from a stream the backing field
     * is {@code null} rather than empty, and this method throws {@link NullPointerException} at
     * {@code this.diagnostics.isEmpty()} instead of returning the message.
     *
     * @return the full report
     * @throws NullPointerException if this instance was restored from a stream
     */
    @Contract(pure = true)
    @NotNull
    public String report() {
        if (this.diagnostics.isEmpty()) {
            return getMessage();
        }
        return getMessage() + System.lineSeparator() + System.lineSeparator()
                + this.diagnostics.stream()
                        .map(Diagnostic::format)
                        .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
    }
}
