package de.splatgames.aether.weaver.engine.text;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Map;
import java.util.Objects;

/**
 * Rewrites text so that a stream can encode every character in it.
 *
 * <p>A console whose charset cannot represent a character prints a substitute chosen by the
 * encoder, which loses the distinction between characters that had a sensible ASCII equivalent and
 * characters that had none. Choosing the substitute here keeps that distinction: an em dash becomes
 * {@code -} and a rightwards arrow becomes {@code ->}. A character with no entry becomes
 * {@code ?}, except a lone low surrogate, which matches no case and is dropped with no replacement
 * at all.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless, and every method is pure. The encoder is created per call because
 * {@link java.nio.charset.CharsetEncoder} is documented as unsafe for use by multiple concurrent
 * threads.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConsoleText {

    /** ASCII stand-ins for the characters this project prints, keyed by the original. */
    private static final Map<Character, String> REPLACEMENTS = Map.ofEntries(
            Map.entry('—', "-"),      // em dash
            Map.entry('–', "-"),      // en dash
            Map.entry('→', "->"),     // rightwards arrow
            Map.entry('←', "<-"),     // leftwards arrow
            Map.entry('↳', "->"),     // downwards arrow with tip rightwards
            Map.entry('≠', "!="),     // not equal to
            Map.entry('…', "..."),    // horizontal ellipsis
            Map.entry('‘', "'"),
            Map.entry('’', "'"),
            Map.entry('“', "\""),
            Map.entry('”', "\""),
            Map.entry('·', "*"),      // middle dot
            Map.entry('•', "*"));     // bullet

    /** The stand-in for a character with no entry in {@link #REPLACEMENTS}. */
    private static final String UNKNOWN = "?";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ConsoleText() {
        throw new AssertionError("no instances");
    }

    /**
     * Degrades text to what the given stream's charset can encode.
     *
     * @param text   the text to degrade; must not be {@code null}
     * @param stream the stream whose charset decides; must not be {@code null}
     * @return the text, unchanged when the charset can already encode all of it
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String forStream(@NotNull final String text, @NotNull final PrintStream stream) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(stream, "stream");
        return forCharset(text, stream.charset());
    }

    /**
     * Degrades text to what the given charset can encode.
     *
     * <p>The fast path requires {@link CharsetEncoder#canEncode(CharSequence)} to accept the whole
     * string, so under UTF-8 an unpaired surrogate anywhere in it fails the check and the loop below
     * runs even though every other character is plain UTF-8. The two checks disagree about a
     * surrogate pair: {@link CharsetEncoder#canEncode(char)} refuses every surrogate on its own,
     * matched or not, where the whole-string check accepts a well-formed pair. A perfectly
     * well-formed emoji elsewhere in the same string is therefore degraded to {@code ?} once the loop
     * runs, at the cost of its own pair and no neighbouring character.
     *
     * <p>The same branch decides an unpaired surrogate, which no charset can encode: a lone high
     * surrogate becomes one {@code ?} and the character after it is skipped whether or not it was
     * a low half, and a lone low surrogate is dropped without a replacement.
     *
     * @param text    the text to degrade; must not be {@code null}
     * @param charset the charset that decides; must not be {@code null}
     * @return the text, unchanged when the charset can already encode all of it
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String forCharset(@NotNull final String text, @NotNull final Charset charset) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");

        final CharsetEncoder encoder = charset.newEncoder();
        if (encoder.canEncode(text)) {
            // The overwhelmingly common case on any modern terminal, and it allocates nothing.
            return text;
        }

        final StringBuilder degraded = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (encoder.canEncode(character)) {
                degraded.append(character);
                continue;
            }
            final String replacement = REPLACEMENTS.get(character);
            if (replacement != null) {
                degraded.append(replacement);
            } else if (!Character.isSurrogate(character)) {
                degraded.append(UNKNOWN);
            } else if (Character.isHighSurrogate(character)) {
                // A supplementary character — an emoji in a heading, say. One replacement for the
                // pair, not two, and the low half is skipped with it.
                degraded.append(UNKNOWN);
                index++;
            }
        }
        return degraded.toString();
    }
}
