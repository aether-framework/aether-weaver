/**
 * The selector grammar: how a piece of text in an annotation names a method, a field or a constant.
 *
 * <p>Every {@code method} element of an injection declaration, and most {@code target} elements of an
 * {@code @At}, is a string that has to be turned into a member. {@link MemberSelector#parse(String)}
 * is that step, and everything in this package is either part of the grammar it reads or part of the
 * result it produces. The parser and the rendering rules are package-private; a caller names
 * {@link MemberSelector} and the three implementations it permits.
 *
 * <h2>The grammar</h2>
 *
 * <p>Three spellings share one entry point. A constant is recognised first, a {@code desc:} prefix
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
 * <p>The full classification order, the whitespace rules and the per-form details are specified on
 * {@link MemberSelector}. What matters at this level is that no step is a guess and each step commits:
 * once a spelling has accepted the text, no later one can reinterpret it. Only after the source-form
 * parse has already failed is the text inspected for descriptor syntax, and then only to suggest the
 * missing prefix. That ordering is what keeps {@code state:I} a field named {@code state} of a type
 * named {@code I} rather than a field of type {@code int}: {@code I} is a legal class name in the
 * default package, and reading it as a primitive would bind a weave to a member nobody named.
 *
 * <h2>Which spelling to prefer</h2>
 *
 * <p><b>To name one member exactly, the descriptor form.</b> It is the form to paste from
 * {@code javap} or from a stack trace, it is what {@link MemberSelector#canonical()} returns, and it is
 * the remedy the engine suggests when a selector matches more than one method. Every type in it is
 * exact, so it admits no wildcard.
 *
 * <p><b>To name a shape, the source form.</b> Wildcards, an omitted parameter list and an omitted
 * owner exist only there, and it names types the way Java source does.
 *
 * <p>The distinction that costs the most to get wrong is an <em>absent</em> parameter list against an
 * <em>empty</em> one. {@code charge} matches a method of that name whatever its signature;
 * {@code charge()} matches only the one that takes nothing. The same rule runs through the grammar: a
 * part the selector leaves out is a part it says nothing about.
 *
 * <h2>The three results</h2>
 *
 * <p>{@link MemberSelector} is sealed to {@link MethodSelector}, {@link FieldSelector} and
 * {@link ConstantSelector}, so a {@code switch} over one is exhaustive without a default branch. Which
 * of the three a text becomes is settled by its shape, with exactly one ambiguous case: a source-form
 * selector carrying neither a parameter list nor a type. {@code ledger} could be a field or a method
 * of any signature, and only the declaration the text came from knows which — which is what
 * {@link MemberKind} answers, through {@link MemberSelector#parse(String, MemberKind)}. The hint
 * decides that case and no other, and parsing without one reads a bare name as a method, which is what
 * an injection's {@code method} element means.
 *
 * <p>Inside a method or field selector, each type position is a {@link TypePattern}, sealed to three:
 * {@link TypePattern.Exact} for a type that is already resolved, {@link TypePattern.Named} for a name
 * that still has to be, and {@link TypePattern.Any} for {@code *}. Every type in a descriptor selector
 * is {@link TypePattern.Exact}, and so is a primitive keyword written in the source form; every other
 * source-form name is {@link TypePattern.Named}, whether it was written simple or qualified.
 * {@link MemberSelector#isFullyQualified()} and therefore {@link MemberSelector#canonical()} are built
 * from that distinction: a selector canonicalises only when its owner and every one of its type
 * positions is resolved, which a source-form selector never is.
 *
 * <h2>What parsing refuses</h2>
 *
 * <p>A text that does not parse is a {@link SelectorSyntaxException}, an
 * {@link IllegalArgumentException} carrying the code a build will show, the offset parsing stopped at
 * and, for one of the four codes, a corrected spelling. The annotation processor and the engine's
 * weave parser each catch it and turn it into a diagnostic under the same number, so a selector is
 * refused at compile time and at weave time for the same reason.
 *
 * <ul>
 *   <li>{@code AW1015} — the source grammar was violated: an empty selector, an invalid member or
 *       owner name, an unbalanced {@code <}, a missing type name, a missing expected character, text
 *       left over after the selector ended, or a constant literal its keyword rejects.
 *   <li>{@code AW1017} — the text is a JVM descriptor written without the {@code desc:} prefix.
 *       Reported only after the source parse has already failed, and the only code carrying a
 *       suggestion. That suggestion is not guaranteed to parse: the descriptor form refuses a wildcard,
 *       so a partial wildcard such as {@code a*b(I)V} produces a suggestion that fails as
 *       {@code AW1018}.
 *   <li>{@code AW1018} — the text after {@code desc:} is not a well-formed descriptor: a wildcard, a
 *       missing member name, a field selector with no type, an internal name that is not a class, or a
 *       descriptor the JDK refuses.
 *   <li>{@code AW1019} — a {@code desc:} method selector stops at its closing parenthesis and names no
 *       return type. Append {@code V} for {@code void}.
 * </ul>
 *
 * <p>Two inputs escape as a raw {@link IllegalArgumentException} instead, with no code and no offset:
 * a selector naming an array of {@code void}, as in {@code m(void[])}, and a {@code desc:} method
 * selector whose name is blank, as in {@code desc: ()V}.
 *
 * <p>Parsing is not resolution, and a selector that parses can still fail against a class. A method
 * selector matching nothing is reported as {@code AW1020}, listing every method the target declares,
 * and one matching several as {@code AW1021}, listing the ones that matched. Adding parameter types
 * narrows the first; the descriptor form settles the second. Type arguments are accepted and dropped,
 * because a class file records erased signatures — {@code process(java.util.List<String>)} and
 * {@code process(java.util.List)} parse to equal selectors — and the annotation processor reports
 * {@code AW1016} for an injection whose {@code method} carries them, to say that they had no effect.
 *
 * <h2>Rendering, and what does not round-trip</h2>
 *
 * <p>{@link MemberSelector#render(MemberSelector.Form)} always answers. The source form is available
 * for every selector; the descriptor form is produced only when every type in the signature is present
 * and resolved, the name holds no wildcard and an owner, where there is one, is resolved, and falls
 * back to the source rendering otherwise. Equality compares the parts rather than the spelling —
 * {@link MemberSelector#form()} records how a selector was written and takes no part in it — so
 * {@code m():int} and {@code desc:m()I} are equal and share a hash code.
 *
 * <p>Rendering a selector and parsing the result gives an equal selector back for most selectors, and
 * for one family it does not: a member named after one of the seven words that introduce something
 * else — the five constant keywords {@code int}, {@code long}, {@code float}, {@code double} and
 * {@code class}, plus {@code src} and {@code desc} — written without an owner. Its rendering drops the
 * {@code src:} prefix that made it a member, and reads back as a constant, as a malformed descriptor,
 * or as a differently shaped member. An owner defeats the constant check entirely, because the keyword
 * is read from before the selector's first colon and an owner puts a dot there instead. Prefix such a
 * rendering with {@code src:} by hand before parsing it again.
 *
 * <h2>Matching is the consumer's decision</h2>
 *
 * <p>Parsing settles the shape; deciding which members a selector stands for is something a consumer
 * does with it, and different matchers in this project genuinely differ. The account given on
 * {@link MemberSelector} and {@link TypePattern} describes the matcher behind an {@code @At} target,
 * where a {@link TypePattern.Named} is compared by rendered source name and a name without a dot
 * matches any type whose binary name ends with a dot and that name — which is what lets
 * {@code charge(BigDecimal)} select a method taking {@code java.math.BigDecimal}. The engine's own
 * selector resolver, described on
 * {@link de.splatgames.aether.weaver.api.spi.SelectorResolver}, resolves such a name against imports
 * instead and compares for equality. A selector that matches under one can fail to match under the
 * other.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Every implementation is immutable and safe to share, and parsing holds no state between calls.
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
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.select;
