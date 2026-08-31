package de.splatgames.aether.weaver.api.select;

import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads selector text into a {@link MemberSelector}.
 *
 * <p>One string, one cursor, one pass. The source form is read left to right with a single character of lookahead;
 * the descriptor form is split on the positions the JVM's own syntax fixes, since a descriptor has no structure to
 * descend through. The grammar both implement is specified on {@link MemberSelector}; what this class adds is the
 * order in which the three spellings are tried, which is where every ambiguity in the grammar is settled.
 *
 * <h2>Order of decisions</h2>
 *
 * <p>Constants first, then the {@value MemberSelector#DESCRIPTOR_PREFIX} prefix, then the source form. Each step
 * commits: once a step accepts the text, no later step can reinterpret it. That is what keeps a selector from
 * changing meaning as the grammar grows.
 *
 * <p>The one place where the shape of the text is inspected rather than parsed is
 * {@link #looksLikeDescriptor(String)}, and it runs only after the source-form parse has already failed. Detecting
 * the shape first would let a text that is legal in both readings be silently taken as the other one: {@code I} is
 * a legal class name in the default package, so {@code state:I} has to stay a field of a type called {@code I},
 * and only a text that cannot be a source selector at all is offered the {@code AW1017} suggestion.
 *
 * <h2>Diagnostics</h2>
 *
 * <p>Most failures leave through {@link #error(String, int, DiagnosticCode, String, String)} as a
 * {@link SelectorSyntaxException} carrying a code, an offset and a message: {@code AW1015} for the source grammar
 * and for a malformed constant literal, {@code AW1017} for descriptor syntax without its prefix, {@code AW1018}
 * for a malformed descriptor, and {@code AW1019} for a method descriptor with no return type. Most calls into
 * {@link ClassDesc} and {@link java.lang.constant.MethodTypeDesc} are guarded this way, because those throw
 * {@link RuntimeException} subtypes that carry no offset and no code. Two calls are not guarded. {@link
 * #classDescOf(String, int)} applies an array dimension to {@code void} outside any {@code try}, so
 * {@code m(void[])}, {@code m():void[]} and {@code v:void[]} each escape as a raw {@link IllegalArgumentException}
 * from {@link ClassDesc#arrayType()}, with no code and no offset, rather than as a {@link SelectorSyntaxException}.
 * {@link #parseDescriptorMethod(int, int)} rejects only an empty method name before building a
 * {@link MethodSelector}, so a blank one -- {@code desc: ()V} or {@code desc:a. ()V} -- reaches
 * {@code MethodSelector}'s own constructor outside any {@code try} and escapes the same way, as a raw
 * {@link IllegalArgumentException} with no code and no offset. The field descriptor path guards against this by
 * accident: {@link #parseDescriptorField(int)} builds its {@link FieldSelector} inside a {@code try} that already
 * exists to catch a malformed field descriptor, so the same blank name reported as {@code desc: :I} comes out as
 * {@code AW1018} instead.
 *
 * <p>An instance holds the text and the cursor and is used once. The static entry points are the whole API.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector
 * @see SelectorSyntaxException
 */
final class SelectorParser {

    /**
     * The keywords that name a primitive type in the source form.
     *
     * <p>{@code void} is one of them, so it is accepted wherever a type is accepted, including a parameter
     * position. A class file records {@code V} only as a return type, so a parameter written that way names
     * nothing.
     */
    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    /**
     * The constant keywords that cannot also be a member name.
     *
     * <p>All five are Java keywords, so a field cannot be named any of them and {@code int:42} has exactly one
     * reading. {@code string} is absent on purpose and is handled by {@link #tryParseConstant(String)}, which
     * requires its value to be quoted.
     */
    private static final Set<String> UNAMBIGUOUS_CONSTANT_KEYWORDS = Set.of(
            "int", "long", "float", "double", "class");

    /** The text being parsed, which the offsets in every diagnostic index. */
    private final String text;

    /** The cursor: the index of the next character to read. */
    private int position;

    /**
     * Builds a parser over the given text.
     *
     * <p>The text is the one every offset is measured against, so a caller that strips a prefix before constructing
     * a parser reports offsets relative to what remains.
     *
     * @param text the text to parse
     */
    private SelectorParser(@NotNull final String text) {
        this.text = text;
    }

    /**
     * Parses a selector with no expected kind.
     *
     * @param raw the selector text
     * @return the parsed selector
     * @throws NullPointerException    if {@code raw} is {@code null}
     * @throws SelectorSyntaxException if the text does not parse
     */
    static MemberSelector parse(@NotNull final String raw) {
        return parse(raw, null);
    }

    /**
     * Parses a selector, taking the three spellings in order.
     *
     * <p>The text is trimmed first, so every offset below indexes the trimmed text rather than the argument. The
     * {@value MemberSelector#SOURCE_PREFIX} prefix is removed only for the source-form attempt, which is why a
     * failure inside that attempt reports the body alone while the {@code AW1017} report -- built here -- reports
     * the whole text.
     *
     * @param raw      the selector text
     * @param expected the kind a bare name names, or {@code null} to read one as a method
     * @return the parsed selector
     * @throws NullPointerException    if {@code raw} is {@code null}
     * @throws SelectorSyntaxException if the text does not parse
     */
    static MemberSelector parse(@NotNull final String raw, final @Nullable MemberKind expected) {
        final String text = Objects.requireNonNull(raw, "text").trim();
        if (text.isEmpty()) {
            throw error(text, 0, DiagnosticCode.SELECTOR_SYNTAX_ERROR, "a selector must not be empty");
        }

        final ConstantSelector constant = tryParseConstant(text);
        if (constant != null) {
            return constant;
        }

        if (text.startsWith(MemberSelector.DESCRIPTOR_PREFIX)) {
            return new SelectorParser(text).parseDescriptorForm();
        }

        final String body = text.startsWith(MemberSelector.SOURCE_PREFIX)
                ? text.substring(MemberSelector.SOURCE_PREFIX.length())
                : text;
        try {
            return new SelectorParser(body).parseSourceForm(expected);
        } catch (@NotNull final SelectorSyntaxException e) {
            // The source-form parse failed. Only now is shape detection allowed, and only to
            // suggest the prefix — never to silently reinterpret the input.
            if (looksLikeDescriptor(body)) {
                throw error(text, 0, DiagnosticCode.SELECTOR_MISSING_DESC_PREFIX,
                        '"' + body + "\" looks like a JVM descriptor rather than a source-level "
                                + "selector; add the \"desc:\" prefix to use it as one",
                        MemberSelector.DESCRIPTOR_PREFIX + body);
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------------------------------
    // constants
    // ---------------------------------------------------------------------------------------

    /**
     * Recognises a constant selector, or reports that the text is not one.
     *
     * <p>Answering {@code null} rather than throwing is what lets the caller fall through to the member forms: the
     * keyword test is a guess about the whole text, and a text that fails it is not thereby malformed. A malformed
     * value after a keyword that is unambiguously a constant is a different matter and does throw, since no member
     * reading is left.
     *
     * <p>The keyword is taken from before the first colon, and the value is everything after it, untrimmed. That
     * is why {@code int: 42} fails: {@link Integer#valueOf(String)} is handed the space.
     *
     * @param text the trimmed selector text
     * @return the constant selector, or {@code null} when the text is not a constant
     * @throws SelectorSyntaxException if the text is a constant whose value its keyword rejects
     */
    private static @Nullable ConstantSelector tryParseConstant(@NotNull final String text) {
        if ("null".equals(text)) {
            return new ConstantSelector(ConstantSelector.Kind.NULL, null);
        }
        final int colon = text.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        final String keyword = text.substring(0, colon);
        final String value = text.substring(colon + 1);

        if ("string".equals(keyword)) {
            // 'string' is not a Java keyword, so a field may be named that. Only a quoted value
            // makes this a constant selector.
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return new ConstantSelector(ConstantSelector.Kind.STRING,
                        unescape(value.substring(1, value.length() - 1)));
            }
            return null;
        }
        if (!UNAMBIGUOUS_CONSTANT_KEYWORDS.contains(keyword)) {
            return null;
        }
        if ("class".equals(keyword)) {
            final String name = value.startsWith(MemberSelector.DESCRIPTOR_PREFIX)
                    ? value.substring(MemberSelector.DESCRIPTOR_PREFIX.length())
                    : null;
            try {
                return new ConstantSelector(ConstantSelector.Kind.CLASS,
                        name != null ? ClassDesc.ofDescriptor(name) : classDescOf(value, 0));
            } catch (@NotNull final RuntimeException e) {
                throw error(text, colon + 1, DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                        "not a valid class name: " + value);
            }
        }
        try {
            return switch (keyword) {
                case "int" -> new ConstantSelector(ConstantSelector.Kind.INT, Integer.valueOf(value));
                case "long" -> new ConstantSelector(ConstantSelector.Kind.LONG, Long.valueOf(value));
                case "float" -> new ConstantSelector(ConstantSelector.Kind.FLOAT, Float.valueOf(value));
                case "double" -> new ConstantSelector(ConstantSelector.Kind.DOUBLE, Double.valueOf(value));
                default -> null;
            };
        } catch (@NotNull final NumberFormatException e) {
            throw error(text, colon + 1, DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                    "not a valid " + keyword + " literal: " + value);
        }
    }

    /**
     * Reverses the escaping a string constant's rendering applies.
     *
     * <p>Quotes are unescaped before backslashes, the reverse of the order the rendering uses, so that a rendered
     * value reads back unchanged.
     *
     * @param raw the text between the quotes
     * @return the value it stands for
     */
    private static String unescape(@NotNull final String raw) {
        return raw.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ---------------------------------------------------------------------------------------
    // source form
    // ---------------------------------------------------------------------------------------

    /**
     * Parses the source form.
     *
     * <p>The head -- everything up to the first {@code (} or {@code :} -- is read and split into an owner and a
     * name before the shape is decided, because the shape is decided by what follows it: a parenthesis makes a
     * method, a colon makes a field, and the end of the text leaves a bare name for {@code expected} to resolve.
     *
     * @param expected the kind a bare name names, or {@code null} to read one as a method
     * @return the parsed selector
     * @throws SelectorSyntaxException if the text does not parse
     */
    private MemberSelector parseSourceForm(final @Nullable MemberKind expected) {
        final String head = readUntilAny("(:");
        if (head.isEmpty()) {
            throw fail(DiagnosticCode.SELECTOR_SYNTAX_ERROR, "expected a member name");
        }

        final Split split = splitOwnerAndName(head, 0);
        final TypePattern owner = split.owner();
        final String name = split.name();

        if (atEnd()) {
            // A bare name, with neither a parameter list nor a type. The shape is genuinely
            // ambiguous — it could name a field or a method of any signature — so the caller's
            // context decides. Where there is none, it reads as a method with an unconstrained
            // signature, which is the dominant use and what @Inject(method = "charge") means.
            return expected == MemberKind.FIELD
                    ? new FieldSelector(owner, name, null, MemberSelector.Form.SOURCE)
                    : new MethodSelector(owner, name, null, null, MemberSelector.Form.SOURCE);
        }

        if (peek() == '(') {
            expect('(');
            final List<TypePattern> parameters = new ArrayList<>();
            if (peek() != ')') {
                do {
                    parameters.add(parseSourceType());
                } while (consumeIf(','));
            }
            expect(')');
            TypePattern returnType = null;
            if (consumeIf(':')) {
                returnType = parseSourceType();
            }
            requireEnd();
            return new MethodSelector(owner, name, parameters, returnType, MemberSelector.Form.SOURCE);
        }

        expect(':');
        final TypePattern type = parseSourceType();
        requireEnd();
        return new FieldSelector(owner, name, type, MemberSelector.Form.SOURCE);
    }

    /**
     * A head split into the class it names and the member it names.
     *
     * @param owner the declaring class, or {@code null} when the head named none
     * @param name  the member name
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Split(@Nullable TypePattern owner, String name) {
    }

    /**
     * Splits a head into an owner and a member name.
     *
     * <p>The split is on the last dot, not the first: a member name cannot contain a dot, so everything before the
     * last one is the owner however many packages deep it goes. A leading {@code #} states that there is no owner
     * and takes the rest as the name, which is the same result a dotless head gives.
     *
     * <p>Both halves are validated here rather than at the end, so that the offset in the diagnostic points at the
     * half that is wrong.
     *
     * @param head      the text before the first {@code (} or {@code :}, already trimmed
     * @param headStart the index of {@code head} within the parsed text, for the diagnostic offset
     * @return the owner and the name
     * @throws SelectorSyntaxException with {@code AW1015} if either half is not a valid name
     */
    private Split splitOwnerAndName(@NotNull final String head, final int headStart) {
        if (head.startsWith("#")) {
            final String name = head.substring(1);
            if (!SelectorRendering.isValidMemberName(name)) {
                throw error(this.text, headStart + 1, DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                        "'#' must be followed by a member name, but found \"" + name + '"');
            }
            return new Split(null, name);
        }
        final int lastDot = head.lastIndexOf('.');
        if (lastDot < 0) {
            if (!SelectorRendering.isValidMemberName(head)) {
                throw error(this.text, headStart, DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                        '"' + head + "\" is not a valid member name");
            }
            return new Split(null, head);
        }
        final String ownerName = head.substring(0, lastDot);
        final String name = head.substring(lastDot + 1);
        if (!SelectorRendering.isValidOwnerName(ownerName)) {
            throw error(this.text, headStart, DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                    '"' + ownerName + "\" is not a valid owner name");
        }
        if (!SelectorRendering.isValidMemberName(name)) {
            throw error(this.text, headStart + lastDot + 1, DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                    '"' + name + "\" is not a valid member name");
        }
        return new Split(TypePattern.named(ownerName, 0), name);
    }

    /**
     * Parses one type in the source form.
     *
     * <p>Reads a wildcard, or a name followed by optional type arguments and optional array brackets. Spaces are
     * skipped before the type, between the name and its brackets, and after it, so that a signature pasted from
     * source needs no reformatting; they are not skipped anywhere else in the grammar.
     *
     * <p>A primitive keyword resolves to a {@link TypePattern.Exact} here, including its array dimensions. Every
     * other name becomes a {@link TypePattern.Named} and is not checked against any naming rule: whether it refers
     * to a class is decided where the selector is matched.
     *
     * @return the parsed pattern
     * @throws SelectorSyntaxException with {@code AW1015} if no type name is present or a {@code <} is unbalanced
     */
    private TypePattern parseSourceType() {
        skipSpaces();
        if (consumeIf('*')) {
            skipSpaces();
            return TypePattern.any();
        }
        final int start = this.position;
        while (!atEnd() && (Character.isJavaIdentifierPart(peek()) || peek() == '.')) {
            this.position++;
        }
        if (this.position == start) {
            throw fail(DiagnosticCode.SELECTOR_SYNTAX_ERROR, "expected a type name");
        }
        String name = this.text.substring(start, this.position);

        // Generic type arguments are accepted and ignored: selectors match erased signatures,
        // and a user pasting a signature from source should not have to strip them by hand.
        if (!atEnd() && peek() == '<') {
            int depth = 0;
            while (!atEnd()) {
                final char c = this.text.charAt(this.position++);
                if (c == '<') {
                    depth++;
                } else if (c == '>' && --depth == 0) {
                    break;
                }
            }
            if (depth != 0) {
                throw fail(DiagnosticCode.SELECTOR_SYNTAX_ERROR, "unbalanced '<': expected '>'");
            }
        }

        int arrayDepth = 0;
        while (true) {
            skipSpaces();
            if (!consumeIf('[')) {
                break;
            }
            expect(']');
            arrayDepth++;
        }
        skipSpaces();

        if (PRIMITIVES.contains(name)) {
            return TypePattern.of(classDescOf(name, arrayDepth));
        }
        return TypePattern.named(name, arrayDepth);
    }

    // ---------------------------------------------------------------------------------------
    // descriptor form
    // ---------------------------------------------------------------------------------------

    /**
     * Parses the {@value MemberSelector#DESCRIPTOR_PREFIX} form.
     *
     * <p>Wildcards are refused before anything else is read. The form promises exactly one member, and a text that
     * mixes the two conventions is a mistake worth naming rather than a pattern to honour.
     *
     * <p>A method and a field are told apart by an opening parenthesis anywhere after the prefix. Nothing here is
     * trimmed: the cursor starts immediately after the prefix and the rest is taken as written, because a
     * descriptor's characters are all significant.
     *
     * @return the parsed selector
     * @throws SelectorSyntaxException with {@code AW1018} or {@code AW1019} if the descriptor is not well formed
     */
    private MemberSelector parseDescriptorForm() {
        this.position = MemberSelector.DESCRIPTOR_PREFIX.length();
        final int bodyStart = this.position;

        if (this.text.indexOf('*', bodyStart) >= 0) {
            throw error(this.text, this.text.indexOf('*', bodyStart),
                    DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR,
                    "wildcards are not permitted in the descriptor form: it names exactly one "
                            + "member. Use the source form for pattern matching");
        }

        final int paren = this.text.indexOf('(', bodyStart);
        return paren >= 0
                ? parseDescriptorMethod(bodyStart, paren)
                : parseDescriptorField(bodyStart);
    }

    /**
     * Parses a method in the descriptor form.
     *
     * <p>The owner is whatever precedes the last dot before the parenthesis, as an internal name or an array
     * descriptor, and the name is whatever follows it. The name is not held to the source grammar's stricter rule
     * for an identifier: {@code lambda$process$0} and {@code access$000} are nameable here, and are nameable in the
     * source form too since {@code $} is a valid Java identifier character. What the relaxed rule actually admits
     * that the source grammar does not is a name containing a space or an unbalanced bracket, such as {@code a b}
     * or {@code a)b}; {@code <init>} and {@code <clinit>} are nameable in both forms, since the source grammar
     * accepts them explicitly.
     *
     * <p>A descriptor ending at its closing parenthesis is reported as {@code AW1019} rather than handed to the
     * JDK, so that the message names the missing return type instead of quoting a parse error.
     *
     * @param bodyStart the index just past the prefix
     * @param paren     the index of the opening parenthesis
     * @return the parsed method selector
     * @throws SelectorSyntaxException with {@code AW1018} or {@code AW1019} if the descriptor is not well formed
     */
    private MemberSelector parseDescriptorMethod(final int bodyStart, final int paren) {
        final String head = this.text.substring(bodyStart, paren);
        final int lastDot = head.lastIndexOf('.');
        final TypePattern owner = lastDot < 0
                ? null
                : TypePattern.of(internalNameToDesc(head.substring(0, lastDot), bodyStart));
        final String name = lastDot < 0 ? head : head.substring(lastDot + 1);
        if (name.isEmpty()) {
            throw error(this.text, bodyStart + lastDot + 1,
                    DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR, "expected a method name");
        }

        final String descriptor = this.text.substring(paren);
        if (descriptor.endsWith(")")) {
            throw error(this.text, this.text.length(),
                    DiagnosticCode.SELECTOR_DESCRIPTOR_MISSING_RETURN_TYPE,
                    "descriptor selectors must be exact: \"" + this.text
                            + "\" is missing the return type (use 'V' for void)");
        }
        final java.lang.constant.MethodTypeDesc type;
        try {
            type = java.lang.constant.MethodTypeDesc.ofDescriptor(descriptor);
        } catch (@NotNull final RuntimeException e) {
            throw error(this.text, paren, DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR,
                    "invalid method descriptor \"" + descriptor + "\": " + e.getMessage());
        }
        return new MethodSelector(owner, name,
                type.parameterList().stream().map(TypePattern::of).toList(),
                TypePattern.of(type.returnType()),
                MemberSelector.Form.DESCRIPTOR);
    }

    /**
     * Parses a field in the descriptor form.
     *
     * <p>The type follows the last colon in the whole text, which is why a field descriptor containing no colon of
     * its own is unambiguous even though the prefix ends in one: a colon at or before the body's start means no
     * type was written, and that is reported as {@code AW1018}.
     *
     * @param bodyStart the index just past the prefix
     * @return the parsed field selector
     * @throws SelectorSyntaxException with {@code AW1018} if the name or the descriptor is missing or malformed
     */
    private MemberSelector parseDescriptorField(final int bodyStart) {
        final int colon = this.text.lastIndexOf(':');
        if (colon < bodyStart) {
            throw error(this.text, this.text.length(), DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR,
                    "a descriptor field selector needs a type, as in \"desc:owner.name:I\"");
        }
        final String head = this.text.substring(bodyStart, colon);
        final int lastDot = head.lastIndexOf('.');
        final TypePattern owner = lastDot < 0
                ? null
                : TypePattern.of(internalNameToDesc(head.substring(0, lastDot), bodyStart));
        final String name = lastDot < 0 ? head : head.substring(lastDot + 1);
        if (name.isEmpty()) {
            throw error(this.text, bodyStart, DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR,
                    "expected a field name");
        }
        final String descriptor = this.text.substring(colon + 1);
        try {
            return new FieldSelector(owner, name,
                    TypePattern.of(ClassDesc.ofDescriptor(descriptor)),
                    MemberSelector.Form.DESCRIPTOR);
        } catch (@NotNull final RuntimeException e) {
            throw error(this.text, colon + 1, DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR,
                    "invalid field descriptor \"" + descriptor + "\": " + e.getMessage());
        }
    }

    /**
     * Turns a descriptor-form owner into a {@link ClassDesc}.
     *
     * <p>An owner in a class file is an internal name for a class and a full descriptor for an array, so a name
     * beginning with {@code [} is taken as it stands and anything else is wrapped in {@code L} and {@code ;}.
     *
     * @param internalName the owner as written
     * @param offset       the index to report a failure at
     * @return the owner as a {@link ClassDesc}
     * @throws SelectorSyntaxException with {@code AW1018} if the name is not a class descriptor
     */
    private ClassDesc internalNameToDesc(@NotNull final String internalName, final int offset) {
        try {
            return internalName.startsWith("[")
                    ? ClassDesc.ofDescriptor(internalName)
                    : ClassDesc.ofDescriptor('L' + internalName + ';');
        } catch (@NotNull final RuntimeException e) {
            throw error(this.text, offset, DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR,
                    "invalid internal name \"" + internalName + "\": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // shape detection — used only to suggest the desc: prefix after a failed source parse
    // ---------------------------------------------------------------------------------------

    /**
     * Reports whether a text that failed the source grammar reads as a JVM descriptor.
     *
     * <p>Used for the {@code AW1017} suggestion and for nothing else. A text with a parenthesis is offered to
     * {@link java.lang.constant.MethodTypeDesc}, and one with a colon has what follows the last colon offered to
     * {@link ClassDesc}; the JDK accepting it is the answer.
     *
     * @param text the body of a selector whose source-form parse has already failed
     * @return whether the text parses as a descriptor
     */
    private static boolean looksLikeDescriptor(@NotNull final String text) {
        // Detection must never throw: it runs while another exception is already in flight, and
        // letting a second one escape would replace an accurate diagnostic with a JDK internal.
        // The ofDescriptor methods are not documented to restrict themselves to
        // IllegalArgumentException — MethodTypeDesc.ofDescriptor("(") throws
        // StringIndexOutOfBoundsException — so every RuntimeException is treated as "no".
        final int paren = text.indexOf('(');
        if (paren >= 0) {
            try {
                java.lang.constant.MethodTypeDesc.ofDescriptor(text.substring(paren));
                return true;
            } catch (@NotNull final RuntimeException e) {
                return false;
            }
        }
        final int colon = text.lastIndexOf(':');
        if (colon < 0) {
            return false;
        }
        try {
            ClassDesc.ofDescriptor(text.substring(colon + 1));
            return true;
        } catch (@NotNull final RuntimeException e) {
            return false;
        }
    }

    /**
     * Resolves a source-form type name to a {@link ClassDesc}.
     *
     * <p>The nine primitive keywords are mapped to their own constants; anything else goes through
     * {@link ClassDesc#of(String)}, which is why this method also serves {@code class:} constants, where a name
     * that is not a class is what the caller catches.
     *
     * @param name       the type name, a primitive keyword or a binary class name
     * @param arrayDepth the number of array dimensions to apply
     * @return the type
     * @throws IllegalArgumentException if {@code name} is not a primitive keyword and not a valid binary name, or
     *                                  if {@code name} is {@code "void"} and {@code arrayDepth} is greater than
     *                                  zero, since {@link ClassDesc#arrayType()} refuses an array of {@code void}
     */
    private static ClassDesc classDescOf(@NotNull final String name, final int arrayDepth) {
        ClassDesc desc = switch (name) {
            case "boolean" -> java.lang.constant.ConstantDescs.CD_boolean;
            case "byte" -> java.lang.constant.ConstantDescs.CD_byte;
            case "char" -> java.lang.constant.ConstantDescs.CD_char;
            case "short" -> java.lang.constant.ConstantDescs.CD_short;
            case "int" -> java.lang.constant.ConstantDescs.CD_int;
            case "long" -> java.lang.constant.ConstantDescs.CD_long;
            case "float" -> java.lang.constant.ConstantDescs.CD_float;
            case "double" -> java.lang.constant.ConstantDescs.CD_double;
            case "void" -> java.lang.constant.ConstantDescs.CD_void;
            default -> ClassDesc.of(name);
        };
        for (int i = 0; i < arrayDepth; i++) {
            desc = desc.arrayType();
        }
        return desc;
    }

    // ---------------------------------------------------------------------------------------
    // cursor
    // ---------------------------------------------------------------------------------------

    /**
     * Reports whether the cursor has passed the last character.
     *
     * @return whether nothing is left to read
     */
    private boolean atEnd() {
        return this.position >= this.text.length();
    }

    /**
     * Returns the character at the cursor without consuming it.
     *
     * <p>Answers {@code '\0'} at the end of the text, so a caller can compare against an expected character
     * without checking {@link #atEnd()} first.
     *
     * @return the current character, or {@code '\0'} at the end of the text
     */
    private char peek() {
        return atEnd() ? '\0' : this.text.charAt(this.position);
    }

    /**
     * Advances the cursor past any spaces.
     *
     * <p>Only the space character. A selector arrives from an annotation element on one line, and treating a tab
     * or a newline as whitespace would accept text no source file produces.
     */
    private void skipSpaces() {
        while (!atEnd() && this.text.charAt(this.position) == ' ') {
            this.position++;
        }
    }

    /**
     * Consumes the given character if the cursor is on it.
     *
     * <p>Does not skip spaces first, which is what makes the return-type colon have to follow its closing
     * parenthesis immediately.
     *
     * @param expected the character to consume
     * @return whether it was there and has been consumed
     */
    private boolean consumeIf(final char expected) {
        if (!atEnd() && this.text.charAt(this.position) == expected) {
            this.position++;
            return true;
        }
        return false;
    }

    /**
     * Consumes the given character, failing if it is not there.
     *
     * <p>Skips spaces first, so a space may precede the parentheses, the closing bracket of an array and the colon
     * that introduces a field's type.
     *
     * @param expected the character required at this position
     * @throws SelectorSyntaxException with {@code AW1015} if the character is not there
     */
    private void expect(final char expected) {
        skipSpaces();
        if (!consumeIf(expected)) {
            throw fail(DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                    atEnd() ? "expected '" + expected + "' but the selector ended"
                            : "expected '" + expected + "' but found '" + peek() + '\'');
        }
    }

    /**
     * Requires that nothing but spaces is left.
     *
     * <p>Trailing text is reported rather than ignored: a selector that silently dropped what it did not
     * understand would bind to a member the author did not write.
     *
     * @throws SelectorSyntaxException with {@code AW1015} if anything is left
     */
    private void requireEnd() {
        skipSpaces();
        if (!atEnd()) {
            throw fail(DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                    "unexpected trailing text: \"" + this.text.substring(this.position) + '"');
        }
    }

    /**
     * Reads up to the first character of the given set, and trims what it read.
     *
     * <p>Stops without consuming the terminator, so the caller decides what the terminator means. The trim is what
     * allows a space between a member name and its parameter list.
     *
     * @param stops the characters to stop at
     * @return the text read, trimmed, and empty when the cursor is already on a terminator
     */
    private String readUntilAny(@NotNull final String stops) {
        final int start = this.position;
        while (!atEnd() && stops.indexOf(this.text.charAt(this.position)) < 0) {
            this.position++;
        }
        return this.text.substring(start, this.position).trim();
    }

    /**
     * Builds a failure at the cursor.
     *
     * @param code    the diagnostic code
     * @param message what went wrong
     * @return the exception to throw
     */
    private SelectorSyntaxException fail(@NotNull final DiagnosticCode code, @NotNull final String message) {
        return error(this.text, this.position, code, message);
    }

    /**
     * Builds a failure with no suggestion.
     *
     * @param text    the text the offset indexes
     * @param offset  the index parsing stopped at
     * @param code    the diagnostic code
     * @param message what went wrong
     * @return the exception to throw
     */
    private static SelectorSyntaxException error(@NotNull final String text, final int offset,
                                                 @NotNull final DiagnosticCode code, @NotNull final String message) {
        return error(text, offset, code, message, null);
    }

    /**
     * Builds a failure, optionally carrying a corrected spelling.
     *
     * <p>Every diagnostic this class reports is built here, so the offset and the text always belong to the same
     * string and a caret rendered from them always lines up.
     *
     * @param text       the text the offset indexes
     * @param offset     the index parsing stopped at
     * @param code       the diagnostic code
     * @param message    what went wrong
     * @param suggestion a corrected spelling of the whole selector, or {@code null} when none can be offered
     * @return the exception to throw
     */
    private static SelectorSyntaxException error(@NotNull final String text, final int offset,
                                                 @NotNull final DiagnosticCode code, @NotNull final String message,
                                                 final @Nullable String suggestion) {
        return new SelectorSyntaxException(code, text, offset, message, suggestion);
    }
}
