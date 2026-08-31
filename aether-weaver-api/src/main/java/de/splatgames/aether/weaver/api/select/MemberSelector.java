package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Optional;

/**
 * A parsed selector: the text that names the method, the field or the constant a declaration acts on.
 *
 * <p>A selector reaches the framework as a string in an annotation element -- the {@code method} of an injection,
 * the {@code target} of an {@code @At} -- and such a string is read by {@link #parse(String)} or
 * {@link #parse(String, MemberKind)}; the {@code ofDescriptor} factories build one from parts instead. Either way
 * the result is one of exactly three implementations: {@link MethodSelector}, {@link FieldSelector} or
 * {@link ConstantSelector}. The interface is sealed, so a {@code switch} over a selector is exhaustive without a
 * default branch.
 *
 * <h2>The grammar</h2>
 *
 * <p>Three spellings share one entry point. A constant is recognised first, a {@value #DESCRIPTOR_PREFIX} prefix
 * selects the exact JVM form, and everything else is read as the source form.
 *
 * <pre>{@code
 * selector       ::= constant | descriptorForm | sourceForm
 *
 * constant       ::= "null"
 *                  | "int:"    integerLiteral
 *                  | "long:"   integerLiteral
 *                  | "float:"  decimalLiteral
 *                  | "double:" decimalLiteral
 *                  | "string:" '"' characters '"'
 *                  | "class:"  binaryName
 *                  | "class:desc:" fieldDescriptor
 *
 * descriptorForm ::= "desc:" [ internalName "." ] descriptorName
 *                    ( methodDescriptor | ":" fieldDescriptor )
 * descriptorName ::= any non-blank text that contains neither "." nor "(" nor "*"
 *
 * sourceForm     ::= [ "src:" ] [ owner "." | "#" ] memberName [ parameterList ] [ ":" type ]
 * parameterList  ::= "(" [ type ( "," type )* ] ")"
 * owner          ::= identifier ( "." identifier )*
 * memberName     ::= identifier | "*" | "<init>" | "<clinit>"
 * type           ::= "*" | typeName [ typeArguments ] ( "[" "]" )*
 * typeName       ::= a run of Java identifier characters and dots
 * }</pre>
 *
 * <p>An {@code identifier} is a Java identifier: {@link Character#isJavaIdentifierStart(char)} for the first
 * character and {@link Character#isJavaIdentifierPart(char)} for the rest. A {@code typeName} is not held to that
 * rule; it is a run of identifier characters and dots, and whether it names a class at all is decided when the
 * selector is matched rather than when it is parsed.
 *
 * <h2>How a text is classified</h2>
 *
 * <p>The order is fixed, and no step is a guess.
 *
 * <ol>
 *   <li>The text is trimmed. An empty result is reported as {@code AW1015}.
 *   <li>A constant is recognised: the exact text {@code null}, or a keyword from the grammar above followed by a
 *       colon. {@code string:} counts only when its value is quoted, because {@code string} is not reserved in
 *       Java and a field may be named that; the other five are reserved words that no Java source can use as a
 *       member name.
 *   <li>A text beginning with {@value #DESCRIPTOR_PREFIX} is read as the descriptor form, and nothing beyond that
 *       prefix is ever reinterpreted as source syntax.
 *   <li>Everything else is read as the source form, with a leading {@value #SOURCE_PREFIX} stripped if present.
 *   <li>Only when that parse has already failed is the text inspected for descriptor syntax. A text that the JDK
 *       accepts as a method or field descriptor is reported as {@code AW1017} carrying, as its suggestion, the
 *       {@value #SOURCE_PREFIX}-stripped body with {@value #DESCRIPTOR_PREFIX} prepended; otherwise the original
 *       {@code AW1015} stands. The suggestion is not guaranteed to parse: the descriptor form refuses a wildcard
 *       outright, so a partial wildcard the source grammar also rejects, such as {@code a*b(I)V}, produces a
 *       suggestion, {@code desc:a*b(I)V}, that fails as {@code AW1018}.
 * </ol>
 *
 * <p>Step five never rescues a text that parsed. {@code state:I} is a field named {@code state} of a type named
 * {@code I}, not a field of type {@code int}, because {@code I} is a legal class name in the default package and
 * reading it as a primitive would bind a weave to a member nobody named. Write {@code desc:state:I} for the
 * descriptor reading.
 *
 * <h2>The source form</h2>
 *
 * <p>The form to write by hand. It names types the way Java source does and it is the only form that accepts a
 * pattern.
 *
 * <ul>
 *   <li><b>Owner.</b> The text before the last dot of the head, as in {@code com.acme.Gateway.send(Payment)}. An
 *       owner that is not a run of dot-separated Java identifiers is reported as {@code AW1015}, which is what
 *       {@code com/acme/Gateway.send()} produces: slashes belong to the descriptor form.
 *   <li><b>No owner.</b> Omit it, or write a leading {@code #}. A member name cannot contain a dot, so
 *       {@code #state} and {@code state} parse to the same selector; the {@code #} refuses an owner rather than
 *       splitting one off, which is why {@code #a.b} is reported as {@code AW1015} while {@code a.b} names the
 *       member {@code b} of {@code a}. Rendering never emits it.
 *   <li><b>Name.</b> A Java identifier, {@code <init>}, {@code <clinit>}, or {@code *} on its own. A partial
 *       wildcard is not a name: {@code get*} and {@code *get} are reported as {@code AW1015}.
 *   <li><b>Parameter list.</b> Absent and empty differ, and the difference is the point. {@code charge} matches
 *       {@code charge} of any signature; {@code charge()} matches only the one that takes nothing. The parentheses
 *       must be adjacent when the list is empty -- {@code close( )} is reported as {@code AW1015}, because a type
 *       is expected once anything other than {@code )} follows the opening parenthesis.
 *   <li><b>Return type.</b> Written after a colon, as in {@code get():String}, and unconstrained when omitted. The
 *       colon must follow the closing parenthesis immediately: {@code m(int) : void} ends the selector at the
 *       parenthesis and the rest is reported as {@code AW1015} trailing text.
 *   <li><b>Types.</b> A primitive keyword ({@code boolean}, {@code byte}, {@code char}, {@code short},
 *       {@code int}, {@code long}, {@code float}, {@code double}, {@code void}) becomes a resolved
 *       {@link TypePattern.Exact}; anything else becomes a {@link TypePattern.Named} carrying the text as written.
 *       {@code *} is {@link TypePattern.Any}. Array brackets follow the name, and spaces are permitted inside and
 *       around them: {@code String [ ]} and {@code String[]} are one type.
 *   <li><b>Type arguments.</b> Accepted and dropped, because a class file records erased signatures:
 *       {@code process(java.util.List<String>)} and {@code process(java.util.List)} parse to equal selectors. An
 *       unbalanced {@code <} is reported as {@code AW1015}. The annotation processor reports {@code AW1016} for an
 *       injection whose {@code method} carries type arguments, to say that they had no effect.
 *   <li><b>Whitespace.</b> The whole text is trimmed and so is the head, so a space may sit before the opening
 *       parenthesis and on either side of the colon that introduces a field's type. Spaces are skipped around
 *       parameter types, around array brackets and after a colon. They are skipped nowhere else, and the one place
 *       that costs something is the colon before a method's return type.
 * </ul>
 *
 * <h2>The descriptor form</h2>
 *
 * <p>The form to paste from {@code javap} or from a stack trace. Every type in it is exact and it admits no
 * wildcard, and the parser holds it to that. With an owner, it names exactly one member; omitting the owner is
 * allowed as well, and then it names a signature exact in every type without being tied to any class.
 *
 * <ul>
 *   <li>The owner is an internal name -- {@code com/acme/Gateway}, or a full descriptor such as
 *       {@code [Ljava/lang/String;} for an array -- separated from the member name by a dot. Omitting the owner is
 *       allowed and leaves the selector without one.
 *   <li>The member name is whatever lies between that dot and the descriptor, and it is not held to the source
 *       grammar's stricter rule for an identifier: a {@code $} is a valid Java identifier character, so
 *       {@code lambda$process$0} and {@code access$000} are nameable in the source form as well as here. An empty
 *       name is reported as {@code AW1018}.
 *   <li>A method descriptor must carry its return type. {@code desc:charge(Ljava/math/BigDecimal;)} is reported as
 *       {@code AW1019} rather than read as a match-anything; append {@code V} for {@code void}.
 *   <li>A field descriptor follows a colon, and the colon is the last one in the text:
 *       {@code desc:com/acme/Session.state:I}. A descriptor field selector without one is reported as
 *       {@code AW1018}, which is also what {@code desc:} and {@code desc:charge} produce.
 *   <li>A wildcard anywhere after the prefix is reported as {@code AW1018}. The form leaves no type unresolved,
 *       so pattern matching belongs in the source form.
 *   <li>A descriptor the JDK refuses is reported as {@code AW1018}, carrying the JDK's own message.
 *   <li>Nothing inside the form is trimmed. The text after the prefix is taken exactly as written.
 * </ul>
 *
 * <h2>Constants</h2>
 *
 * <p>{@code null}, {@code int:42}, {@code long:7}, {@code float:1.5}, {@code double:1.0}, {@code string:"retry"}
 * and {@code class:java.util.List} parse to a {@link ConstantSelector}, which names a loaded value rather than a
 * member. The value's syntax and its normalisation are specified on that class.
 *
 * <h2>Which spelling to prefer</h2>
 *
 * <p>Several spellings parse to one selector. Where that happens, one of them is what rendering produces, and it is
 * the one to write.
 *
 * <ul>
 *   <li><b>Naming one member exactly: the descriptor form.</b> It is what {@link #canonical()} returns, and it is
 *       the remedy the engine suggests when a selector matches more than one method ({@code AW1021}). In
 *       {@code de.splatgames.aether.weaver.engine.inject.point.Targets}, the matcher behind an {@code @At} target,
 *       a descriptor-form owner is compared exactly rather than by simple name.
 *   <li><b>Naming a shape: the source form.</b> Wildcards, an omitted parameter list and an omitted owner exist
 *       only there.
 *   <li>{@value #SOURCE_PREFIX} is optional and is never rendered. {@code src:charge(BigDecimal)} and
 *       {@code charge(BigDecimal)} are the same selector. The prefix does suppress constant recognition, since the
 *       keyword is looked for at the start of the untouched text: {@code src:null} is a method named {@code null},
 *       {@code null} is the null constant.
 *   <li>A leading {@code #} is optional and is never rendered.
 *   <li>Type arguments are dropped, so {@code List<String>} renders as {@code List}.
 *   <li>A numeric constant is rendered through {@link Integer#toString(int)} and its siblings, so
 *       {@code float:1.5f} renders as {@code float:1.5}.
 *   <li>{@code class:desc:Ljava/util/List;} renders as {@code class:java.util.List}.
 * </ul>
 *
 * <h2>Rendering, and the canonical form</h2>
 *
 * <p>{@link #render(Form)} always answers, in both forms.
 *
 * <ul>
 *   <li>{@link Form#SOURCE} is available for every selector and does not deliberately produce a
 *       {@value #DESCRIPTOR_PREFIX} prefix, though nothing inspects the rendered name for one: a field literally
 *       named {@code desc} and written without an owner, such as {@code src:desc:Foo}, renders as {@code desc:Foo},
 *       text that happens to begin with the prefix and reads back as a malformed descriptor ({@code AW1018}) rather
 *       than as that field. Otherwise it is lossy for a selector built from descriptors: a primitive comes back as a
 *       primitive, while an owner and any reference type come back as a {@link TypePattern.Named}. So
 *       {@code desc:m()I} renders as {@code m():int} and re-parses to an equal selector, and
 *       {@code desc:com/acme/S.state:I} renders as {@code com.acme.S.state:int} and re-parses to one that
 *       constrains the same shape without being resolved.
 *   <li>{@link Form#DESCRIPTOR} requires every type in the signature to be present and resolved and the name to
 *       hold no wildcard; for a {@link MethodSelector} the parameter list and the return type must both be present,
 *       and for a {@link FieldSelector} the type must be present. An owner, when one is present, must also be
 *       resolved -- an unresolved owner falls back to the source rendering even when the rest of the signature
 *       qualifies, which is why {@code Foo.m(int):void} renders as itself rather than as {@code desc:Foo.m(I)V}.
 *       Falling short of any of that, it falls back to the source rendering, which is not guaranteed to omit the
 *       {@value #DESCRIPTOR_PREFIX} prefix either: the {@code src:desc:Foo} example above renders, in either form,
 *       as the same {@code desc:Foo} -- a rendering that looks like the descriptor form without being exact, and
 *       that is read downstream as a malformed one rather than as the field it names.
 *   <li>{@link #canonical()} is the strict answer for a {@link MethodSelector} or a {@link FieldSelector}: the
 *       descriptor form when {@link #isFullyQualified()} holds, and {@link Optional#empty()} otherwise. A
 *       {@link ConstantSelector} has no separate descriptor form to fall back to -- its
 *       {@link ConstantSelector#isFullyQualified()} is unconditionally {@code true}, so its
 *       {@link ConstantSelector#canonical()} is always present, equal to its one rendering rather than to a
 *       spelling carrying {@value #DESCRIPTOR_PREFIX}. Two selectors that are equal have the same canonical form
 *       regardless of how they were produced.
 * </ul>
 *
 * <p>Rendering a selector in the form it was parsed from and reading the result back gives an equal selector for
 * most selectors, but not for every one, because a bare, ownerless name is tried against the constant grammar
 * before it is read as a member. Five keywords -- {@code int}, {@code long}, {@code float}, {@code double} and
 * {@code class} -- introduce a constant as soon as a colon follows them with no owner in front, and only once none
 * of those five keywords match are {@code src} and {@code desc} tested, as whole prefixes rather than as keywords
 * up to a colon; the bare word {@code null} is a constant on its own. A field named after one of these seven,
 * written without an owner, is therefore unsafe to round-trip:
 *
 * <ul>
 *   <li>{@code src:int:42} is a field called {@code int}; its rendering {@code int:42} reads back as the constant
 *       {@code 42} rather than the field.
 *   <li>{@code src:desc:int} is a field called {@code desc}; its rendering {@code desc:int} begins with
 *       {@value #DESCRIPTOR_PREFIX} and reads back as a malformed descriptor, {@code AW1018}, rather than as any
 *       field.
 *   <li>{@code src:src:int} is a field called {@code src}; its rendering {@code src:int} has the
 *       {@value #SOURCE_PREFIX} prefix stripped again on the way back in and reads back as a bare name -- a
 *       {@link MethodSelector} named {@code int} -- silently unequal to the field it started as.
 * </ul>
 *
 * <p>An owner defeats the constant check entirely, because the keyword is read from before the selector's first
 * colon and an owner puts a dot there instead: {@code Foo.int:I} parses and renders without needing the
 * {@value #SOURCE_PREFIX} prefix at all. Prefix a bare, ownerless rendering of one of these seven names by hand
 * before parsing it again.
 *
 * <h2>Equality</h2>
 *
 * <p>Equality compares the parts, not the spelling. {@link #form()} records how a selector was written and takes no
 * part in it, so {@code m():int} and {@code desc:m()I} are equal and share a hash code. Two selectors of different
 * implementations are never equal, since each {@code equals} requires its own type.
 *
 * <h2>What the parts mean when the selector is matched</h2>
 *
 * <p>Parsing settles the shape; matching is one thing the engine does with it, and different engine matchers are
 * free to do it differently. In {@code de.splatgames.aether.weaver.engine.inject.point.Targets}, the matcher behind
 * an {@code @At} target, a {@link TypePattern.Exact} is compared by equality and a {@link TypePattern.Named} by
 * rendered source name, where a name without a dot matches any type whose binary name ends with a dot and that name
 * -- so {@code charge(BigDecimal)} selects a method taking {@code java.math.BigDecimal}. A descriptor selector holds
 * only {@code Exact} patterns and therefore never matches by simple name there.
 *
 * <p>A method selector that matches nothing is reported as {@code AW1020}, listing every method the target
 * declares, and one that matches several methods as {@code AW1021}, listing the ones that matched. Adding
 * parameter types narrows the first; the descriptor form settles the second.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // A method of any signature, on the weave's own target.
 * MemberSelector any = MemberSelector.parse("charge");
 *
 * // The no-argument overload, and only that one.
 * MemberSelector exactArity = MemberSelector.parse("charge()");
 *
 * // A field, which a bare name would otherwise read as a method.
 * MemberSelector field = MemberSelector.parse("ledger", MemberKind.FIELD);
 *
 * // One method, named exactly, with no dependence on imports.
 * MemberSelector pinned =
 *         MemberSelector.parse("desc:com/acme/Gateway.send(Lcom/acme/Payment;)V");
 *
 * // The same member, built rather than written.
 * MemberSelector built = MemberSelector.ofDescriptor(
 *         ClassDesc.of("com.acme.Gateway"), "send",
 *         MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("com.acme.Payment")));
 *
 * assert pinned.equals(built);
 * assert pinned.canonical().orElseThrow()
 *         .equals("desc:com/acme/Gateway.send(Lcom/acme/Payment;)V");
 * }</pre>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Every implementation is immutable and safe to share. {@link #parse(String)} holds no state between calls.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MethodSelector
 * @see FieldSelector
 * @see ConstantSelector
 * @see TypePattern
 * @see SelectorSyntaxException
 */
public sealed interface MemberSelector permits MethodSelector, FieldSelector, ConstantSelector {

    /**
     * The prefix that introduces the descriptor form.
     *
     * <p>The spelling {@link MethodSelector#canonical()} and {@link FieldSelector#canonical()} produce when
     * present. A {@link ConstantSelector} has no descriptor form and its {@link ConstantSelector#canonical()}
     * never carries this prefix.
     *
     * <p>Also accepted after {@code class:} to write a class constant as a field descriptor, as in
     * {@code class:desc:Ljava/util/List;}.
     */
    String DESCRIPTOR_PREFIX = "desc:";

    /**
     * The optional prefix that states the source form explicitly.
     *
     * <p>Stripped before parsing and never rendered. Because a constant keyword is looked for before the prefix is
     * removed, writing it suppresses constant recognition: {@code src:int:42} is a field named {@code int}, and
     * {@code src:null} is a method named {@code null}. Rendering such a selector drops the prefix and with it that
     * reading, so the text has to be prefixed again before it means the same thing.
     */
    String SOURCE_PREFIX = "src:";

    /**
     * Parses a selector without a context to resolve an ambiguous shape.
     *
     * <p>A bare name -- no parameter list and no type -- reads as a method of any signature, which is what
     * {@code method = "charge"} means on an injection. Every other shape is unambiguous on its own.
     *
     * @param text the selector to parse; leading and trailing whitespace is ignored
     * @return the parsed selector
     * @throws NullPointerException     if {@code text} is {@code null}
     * @throws SelectorSyntaxException  if the text does not parse, carrying {@code AW1015}, {@code AW1017},
     *                                  {@code AW1018} or {@code AW1019} and the offset at which parsing stopped
     * @throws IllegalArgumentException if the text names an array of {@code void}, as in {@code m(void[])}, or a
     *                                  {@value #DESCRIPTOR_PREFIX} method selector with a blank name, as in
     *                                  {@code desc: ()V} -- either case escapes the parser as a raw exception with
     *                                  no code and no offset, rather than as a {@link SelectorSyntaxException}
     */
    @Contract("_ -> new")
    static MemberSelector parse(@NotNull final String text) {
        return SelectorParser.parse(text, null);
    }

    /**
     * Parses a selector, saying which kind of member a bare name should name.
     *
     * <p>The hint decides one case and no other: a source-form selector with neither a parameter list nor a type.
     * A parameter list makes it a method and a type makes it a field whatever the hint says, and the descriptor
     * form and the constant forms ignore it entirely, so {@code parse("charge(int)", MemberKind.FIELD)} is a
     * {@link MethodSelector}.
     *
     * @param text     the selector to parse; leading and trailing whitespace is ignored
     * @param expected the kind a bare name names
     * @return the parsed selector
     * @throws NullPointerException     if either argument is {@code null}
     * @throws SelectorSyntaxException  if the text does not parse, carrying {@code AW1015}, {@code AW1017},
     *                                  {@code AW1018} or {@code AW1019} and the offset at which parsing stopped
     * @throws IllegalArgumentException if the text names an array of {@code void}, as in {@code v:void[]}, or a
     *                                  {@value #DESCRIPTOR_PREFIX} method selector with a blank name, as in
     *                                  {@code desc: ()V} -- either case escapes the parser as a raw exception with
     *                                  no code and no offset, rather than as a {@link SelectorSyntaxException}
     */
    @Contract("_, _ -> new")
    static MemberSelector parse(@NotNull final String text, @NotNull final MemberKind expected) {
        return SelectorParser.parse(text, java.util.Objects.requireNonNull(expected, "expected"));
    }

    /**
     * Builds a method selector with no owner from a name and a descriptor.
     *
     * <p>The result is exact in its signature and unqualified in its owner, so {@link #isFullyQualified()} is
     * {@code false} and {@link #canonical()} is empty. Pass an owner to the four-part form for a selector that
     * canonicalises.
     *
     * @param name the method name, which may be {@code <init>} or {@code <clinit>} and must not be blank
     * @param type the erased signature
     * @return a method selector in {@link Form#DESCRIPTOR}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    @Contract(value = "_, _ -> new", pure = true)
    static MethodSelector ofDescriptor(@NotNull final String name, @NotNull final MethodTypeDesc type) {
        return MethodSelector.ofDescriptor(null, name, type);
    }

    /**
     * Builds a fully qualified method selector from an owner, a name and a descriptor.
     *
     * <p>Equal to the selector {@link #parse(String)} produces for the same member written in the descriptor form,
     * and with the same hash code and the same {@link #canonical()} text, so a selector's identity does not depend
     * on whether it was written or built.
     *
     * @param owner the declaring class
     * @param name  the method name, which may be {@code <init>} or {@code <clinit>} and must not be blank
     * @param type  the erased signature
     * @return a method selector in {@link Form#DESCRIPTOR}
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}; {@code owner} is not
     *                                  checked at run time and a {@code null} owner is accepted, producing a
     *                                  selector that is not {@linkplain #isFullyQualified() fully qualified}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    static MethodSelector ofDescriptor(@NotNull final ClassDesc owner,
                                       @NotNull final String name,
                                       @NotNull final MethodTypeDesc type) {
        return MethodSelector.ofDescriptor(owner, name, type);
    }

    /**
     * Builds a field selector with no owner from a name and a field descriptor.
     *
     * <p>The type is exact and the owner is absent, so {@link #isFullyQualified()} is {@code false} and
     * {@link #canonical()} is empty.
     *
     * @param name the field name; must not be blank
     * @param type the field's type
     * @return a field selector in {@link Form#DESCRIPTOR}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    @Contract(value = "_, _ -> new", pure = true)
    static FieldSelector ofFieldDescriptor(@NotNull final String name, @NotNull final ClassDesc type) {
        return FieldSelector.ofDescriptor(null, name, type);
    }

    /**
     * Builds a fully qualified field selector from an owner, a name and a field descriptor.
     *
     * @param owner the declaring class
     * @param name  the field name; must not be blank
     * @param type  the field's type
     * @return a field selector in {@link Form#DESCRIPTOR}
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}; {@code owner} is not
     *                                  checked at run time and a {@code null} owner is accepted, producing a
     *                                  selector that is not {@linkplain #isFullyQualified() fully qualified}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    static FieldSelector ofFieldDescriptor(@NotNull final ClassDesc owner,
                                           @NotNull final String name,
                                           @NotNull final ClassDesc type) {
        return FieldSelector.ofDescriptor(owner, name, type);
    }

    /**
     * Reports which spelling this selector was written in.
     *
     * <p>A record of provenance, not of capability: {@link #render(Form)} answers in either form whatever this
     * says, and equality ignores it. {@link Object#toString()} uses it, so a selector prints the way it arrived.
     *
     * @return the form this selector was parsed from, or {@link Form#DESCRIPTOR} when it was built from descriptors
     */
    @Contract(pure = true)
    Form form();

    /**
     * Returns the one spelling that names this member exactly.
     *
     * <p>Present exactly when {@link #isFullyQualified()} holds. For a {@link MethodSelector} or a
     * {@link FieldSelector} it is then equal to {@code render(Form.DESCRIPTOR)}, the descriptor form. A
     * {@link ConstantSelector} is unconditionally fully qualified, so this is always present for one, equal to its
     * one rendering rather than to a distinct descriptor spelling. Equal selectors canonicalise identically, which
     * is what makes the text usable as an identity: a plan fingerprint built from it does not change when a weave
     * is rewritten from the source form to the descriptor form.
     *
     * @return the descriptor form for a {@link MethodSelector} or {@link FieldSelector} naming exactly one member,
     *         the one rendering of a {@link ConstantSelector}, or {@link Optional#empty()} when a
     *         {@link MethodSelector} or {@link FieldSelector} names a shape rather than one member
     */
    @Contract(pure = true)
    Optional<String> canonical();

    /**
     * Renders this selector in the requested form.
     *
     * <p>{@link Form#SOURCE} always answers. {@link Form#DESCRIPTOR} answers with the descriptor form only when
     * every type in the signature is present and resolved, the name holds no wildcard, and an owner, when one is
     * present, is resolved -- for a {@link MethodSelector} the parameter list and the return type must both be
     * present as well, and for a {@link FieldSelector} the type must be present -- and falls back to the source
     * rendering when any of that is missing. That fallback is not guaranteed to omit the
     * {@value #DESCRIPTOR_PREFIX} prefix: a member literally named {@code desc} and written without an owner
     * renders, in either form, as text that begins with the
     * prefix regardless of which one was asked for. Rendering in the form the selector was parsed from and parsing
     * the result gives an equal selector again, except for a member named after one of the seven words -- five
     * constant keywords, {@code src} and {@code desc} -- listed above under the unsafe-name paragraph, written
     * without an owner; recovering the original selector then requires prefixing the rendered text with
     * {@value #SOURCE_PREFIX} by hand.
     *
     * @param form the form to render in
     * @return the rendered selector, never blank
     * @throws NullPointerException if {@code form} is {@code null}
     */
    @Contract(pure = true)
    String render(Form form);

    /**
     * Reports whether this selector names exactly one member.
     *
     * <p>True when an owner is present and resolved, the name holds no wildcard, and every type in the signature is
     * resolved. A selector parsed from the descriptor form satisfies all of that as soon as it carries an owner,
     * and so does one built by {@link #ofDescriptor(ClassDesc, String, MethodTypeDesc)} or
     * {@link #ofFieldDescriptor(ClassDesc, String, ClassDesc)}. A source-form selector never does: an owner
     * written in source form is a name to be resolved rather than a resolved type, whatever the rest of the
     * selector looks like. A {@link ConstantSelector} is always fully qualified, since it names a value outright.
     *
     * @return whether this selector is exact in owner, name and signature
     */
    @Contract(pure = true)
    boolean isFullyQualified();

    /**
     * The spelling a selector is written in.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum Form {

        /**
         * The Java-like form: {@code com.acme.Gateway.send(Payment):Result}.
         *
         * <p>Available for every selector, and the only form that admits a wildcard, an omitted parameter list or
         * an unresolved type name.
         */
        SOURCE,

        /**
         * The JVM form: {@code desc:com/acme/Gateway.send(Lcom/acme/Payment;)V}.
         *
         * <p>Names exactly one member when the selector carries an owner, and otherwise a signature exact in every
         * type but tied to no class. Rendering into it falls back to {@link #SOURCE} for a selector that has no
         * descriptor form.
         */
        DESCRIPTOR
    }
}
