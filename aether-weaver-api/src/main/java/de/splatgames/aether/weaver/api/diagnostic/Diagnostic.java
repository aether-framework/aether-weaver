package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reported condition: a code, a severity, a message, a place, any number of supporting details
 * and an optional remedy.
 *
 * <p>This is the unit every part of Aether Weaver reports through. The annotation processor turns
 * one into a {@code javax.tools.Diagnostic} on the compiler's messager, the engine hands one to a
 * {@link de.splatgames.aether.weaver.api.spi.DiagnosticListener}, and the Maven plugin prints one.
 * A diagnostic is immutable, is created through {@link #builder(DiagnosticId)} or
 * {@link #of(DiagnosticId, String)}, and holds no reference to anything mutable.
 *
 * <h2>What the builder fills in</h2>
 *
 * <p>Only the code is required. Two of the remaining parts have defaults taken from the code
 * itself, and both are resolved once, when {@link Builder#build()} runs:
 *
 * <ul>
 *   <li><b>Severity</b> defaults to {@link DiagnosticId#defaultSeverity()}. A reporting site may
 *       raise or lower it for one report with {@link Builder#severity(Severity)}, and
 *       {@link #isSuppressible()} then follows the severity the diagnostic actually carries rather
 *       than the code's default.
 *   <li><b>Message</b> defaults to {@link DiagnosticId#summary()}. A summary describes the
 *       condition in general and names no class, member or position, so a diagnostic that keeps the
 *       default is markedly less useful than one that says what happened here; the fallback exists
 *       so that a report is never blank.
 *   <li><b>Location</b> defaults to {@link Location#UNKNOWN}, which {@link #format()} then omits.
 *   <li><b>Details</b> default to empty, and accumulate: every call to
 *       {@link Builder#detail(String)} appends.
 *   <li><b>Remedy</b> defaults to absent.
 * </ul>
 *
 * <h2>How {@link #format()} lays a diagnostic out</h2>
 *
 * <p>One line for the code, the position and the message; one indented line per detail; one final
 * line for the remedy. Lines are joined with {@link System#lineSeparator()}.
 *
 * <pre>{@code
 * AW1043 com.acme.Ledger.charge: com.acme.AuditWeave#onCharge matched 0 positions, and requires 1
 *     injection: onCharge
 *     point: INVOKE target=Gateway.send
 *   remedy: narrow it with an ordinal or a slice
 * }</pre>
 *
 * <p>The position is written only when the location is not {@link Location#UNKNOWN}; what gets
 * written is whatever {@link Location#format()} renders for it, which falls through to the target
 * or the weave class when there is no usable source line, and only reaches the literal
 * {@code <unknown location>} when there is no usable source line and neither a target class nor a
 * weave class is set. The decision to print a position at all is made by comparing against
 * {@link Location#UNKNOWN}, not by inspecting what {@link Location#format()} produced.
 *
 * <h2>Equality</h2>
 *
 * <p>Componentwise over all six parts, with the code compared using {@link Object#equals(Object)}
 * rather than {@code ==}. That matters for {@link PluginDiagnosticId}, which is a record: two
 * separately constructed plugin identities describing the same condition produce equal diagnostics.
 * Two reports that differ only in a detail, or only in the order of their details, are not equal.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * reporter.report(Diagnostic.builder(DiagnosticCode.TOO_MANY_INJECTION_POINTS)
 *         .message("onCharge matched 3 positions in charge(BigDecimal), and allows at most 1")
 *         .location(Location.builder()
 *                 .weave("com.acme.AuditWeave", "onCharge")
 *                 .target("com.acme.Ledger", "charge")
 *                 .build())
 *         .detail("injection: onCharge")
 *         .remedy("narrow it with an ordinal or a slice")
 *         .build());
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see DiagnosticCode
 * @see Location
 * @see WeaveException
 */
public final class Diagnostic {

    /** The condition being reported; never {@code null}. */
    private final DiagnosticId code;

    /** The severity of this report, which may differ from {@link #code}'s default. */
    private final Severity severity;

    /** What happened here, or {@link DiagnosticId#summary()} when the builder was given nothing. */
    private final String message;

    /** Where it happened; {@link Location#UNKNOWN} when the builder was given nothing. */
    private final Location location;

    /** Supporting lines, in the order they were added; immutable and possibly empty. */
    private final List<String> details;

    /** What to do about it, or {@code null} when the reporting site offered no advice. */
    private final @Nullable String remedy;

    /**
     * Copies the builder's state, resolving the severity and message defaults from the code.
     *
     * @param builder the builder to copy; must not be {@code null}
     */
    private Diagnostic(@NotNull final Builder builder) {
        this.code = builder.code;
        this.severity = builder.severity != null ? builder.severity : builder.code.defaultSeverity();
        this.message = builder.message != null ? builder.message : builder.code.summary();
        this.location = builder.location;
        this.details = List.copyOf(builder.details);
        this.remedy = builder.remedy;
    }

    /**
     * Creates a diagnostic with a code and a message and nothing else.
     *
     * <p>The severity is the code's default, the location is {@link Location#UNKNOWN}, there are no
     * details and there is no remedy. Equivalent to {@code builder(code).message(message).build()}.
     *
     * @param code    the condition being reported; must not be {@code null}
     * @param message what happened; must not be {@code null}
     * @return a new diagnostic
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static Diagnostic of(@NotNull final DiagnosticId code, @NotNull final String message) {
        return builder(code).message(message).build();
    }

    /**
     * Creates a builder for the given condition.
     *
     * <p>The code is the one part that has no default and cannot be changed afterwards.
     *
     * @param code the condition being reported; must not be {@code null}
     * @return a new builder
     * @throws NullPointerException if {@code code} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static Builder builder(@NotNull final DiagnosticId code) {
        return new Builder(code);
    }

    /**
     * Returns the condition being reported.
     *
     * <p>The same instance the builder was given. For a built-in condition this is a
     * {@link DiagnosticCode}, and comparing it with {@code ==} against a constant is safe; for a
     * contributed one it may be any {@link DiagnosticId}, so a general consumer should compare
     * {@link DiagnosticId#code()} or use {@link Object#equals(Object)}.
     *
     * @return the code
     */
    @NotNull
    public DiagnosticId code() {
        return this.code;
    }

    /**
     * Returns the severity of this report.
     *
     * <p>The code's {@link DiagnosticId#defaultSeverity()} unless the builder was told otherwise,
     * in which case it is what the builder was told.
     *
     * @return the severity
     */
    @NotNull
    public Severity severity() {
        return this.severity;
    }

    /**
     * Returns what happened.
     *
     * <p>The message the builder was given, or the code's {@link DiagnosticId#summary()} when it
     * was given none. {@link DiagnosticId#summary()} is documented never to be blank, and
     * {@link PluginDiagnosticId} rejects a blank one at construction, but a hand-written
     * {@link DiagnosticId} is not required to, so the fallback can still be blank for such an
     * implementation. An explicit message is only checked for {@code null} and so may be the empty
     * string regardless.
     *
     * @return the message
     */
    @NotNull
    public String message() {
        return this.message;
    }

    /**
     * Returns where it happened.
     *
     * <p>{@link Location#UNKNOWN} when the builder was given no location; never {@code null}.
     *
     * @return the location
     */
    @NotNull
    public Location location() {
        return this.location;
    }

    /**
     * Returns the supporting lines, in the order they were added.
     *
     * <p>These are the facts a reader needs to act on the message — the candidates that were
     * considered, the descriptors that did not match, the plan fingerprints that differ — and they
     * are rendered by {@link #format()} one per line, indented by four spaces.
     *
     * @return an unmodifiable list, possibly empty
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> details() {
        return this.details;
    }

    /**
     * Returns what to do about the condition.
     *
     * <p>Absent for a diagnostic whose reporting site offered no advice. Where present it is a
     * single line of prose, rendered by {@link #format()} after every detail.
     *
     * @return the remedy, or empty when none was given
     */
    @NotNull
    public Optional<String> remedy() {
        return Optional.ofNullable(this.remedy);
    }

    /**
     * Reports whether this diagnostic may be silenced.
     *
     * <p>Asks {@link #severity()}, which is the severity of this report rather than the code's
     * default. A site that raised a warning to {@link Severity#ERROR} therefore produced a
     * diagnostic that cannot be suppressed, even though {@link DiagnosticId#isSuppressible()} on
     * its code says otherwise.
     *
     * @return {@code true} unless {@link #severity()} is {@link Severity#ERROR}
     */
    @Contract(pure = true)
    public boolean isSuppressible() {
        return this.severity.isSuppressible();
    }

    /**
     * Renders the diagnostic as the multi-line block a build log shows.
     *
     * <p>The code, then the position and a colon when the location is not {@link Location#UNKNOWN},
     * then the message. Each detail follows on its own line indented by four spaces, and a remedy
     * closes the block on a line beginning {@code "  remedy: "}. Lines are joined with
     * {@link System#lineSeparator()}, so the result is platform-dependent in exactly that respect
     * and in no other. The severity does not appear; a caller that wants it must add it.
     *
     * @return the rendered diagnostic, one line plus one per detail plus one for the remedy
     */
    @Contract(pure = true)
    @NotNull
    public String format() {
        final StringBuilder sb = new StringBuilder(128);
        sb.append(this.code.code());
        if (this.location.hasSourcePosition() || !this.location.equals(Location.UNKNOWN)) {
            sb.append(' ').append(this.location.format()).append(':');
        }
        sb.append(' ').append(this.message);
        for (final String detail : this.details) {
            sb.append(System.lineSeparator()).append("    ").append(detail);
        }
        if (this.remedy != null) {
            sb.append(System.lineSeparator()).append("  remedy: ").append(this.remedy);
        }
        return sb.toString();
    }

    /**
     * Compares code, severity, message, location, details and remedy.
     *
     * <p>The code is compared with {@link Object#equals(Object)} rather than with {@code ==}, so a
     * {@link PluginDiagnosticId} constructed twice with the same components compares equal. Details
     * are compared as a list, so their order is significant.
     *
     * @param o the object to compare against
     * @return {@code true} when {@code o} is a diagnostic with all six parts equal
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Diagnostic other)) {
            return false;
        }
        return this.code.equals(other.code)
                && this.severity == other.severity
                && this.message.equals(other.message)
                && this.location.equals(other.location)
                && this.details.equals(other.details)
                && Objects.equals(this.remedy, other.remedy);
    }

    /**
     * Hashes all six parts, consistently with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.code, this.severity, this.message,
                this.location, this.details, this.remedy);
    }

    /**
     * Returns a single-line summary: the code, the severity and the message.
     *
     * <p>Deliberately shorter than {@link #format()} — no position, no details and no remedy — so
     * that a diagnostic interpolated into an exception message or a log line stays on one line.
     * {@link #format()} is what a build log should print.
     *
     * @return the code, the severity and the message, separated by single spaces
     */
    @Override
    public String toString() {
        return this.code.code() + ' ' + this.severity + ' ' + this.message;
    }

    /**
     * Collects the parts of a {@link Diagnostic} before it is built.
     *
     * <p>Obtained from {@link Diagnostic#builder(DiagnosticId)}. Every method returns this builder,
     * so calls chain, and every argument is rejected when {@code null} rather than being stored as
     * an absent value — omitting a part means not calling its method. The code is fixed at
     * construction and has no setter.
     *
     * <p>Setting a part twice replaces it, except for details, which accumulate:
     * {@link #detail(String)}, {@link #details(Collection)} and {@link #details(String...)} all
     * append to the same list in call order. A builder is not thread-safe; {@link #build()} may be
     * called more than once and returns an independent, equal diagnostic each time.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Builder {

        /** The condition being reported; fixed at construction. */
        private final DiagnosticId code;

        /** Supporting lines, in call order. */
        private final List<String> details = new ArrayList<>();

        /** The severity override, or {@code null} to take the code's default. */
        private @Nullable Severity severity;

        /** The message, or {@code null} to take the code's summary. */
        private @Nullable String message;

        /** Where the condition arose; {@link Location#UNKNOWN} until set. */
        private Location location = Location.UNKNOWN;

        /** What to do about it, or {@code null} when there is no advice to give. */
        private @Nullable String remedy;

        /**
         * Creates a builder for the given condition.
         *
         * @param code the condition being reported; must not be {@code null}
         * @throws NullPointerException if {@code code} is {@code null}
         */
        private Builder(@NotNull final DiagnosticId code) {
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Overrides the severity for this one report.
         *
         * <p>The code's {@link DiagnosticId#defaultSeverity()} is used when this is not called. An
         * override changes what {@link Diagnostic#isSuppressible()} answers, so raising a warning
         * to {@link Severity#ERROR} here makes that particular report unsuppressible.
         *
         * @param severity the severity to report at; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code severity} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder severity(@NotNull final Severity severity) {
            this.severity = Objects.requireNonNull(severity, "severity");
            return this;
        }

        /**
         * Sets what happened, in this particular case.
         *
         * <p>Without it the diagnostic falls back to {@link DiagnosticId#summary()}, which
         * describes the condition in general and names nothing specific.
         *
         * @param message the message; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code message} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder message(@NotNull final String message) {
            this.message = Objects.requireNonNull(message, "message");
            return this;
        }

        /**
         * Sets where the condition arose.
         *
         * <p>Without it the location is {@link Location#UNKNOWN} and {@link Diagnostic#format()}
         * writes no position at all.
         *
         * @param location the location; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code location} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder location(@NotNull final Location location) {
            this.location = Objects.requireNonNull(location, "location");
            return this;
        }

        /**
         * Appends one supporting line.
         *
         * <p>Details accumulate rather than replace, and {@link Diagnostic#format()} renders them
         * in the order they were added.
         *
         * @param detail the line to append; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code detail} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder detail(@NotNull final String detail) {
            this.details.add(Objects.requireNonNull(detail, "detail"));
            return this;
        }

        /**
         * Appends every line of a collection, in iteration order.
         *
         * <p>Each element goes through {@link #detail(String)}, so a {@code null} element is
         * rejected — and the elements before it have already been appended, since the check happens
         * per element rather than up front.
         *
         * @param details the lines to append; must not be {@code null} and must contain no
         *                {@code null}
         * @return this builder
         * @throws NullPointerException if {@code details} is {@code null} or contains {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder details(@NotNull final Collection<String> details) {
            for (final String detail : Objects.requireNonNull(details, "details")) {
                detail(detail);
            }
            return this;
        }

        /**
         * Appends every line given, in argument order.
         *
         * <p>Passing no arguments appends nothing and is not an error. Passing a {@code null} array
         * is, and is not the same as passing none.
         *
         * @param details the lines to append; must not be {@code null} and must contain no
         *                {@code null}
         * @return this builder
         * @throws NullPointerException if {@code details} is {@code null} or contains {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder details(@NotNull final String... details) {
            return details(Arrays.asList(Objects.requireNonNull(details, "details")));
        }

        /**
         * Sets what to do about the condition.
         *
         * <p>One line of prose addressed to whoever has to fix it, rendered last by
         * {@link Diagnostic#format()}. Without it the diagnostic simply carries no advice.
         *
         * @param remedy the remedy; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code remedy} is {@code null}
         */
        @Contract("_ -> this")
        @NotNull
        public Builder remedy(@NotNull final String remedy) {
            this.remedy = Objects.requireNonNull(remedy, "remedy");
            return this;
        }

        /**
         * Creates the diagnostic, resolving the severity and message defaults from the code.
         *
         * <p>The details are copied, so a later call to {@link #detail(String)} does not change a
         * diagnostic that has already been built.
         *
         * @return a new diagnostic
         */
        @Contract(value = " -> new", pure = true)
        @NotNull
        public Diagnostic build() {
            return new Diagnostic(this);
        }
    }
}
