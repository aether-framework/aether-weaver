# JavaDoc house style

The reference every implementer and reviewer is measured against. Prose rules argue with
each other; worked examples settle it, so the three at the bottom are the real content of
this file and the rules above them are only what those examples have in common.

Read this together with `CLAUDE.md`, which states the scope and the prohibitions. Where
the two disagree, `CLAUDE.md` wins.

## Where the rules come from

Three sources, in order of authority.

1. **The [JavaDoc Documentation Comment Specification][spec]** decides what a doc comment
   *is*: an optional main description followed by block tags. It defines what the standard
   doclet requires of a method comment — a main description, one `@param` per type
   parameter, one `@param` per formal parameter, a `@return` unless the return type is
   `void`, and one `@throws` per checked exception in the `throws` clause.
2. **[How to Write Doc Comments for the Javadoc Tool][oracle]** decides how it reads. Its
   two load-bearing instructions: the first sentence is a summary that must stand alone,
   and effort belongs on "boundary conditions, argument ranges and corner cases" rather
   than on restating what a signature already says.
3. **[Java Style Guidelines for JDK Release Projects][openjdk]** decides the typography:
   inline tags over the equivalent HTML, `<p>` to open a paragraph and no closing `</p>`.

[spec]: https://docs.oracle.com/en/java/javase/25/docs/specs/javadoc/doc-comment-spec.html
[oracle]: https://www.oracle.com/technical-resources/articles/java/javadoc-tool.html
[openjdk]: https://cr.openjdk.org/~alundblad/styleguide/index-v6.html

## Form

**The first sentence is a summary and is extracted.** It appears alone in the summary
table, detached from everything after it, so it has to be complete on its own and it has
to end in a period the tool can find.

**Third person, declarative.** `Returns the site`, never `Return the site` and never
`This method returns the site`. A class or field takes a noun phrase; a method takes a
verb phrase with the subject omitted.

**`@param`, `@return` and `@throws` take phrases, not sentences.** Lower case, no closing
period. `@param text the text to degrade`, not `@param text The text to degrade.`

**Tag order** is `@param` for type parameters, `@param` for formal parameters in
declaration order, `@return`, `@throws`, `@since`, `@see`.

**`@author Erik Pförtner` and `@since 0.1.0` on types, and only on types.** A member-level
`@since` fails `JavadocCoverageTest`.

**`{@code}` and `{@link}`, never `<code>` or a raw `<a href>`.** Open paragraphs with
`<p>` and do not close them.

**Cross-module `{@link}` must be fully qualified.** An import added to satisfy a link is
read by `ProjectStructureTest` as a dependency and breaks the architecture rules.

**120 columns**, JavaDoc included. Only a line carrying a URL is exempt.

## How much to write

Length is not a virtue and not a vice. The question is always whether a reader who has
only the generated page can use the thing correctly and predict what it does. That answer
depends entirely on who the reader is, so the depth does too.

**Published API — the whole of `aether-weaver-api`. Write a specification.**

This is the tier that matters, and the one where too little is far more expensive than too
much. The reader cannot see the engine, cannot read the injector, and will not guess. A
class comment of several hundred lines is not excessive here: `java.lang.String` runs to
that length because the contract genuinely has that many corners, and this project's
annotations have the same property. The benchmark is the JDK's own API pages.

It is the whole module, not only the annotations. Each package owes something different,
and none of them is a thin tier:

| Package | What its documentation has to answer |
| --- | --- |
| `api` | the annotations: every constraint, every diagnostic, every interaction |
| `api.diagnostic` | per code: what triggers it, what the user does about it, why it is that severity. This is the vocabulary a user meets in a failing build, and a code with a one-line summary sends them to a search engine |
| `api.select` | the selector grammar, written as a grammar: what parses, what does not, and which spelling is preferred where several are accepted |
| `api.manifest` | the format, exactly: what is written, in what order, how a reader should treat a field it does not recognise, and what changes are compatible |
| `api.spi` | the contract an implementer must satisfy — when each method is called, on which thread, what may be null, what happens if it throws |
| `api.callback` | what the carrier permits at each point, and what it refuses |
| `api.model` | the meaning of each component, and which combinations are valid |
| `api.experimental` | the stability promise, stated plainly: what may change, in which release, and what a caller should expect to rewrite |

Everything below is part of the specification, not an optional extra:

- **Every constraint on how the thing may be used**, stated as a rule rather than implied
  by an example.
- **Every way it can fail**, each named with its diagnostic code. A user who reads
  `AW1062` in their build output must be able to find that code in the documentation of
  the annotation that reported it.
- **Every side effect, and every interaction with another feature.** This is the one that
  gets skipped, and it is the reason a user ends up certain they followed the
  documentation and the code broke anyway. If a rule holds in the simple case and stops
  holding once a second weave touches the same target, the documentation says so at the
  point where the rule is stated.
- **What is ordered, and by what.** Where two declarations can apply to the same place,
  the reader needs to know which wins and whether the answer is stable between builds.
- **At least one complete, compiling example** for anything with a shape to get wrong.

**Engine internals — audience: whoever maintains this.** The reader has the code open, so
restating behaviour wastes their time. The comment earns its place by explaining why the
code has the shape it has: the constraint that forced it, the alternative that fails.

**Drivers and package-private helpers — audience: whoever debugs a deployment.** Enough to
place the class in the run and to say what it returns when it cannot answer.

## Substance

**Describe the code in front of you.** Nothing else is admissible — not history, not a
removed document, not a sibling class that looks similar.

**Name the failure and its code.** Where the source reports a diagnostic, the
documentation of the thing that can trigger it names the code, in `{@code AW1234}` form,
and says what to do instead. The codes are the vocabulary a user meets in their build
output, and documentation that never mentions them leaves them to search the internet for
a string that appears in one place on earth.

**A sentence a reviewer cannot anchor to a line of code is rejected.** This is the whole
point of the reviewer split, and "plausible and unverifiable" is the failure it exists to
catch.

**Say what a signature cannot.** The value of a comment is in what happens at the edges:
what a null argument does, which value means unbounded, what the method costs, what it
refuses. Restating the signature in words adds a line and no information.

**A measurement stays a measurement.** Where a line comment in the source records a
measured number, it may be quoted with its units and its condition. Where no such record
exists, measure it now or leave it out.

**No hedging.** A claim needing `probably` or `apparently` was not established from the
code, and the reader cannot tell which kind it is.

## What the gates already check

Do not spend review attention on these; they fail the build on their own.

| Gate | Catches |
| --- | --- |
| `JavadocCoverageTest` | a type or member with no comment; `@since` on a member |
| `JavadocStyleTest` | emoji, first person, self-reference, past tense about the project, hedging; an `AW####` that no `DiagnosticCode` declares; a `{@link}` from a published declaration to a package-private type of the same package |
| Checkstyle | line length, unused and star imports, module boundaries |
| `maven-javadoc-plugin`, `doclint=all,-missing` | a `{@link}` that resolves to nothing, malformed HTML, a `@param` naming no parameter |

---

# The three exemplars

One per audience, because the audience is what decides the depth. They are complete
comments for real types in this repository, written against the current source, and each
was applied to its file and put through `mvn verify` before being written down here: an
exemplar that cannot pass the gates is not an exemplar.

The difference in length between the first and the other two is the single most important
thing on this page. It is a deliberate ratio, not an accident of effort.

## 1. A public API annotation — audience: someone using the framework

`aether-weaver-api/.../api/Wrap.java`. The reader has never seen the engine and never
will. Every element gets its own comment, because an annotation element is a method and
the summary table lists it as one.

Read the length as the point, not as an accident. Every paragraph below exists because
leaving it out produces a user who followed the documentation and whose build broke.

```java
/**
 * Hands a matched operation to a handler that decides whether, when and how often to
 * perform it.
 *
 * <p>A wrap does not replace what it matches; it surrounds it. The handler receives an
 * {@link Operation} standing for the matched instruction, and may call it once, several
 * times, or not at all, and may return a value other than the one it produced. Where
 * {@link Redirect} substitutes an operation and never sees the original, a wrap always
 * keeps a handle to it.
 *
 * <h2>The handler's shape</h2>
 *
 * <p>A handler is a method of the weave class satisfying all of the following. Each is
 * checked by the annotation processor at compile time and again by the engine before any
 * bytes are written, so a violation fails the build rather than surfacing at run time.
 *
 * <ul>
 *   <li><b>It is {@code static}.</b> Reported as {@code AW1005} otherwise. A wrap can end
 *       up nested inside another weave's wrap, and an inner level is reached through
 *       {@link Operation#call(Object...)}, which carries the operation's own arguments and
 *       no receiver. State beyond those arguments belongs in a static field of the weave.
 *   <li><b>Its last parameter is an {@link Operation}</b>. Reported as {@code AW1063} when
 *       absent. Write its type argument as the operation's result type, boxed; nothing
 *       checks that, because the match compares the erased type, so a wrong type argument
 *       compiles and weaves and fails as a {@link ClassCastException} inside the handler.
 *   <li><b>Nothing follows that {@link Operation}.</b> Reported as {@code AW1062}. This is
 *       the rule that holds until a second weave arrives: a handler with trailing
 *       parameters works as the outermost wrap, because the enclosing method's arguments
 *       are still on the stack, and fails the moment another weave nests inside it, since
 *       an inner level receives only what {@link Operation#call(Object...)} carries.
 *   <li><b>Parameters before the {@link Operation} are all of the operation's own
 *       arguments</b>, in declaration order, and not a prefix of them. Reported as
 *       {@code AW1040}. A prefix is what {@link Redirect} permits; a wrap is matched on
 *       exact arity, so a handler that wants fewer wants a redirect instead.
 *   <li><b>It names no shift.</b> Reported as {@code AW1102}, at every point rather than
 *       only at {@link Point#HEAD}: a wrap takes over the operation it matched, so a
 *       neighbouring instruction is not something the handler's signature describes.
 *   <li><b>It is accessible from the woven class</b>, checked only for a
 *       {@code @Weave(kind = Kind.STATIC)}. Reported as {@code AW1042}.
 * </ul>
 *
 * <h2>What the matched position must be</h2>
 *
 * <p>Not every position a selector can name is an operation that can be surrounded. A
 * position that names no operation is reported as {@code AW1061} rather than woven, and
 * the same code covers {@link Redirect}.
 *
 * <h2>Nesting, and which wrap ends up outermost</h2>
 *
 * <p>Several weaves may wrap one operation, and they nest rather than collide — unlike
 * {@link Redirect}, where a second declaration on one call site is reported as
 * {@code AW1060}. The outermost is the one whose weave declares the highest
 * {@code @Weave(priority)}. Ties are broken by weave class name, then handler name, then
 * handler descriptor, so the order is total: two builds of the same inputs produce the
 * same nesting, and a weave added later cannot silently reorder the ones already there.
 *
 * <p>A handler therefore cannot assume it sees the target's own operation. Calling
 * {@link Operation#call(Object...)} reaches the next level in, which may be another
 * weave's handler.
 *
 * <h2>How many matches are required</h2>
 *
 * <p>One declaration may match several instructions. {@link #require()} is the fewest that
 * count as success and {@link #allow()} the most; falling short is reported as
 * {@code AW1043} and exceeding is {@code AW1044}. Neither default reads the way it looks.
 * An {@link #allow()} of {@code 0} imposes no upper bound rather than forbidding every
 * match. A {@link #require()} of {@code 0} is a sentinel rather than a value: a class file
 * records only the elements that were written, so an omitted {@link #require()} becomes the
 * injector's own default of one match, while a {@code 0} written out requires none.
 *
 * <p>A declaration naming a {@link #group()} is accounted differently: its matches are
 * added to the group's total and its own {@link #require()} is not checked, so that
 * several declarations can answer for one another where any one of them alone would fail.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     @Wrap(method = "charge(Ljava/math/BigDecimal;)Lcom/acme/Receipt;",
 *           at = @At(Point.INVOKE),
 *           require = 1)
 *     private static Receipt aroundCharge(BigDecimal amount, Operation<Receipt> op) {
 *         if (amount.signum() <= 0) {
 *             return Receipt.rejected();   // the operation is never performed
 *         }
 *         return op.call(amount);          // the next level in, not necessarily the target
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Redirect
 * @see Operation
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Wrap {

    /**
     * The method to weave into, written as a selector.
     *
     * <p>A selector that resolves to no method is reported as {@code AW1020}, and one that
     * resolves to more than one as {@code AW1021}; naming the descriptor disambiguates an
     * overload.
     *
     * @return the target method selector
     */
    String method();

    /**
     * Where inside the target the operation is looked for.
     *
     * @return the injection point
     */
    At at();

    /**
     * Narrows the search to one or more regions of the target rather than its whole body.
     *
     * <p>An ordinal in {@link #at()} counts within the narrowed region, not within the
     * method, so adding a slice to an otherwise unchanged declaration can change which
     * instruction it matches.
     *
     * @return the slices to search, or an empty array to search the whole method
     */
    Slice[] slice() default {};

    /**
     * Distinguishes this declaration from others in diagnostics.
     *
     * @return the identifier, or an empty string to let one be derived
     */
    String id() default "";

    /**
     * The fewest matches that count as success.
     *
     * <p>Not checked when {@link #group()} is set; the group's total is checked instead.
     *
     * @return the fewest matches that count as success; omitting this asks for one, while
     *         a {@code 0} written out requires none
     */
    int require() default 0;

    /**
     * The most matches that count as success.
     *
     * @return the maximum number of matches; {@code 0} imposes no upper bound rather than
     *         permitting none
     */
    int allow() default 0;

    /**
     * Accounts this declaration's matches against a named group rather than on its own, so
     * that several declarations can answer for one another.
     *
     * @return the group name, or an empty string to be accounted alone
     */
    String group() default "";
}
```

Three things to take from it.

**The rule that stops holding.** The `AW1062` bullet does not say "no parameters after the
Operation" and stop. It says what happens if the rule is ignored — works now, breaks when
a second weave arrives — because that is the shape of bug a user cannot diagnose from
their own code.

**Every failure carries its code.** A user reading `AW1044` in a build log finds it here,
next to the element that causes it.

**The elements say what the identifier cannot.** `require()` does not read "the required
number of matches". It says that omitting it and writing `0` mean different things, and
that `group()` disables it — neither of which is visible in `int require() default 0`. The
first is not visible in the annotation at all: it is settled in `WeaveClassParser`, where
an element the class file never recorded becomes one match. An element whose declared
default is a sentinel earns this much text every time.

## 2. An engine internal — audience: whoever maintains it

`aether-weaver-engine/.../engine/text/ConsoleText.java`. The drop in length from the
exemplar above is the point, not a lapse. Here the reader has the code open, so restating
behaviour costs them time; the comment earns its place by explaining the shape of the
code. Nothing in this tier is published, and nobody will build against it from the
generated page alone.

```java
/**
 * Rewrites text so that a stream can encode every character in it.
 *
 * <p>A console whose charset cannot represent a character prints a substitute chosen by
 * the encoder, which loses the distinction between characters that had a sensible ASCII
 * equivalent and characters that had none. Choosing the substitute here keeps that
 * distinction: an em dash becomes {@code -} and an arrow becomes {@code ->}, while
 * anything without an entry becomes {@code ?}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless, and every method is pure. The encoder is created per call because
 * {@link java.nio.charset.CharsetEncoder} is not safe to share.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConsoleText {

    /** ASCII stand-ins for the characters this project prints, keyed by the original. */
    private static final Map<Character, String> REPLACEMENTS = ...;

    /** The stand-in for a character with no entry in {@link #REPLACEMENTS}. */
    private static final String UNKNOWN = "?";

    /**
     * Degrades text to what the given stream's charset can encode.
     *
     * @param text   the text to degrade; must not be {@code null}
     * @param stream the stream whose charset decides; must not be {@code null}
     * @return the text, unchanged when the charset can already encode all of it
     * @throws NullPointerException if either argument is {@code null}
     */
    public static String forStream(final String text, final PrintStream stream) { ... }

    /**
     * Degrades text to what the given charset can encode.
     *
     * <p>Text the charset already accepts is returned unchanged and allocates nothing,
     * which is the case on any terminal that speaks UTF-8. Otherwise each character is
     * replaced individually; a supplementary character costs one {@code ?} for the
     * surrogate pair rather than two.
     *
     * @param text    the text to degrade; must not be {@code null}
     * @param charset the charset that decides; must not be {@code null}
     * @return the text, unchanged when the charset can already encode all of it
     * @throws NullPointerException if either argument is {@code null}
     */
    public static String forCharset(final String text, final Charset charset) { ... }
}
```

The surrogate sentence is the one worth having. It is the only part a reader would get
wrong, and it is anchored: the loop skips the low half after appending one replacement.

## 3. A driver internal — audience: whoever debugs a deployment

`aether-weaver-runtime/.../runtime/AotCache.java`, package-private. Visibility changes
nothing about whether it is documented.

```java
/**
 * Reports the flag that named an AOT cache on this JVM's command line.
 *
 * <p>This reads the arguments the JVM was started with rather than the state of any
 * cache. A run that names a cache file which turns out to be unusable is still reported
 * here, because the command line is what the reader can compare against their deployment.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class AotCache {

    /** The flag that suppresses a cache named alongside it. */
    private static final String OFF = "-XX:AOTMode=off";

    /** The flag prefixes that name a cache file. */
    private static final List<String> CACHE_FLAGS = ...;

    /**
     * Reports the cache flag this JVM was started with.
     *
     * @return the flag as written on the command line, or {@code null} when none was
     *         given or the arguments cannot be read
     */
    static String active() { ... }

    /**
     * Reports the cache flag among the given arguments.
     *
     * <p>{@code -XX:AOTMode=off} vetoes the others: it was measured to load nothing from
     * a cache given beside it. {@code -XX:AOTMode} in any other form names no file and is
     * not treated as a cache.
     *
     * @param arguments the JVM arguments to search; must not be {@code null}
     * @return the first cache flag found, or {@code null} when none is present
     * @throws NullPointerException if {@code arguments} is {@code null}
     */
    static String detect(final List<String> arguments) { ... }
}
```

`active()` documents returning `null` for two different reasons, one of which — the
arguments being unreadable — is visible only in a `catch` clause. That is exactly the kind
of thing the summary table cannot show and the comment must.

---

## Rejected phrasings

Each of these was written, and each is wrong for a reason that generalises.

| Rejected | Why | Instead |
| --- | --- | --- |
| `Gets the target method.` | restates the identifier | `The method to weave into, named as a selector.` |
| `This method returns the site.` | names itself; wastes the summary | `Returns the site.` |
| `@param text The text to degrade.` | sentence case and a period on a phrase | `@param text the text to degrade` |
| `Returns the maximum number of matches.` | true and useless; `0` is the whole question | `the maximum number of matches; {@code 0} imposes no upper bound` |
| `This is probably because the JVM caches it.` | a hedge standing in for a fact | remove it, or measure it and state the number |
| `The old parser did this differently.` | describes a state the reader cannot check | remove it |
| `See above for details.` | a member is read alone, out of a search result | link the member, or repeat the sentence |
