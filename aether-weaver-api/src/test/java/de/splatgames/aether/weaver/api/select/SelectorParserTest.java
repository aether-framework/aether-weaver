package de.splatgames.aether.weaver.api.select;

import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The selector grammar as executable specification: what {@link MemberSelector#parse(String)} accepts,
 * what it refuses, and which diagnostic each refusal carries.
 *
 * <p>A selector is the text a user writes in {@code @Inject(method = ...)} and in the {@code target} of
 * an {@code @At}, so every acceptance here is a spelling the project has promised to keep reading and
 * every rejection is a spelling it has promised not to guess at. The second half is the load-bearing
 * one: a grammar that quietly accepts more than it documents binds weaves to members nobody named, and
 * the binding is invisible until the wrong method is rewritten.
 *
 * <h2>What the cases can and cannot prove</h2>
 *
 * <p>Parsing is a pure function of the text. No target class is present, nothing is resolved against a
 * classpath, and no diagnostic is reported anywhere: a refusal is a thrown
 * {@link SelectorSyntaxException} carrying a {@link DiagnosticCode}, which a caller turns into a report
 * of its own. A case that asserts a code therefore pins what the exception carries, not what any build
 * prints.
 *
 * <p>Equality is on the parts rather than the spelling, and {@link MemberSelector#form()} takes no part
 * in it, which is what lets an equivalence case compare two spellings of one member directly.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class SelectorParserTest {

    /**
     * The spelling a user writes by hand: names as they appear in source, with an optional owner and an
     * optional signature.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("source form")
    class SourceForm {

        /**
         * Asserts that {@code charge} parses as a {@link MethodSelector} whose parameter list is absent.
         *
         * <p>The ambiguous shape is a bare name: no parameter list and no type, so nothing in the text says
         * whether a method or a field is meant. Without a hint it is a method, which is what
         * {@code method = "charge"} has to mean on an injection.
         *
         * <p>An absent parameter list is an unconstrained signature, distinct from an empty one.
         */
        @Test
        @DisplayName("a bare name reads as a method of any signature when no context is given")
        void bareNameDefaultsToMethod() {
            final MemberSelector selector = MemberSelector.parse("charge");
            assertThat(selector).isInstanceOf(MethodSelector.class);
            final MethodSelector method = (MethodSelector) selector;
            assertThat(method.name()).isEqualTo("charge");
            assertThat(method.parameters()).as("an unconstrained signature").isEmpty();
        }

        /**
         * Asserts that a bare name parses as a {@link FieldSelector} when {@link MemberKind#FIELD} is passed.
         *
         * <p>The hint resolves the one ambiguous shape and nothing else, which is how a field-shaped
         * declaration reads {@code ledger} as a field without the user having to write a type.
         */
        @Test
        @DisplayName("a bare name reads as a field when the context says so")
        void bareNameHonoursContext() {
            final MemberSelector selector =
                    MemberSelector.parse("ledger", MemberKind.FIELD);
            assertThat(selector).isInstanceOf(FieldSelector.class);
            assertThat(((FieldSelector) selector).type()).isEmpty();
        }

        /**
         * Asserts that a parameter list makes a text a method and a type makes it a field, whatever the hint
         * says.
         *
         * <p>The hint is a tie-breaker, not an override. Letting it win over the shape would turn a mistyped
         * hint at a call site into a selector that binds to a different member than the text names.
         */
        @Test
        @DisplayName("an unambiguous shape ignores the context hint")
        void unambiguousShapeIgnoresContext() {
            assertThat(MemberSelector.parse("charge(BigDecimal)", MemberKind.FIELD))
                    .isInstanceOf(MethodSelector.class);
            assertThat(MemberSelector.parse("ledger:com.acme.Ledger", MemberKind.METHOD))
                    .isInstanceOf(FieldSelector.class);
        }

        /**
         * Asserts that a leading {@code #} yields a selector with no owner.
         *
         * <p>The {@code #} refuses an owner rather than introducing one, so {@code #state} and {@code state}
         * parse to the same selector and the marker is a way of saying out loud that the member belongs to
         * the weave's own target. A member name cannot contain a dot, which is why the marker is never needed
         * to separate anything.
         */
        @Test
        @DisplayName("'#' names the target class explicitly")
        void hashDenotesTheTarget() {
            final FieldSelector field =
                    (FieldSelector) MemberSelector.parse("#state", MemberKind.FIELD);
            assertThat(field.name()).isEqualTo("state");
            assertThat(field.owner()).isEmpty();
        }

        /**
         * Asserts that {@code close()} carries a parameter list that is present and empty.
         *
         * <p>Present-and-empty and absent are different constraints on the same field: the first matches a
         * no-argument method alone, the second matches any signature. Collapsing them would make
         * {@code close()} match every overload of {@code close}, and a declaration that named the
         * no-argument one would be reported as ambiguous instead of binding.
         */
        @Test
        @DisplayName("an empty parameter list means exactly zero parameters")
        void emptyParameterListIsNotAbsent() {
            final MethodSelector method = (MethodSelector) MemberSelector.parse("close()");
            assertThat(method.parameters()).isPresent();
            assertThat(method.parameters().orElseThrow()).isEmpty();
        }

        /**
         * Asserts that a name written without a parameter list leaves the signature unconstrained.
         *
         * <p>The other half of the pair above, written with the {@code #} marker to show that the two are
         * independent of each other.
         */
        @Test
        @DisplayName("an omitted parameter list matches any signature")
        void omittedParameterListMatchesAnySignature() {
            final MethodSelector any = (MethodSelector) MemberSelector.parse("#charge");
            assertThat(any.parameters()).isEmpty();
        }

        /**
         * Asserts that one parameter list holds a simple name with array depth, a primitive, a fully
         * qualified name, a wildcard and a two-dimensional array.
         *
         * <p>Each parameter is compared against the {@link TypePattern} it should have produced rather than
         * against its rendering, which pins the distinction the patterns draw: {@code String} and
         * {@code java.util.List} stay unresolved names to be matched against the target's own imports, while
         * {@code int} and {@code byte[][]} are resolved outright. A parser that resolved a simple name would
         * bind it to whatever class of that name it found first.
         */
        @Test
        @DisplayName("parameters may be named, primitive, arrays, or wildcards")
        void parameterShapes() {
            final MethodSelector method = (MethodSelector) MemberSelector.parse(
                    "process(String[], int, java.util.List, *, byte[][])");
            assertThat(method.parameters().orElseThrow()).satisfiesExactly(
                    p -> assertThat(p).isEqualTo(TypePattern.named("String", 1)),
                    p -> assertThat(p).isEqualTo(TypePattern.of(ConstantDescs.CD_int)),
                    p -> assertThat(p).isEqualTo(TypePattern.named("java.util.List", 0)),
                    p -> assertThat(p).isEqualTo(TypePattern.any()),
                    p -> assertThat(p).isEqualTo(TypePattern.of(ConstantDescs.CD_byte.arrayType().arrayType())));
        }

        /**
         * Asserts that two selectors differing only in return type are unequal and keep the type each was
         * written with.
         *
         * <p>The return type is not part of a Java overload, but it is part of a method descriptor, so a
         * selector needs it to tell apart two members that a source-level reader would call the same method.
         * Dropping it from equality would make one declaration match both.
         */
        @Test
        @DisplayName("a return type disambiguates an overload pair")
        void returnTypeDisambiguates() {
            final MethodSelector asString = (MethodSelector) MemberSelector.parse("get():String");
            final MethodSelector asInt = (MethodSelector) MemberSelector.parse("get():int");
            assertThat(asString).isNotEqualTo(asInt);
            assertThat(asString.returnType()).contains(TypePattern.named("String", 0));
            assertThat(asInt.returnType()).contains(TypePattern.of(ConstantDescs.CD_int));
        }

        /**
         * Asserts that an owner may be written as a simple name or fully qualified, and that the split
         * between owner and member name falls at the last dot.
         *
         * <p>Both forms produce an unresolved {@link TypePattern}: a source-form owner is a name to be
         * resolved later, whichever way it is spelled, which is why neither of these is fully qualified in
         * the sense {@link MemberSelector#isFullyQualified()} means.
         *
         * @param text  the selector to parse
         * @param owner the owner the parse is expected to yield
         * @param name  the member name the parse is expected to yield
         */
        @ParameterizedTest
        @DisplayName("an owner may be a simple name or fully qualified")
        @CsvSource({
                "Gateway.send(Payment),        Gateway,        send",
                "com.acme.Gateway.send(P),     com.acme.Gateway, send",
        })
        void ownerForms(final String text, final String owner, final String name) {
            final MethodSelector method = (MethodSelector) MemberSelector.parse(text);
            assertThat(method.owner()).contains(TypePattern.named(owner, 0));
            assertThat(method.name()).isEqualTo(name);
        }

        /**
         * Asserts that {@code <init>} and {@code <clinit>} parse and report themselves as initialisers.
         *
         * <p>Neither is a Java identifier, so a grammar built only from identifiers would refuse both, and a
         * weave could not reach a constructor or a static initialiser from the source form at all.
         *
         * @param text the selector to parse
         */
        @ParameterizedTest
        @DisplayName("constructors and the static initialiser parse")
        @ValueSource(strings = {"<init>()", "<init>(String, int)", "<clinit>()"})
        void initialisers(final String text) {
            final MethodSelector method = (MethodSelector) MemberSelector.parse(text);
            assertThat(method.isInitialiser()).isTrue();
        }

        /**
         * Asserts that a colon and a type after a bare name make a field selector carrying that type.
         *
         * <p>The type is the field's own, not a signature, and it is what distinguishes this shape from a
         * method's return type: the colon follows a name with no parameter list.
         */
        @Test
        @DisplayName("a field selector may carry a type")
        void fieldWithType() {
            final FieldSelector field =
                    (FieldSelector) MemberSelector.parse("ledger:com.acme.Ledger");
            assertThat(field.name()).isEqualTo("ledger");
            assertThat(field.type()).contains(TypePattern.named("com.acme.Ledger", 0));
        }

        /**
         * Asserts that the {@code src:} prefix parses to the same selector as no prefix at all.
         *
         * <p>The prefix exists to force the source form on a text that would otherwise be read as something
         * else -- a member named after a constant keyword, or one named {@code desc}. It is never rendered
         * back, so writing it is a decision about the input alone.
         */
        @Test
        @DisplayName("the src: prefix is accepted and equivalent to no prefix")
        void explicitSourcePrefix() {
            assertThat(MemberSelector.parse("src:charge(BigDecimal)"))
                    .isEqualTo(MemberSelector.parse("charge(BigDecimal)"));
        }

        /**
         * Asserts that a parameterised type parses to the same selector as its erasure.
         *
         * <p>Weaving is on descriptors, where no type argument survives, so a selector that kept one would
         * name something that never appears in a class file. Accepting the spelling means a user can paste a
         * signature out of source without editing it.
         *
         * <p>Parsing is silent about the discarded arguments; the annotation processor is what notes them, as
         * {@code AW1016}.
         */
        @Test
        @DisplayName("generic type arguments are accepted and erased")
        void genericsAreErased() {
            assertThat(MemberSelector.parse("process(java.util.List<String>)"))
                    .isEqualTo(MemberSelector.parse("process(java.util.List)"));
        }

        /**
         * Asserts that a name of {@code *} parses and does not make the selector fully qualified.
         *
         * <p>A wildcard is the whole name or nothing: the grammar has no partial wildcard, so {@code get*} is
         * a syntax error rather than a prefix match. The second assertion is the consequence that matters --
         * a selector holding a wildcard names a shape rather than a member, so it can never canonicalise and
         * can never be used as an identity.
         */
        @Test
        @DisplayName("a wildcard name matches every member")
        void wildcardName() {
            final MethodSelector method = (MethodSelector) MemberSelector.parse("*(*)");
            assertThat(method.name()).isEqualTo("*");
            assertThat(method.isFullyQualified()).isFalse();
        }
    }

    /**
     * The exact spelling: internal names and JVM descriptors, behind the {@code desc:} prefix.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("descriptor form")
    class DescriptorForm {

        /**
         * Asserts that a method descriptor with an owner parses into resolved patterns and reports itself as
         * fully qualified.
         *
         * <p>Every part of the text is exact, so nothing is left to resolve: the owner becomes a resolved
         * {@link java.lang.constant.ClassDesc} rather than a name, and the selector names one member. That is
         * the property the rest of the system builds on, since it is what makes a canonical form available.
         */
        @Test
        @DisplayName("a method descriptor parses into resolved patterns")
        void methodDescriptor() {
            final MethodSelector method = (MethodSelector)
                    MemberSelector.parse("desc:com/acme/Gateway.send(Lcom/acme/Payment;)V");
            assertThat(method.form()).isEqualTo(MemberSelector.Form.DESCRIPTOR);
            assertThat(method.name()).isEqualTo("send");
            assertThat(method.owner()).contains(TypePattern.of(ClassDesc.of("com.acme.Gateway")));
            assertThat(method.returnType()).contains(TypePattern.of(ConstantDescs.CD_void));
            assertThat(method.isFullyQualified()).isTrue();
        }

        /**
         * Asserts the source spelling each of the eight primitive descriptors and {@code V} renders to.
         *
         * <p>The mapping is a table with no structure to fall back on, so a wrong or missing row is a silent
         * misreading rather than a parse failure -- reading {@code J} as {@code int} would produce a selector
         * that parses, renders and matches the wrong member.
         *
         * <p>The assertion is on {@link TypePattern#renderSource()} of the return type. Nothing here re-parses
         * the rendering.
         *
         * @param descriptor the one-character descriptor to parse as a return type
         * @param sourceName the source spelling it should render as
         */
        @ParameterizedTest
        @DisplayName("every primitive descriptor round-trips")
        @CsvSource({"Z,boolean", "B,byte", "C,char", "S,short", "I,int",
                    "J,long", "F,float", "D,double", "V,void"})
        void primitives(final String descriptor, final String sourceName) {
            final MethodSelector method = (MethodSelector) MemberSelector.parse("desc:m()" + descriptor);
            assertThat(method.returnType().orElseThrow().renderSource()).isEqualTo(sourceName);
        }

        /**
         * Asserts that a descriptor holding a nested array, a reference type and a primitive splits into
         * three parameters, and that an array return type parses.
         *
         * <p>A descriptor's parameter list has no separators, so the split is driven entirely by the shape of
         * each type: the count is the assertion that a reference type was read to its semicolon and that the
         * brackets of an array were attached to the type that follows them rather than counted as types.
         */
        @Test
        @DisplayName("nested arrays and reference types parse")
        void arraysAndReferences() {
            final MethodSelector method = (MethodSelector)
                    MemberSelector.parse("desc:m([[BLjava/lang/String;I)[Ljava/lang/Object;");
            assertThat(method.parameters().orElseThrow()).hasSize(3);
            assertThat(method.returnType())
                    .contains(TypePattern.of(ClassDesc.of("java.lang.Object").arrayType()));
        }

        /**
         * Asserts that a lambda body, a synthetic accessor and both initialisers can be named in the
         * descriptor form.
         *
         * <p>The case pins that the descriptor form imposes no identifier rule on the name.
         *
         * <p>Each input omits the owner, so none of them is fully qualified; that assertion is about the
         * missing owner and not about the name.
         *
         * @param text the selector to parse
         */
        @ParameterizedTest
        @DisplayName("synthetic and initialiser names are nameable, which the source form cannot do")
        @ValueSource(strings = {
                "desc:lambda$process$0(Ljava/lang/String;)Z",
                "desc:access$000(Lcom/acme/Outer;)I",
                "desc:<init>(Ljava/lang/String;I)V",
                "desc:<clinit>()V"})
        void syntheticNames(final String text) {
            final MethodSelector method = (MethodSelector) MemberSelector.parse(text);
            assertThat(method.isFullyQualified()).isFalse();   // no owner given
            assertThat(method.name()).isNotBlank();
        }

        /**
         * Asserts that a field descriptor with an owner parses into a resolved type and is fully qualified.
         *
         * <p>{@code desc:com/acme/Session.state:I} is the spelling that reaches a field of primitive type
         * unambiguously, which the source form cannot do: {@code state:I} names a field of a class called
         * {@code I}.
         */
        @Test
        @DisplayName("a field descriptor parses")
        void fieldDescriptor() {
            final FieldSelector field = (FieldSelector)
                    MemberSelector.parse("desc:com/acme/Session.state:I");
            assertThat(field.name()).isEqualTo("state");
            assertThat(field.type()).contains(TypePattern.of(ConstantDescs.CD_int));
            assertThat(field.isFullyQualified()).isTrue();
        }

        /**
         * Asserts that a descriptor selector may omit its owner.
         *
         * <p>An ownerless descriptor is exact about the signature and silent about where the member lives,
         * which is what a declaration wants when the owner is the weave's target and is already known.
         */
        @Test
        @DisplayName("the owner may be omitted")
        void ownerMayBeOmitted() {
            final MethodSelector method = (MethodSelector) MemberSelector.parse("desc:charge(D)V");
            assertThat(method.owner()).isEmpty();
            assertThat(method.name()).isEqualTo("charge");
        }
    }

    /**
     * What the descriptor form refuses, and with which code.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("descriptor form rejections")
    class DescriptorRejections {

        /**
         * Asserts that a method descriptor without a return type is {@code AW1019}, with a message naming
         * what is missing.
         *
         * <p>A missing return type is the one omission that could plausibly be read as a wildcard, and it is
         * a common one because a signature copied out of a stack trace or an IDE often ends at the closing
         * parenthesis. Treating it as unconstrained would make an exact form partly inexact; the separate
         * code is what lets the message say to append the return type rather than to fix the syntax.
         */
        @Test
        @DisplayName("a missing return type is AW1019, not a silent match-anything")
        void missingReturnType() {
            final SelectorSyntaxException e = catchThrowableOfType(
                    SelectorSyntaxException.class,
                    () -> MemberSelector.parse("desc:charge(Ljava/math/BigDecimal;)"));
            assertThat(e.code()).isEqualTo(DiagnosticCode.SELECTOR_DESCRIPTOR_MISSING_RETURN_TYPE);
            assertThat(e).hasMessageContaining("missing the return type");
        }

        /**
         * Asserts that a wildcard anywhere after the {@code desc:} prefix is {@code AW1018}.
         *
         * <p>The form leaves no type unresolved, so a wildcard in it has no meaning to give. Accepting one
         * would produce a selector that claims the exactness of the descriptor form and matches a set of
         * members, which is the state {@link MemberSelector#canonical()} exists to rule out.
         */
        @Test
        @DisplayName("wildcards are rejected because the form is exact by definition")
        void wildcardsRejected() {
            final SelectorSyntaxException e = catchThrowableOfType(
                    SelectorSyntaxException.class,
                    () -> MemberSelector.parse("desc:charge(*)V"));
            assertThat(e.code()).isEqualTo(DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR);
            assertThat(e).hasMessageContaining("wildcards are not permitted");
        }

        /**
         * Asserts that three malformed descriptors are {@code AW1018}: a reference type with no terminating
         * semicolon, an unknown type character in a method descriptor, and the same in a field descriptor.
         *
         * <p>All three are the kind of damage a hand edit does to a pasted descriptor, and all three have to
         * fail rather than parse into something: {@code Q} is not a descriptor character, and an unterminated
         * reference type would otherwise swallow the parameters that follow it.
         *
         * @param text the selector to parse
         */
        @ParameterizedTest
        @DisplayName("a malformed descriptor is AW1018")
        @ValueSource(strings = {
                "desc:charge(Ljava/math/BigDecimal)V",
                "desc:charge(Q)V",
                "desc:state:Q"})
        void malformedDescriptor(final String text) {
            final SelectorSyntaxException e = catchThrowableOfType(
                    SelectorSyntaxException.class, () -> MemberSelector.parse(text));
            assertThat(e.code()).isEqualTo(DiagnosticCode.SELECTOR_MALFORMED_DESCRIPTOR);
        }
    }

    /**
     * How the parser decides which spelling it is looking at, and what it does when the text was written
     * for the other one.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("prefix detection")
    class PrefixDetection {

        /**
         * Asserts that a descriptor written without the {@code desc:} prefix is {@code AW1017} and carries a
         * suggestion that parses.
         *
         * <p>Pasting a descriptor into a declaration that expects the source form is the most common way to
         * get a selector wrong, and the text is not nonsense: it parses as a source selector naming
         * parameters of oddly spelled types. The parser reports the mistake rather than binding to whatever
         * that would match, and the suggestion is the same text with the prefix in front.
         *
         * <p>The final assertion re-parses the suggestion, which pins that the advice is actionable for these
         * two inputs. A suggestion is not guaranteed to parse in general: a partial wildcard is refused by
         * both forms, so the suggested text fails as {@code AW1018}.
         *
         * @param pasted the descriptor written without its prefix
         */
        @ParameterizedTest
        @DisplayName("a descriptor pasted without the prefix is AW1017 with a corrected suggestion")
        @ValueSource(strings = {
                "charge(Ljava/math/BigDecimal;)V",
                "process([Ljava/lang/String;)I"})
        void suggestsThePrefix(final String pasted) {
            final SelectorSyntaxException e = catchThrowableOfType(
                    SelectorSyntaxException.class, () -> MemberSelector.parse(pasted));
            assertThat(e.code()).isEqualTo(DiagnosticCode.SELECTOR_MISSING_DESC_PREFIX);
            assertThat(e.suggestion()).contains("desc:" + pasted);
            assertThat(MemberSelector.parse(e.suggestion().orElseThrow())).isNotNull();
        }

        /**
         * Asserts that {@code foo(I)} keeps its parameter as an unresolved name rather than reading it as
         * {@code int}.
         *
         * <p>The form is decided by the prefix and never inferred from the content. {@code I} is a legal
         * class name in the default package, so a parser that guessed would bind a weave to a member that
         * nobody named, with no diagnostic anywhere: the selector parses either way and the difference only
         * shows up in which method is rewritten.
         */
        @Test
        @DisplayName("the form is never guessed: foo(I) stays a source selector")
        void formIsNeverGuessed() {
            // 'I' is a legal (if unusual) class name in the default package. Silently reading
            // this as 'int' would be a wrong binding with no diagnostic.
            final MethodSelector method = (MethodSelector) MemberSelector.parse("foo(I)");
            assertThat(method.parameters().orElseThrow())
                    .containsExactly(TypePattern.named("I", 0));
        }
    }

    /**
     * The constant forms, which name a value in a target's body rather than a member.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("constants")
    class Constants {

        /**
         * Asserts that all seven {@link ConstantSelector.Kind} constants parse, and that each carries the
         * value it names.
         *
         * <p>The values are compared boxed, which is the assertion that matters for the numeric kinds:
         * {@code int:42} yields an {@link Integer} and {@code long:7} a {@link Long} from text that differs
         * only in the keyword. A kind that produced the wrong box would match a constant of the wrong width
         * in the target's constant pool.
         *
         * <p>{@code class:} yields a {@link java.lang.constant.ClassDesc} rather than a loaded class, so
         * naming a constant never loads anything.
         */
        @Test
        @DisplayName("all seven kinds parse")
        void allKinds() {
            assertThat(((ConstantSelector) MemberSelector.parse("null")).kind())
                    .isEqualTo(ConstantSelector.Kind.NULL);
            assertThat(((ConstantSelector) MemberSelector.parse("int:42")).value()).contains(42);
            assertThat(((ConstantSelector) MemberSelector.parse("long:7")).value()).contains(7L);
            assertThat(((ConstantSelector) MemberSelector.parse("float:1.5")).value()).contains(1.5f);
            assertThat(((ConstantSelector) MemberSelector.parse("double:1.0")).value()).contains(1.0d);
            assertThat(((ConstantSelector) MemberSelector.parse("string:\"retry\"")).value())
                    .contains("retry");
            assertThat(((ConstantSelector) MemberSelector.parse("class:java.util.List")).value())
                    .contains(ClassDesc.of("java.util.List"));
        }

        /**
         * Asserts that an escaped quotation mark inside a string constant is unescaped, and that the
         * selector's own rendering parses back to it.
         *
         * <p>The value is what gets compared against a constant in the target, so it has to be the text the
         * user meant rather than the text they had to write. The round trip is the other half: escaping on
         * the way out has to undo the unescaping on the way in, or a selector stops naming its own constant
         * once it has been rendered.
         */
        @Test
        @DisplayName("escapes inside a string literal are unescaped")
        void stringEscapes() {
            final ConstantSelector selector =
                    (ConstantSelector) MemberSelector.parse("string:\"a\\\"b\"");
            assertThat(selector.value()).contains("a\"b");
            assertThat(MemberSelector.parse(selector.render(MemberSelector.Form.SOURCE)))
                    .isEqualTo(selector);
        }

        /**
         * Asserts that {@code string:} followed by an unquoted type name is a field selector.
         *
         * <p>A constant keyword is recognised only where the text after it has the shape that keyword
         * introduces. {@code string} is not a Java keyword, so a field may be named that, and a parser that
         * claimed the prefix unconditionally would make such a field unnameable.
         */
        @Test
        @DisplayName("an unquoted 'string:' is a field, not a constant")
        void unquotedStringIsAField() {
            // 'string' is not a Java keyword, so a field may legitimately be named that.
            assertThat(MemberSelector.parse("string:java.lang.String"))
                    .isInstanceOf(FieldSelector.class);
        }

        /**
         * Asserts that {@code class:} accepts a field descriptor as well as a source type name, and that the
         * two parse equal.
         *
         * <p>A class constant reaches a selector from two directions -- typed by a user, or copied out of a
         * constant pool -- and the two spellings have to be one selector so that a weave written either way
         * matches the same instruction.
         */
        @Test
        @DisplayName("class: accepts the descriptor spelling too")
        void classAcceptsDescriptor() {
            assertThat(MemberSelector.parse("class:desc:Ljava/util/List;"))
                    .isEqualTo(MemberSelector.parse("class:java.util.List"));
        }
    }

    /**
     * What a rejection looks like from the caller's side.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("syntax errors carry an accurate offset")
    class SyntaxErrors {

        /**
         * Asserts that eighteen malformed texts are refused as a {@link SelectorSyntaxException}.
         *
         * <p>They are near-misses rather than nonsense: empty and blank text, an owner marker with nothing
         * after it, parentheses that are unbalanced or in the wrong place, an empty or trailing parameter,
         * dots in positions no name can occupy, a colon with one side missing, an unclosed type argument,
         * trailing text after a complete selector, and a {@code desc:} prefix with too little behind it.
         * Each is text a user could write, and each has an interpretation a lenient parser could reach for.
         *
         * <p>The assertion is on the exception type alone. Which code an input carries is not pinned here.
         *
         * @param text the selector to parse
         */
        @ParameterizedTest
        @DisplayName("malformed inputs are rejected")
        @ValueSource(strings = {
                "", "   ", "#", "charge(", "charge(BigDecimal", "charge)", "charge(,)",
                "charge(BigDecimal,)", ".charge()", "charge().", "charge():", "a..b()",
                "charge(BigDecimal) extra", "charge(List<String)", "desc:", "desc:charge",
                "charge(:)", ":int"})
        void malformedInputsAreRejected(final String text) {
            assertThatThrownBy(() -> MemberSelector.parse(text))
                    .isInstanceOf(SelectorSyntaxException.class);
        }

        /**
         * Asserts the offset a truncated selector reports, and that the exception can render a caret.
         *
         * <p>The offset for this input is the length of the text: parsing stopped because the input ran out,
         * so the position it names is one past the last character rather than a character in it. The caret
         * rendering is what turns that number into something a user reads in a build log without counting.
         */
        @Test
        @DisplayName("the offset points at the offending character")
        void offsetIsAccurate() {
            final SelectorSyntaxException e = catchThrowableOfType(
                    SelectorSyntaxException.class, () -> MemberSelector.parse("charge(BigDecimal"));
            assertThat(e.offset()).isEqualTo("charge(BigDecimal".length());
            assertThat(e.formatWithCaret()).contains("^");
        }

        /**
         * Asserts that text after a complete selector is reported rather than ignored.
         *
         * <p>A parser that stopped at the first complete parse would accept a selector with a typo after it
         * and bind to the part it understood, which is a silent mismatch between what the declaration says
         * and what it does.
         */
        @Test
        @DisplayName("trailing text is reported rather than ignored")
        void trailingTextIsReported() {
            final SelectorSyntaxException e = catchThrowableOfType(
                    SelectorSyntaxException.class,
                    () -> MemberSelector.parse("charge(BigDecimal) nonsense"));
            assertThat(e).hasMessageContaining("trailing text");
        }

        /**
         * Asserts that five truncated inputs come out as a {@link SelectorSyntaxException} rather than as a
         * JDK exception from the descriptor API.
         *
         * <p>Shape detection runs while a parse failure is already in flight, and the JDK's descriptor
         * parsing throws exceptions of its own that are not all {@link IllegalArgumentException}. An
         * unguarded call there replaces an accurate diagnostic with a raw JDK internal, which reaches the
         * user as a stack trace with no code, no offset and no caret.
         *
         * <p>One of the five, {@code charge(}, is also in the list above. The other four are not covered by
         * any other case.
         */
        @Test
        @DisplayName("a JDK descriptor exception never escapes as itself")
        void jdkExceptionsAreNeverLeaked() {
            // MethodTypeDesc.ofDescriptor("(") throws StringIndexOutOfBoundsException rather than
            // IllegalArgumentException. Shape detection runs while a parse failure is already in
            // flight, so an unguarded call there would replace an accurate diagnostic with a raw
            // JDK internal.
            for (final String text : new String[]{"charge(", "m(", "x(:", "desc:m(", "desc:x:"}) {
                assertThatThrownBy(() -> MemberSelector.parse(text))
                        .as("parsing \"%s\"", text)
                        .isInstanceOf(SelectorSyntaxException.class);
            }
        }

        /**
         * Asserts that a {@code null} text is a {@link NullPointerException}.
         *
         * <p>A missing selector is a programming error at the call site rather than a malformed one written
         * by a user, so it is not given a code, an offset or a caret.
         */
        @Test
        @DisplayName("null input is a NullPointerException, not a syntax error")
        void nullInput() {
            assertThatThrownBy(() -> MemberSelector.parse(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Rendering and re-parsing, and the equality of two spellings of one member.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("round-trip and equivalence")
    class RoundTrip {

        /**
         * Nineteen selectors spanning both forms and every kind of member.
         *
         * <p>Eleven are source form, four are descriptor form and four are constants. Two of the
         * descriptor-form entries carry an owner and two do not, which is the difference that decides
         * whether a selector has a canonical form.
         */
        private static final String[] VALID = {
                "ledger", "#state", "close()", "charge(BigDecimal)",
                "process(String[], int, java.util.List, *)", "get():String",
                "<init>(String, int)", "<clinit>()", "Gateway.send(Payment)",
                "com.acme.Gateway.send(com.acme.Payment):com.acme.Result",
                "ledger:com.acme.Ledger",
                "desc:com/acme/Gateway.send(Lcom/acme/Payment;)V",
                "desc:lambda$process$0(Ljava/lang/String;)Z",
                "desc:com/acme/Session.state:I",
                "desc:m([[BLjava/lang/String;I)[Ljava/lang/Object;",
                "null", "int:42", "string:\"retry\"", "class:java.util.List",
        };

        /**
         * Asserts that every entry of {@link #VALID}, rendered in the form it was parsed from, re-parses to
         * an equal selector.
         *
         * <p>Rendering is what a diagnostic prints and what a plan records, so a rendering that reads back as
         * a different selector turns a report into a false lead. All nineteen are covered, because the form
         * asked for is the selector's own and every selector renders in it.
         */
        @Test
        @DisplayName("rendering in the original form re-parses to an equal selector")
        void renderRoundTrips() {
            for (final String text : VALID) {
                final MemberSelector parsed = MemberSelector.parse(text);
                final String rendered = parsed.render(parsed.form());
                assertThat(MemberSelector.parse(rendered))
                        .as("round-trip of \"%s\" via \"%s\"", text, rendered)
                        .isEqualTo(parsed);
            }
        }

        /**
         * Asserts that a canonical form, where one exists, re-parses to the selector it came from.
         *
         * <p>The canonical form is an identity: a plan fingerprint built from it must not change when a weave
         * is rewritten from one spelling into the other, which requires that the text names the same member
         * when read back.
         *
         * <p>The body runs under {@link java.util.Optional#ifPresent(java.util.function.Consumer)}, so an
         * entry with no canonical form is skipped in silence rather than failing. Of the nineteen, the six
         * that are fully qualified are asserted on -- the two descriptor entries carrying an owner and the
         * four constants -- and the other thirteen contribute nothing.
         */
        @Test
        @DisplayName("the canonical form re-parses to an equal selector")
        void canonicalRoundTrips() {
            for (final String text : VALID) {
                final MemberSelector parsed = MemberSelector.parse(text);
                parsed.canonical().ifPresent(canonical ->
                        assertThat(MemberSelector.parse(canonical))
                                .as("canonical round-trip of \"%s\" via \"%s\"", text, canonical)
                                .isEqualTo(parsed));
            }
        }

        /**
         * Asserts that a selector parsed from a descriptor and one built from {@link java.lang.constant}
         * parts are equal, share a hash code, and canonicalise to the same present value.
         *
         * <p>The two production paths have to converge, because a weave discovered from a class file and one
         * built by a driver end up in the same plan. If they did not, the same member would appear twice
         * under two identities and a fingerprint would depend on which path produced it.
         *
         * <p>The canonical form is asserted present as well as equal, so two selectors that both had none
         * could not satisfy the case.
         */
        @Test
        @DisplayName("both spellings of one member are equal and share a canonical form")
        void formEquivalence() {
            final MemberSelector viaDescriptor =
                    MemberSelector.parse("desc:com/acme/Gateway.send(Lcom/acme/Payment;)V");
            final MethodSelector viaFactory = MemberSelector.ofDescriptor(
                    ClassDesc.of("com.acme.Gateway"), "send",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("com.acme.Payment")));

            assertThat(viaDescriptor)
                    .as("the parsed and the programmatically built selector must be equal")
                    .isEqualTo(viaFactory);
            assertThat(viaDescriptor.canonical())
                    .as("equal selectors must canonicalise identically, so the plan fingerprint "
                            + "does not depend on how the selector was produced")
                    .isEqualTo(viaFactory.canonical())
                    .isPresent();
            assertThat(viaDescriptor.hashCode()).isEqualTo(viaFactory.hashCode());
        }

        /**
         * Asserts the exact source rendering of a descriptor selector, and that its descriptor rendering
         * re-parses to itself.
         *
         * <p>The source rendering is asserted by equality rather than by re-parsing, which pins the shape a
         * user sees: an internal name becomes a dotted name, a descriptor becomes a source type, and the
         * return type is written after a colon because the source form has nowhere else to put it.
         */
        @Test
        @DisplayName("rendering to the other form produces a parseable selector")
        void crossFormRendering() {
            final MemberSelector descriptor =
                    MemberSelector.parse("desc:com/acme/Gateway.send(Lcom/acme/Payment;)V");

            final String asSource = descriptor.render(MemberSelector.Form.SOURCE);
            assertThat(asSource).isEqualTo("com.acme.Gateway.send(com.acme.Payment):void");

            final String asDescriptor = descriptor.render(MemberSelector.Form.DESCRIPTOR);
            assertThat(MemberSelector.parse(asDescriptor)).isEqualTo(descriptor);
        }
    }

    /**
     * Building a selector from parts rather than from text.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("programmatic construction")
    class Programmatic {

        /**
         * Asserts that the owner-carrying factories produce fully qualified selectors whose canonical form is
         * the descriptor text.
         *
         * <p>A driver that already holds a {@link java.lang.constant.ClassDesc} and a
         * {@link java.lang.constant.MethodTypeDesc} has no reason to render text and parse it back, and the
         * canonical form is asserted literally so that the factory path is pinned to the same spelling the
         * descriptor form parses.
         */
        @Test
        @DisplayName("descriptor factories produce fully qualified selectors")
        void factories() {
            final MethodSelector method = MemberSelector.ofDescriptor(
                    ClassDesc.of("com.acme.PaymentService"), "charge",
                    MethodTypeDesc.of(ClassDesc.of("com.acme.Receipt"),
                            ClassDesc.of("java.math.BigDecimal")));
            assertThat(method.isFullyQualified()).isTrue();
            assertThat(method.canonical())
                    .contains("desc:com/acme/PaymentService.charge(Ljava/math/BigDecimal;)Lcom/acme/Receipt;");

            final FieldSelector field = MemberSelector.ofFieldDescriptor(
                    ClassDesc.of("com.acme.Session"), "state", ConstantDescs.CD_int);
            assertThat(field.canonical()).contains("desc:com/acme/Session.state:I");
        }

        /**
         * Asserts that a factory-built selector with no owner is not fully qualified.
         *
         * <p>An exact signature is not enough: without an owner the selector still names a member of whatever
         * class it is applied to, so it cannot serve as an identity. The overload that takes no owner is
         * therefore not a shortcut for the one that does.
         */
        @Test
        @DisplayName("a selector without an owner is not fully qualified")
        void ownerlessIsNotFullyQualified() {
            assertThat(MemberSelector.ofDescriptor("charge",
                    MethodTypeDesc.of(ConstantDescs.CD_void)).isFullyQualified()).isFalse();
        }
    }
}
