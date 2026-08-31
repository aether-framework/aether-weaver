package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a {@link Diagnostic} happened, in as much detail as the reporting site could supply.
 *
 * <p>A location carries up to three independent descriptions of one place, and any of them may be
 * absent:
 *
 * <ul>
 *   <li>a <b>source position</b> — a file, a line and a column — which is what an editor can jump
 *       to;
 *   <li>a <b>weave position</b> — a weave class and optionally the handler within it — which is
 *       what identifies the declaration that is at fault;
 *   <li>a <b>target position</b> — a target class and optionally the method within it — which is
 *       what identifies the code being modified.
 * </ul>
 *
 * <p>All three may be set at once, and {@link #format()} then picks one to render. Nothing is
 * validated: a file name is not checked for existence, a class name is not checked for shape, and a
 * line or column is not checked for sign. This is a carrier for text a reporting site already has.
 *
 * <h2>Which description {@link #format()} renders</h2>
 *
 * <p>Exactly one, chosen by a fixed precedence rather than by combining them. The order is source
 * position, then target, then weave, and it exists because a reader who has a file and a line does
 * not need a class name to find the place.
 *
 * <ol>
 *   <li>{@code file:line:column} when {@link #hasSourcePosition()} and the column is positive.
 *   <li>{@code file:line} when {@link #hasSourcePosition()} and the column is not positive.
 *   <li>{@code TargetClass.targetMethod}, or {@code TargetClass} alone, when a target class is set.
 *   <li>{@code WeaveClass#handler}, or {@code WeaveClass} alone, when a weave class is set.
 *   <li>{@code <unknown location>} when none of the above applies.
 * </ol>
 *
 * <p>A file set together with a line of zero or less does not count as a source position, so such a
 * location falls through to the target or the weave — or renders as {@code <unknown location>}
 * while still not being equal to {@link #UNKNOWN}, which is the one combination that reads oddly in
 * {@link Diagnostic#format()}: the diagnostic prints {@code <unknown location>:} rather than
 * omitting the position, because it decides whether to print by comparing against {@link #UNKNOWN}
 * rather than by looking at what {@link #format()} produced.
 *
 * <h2>Immutability and equality</h2>
 *
 * <p>Instances are immutable and are created through {@link #builder()}. Equality is componentwise
 * over all seven fields, so a location that names the same class through a different combination of
 * fields is a different location.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Location where = Location.builder()
 *         .source("src/main/java/com/acme/AuditWeave.java", 42, 9)
 *         .weave("com.acme.AuditWeave", "onCharge")
 *         .target("com.acme.Ledger", "charge")
 *         .build();
 *
 * where.format();   // "src/main/java/com/acme/AuditWeave.java:42:9" — the source position wins
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Diagnostic#location()
 */
public final class Location {

    /**
     * The location of a diagnostic that names no place at all.
     *
     * <p>Every field is unset: no file, no weave class, no target class, and a line and column of
     * {@code -1}. It is the value {@link Diagnostic.Builder} starts from, and
     * {@link Diagnostic#format()} treats it specially — a diagnostic whose location equals this one
     * and has no source position prints no position at all.
     */
    public static final Location UNKNOWN = new Builder().build();

    /** The source file, or {@code null} when the reporting site had none. */
    private final @Nullable String file;

    /** The 1-based line within {@link #file}; {@code -1} when unset. */
    private final int line;

    /** The 1-based column within {@link #line}; {@code -1} when unset. */
    private final int column;

    /** The binary name of the weave class that declared what is at fault, or {@code null}. */
    private final @Nullable String weaveClass;

    /** The handler within {@link #weaveClass}, or {@code null} when the fault is class-wide. */
    private final @Nullable String handler;

    /** The binary name of the class being modified, or {@code null}. */
    private final @Nullable String targetClass;

    /** The method within {@link #targetClass}, or {@code null} when the fault is class-wide. */
    private final @Nullable String targetMethod;

    /**
     * Copies the builder's state into the finished location.
     *
     * @param builder the builder to copy; must not be {@code null}
     */
    private Location(@NotNull final Builder builder) {
        this.file = builder.file;
        this.line = builder.line;
        this.column = builder.column;
        this.weaveClass = builder.weaveClass;
        this.handler = builder.handler;
        this.targetClass = builder.targetClass;
        this.targetMethod = builder.targetMethod;
    }

    /**
     * Creates a builder with every part unset.
     *
     * <p>Building without calling anything else yields a location equal to {@link #UNKNOWN}.
     *
     * @return a new builder
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the source file, if one was given.
     *
     * <p>Whatever the reporting site passed to {@link Builder#source(String, int, int)}. Nothing
     * here interprets or resolves it.
     *
     * @return the file, or empty when no source position was set
     */
    @NotNull
    public Optional<String> file() {
        return Optional.ofNullable(this.file);
    }

    /**
     * Returns the line within {@link #file()}.
     *
     * @return the 1-based line, or {@code -1} when no source position was set
     */
    public int line() {
        return this.line;
    }

    /**
     * Returns the column within {@link #line()}.
     *
     * <p>A column of zero or less is not rendered by {@link #format()} even when the file and line
     * are, which is how a reporting site that knows the line but not the column says so.
     *
     * @return the 1-based column, or {@code -1} when no source position was set
     */
    public int column() {
        return this.column;
    }

    /**
     * Returns the weave class the faulty declaration belongs to.
     *
     * @return the weave class's binary name, or empty when none was set
     */
    @NotNull
    public Optional<String> weaveClass() {
        return Optional.ofNullable(this.weaveClass);
    }

    /**
     * Returns the handler within {@link #weaveClass()}.
     *
     * <p>Empty both when no weave class was set and when one was set without a handler, so a caller
     * that needs to tell those apart must ask {@link #weaveClass()} as well.
     *
     * @return the handler's name, or empty when none was set
     */
    @NotNull
    public Optional<String> handler() {
        return Optional.ofNullable(this.handler);
    }

    /**
     * Returns the class being modified.
     *
     * @return the target class's binary name, or empty when none was set
     */
    @NotNull
    public Optional<String> targetClass() {
        return Optional.ofNullable(this.targetClass);
    }

    /**
     * Returns the method within {@link #targetClass()}.
     *
     * <p>Empty both when no target class was set and when one was set without a method.
     *
     * @return the target method's name, or empty when none was set
     */
    @NotNull
    public Optional<String> targetMethod() {
        return Optional.ofNullable(this.targetMethod);
    }

    /**
     * Reports whether this location names a place in a source file.
     *
     * <p>True only when a file is set <em>and</em> the line is positive. A file with a line of zero
     * or less is not a source position, and {@link #format()} then falls through to the target or
     * the weave.
     *
     * @return {@code true} when a file and a positive line are both present
     */
    @Contract(pure = true)
    public boolean hasSourcePosition() {
        return this.file != null && this.line > 0;
    }

    /**
     * Renders the most specific description this location carries.
     *
     * <p>Source position first, then target, then weave, and failing all three the literal text
     * {@code <unknown location>}. The full precedence, including which separators are used, is
     * given in the class description.
     *
     * @return the rendered position, never empty
     */
    @Contract(pure = true)
    @NotNull
    public String format() {
        if (hasSourcePosition()) {
            return this.column > 0
                    ? this.file + ':' + this.line + ':' + this.column
                    : this.file + ':' + this.line;
        }
        if (this.targetClass != null) {
            return this.targetMethod != null
                    ? this.targetClass + '.' + this.targetMethod
                    : this.targetClass;
        }
        if (this.weaveClass != null) {
            return this.handler != null
                    ? this.weaveClass + '#' + this.handler
                    : this.weaveClass;
        }
        return "<unknown location>";
    }

    /**
     * Compares all seven parts.
     *
     * <p>Two locations that {@link #format()} renders identically are not necessarily equal: a
     * source position hides the weave and target parts, which are still compared here.
     *
     * @param o the object to compare against
     * @return {@code true} when {@code o} is a location with the same file, line, column, weave
     *         class, handler, target class and target method
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Location other)) {
            return false;
        }
        return this.line == other.line
                && this.column == other.column
                && Objects.equals(this.file, other.file)
                && Objects.equals(this.weaveClass, other.weaveClass)
                && Objects.equals(this.handler, other.handler)
                && Objects.equals(this.targetClass, other.targetClass)
                && Objects.equals(this.targetMethod, other.targetMethod);
    }

    /**
     * Hashes all seven parts, consistently with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.file, this.line, this.column,
                this.weaveClass, this.handler, this.targetClass, this.targetMethod);
    }

    /**
     * Returns {@link #format()}, so a location interpolated into a message reads as a position.
     *
     * <p>This is lossy in the same way {@link #format()} is: the parts the precedence did not pick
     * do not appear.
     *
     * @return the rendered position
     */
    @Override
    public String toString() {
        return format();
    }

    /**
     * Collects the parts of a {@link Location} before it is built.
     *
     * <p>Obtained from {@link Location#builder()}. Each method returns this builder, so calls
     * chain, and each may be called more than once — the last call for a given part wins. A builder
     * is not thread-safe and is not meant to be shared; {@link #build()} may be called more than
     * once and returns an independent, equal location each time.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Builder {

        /** The source file, or {@code null} while unset. */
        private @Nullable String file;

        /** The line within {@link #file}, {@code -1} while unset. */
        private int line = -1;

        /** The column within {@link #line}, {@code -1} while unset. */
        private int column = -1;

        /** The weave class, or {@code null} while unset. */
        private @Nullable String weaveClass;

        /** The handler within {@link #weaveClass}, or {@code null}. */
        private @Nullable String handler;

        /** The target class, or {@code null} while unset. */
        private @Nullable String targetClass;

        /** The method within {@link #targetClass}, or {@code null}. */
        private @Nullable String targetMethod;

        /** Creates a builder with every part unset. */
        private Builder() {
        }

        /**
         * Sets the source position.
         *
         * <p>Neither number is validated. A line of zero or less makes
         * {@link Location#hasSourcePosition()} false however the file reads, and a column of zero
         * or less is simply not rendered.
         *
         * @param file   the source file; must not be {@code null}
         * @param line   the 1-based line, or a non-positive value when it is not known
         * @param column the 1-based column, or a non-positive value when it is not known
         * @return this builder
         * @throws NullPointerException if {@code file} is {@code null}
         */
        @Contract("_, _, _ -> this")
        @NotNull
        public Builder source(@NotNull final String file, final int line, final int column) {
            this.file = Objects.requireNonNull(file, "file");
            this.line = line;
            this.column = column;
            return this;
        }

        /**
         * Names the weave class, and optionally the handler within it, that the diagnostic is
         * about.
         *
         * @param weaveClass the weave class's binary name; must not be {@code null}
         * @param handler    the handler's name, or {@code null} when the diagnostic is about the
         *                   class as a whole
         * @return this builder
         * @throws NullPointerException if {@code weaveClass} is {@code null}
         */
        @Contract("_, _ -> this")
        @NotNull
        public Builder weave(@NotNull final String weaveClass, final @Nullable String handler) {
            this.weaveClass = Objects.requireNonNull(weaveClass, "weaveClass");
            this.handler = handler;
            return this;
        }

        /**
         * Names the target class, and optionally the method within it, that the diagnostic is
         * about.
         *
         * <p>A target set here outranks a weave set through {@link #weave(String, String)} when
         * {@link Location#format()} chooses what to render.
         *
         * @param targetClass  the target class's binary name; must not be {@code null}
         * @param targetMethod the method's name, or {@code null} when the diagnostic is about the
         *                     class as a whole
         * @return this builder
         * @throws NullPointerException if {@code targetClass} is {@code null}
         */
        @Contract("_, _ -> this")
        @NotNull
        public Builder target(@NotNull final String targetClass, final @Nullable String targetMethod) {
            this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
            this.targetMethod = targetMethod;
            return this;
        }

        /**
         * Creates the location from the parts collected so far.
         *
         * <p>The builder stays usable afterwards, and a further call returns a separate instance.
         *
         * @return a new location
         */
        @Contract(value = " -> new", pure = true)
        @NotNull
        public Location build() {
            return new Location(this);
        }
    }
}
