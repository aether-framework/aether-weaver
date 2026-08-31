package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;

import java.io.Serial;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports a selector that does not parse, with the position it failed at and the code the build will show.
 *
 * <p>Thrown by {@link MemberSelector#parse(String)} and {@link MemberSelector#parse(String, MemberKind)}. The
 * annotation processor and the engine's weave parser each catch it and turn it into a diagnostic carrying this
 * exception's own {@link #code()}, {@link #getMessage()} and {@link #suggestion()}, so a selector is refused at
 * compile time and at weave time for the same reason and under the same number.
 *
 * <p>An {@link IllegalArgumentException}, because a malformed selector is a malformed argument. A {@code null}
 * argument is not: {@code parse(null)} throws {@link NullPointerException}.
 *
 * <h2>The codes</h2>
 *
 * <ul>
 *   <li>{@code AW1015} -- the source grammar was violated. An empty selector, an invalid member or owner name, an
 *       unbalanced {@code <}, a missing type name, a missing expected character, text left over after the selector
 *       ended, or a constant literal its keyword rejects.
 *   <li>{@code AW1017} -- the text is a JVM descriptor written without the {@value MemberSelector#DESCRIPTOR_PREFIX}
 *       prefix. Reported only after the source parse has already failed, and the only code that carries a
 *       {@link #suggestion()}.
 *   <li>{@code AW1018} -- the text after {@value MemberSelector#DESCRIPTOR_PREFIX} is not a well-formed
 *       descriptor: a wildcard, a missing member name, a field selector with no type, an internal name that is not
 *       a class, or a descriptor the JDK refuses.
 *   <li>{@code AW1019} -- a {@value MemberSelector#DESCRIPTOR_PREFIX} method selector stops at its closing
 *       parenthesis and names no return type.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * try {
 *     MemberSelector.parse("charge(BigDecimal");
 * } catch (SelectorSyntaxException e) {
 *     System.out.println(e.code().code());     // AW1015
 *     System.out.println(e.formatWithCaret());
 *     // charge(BigDecimal
 *     //                  ^ expected ')' but the selector ended
 * }
 * }</pre>
 *
 * <p>The selector, the code and the suggestion are {@code transient}, so an instance that has been serialised and
 * read back carries its message and its offset alone.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector#parse(String)
 * @see DiagnosticCode
 */
public final class SelectorSyntaxException extends IllegalArgumentException {

    /** Fixes the serialised form against changes to the field layout. */
    @Serial
    private static final long serialVersionUID = -7822735800538382379L;

    /** The text parsing stopped in, which the {@link #offset} indexes. */
    private final transient String selector;

    /** The index into {@link #selector} at which parsing stopped. */
    private final int offset;

    /** The diagnostic code a build reports this failure under. */
    private final transient DiagnosticCode code;

    /** A corrected spelling, or {@code null} when none can be offered. */
    private final transient String suggestion;

    /**
     * Builds a syntax failure.
     *
     * <p>The offset is not range-checked: it indexes {@code selector}, and a parse that stopped at the end of the
     * text records the length. {@link #formatWithCaret()} clamps rather than failing.
     *
     * @param code       the code a build reports this failure under
     * @param selector   the text parsing stopped in, which is the argument trimmed and, for most source-form
     *                   failures, with the {@value MemberSelector#SOURCE_PREFIX} prefix removed; {@code AW1017} is
     *                   built from the trimmed argument instead and keeps the prefix when one was written
     * @param offset     the index into {@code selector} at which parsing stopped
     * @param message    what went wrong, as it will appear in the build output
     * @param suggestion a corrected spelling of the whole selector, or {@code null} when none can be offered
     * @throws NullPointerException if {@code code}, {@code selector} or {@code message} is {@code null}
     */
    public SelectorSyntaxException(@NotNull final DiagnosticCode code,
                                   @NotNull final String selector,
                                   final int offset,
                                   @NotNull final String message,
                                   @Nullable final String suggestion) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.offset = offset;
        this.suggestion = suggestion;
    }

    /**
     * Returns the code a build reports this failure under.
     *
     * <p>One of {@code AW1015}, {@code AW1017}, {@code AW1018} and {@code AW1019}. The annotation processor and
     * the engine's weave parser report this code rather than choosing one of their own, so the number in a
     * compiler diagnostic and the number in a weave-time error name the same mistake.
     *
     * @return the diagnostic code, never {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public DiagnosticCode code() {
        return this.code;
    }

    /**
     * Returns the text parsing stopped in.
     *
     * <p>Not necessarily the argument that was passed: the input is trimmed first, and a source-form failure other
     * than {@code AW1017} reports the text with its {@value MemberSelector#SOURCE_PREFIX} prefix already removed,
     * so that {@link #offset()} indexes what is returned here. An {@code AW1017} failure reports the trimmed
     * argument with any {@value MemberSelector#SOURCE_PREFIX} prefix still in place; its {@link #offset()} is
     * {@code 0}, so the prefix does not throw the caret off.
     *
     * @return the selector text this failure is about, never {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public String selector() {
        return this.selector;
    }

    /**
     * Returns the index into {@link #selector()} at which parsing stopped.
     *
     * <p>Points at the offending character, or at the length of the text when the selector ended too early. It is
     * {@code 0} for a failure that is about the whole selector rather than one position, which is the case for
     * {@code AW1017}.
     *
     * @return the offset, which may equal the length of {@link #selector()}
     */
    @Contract(pure = true)
    public int offset() {
        return this.offset;
    }

    /**
     * Returns a corrected spelling of the whole selector.
     *
     * <p>Present for {@code AW1017}, where it is {@value MemberSelector#DESCRIPTOR_PREFIX} prepended to the text
     * that failed the source-form parse -- the argument trimmed and with any leading
     * {@value MemberSelector#SOURCE_PREFIX} prefix already removed -- and empty for the other three codes the
     * parser reports. It is not guaranteed to itself be a selector that parses: the descriptor form refuses a
     * wildcard outright, so a partial wildcard the source grammar also rejects, such as {@code a*b(I)V}, produces
     * a suggestion, {@code desc:a*b(I)V}, that fails to parse as well. The annotation processor and the engine's
     * weave parser pass it through as the diagnostic's remedy, so an editor can offer the fix directly.
     *
     * @return the corrected selector, or {@link Optional#empty()} when none can be offered
     */
    @Contract(pure = true)
    @NotNull
    public Optional<String> suggestion() {
        return Optional.ofNullable(this.suggestion);
    }

    /**
     * Renders the selector with a caret under the position that failed.
     *
     * <p>Two lines joined by {@link System#lineSeparator()}: the selector, then spaces up to the offset, a caret,
     * a space and the message. The offset is clamped into the text, so a caret is produced even for an offset past
     * the end -- which is what a selector that ended too early records.
     *
     * <p>The caret lines up only in a fixed-width font and only for text without tabs, which is what a selector is.
     *
     * @return the two-line rendering
     */
    @Contract(pure = true)
    @NotNull
    public String formatWithCaret() {
        final int caret = Math.max(0, Math.min(this.offset, this.selector.length()));
        return this.selector + System.lineSeparator() + " ".repeat(caret) + "^ " + getMessage();
    }
}
