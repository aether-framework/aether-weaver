package de.splatgames.aether.weaver.api.manifest;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The JSON parser and string quoter the manifest format is built on.
 *
 * <p>This reads a deliberately narrow subset of JSON, but a broader one than
 * {@link ManifestWriter} ever emits: it accepts {@code null}, the escapes {@code \/}, {@code \b}
 * and {@code \f}, a duplicate key, and a leading zero in a number, none of which the writer
 * produces. The manifest is read from whatever is on the classpath, so every syntax this parser
 * accepts is a path that has to be correct on untrusted input regardless of whether the writer
 * exercises it. What is refused for a syntax reason is refused with an
 * {@link IllegalArgumentException} naming the offset parsing had reached; the one refusal that is
 * not a syntax error — {@link #readObject(String)} finding a well-formed value that is not an
 * object — carries no offset, because parsing had already finished. Neither case returns a
 * partial result.
 *
 * <h2>The accepted grammar</h2>
 *
 * <ul>
 *   <li><b>Objects</b> with string keys. A key must be a quoted string; a bare identifier is
 *       refused. Duplicate keys are permitted and the last one wins. Key order is preserved.
 *   <li><b>Arrays</b>, order preserved.
 *   <li><b>Strings</b>, with the escapes {@code \"}, {@code \\}, {@code \/}, {@code \b},
 *       {@code \f}, {@code \n}, {@code \r}, {@code \t}, and a backslash followed by {@code u} and
 *       four characters read with {@link Integer#parseInt(String, int)} in base 16. That accepts a
 *       leading {@code +} or {@code -} among those four, so a backslash followed by
 *       {@code u+041} parses to {@code A} while a backslash followed by {@code u-041} parses to a
 *       negative code point that becomes a garbage character when cast to {@code char}, rather
 *       than being refused; four genuine hexadecimal digits are the only input this is meant for.
 *       Any escape other than one of these nine is refused. An unescaped character is taken as
 *       itself, so the text is read as UTF-16 without further validation.
 *   <li><b>Integers only</b>, as an optional {@code -} followed by digits, parsed as a
 *       {@link Long}. A fraction, an exponent, a leading {@code +} and a value outside
 *       {@code long} range are all refused. A leading zero is accepted, so {@code 01} reads as
 *       {@code 1}.
 *   <li><b>{@code true}, {@code false} and {@code null}.</b> A JSON {@code null} becomes a Java
 *       {@code null} inside the map or list holding it.
 * </ul>
 *
 * <p>Refused: comments, trailing commas, single-quoted strings, a byte-order mark, and any
 * content after the top-level value. Whitespace between tokens is a space, a tab, a line feed or
 * a carriage return; nothing else counts as whitespace.
 *
 * <h2>Depth</h2>
 *
 * <p>A value enclosed by more than {@value #MAX_DEPTH} objects or arrays is refused. Parsing is
 * recursive, and a document from the classpath must not be able to drive the recursion until the
 * stack ends. The counter advances for every object and every array, not only for the four named
 * levels of the document, so a real manifest already nests deeper than that count suggests: a
 * point's {@code "point"} string, in the example document on {@link WeaveManifest}, sits seven
 * objects and arrays deep — root object, {@code "weaves"} array, weave object,
 * {@code "injectors"} array, injector object, {@code "points"} array, point object — with
 * {@value #MAX_DEPTH} chosen to leave that comfortable room to grow.
 *
 * <h2>Thread safety</h2>
 *
 * <p>An instance holds the position it has reached and is not safe to share, which is why the
 * constructor is private and {@link #readObject(String)} creates one per call. Both static
 * methods are therefore safe to call concurrently.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class Json {

    /** The most objects and arrays that may enclose a value before parsing refuses to descend. */
    private static final int MAX_DEPTH = 64;

    /** The document being parsed. */
    private final String text;

    /** How far into {@link #text} parsing has reached, in characters. */
    private int position;

    /**
     * Binds a parser to the document it will read.
     *
     * @param text the document text; must not be {@code null}
     */
    private Json(@NotNull final String text) {
        this.text = text;
    }

    /**
     * Parses a whole document that must be a JSON object.
     *
     * <p>The result is a {@link LinkedHashMap}, so iteration follows the order the keys appear in
     * the document. Values are a {@link Map}, a {@link List}, a {@link String}, a {@link Long}, a
     * {@link Boolean} or {@code null}, which are the only shapes this parser produces.
     *
     * @param text the document text; must not be {@code null}
     * @return the top-level object, with insertion order preserved
     * @throws NullPointerException     if {@code text} is {@code null}
     * @throws IllegalArgumentException if the text is not one well-formed JSON value in the
     *                                  accepted subset, if anything but whitespace follows that
     *                                  value, or if the value is not an object
     */
    @NotNull
    public static Map<String, Object> readObject(@NotNull final String text) {
        Objects.requireNonNull(text, "text");
        final Json parser = new Json(text);
        parser.skipWhitespace();
        final Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.fail("trailing content after the document");
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("the manifest must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /**
     * Renders a string as a JSON string literal, with the surrounding quotes.
     *
     * <p>Only what has to be escaped is escaped: the quote, the backslash, and the three control
     * characters with short escapes. Any other character below {@code U+0020} becomes
     * a backslash, {@code u} and four lowercase hexadecimal digits. Everything above that is
     * itself, including {@code /} and every non-ASCII character, because the document is UTF-8
     * and escaping printable text would only cost the person reading it.
     *
     * @param value the text to render; must not be {@code null}
     * @return the quoted and escaped literal
     * @throws NullPointerException if {@code value} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String quote(@NotNull final String value) {
        Objects.requireNonNull(value, "value");
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Control characters must be escaped; everything else is written as itself,
                    // because the manifest is UTF-8 and \\u-escaping printable text would only make
                    // it unreadable to the person debugging it.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Reads one value, dispatching on its first character.
     *
     * <p>Anything that is not an object, an array, a string or a literal is handed to
     * {@link #readNumber()}, which is where an unexpected character is finally rejected.
     *
     * @param depth how many objects and arrays enclose this value; the top-level value is
     *              {@code 0}
     * @return the value, which is {@code null} for the literal {@code null}
     * @throws IllegalArgumentException if the value is malformed, absent, or nested deeper than
     *                                  {@value #MAX_DEPTH}
     */
    @Nullable
    private Object readValue(final int depth) {
        if (depth > MAX_DEPTH) {
            throw fail("nested more than " + MAX_DEPTH + " deep");
        }
        skipWhitespace();
        if (this.position >= this.text.length()) {
            throw fail("expected a value");
        }
        final char c = this.text.charAt(this.position);
        return switch (c) {
            case '{' -> readObjectValue(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    /**
     * Reads an object, starting at its opening brace.
     *
     * <p>A duplicate key overwrites the value of the earlier one and keeps its position, which is
     * what {@link LinkedHashMap} does on a repeated {@code put}.
     *
     * @param depth how many objects and arrays enclose this object
     * @return the object's entries, in the order the keys first appear
     * @throws IllegalArgumentException if a key is not a quoted string, a colon or a separator is
     *                                  missing, the object is not closed, or any value is
     *                                  malformed
     */
    @NotNull
    private Map<String, Object> readObjectValue(final int depth) {
        expect('{');
        final Map<String, Object> entries = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            this.position++;
            return entries;
        }
        while (true) {
            skipWhitespace();
            final String key = readString();
            skipWhitespace();
            expect(':');
            entries.put(key, readValue(depth + 1));
            skipWhitespace();
            final char next = peek();
            this.position++;
            if (next == '}') {
                return entries;
            }
            if (next != ',') {
                throw fail("expected ',' or '}' in an object");
            }
        }
    }

    /**
     * Reads an array, starting at its opening bracket.
     *
     * @param depth how many objects and arrays enclose this array
     * @return the entries in order, which may contain {@code null} for a JSON {@code null}
     * @throws IllegalArgumentException if a separator is missing, the array is not closed, or any
     *                                  entry is malformed — including the empty entry a trailing
     *                                  comma leaves behind
     */
    @NotNull
    private List<Object> readArray(final int depth) {
        expect('[');
        final List<Object> entries = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            this.position++;
            return entries;
        }
        while (true) {
            entries.add(readValue(depth + 1));
            skipWhitespace();
            final char next = peek();
            this.position++;
            if (next == ']') {
                return entries;
            }
            if (next != ',') {
                throw fail("expected ',' or ']' in an array");
            }
        }
    }

    /**
     * Reads a string, starting at its opening quote.
     *
     * <p>A backslash-{@code u} escape is read as one UTF-16 code unit, so a supplementary character
     * written as a surrogate pair arrives as the pair.
     *
     * @return the unescaped contents, without the quotes
     * @throws IllegalArgumentException if the string is not opened or not closed, an escape is
     *                                  cut short, or an escape is one this parser does not accept
     */
    @NotNull
    private String readString() {
        expect('"');
        final StringBuilder out = new StringBuilder();
        while (true) {
            if (this.position >= this.text.length()) {
                throw fail("unterminated string");
            }
            final char c = this.text.charAt(this.position++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (this.position >= this.text.length()) {
                throw fail("unterminated escape");
            }
            final char escape = this.text.charAt(this.position++);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (this.position + 4 > this.text.length()) {
                        throw fail("truncated \\u escape");
                    }
                    final String hex = this.text.substring(this.position, this.position + 4);
                    this.position += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (final NumberFormatException malformed) {
                        throw fail("invalid \\u escape \"" + hex + '"');
                    }
                }
                default -> throw fail("unknown escape '\\" + escape + '\'');
            }
        }
    }

    /**
     * Reads an integer.
     *
     * <p>This is also the default branch of {@link #readValue(int)}, so it is where a character
     * that can start no value at all is rejected: nothing consumed means no value was found,
     * which is reported as {@code expected a value} rather than as a bad number.
     *
     * @return the value, always a {@link Long} however small it is
     * @throws IllegalArgumentException if no digit follows, or the digits do not fit in a
     *                                  {@code long}
     */
    @NotNull
    private Long readNumber() {
        final int start = this.position;
        if (peek() == '-') {
            this.position++;
        }
        while (this.position < this.text.length()
                && Character.isDigit(this.text.charAt(this.position))) {
            this.position++;
        }
        if (this.position == start) {
            throw fail("expected a value");
        }
        try {
            return Long.parseLong(this.text, start, this.position, 10);
        } catch (final NumberFormatException malformed) {
            throw fail("invalid number");
        }
    }

    /**
     * Reads one of the three bare literals.
     *
     * @param literal the exact text expected at the current position; must not be {@code null}
     * @param value   the value that text stands for, {@code null} for the literal {@code null}
     * @return {@code value}
     * @throws IllegalArgumentException if the document does not continue with {@code literal}
     */
    @Nullable
    private Object readLiteral(@NotNull final String literal, @Nullable final Object value) {
        if (!this.text.startsWith(literal, this.position)) {
            throw fail("expected " + literal);
        }
        this.position += literal.length();
        return value;
    }

    /**
     * Consumes one character, which must be the given one.
     *
     * @param expected the character required at the current position
     * @throws IllegalArgumentException if the document holds another character there, or ends
     */
    private void expect(final char expected) {
        if (peek() != expected) {
            throw fail("expected '" + expected + '\'');
        }
        this.position++;
    }

    /**
     * Returns the character at the current position without consuming it.
     *
     * @return the character parsing has reached
     * @throws IllegalArgumentException if the document has ended, so that a truncated document
     *                                  fails at the first place that needed a character rather
     *                                  than by an index out of bounds
     */
    private char peek() {
        if (this.position >= this.text.length()) {
            throw fail("unexpected end of document");
        }
        return this.text.charAt(this.position);
    }

    /**
     * Advances past a space, a tab, a line feed or a carriage return, and stops at anything else
     * or at the end of the document.
     */
    private void skipWhitespace() {
        while (this.position < this.text.length()) {
            final char c = this.text.charAt(this.position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            this.position++;
        }
    }

    /**
     * Builds the exception for a malformed document.
     *
     * <p>The message carries the current offset. That offset is where parsing stopped, not
     * necessarily where the mistake was made — a missing brace is noticed at the end of the
     * document — but it is what lets a reader find the place in a file no human wrote.
     *
     * @param what what was wrong, as a phrase completing the message; must not be {@code null}
     * @return the exception to throw, which this method does not throw itself
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    private IllegalArgumentException fail(@NotNull final String what) {
        return new IllegalArgumentException(
                "malformed manifest at offset " + this.position + ": " + what);
    }
}
