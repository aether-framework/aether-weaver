package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.util.Objects;
import java.util.Optional;

/**
 * A selector that names a loaded value rather than a member.
 *
 * <p>Written where a constant is what the declaration acts on, as in
 * {@code @At(value = Point.CONSTANT, target = "string:\"retry\"")}. The seven spellings are the whole grammar:
 *
 * <ul>
 *   <li>{@code null} -- the null reference, written bare and with no colon.
 *   <li>{@code int:42}, {@code long:7}, {@code float:1.5}, {@code double:1.0} -- a number, read by
 *       {@link Integer#valueOf(String)}, {@link Long#valueOf(String)}, {@link Float#valueOf(String)} and
 *       {@link Double#valueOf(String)} respectively. A value those reject is reported as {@code AW1015}, which
 *       covers {@code long:7L}, since a type suffix is not part of what {@link Long#valueOf(String)} accepts, and
 *       {@code int: 42}, since the text after the colon is taken exactly as written.
 *   <li>{@code string:"retry"} -- a string, quoted. Inside the quotes {@code \"} stands for a quote and
 *       {@code \\} for a backslash. The value is everything between the first quote and the last, so an unescaped
 *       quote between them is read as part of the value; rendering escapes it.
 *   <li>{@code class:java.util.List} -- a class constant, written as a binary name, or as a field descriptor with
 *       {@code class:desc:Ljava/util/List;}. A primitive keyword names the primitive's own class constant, so
 *       {@code class:int} is the {@code int} class. A name the JDK refuses is reported as {@code AW1015}, which is
 *       what {@code class:java.util.List[]} produces: an array class constant is written as
 *       {@code class:desc:[Ljava/util/List;}.
 * </ul>
 *
 * <p>{@code string:} is the one keyword that is not a Java keyword, so a field may be named that. It counts as a
 * constant only when the value is quoted: {@code string:java.lang.String} is a {@link FieldSelector} naming a field
 * called {@code string}. Writing {@value MemberSelector#SOURCE_PREFIX} in front of any of the seven suppresses
 * constant recognition entirely, because the keyword is looked for before the prefix is removed.
 *
 * <h2>Form, rendering and canonical text</h2>
 *
 * <p>A constant has one spelling, so this class answers the same text for both forms: {@link #form()} is always
 * {@link Form#SOURCE}, {@link #render(MemberSelector.Form)} ignores which form is asked for, and
 * {@link #isFullyQualified()} is always {@code true}, which makes {@link #canonical()} always present. The
 * rendering normalises: a number goes through the wrapper's own {@code toString}, so {@code float:1.5f} renders as
 * {@code float:1.5}, and a class constant renders as a source name, so {@code class:desc:Ljava/util/List;} renders
 * as {@code class:java.util.List}.
 *
 * <h2>Matching</h2>
 *
 * <p>The engine's built-in constant point compares {@link #value()} with the value the instruction loads, by
 * equality. A selector whose value is empty -- the {@link Kind#NULL} kind -- matches the null reference. The
 * {@link #kind()} is a spelling, not a second condition.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * ConstantSelector retryCount = (ConstantSelector) MemberSelector.parse("int:3");
 * assert retryCount.value().orElseThrow().equals(3);
 * assert retryCount.equals(ConstantSelector.of(3));
 *
 * ConstantSelector reason = (ConstantSelector) MemberSelector.parse("string:\"insufficient funds\"");
 * assert reason.render(MemberSelector.Form.SOURCE).equals("string:\"insufficient funds\"");
 *
 * ConstantSelector listType = (ConstantSelector) MemberSelector.parse("class:desc:Ljava/util/List;");
 * assert listType.canonical().orElseThrow().equals("class:java.util.List");
 * }</pre>
 *
 * <p>Immutable and safe to share between threads.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector
 * @see de.splatgames.aether.weaver.api.Point#CONSTANT
 */
public final class ConstantSelector implements MemberSelector {

    /** Which of the seven spellings this selector was written in. */
    private final Kind kind;

    /** The value, or {@code null} for {@link Kind#NULL}, which has none to carry. */
    private final @Nullable ConstantDesc value;

    /**
     * Builds a constant selector from a kind and its value.
     *
     * <p>The pairing is not checked here: the parser and {@link #of(ConstantDesc)} are the only callers, and both
     * derive the kind from the value.
     *
     * @param kind  the spelling this selector uses
     * @param value the value, or {@code null} for {@link Kind#NULL}
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    ConstantSelector(@NotNull final Kind kind, final @Nullable ConstantDesc value) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.value = value;
    }

    /**
     * Builds a selector for a constant that has a spelling in this grammar.
     *
     * <p>{@code null} and {@link ConstantDescs#NULL} both give a {@link Kind#NULL} selector, so a caller holding
     * either form of "no value" gets the same answer. An {@link Integer}, {@link Long}, {@link Float},
     * {@link Double}, {@link String} or {@link ClassDesc} gives the matching kind.
     *
     * <p>Every other {@link ConstantDesc} answers {@code null}. The type is sealed, so what remains is a
     * {@link java.lang.constant.MethodTypeDesc}, a {@link java.lang.constant.MethodHandleDesc} or a
     * {@link java.lang.constant.DynamicConstantDesc}: all three are loadable by an instruction and none of them has
     * a spelling in this grammar, and answering {@code null} is how a caller learns that rather than receiving a
     * selector that could never be written down.
     *
     * @param value the constant to name, or {@code null} for the null reference
     * @return the selector, or {@code null} when the constant has no spelling in this grammar
     */
    @Contract(pure = true)
    @Nullable
    public static ConstantSelector of(final @Nullable ConstantDesc value) {
        if (value == null || ConstantDescs.NULL.equals(value)) {
            return new ConstantSelector(Kind.NULL, null);
        }
        return switch (value) {
            case Integer ignored -> new ConstantSelector(Kind.INT, value);
            case Long ignored -> new ConstantSelector(Kind.LONG, value);
            case Float ignored -> new ConstantSelector(Kind.FLOAT, value);
            case Double ignored -> new ConstantSelector(Kind.DOUBLE, value);
            case String ignored -> new ConstantSelector(Kind.STRING, value);
            case ClassDesc ignored -> new ConstantSelector(Kind.CLASS, value);
            // A method handle, a method type or another dynamic constant is loadable and has no
            // spelling in this grammar. Answering null is how a caller learns that.
            default -> null;
        };
    }

    /**
     * Returns which of the seven spellings this selector uses.
     *
     * @return the kind, never {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public Kind kind() {
        return this.kind;
    }

    /**
     * Returns the value this selector names.
     *
     * <p>Empty exactly for {@link Kind#NULL}. For every other kind the value is present and is of the type that
     * kind implies: an {@link Integer} for {@link Kind#INT}, a {@link ClassDesc} for {@link Kind#CLASS}, and so on.
     *
     * @return the value, or {@link Optional#empty()} for the null reference
     */
    @Contract(pure = true)
    @NotNull
    public Optional<ConstantDesc> value() {
        return Optional.ofNullable(this.value);
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link Form#SOURCE}, since a constant has one spelling and no descriptor form
     */
    @Override
    public Form form() {
        return Form.SOURCE;
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@code true}: a constant selector names one value, with nothing left to resolve
     */
    @Override
    public boolean isFullyQualified() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return the source rendering, always present
     */
    @Override
    public Optional<String> canonical() {
        return Optional.of(render(Form.SOURCE));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The requested form is checked for {@code null} and then ignored: both forms render the same text, which is
     * the one spelling the grammar accepts for the value. A string is quoted and escaped, a class constant is
     * written as a source name, and a number is written by its wrapper's {@code toString}.
     *
     * <p>The {@link Kind#CLASS} branch falls back to the value's own text for a value that is not a
     * {@link ClassDesc}, which the parser and {@link #of(ConstantDesc)} never produce.
     *
     * @param form the form to render in, which does not change the result
     * @return the rendered constant selector
     * @throws NullPointerException if {@code form} is {@code null}
     */
    @Override
    public String render(@NotNull final Form form) {
        Objects.requireNonNull(form, "form");
        return switch (this.kind) {
            case NULL -> "null";
            case STRING -> "string:\"" + escape(String.valueOf(this.value)) + '"';
            case CLASS -> "class:" + (this.value instanceof ClassDesc type
                    ? SelectorRendering.sourceName(type)
                    : String.valueOf(this.value));
            case INT -> "int:" + this.value;
            case LONG -> "long:" + this.value;
            case FLOAT -> "float:" + this.value;
            case DOUBLE -> "double:" + this.value;
        };
    }

    /**
     * Escapes a string value so that the rendering parses back to it.
     *
     * <p>Backslashes are doubled before quotes are escaped, so that the escape character introduced for a quote is
     * not doubled in turn.
     *
     * @param raw the value to escape; must not be {@code null}
     * @return the value with backslashes and quotes escaped
     */
    private static String escape(@NotNull final String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Compares kind and value.
     *
     * @param o the object to compare with
     * @return whether {@code o} is a constant selector of the same kind naming the same value
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ConstantSelector other
                && this.kind == other.kind
                && Objects.equals(this.value, other.value);
    }

    /**
     * Returns a hash code over kind and value.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.kind, this.value);
    }

    /**
     * Returns the source rendering, which is the only spelling a constant has.
     *
     * @return the rendering in {@link Form#SOURCE}
     */
    @Override
    public String toString() {
        return render(Form.SOURCE);
    }

    /**
     * The seven kinds of constant this grammar can name.
     *
     * <p>Each carries the keyword that introduces it. The keyword is the spelling, not a type test: two selectors
     * of different kinds are never equal, but matching compares the value.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Kind {

        /**
         * The null reference.
         *
         * <p>The one kind written without a colon and without a value: the selector is the bare word
         * {@code null}, and {@link ConstantSelector#value()} is empty.
         */
        NULL("null"),

        /** An {@code int}, as in {@code int:42}. */
        INT("int"),

        /** A {@code long}, as in {@code long:7}, written without a type suffix. */
        LONG("long"),

        /** A {@code float}, as in {@code float:1.5}. */
        FLOAT("float"),

        /** A {@code double}, as in {@code double:1.0}. */
        DOUBLE("double"),

        /** A {@link String}, quoted, as in {@code string:"retry"}. */
        STRING("string"),

        /** A class constant, as in {@code class:java.util.List} or {@code class:desc:Ljava/util/List;}. */
        CLASS("class");

        /** The keyword that introduces this kind in a selector. */
        private final String keyword;

        /**
         * Builds a kind with the keyword that introduces it.
         *
         * @param keyword the keyword; must not be {@code null}
         */
        Kind(@NotNull final String keyword) {
            this.keyword = keyword;
        }

        /**
         * Returns the keyword that introduces this kind in a selector.
         *
         * <p>Followed by a colon and the value for every kind but {@link #NULL}, whose keyword is the whole
         * selector.
         *
         * @return the keyword, never blank
         */
        @Contract(pure = true)
        @NotNull
        public String keyword() {
            return this.keyword;
        }
    }
}
