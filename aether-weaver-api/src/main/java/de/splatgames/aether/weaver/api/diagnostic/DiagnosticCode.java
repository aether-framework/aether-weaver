package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The catalogue of conditions Aether Weaver itself reports, one constant per code.
 *
 * <p>Every diagnostic this catalogue reports begins with one of these codes, and the code is the
 * only part of the output that is stable: messages are written per reporting site and change with
 * the class being woven, while a code identifies the condition. A code is never reused for a
 * different condition and never renumbered. A build may also show a {@link PluginDiagnosticId},
 * whose wire form is described in the next section.
 *
 * <h2>The wire form</h2>
 *
 * <p>{@link #code()} is {@code AW} followed by exactly four digits, and contains no colon. That is
 * what keeps this catalogue disjoint from {@link PluginDiagnosticId}, whose wire form is always
 * {@code namespace:IDENTIFIER}. {@link #of(String)} therefore returns empty for every plugin code,
 * and a reader can tell the two apart without a lookup.
 *
 * <p>{@link #toString()} returns the wire form rather than the constant name, so a code
 * interpolated into a log line is a string the reader can search for.
 *
 * <h2>How the number chooses the category</h2>
 *
 * <p>{@link #category()} is not free: it follows from the four digits, read as a number, in fixed
 * ranges. This is what makes a code readable before it is looked up, and it is why a new condition
 * is numbered into the block that already holds its kind rather than appended at the end.
 *
 * <table border="1">
 *   <caption>Number ranges and their categories</caption>
 *   <tr><th>Digits</th><th>Category</th><th>What it is about</th></tr>
 *   <tr><td>{@code 1000} to {@code 1099}</td><td>{@link Category#DECLARATION}</td>
 *       <td>the shape of a weave class, its members and its handlers</td></tr>
 *   <tr><td>{@code 1100} to {@code 1199}</td><td>{@link Category#INJECTION_POINT}</td>
 *       <td>locating a position inside a target method</td></tr>
 *   <tr><td>{@code 1200} to {@code 1299}</td><td>{@link Category#COMPILE_TIME}</td>
 *       <td>what the annotation processor could and could not check</td></tr>
 *   <tr><td>{@code 1300} to {@code 1399}</td><td>{@link Category#EXTENSION}</td>
 *       <td>extension classes and the members they contribute</td></tr>
 *   <tr><td>{@code 1400} to {@code 2099}</td><td>{@link Category#TARGET}</td>
 *       <td>the class file being woven</td></tr>
 *   <tr><td>{@code 2100} to {@code 2199}</td><td>{@link Category#DRIVER}</td>
 *       <td>limits of the driver applying the weave</td></tr>
 *   <tr><td>{@code 2200} to {@code 2299}</td><td>{@link Category#IDEMPOTENCE}</td>
 *       <td>weaving something that has been woven before</td></tr>
 *   <tr><td>{@code 2300} to {@code 2399}</td><td>{@link Category#CONFIGURATION}</td>
 *       <td>manifests and configuration input</td></tr>
 *   <tr><td>{@code 2400} to {@code 2499}</td><td>{@link Category#ENVIRONMENT}</td>
 *       <td>the JVM the weaver is running in</td></tr>
 *   <tr><td>{@code 2500} to {@code 2999}</td><td>{@link Category#BUILD}</td>
 *       <td>what a build produced beyond its own classes</td></tr>
 *   <tr><td>{@code 3000} to {@code 3099}</td><td>{@link Category#POLICY}</td>
 *       <td>what weaving is permitted to touch</td></tr>
 *   <tr><td>{@code 3100} to {@code 3199}</td><td>{@link Category#PLUGIN}</td>
 *       <td>loading and isolating plugins</td></tr>
 *   <tr><td>{@code 3200} to {@code 3999}</td><td>{@link Category#POLICY}</td>
 *       <td>reserved; no constant below occupies this range</td></tr>
 *   <tr><td>{@code 4000} and above</td><td>{@link Category#ENGINE}</td>
 *       <td>output the engine refuses to hand back</td></tr>
 * </table>
 *
 * <h2>Severity, and what a build may ignore</h2>
 *
 * <p>{@link #defaultSeverity()} is a property of the condition. {@link #isSuppressible()} is
 * derived from it and is false for exactly the {@link Severity#ERROR} codes. A reporting site may
 * raise or lower the severity of one report with
 * {@link Diagnostic.Builder#severity(Severity)} — including lowering an {@link Severity#ERROR}
 * code's report to a severity that {@link Diagnostic#isSuppressible()} then treats as
 * suppressible; it cannot change what this catalogue declares for the code itself.
 *
 * <h2>Reserved codes</h2>
 *
 * <p>Five constants are named {@code RESERVED_} followed by their number and summarised as
 * {@code (reserved)}: {@code AW1003}, {@code AW1085}, {@code AW1086}, {@code AW2403} and
 * {@code AW4002}. They hold a number against reuse and no code reports them, so a build cannot
 * produce one. They exist so that the number they occupy cannot be taken by an unrelated condition
 * later.
 *
 * <h2>Where a code is reported</h2>
 *
 * <p>Many conditions are checked twice: once by the annotation processor, which sees source and can
 * put a caret on the offending element, and once by the engine, which sees class files and is the
 * only stage that runs for a weave compiled elsewhere. The code is the same in both cases, and for
 * most of them a user who resolves it at compile time has resolved it at weave time as well. That is
 * not true of every pair: {@code AW1021} and {@code AW1022} are one condition to the processor, which
 * tells a wildcard selector matching several methods apart from a plain name that does, but the
 * engine reports every selector matching several methods as {@code AW1021}, so silencing
 * {@code AW1022} at compile time by setting {@code allow} does not silence the {@code AW1021} the
 * engine still reports at weave time. Where only one of the two can check a condition, the constant
 * below says which.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // Turning a code read from build output back into its catalogue entry.
 * DiagnosticCode.of("AW1043")
 *         .ifPresent(code -> System.out.println(code.category() + ": " + code.summary()));
 * // prints: DECLARATION: No injection point matched
 *
 * DiagnosticCode.of("aw1043");        // empty: the lookup is case-sensitive
 * DiagnosticCode.of("acme:BOOM");     // empty: a plugin code is never in this catalogue
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Diagnostic
 * @see PluginDiagnosticId
 * @see Severity
 */
public enum DiagnosticCode implements DiagnosticId {

    /**
     * A {@code @Weave} names nothing to weave into, so the declaration can never apply.
     *
     * <p>Raised when neither {@code value()} nor {@code targets()} is written. Both the annotation
     * processor and the engine's weave parser check it, and the parser abandons the weave's target
     * list entirely.
     *
     * <p>Give the annotation a class literal, {@code @Weave(Session.class)}, or a name when the
     * target is not on the compile classpath, {@code @Weave(targets = "com.acme.Session")}.
     *
     * <p>An error rather than a warning: a weave with no target is inert, and a build that accepted
     * it would report success for code that never runs.
     */
    WEAVE_NO_TARGETS("AW1001", Severity.ERROR, Category.DECLARATION,
            "@Weave declares no targets"),

    /**
     * A {@code @Weave} declares its targets twice, as class literals and as names.
     *
     * <p>Which of the two is authoritative would be a guess, so neither is used: the weave ends up
     * with no targets at all, and {@code AW1001} is not additionally reported for it.
     *
     * <p>Keep the class literals and delete {@code targets = }, or the other way round. Class
     * literals are checked by the compiler and follow a rename, so they are the form to keep
     * wherever the target is on the compile classpath.
     */
    WEAVE_DUPLICATE_TARGET_DECLARATION("AW1002", Severity.ERROR, Category.DECLARATION,
            "Both value() and targets() given"),

    /**
     * A reserved number in the declaration range.
     *
     * <p>No code reports it and no build can produce it. The constant holds the number so that it
     * cannot be taken by an unrelated condition.
     */
    RESERVED_1003("AW1003", Severity.ERROR, Category.DECLARATION,
            "(reserved)"),

    /**
     * A target named as a string cannot be resolved.
     *
     * <p>Two distinct situations produce it. The annotation processor reports it for a
     * {@code targets()} name that is not on the compile classpath, and only when the weave declares
     * {@code require = Require.REQUIRED}, which is the default; declaring
     * {@code require = Require.OPTIONAL} accepts the absence silently. The engine's weave parser
     * reports it for a name that is not a usable binary class name at all, whatever
     * {@code require()} says, and skips that target.
     *
     * <p>Check the spelling, remembering that a nested class is written with a dollar sign as in
     * {@code "com.acme.Outer$Inner"}. Where the target is deliberately absent at compile time and
     * present at run time, declare {@code require = Require.OPTIONAL}. A class literal is never
     * affected, because the compiler resolves it before either stage sees it.
     */
    WEAVE_TARGET_UNRESOLVABLE("AW1004", Severity.ERROR, Category.DECLARATION,
            "Target is not resolvable and require = REQUIRED"),

    /**
     * A handler is an instance method in a place where there is no instance to call it on.
     *
     * <p>Reported wherever that shape arises, at compile time and at weave time:
     *
     * <ul>
     *   <li>a non-static handler in a {@code @Weave(kind = Kind.STATIC)} weave, which is never
     *       merged into its target and so has no instance;
     *   <li>an {@code @Inject} or {@code @Redirect} handler that is not static and whose declaring
     *       class is not the target itself, which is the same condition seen from the engine;
     *   <li>any non-static {@code @Wrap} handler, refused unconditionally, because a wrap can end
     *       up nested inside another weave's wrap and an inner level is reached through
     *       {@code Operation.call}, which carries the operation's arguments and no receiver;
     *   <li>a merged instance handler bound to a {@code static} target method, which has no
     *       {@code this} to be invoked against.
     * </ul>
     *
     * <p>Declare the handler {@code static}, or declare the weave {@code @Weave(kind =
     * Kind.INSTANCE)} so that it is dissolved into its target and the handler becomes one of that
     * class's own methods. State the handler needs beyond its parameters belongs in a static field
     * of the weave.
     *
     * <p>An error because the alternative is emitting an {@code invokestatic} to an instance method,
     * which fails at link time long after the declaration that caused it.
     */
    STATIC_WEAVE_INSTANCE_HANDLER("AW1005", Severity.ERROR, Category.DECLARATION,
            "Static weave declares a non-static handler"),

    /**
     * A weave class extends something other than {@link Object}.
     *
     * <p>A weave's members are copied into its target, and the target already has a superclass of
     * its own, so there is nowhere for a second one to go.
     *
     * <p>Declare the weave to extend {@link Object} and reach the superclass's members through
     * {@code @Shadow}.
     */
    WEAVE_HAS_SUPERCLASS("AW1006", Severity.ERROR, Category.DECLARATION,
            "Weave class has a superclass other than Object"),

    /**
     * A weave class declares type parameters.
     *
     * <p>Its members are copied verbatim into the target, where a type variable has nothing to bind
     * to. Remove the type parameters; a handler that needs to work over several types takes the
     * erased type, which is what the injected call site provides anyway.
     */
    WEAVE_IS_GENERIC("AW1007", Severity.ERROR, Category.DECLARATION,
            "Weave class is generic"),

    /**
     * A weave class is not {@code final}.
     *
     * <p>A weave class is never subclassed and never instantiated, so a non-final one offers a use
     * it does not have. Declare it {@code final}.
     *
     * <p>A warning rather than an error, because the weave applies unchanged. Not reported for an
     * {@code abstract} weave or an interface: an abstract class cannot be {@code final}, and
     * abstract members are the spelling {@code @Accessor} and {@code @Invoker} use.
     */
    WEAVE_NOT_FINAL("AW1008", Severity.WARNING, Category.DECLARATION,
            "Weave class is not final"),

    /**
     * A target is named as a string although the class is on the compile classpath.
     *
     * <p>Reported by the annotation processor only, since only it knows what the compile classpath
     * holds. Write {@code @Weave(Session.class)} instead: a class literal is checked by the
     * compiler, follows a rename, and survives the class being moved to another package, none of
     * which a string does.
     *
     * <p>Informational, because the string form works.
     */
    WEAVE_TARGET_PREFER_CLASS_LITERAL("AW1009", Severity.INFO, Category.DECLARATION,
            "Target given by name but resolvable as a class literal"),

    /**
     * A selector names an owner type that is not on the compile classpath.
     *
     * <p>Reported by the annotation processor only, and only for an owner written with at least one
     * dot. An unqualified owner is a simple name that cannot be resolved without the file's imports,
     * and reporting it as missing would be wrong more often than right.
     *
     * <p>Check the spelling, or drop the owner when the member belongs to the weave's own target.
     */
    SELECTOR_OWNER_UNRESOLVABLE("AW1010", Severity.ERROR, Category.DECLARATION,
            "Selector owner cannot be resolved"),

    /**
     * A source-form selector does not parse.
     *
     * <p>Covers every way the source grammar can be violated: an empty selector, an invalid member
     * or owner name, a constant literal that is not a valid value of its keyword, an unbalanced
     * {@code <}, a missing type name, a missing expected character, and text left over after the
     * selector ended. The diagnostic carries the offset at which parsing stopped, and
     * {@code de.splatgames.aether.weaver.api.select.SelectorSyntaxException#formatWithCaret()}
     * renders it with a caret under that position.
     *
     * <p>Where the text looks like a JVM descriptor rather than a source-level selector,
     * {@code AW1017} is reported instead, with the corrected spelling as a suggestion.
     */
    SELECTOR_SYNTAX_ERROR("AW1015", Severity.ERROR, Category.DECLARATION,
            "Selector syntax error (with offset)"),

    /**
     * A selector carries generic type arguments, which are dropped.
     *
     * <p>Selectors match erased signatures, because that is what a class file records:
     * {@code List<String>} and {@code List<Integer>} are one method there. The type arguments are
     * accepted so that a signature pasted from source does not have to be stripped by hand, and
     * this says they had no effect.
     *
     * <p>Nothing needs doing. Deleting them makes the selector say what it means.
     */
    SELECTOR_TYPE_ARGUMENTS_IGNORED("AW1016", Severity.INFO, Category.DECLARATION,
            "Type arguments in a selector were ignored (erasure)"),

    /**
     * A selector is written in descriptor syntax without the {@code desc:} prefix.
     *
     * <p>Raised only after the source-form parse has already failed: shape detection never
     * reinterprets input that parsed. The diagnostic carries the same text with {@code desc:}
     * prepended as its suggestion, so an IDE can offer the fix directly.
     *
     * <p>Add the prefix to use the text as a descriptor, or rewrite it in source form.
     */
    SELECTOR_MISSING_DESC_PREFIX("AW1017", Severity.ERROR, Category.DECLARATION,
            "Descriptor syntax used without the desc: prefix"),

    /**
     * A {@code desc:} selector is not a well-formed descriptor.
     *
     * <p>Raised for a wildcard anywhere in the descriptor form, which names exactly one member and
     * admits no pattern; for a missing method or field name; for a field selector with no type, as
     * in {@code desc:owner.name} without the trailing {@code :I}; for an internal name that is not
     * a class descriptor; and for a method or field descriptor the JDK refuses to parse.
     *
     * <p>Use the source form for pattern matching, and write the descriptor exactly as the class
     * file records it otherwise.
     */
    SELECTOR_MALFORMED_DESCRIPTOR("AW1018", Severity.ERROR, Category.DECLARATION,
            "Malformed descriptor after desc:"),

    /**
     * A {@code desc:} method selector ends at its closing parenthesis.
     *
     * <p>A descriptor selector is exact, so the return type is part of what it names and cannot be
     * left off. Append it, using {@code V} for {@code void}, as in {@code desc:owner.name()V}.
     */
    SELECTOR_DESCRIPTOR_MISSING_RETURN_TYPE("AW1019", Severity.ERROR, Category.DECLARATION,
            "desc: method selector is missing its return type"),

    /**
     * The target declares no method matching the selector.
     *
     * <p>Reported for an injection whose {@code method} selector resolves to nothing, for a
     * {@code @Shadow} method that the target does not declare, and for an {@code @Invoker} whose
     * signature matches no method of the target. Every report lists the candidates that were
     * considered, so the diagnostic itself is the reference to check against.
     *
     * <p>An inherited method is not a declared one. Name the class that declares it, or add the
     * parameter types to pick an overload; for an {@code @Invoker} or a {@code @Shadow} method the
     * erased parameter types must match exactly, because that is the same call made from inside the
     * class.
     */
    METHOD_NOT_FOUND("AW1020", Severity.ERROR, Category.DECLARATION,
            "No such method on the target"),

    /**
     * A selector matches more than one method and does not say which.
     *
     * <p>At compile time, raised only when the selector names a member explicitly: a selector whose
     * name is the wildcard matching several methods is {@code AW1022} instead, because the two
     * mistakes have nothing in common there — a wildcard did what it was written to do and merely
     * failed to say how many matches were expected, while a plain name that matched several is an
     * overload the author did not know about. The engine does not draw that distinction: it reports
     * every selector, wildcard or plain, that matches more than one method as this code, so a
     * wildcard whose {@code AW1022} was silenced with {@code allow} at compile time still reports
     * this code at weave time.
     *
     * <p>Add the parameter types, as in {@code run(java.lang.String)}, or use the {@code desc:}
     * form to pin exactly one. The overloads that matched are listed in the diagnostic.
     */
    SELECTOR_AMBIGUOUS("AW1021", Severity.ERROR, Category.DECLARATION,
            "Selector is ambiguous (overloads listed)"),

    /**
     * A wildcard selector matched several methods and no upper bound was declared.
     *
     * <p>Reported by the annotation processor when the selector's name is the wildcard, more than
     * one method matched, and {@code allow} is not set to a positive value.
     *
     * <p>Set {@code allow} to the number that matched. That records the breadth as intentional.
     */
    SELECTOR_WILDCARD_TOO_BROAD("AW1022", Severity.ERROR, Category.DECLARATION,
            "Wildcard selector matched many; allow not set"),

    /**
     * The target method has no body to inject into.
     *
     * <p>The engine raises it for a method whose class file carries no {@code Code} attribute, and
     * the annotation processor for a method declared {@code abstract}.
     *
     * <p>Name an implementing method instead. An abstract declaration says what happens, not how,
     * so there is nothing at that position for a handler to run beside.
     */
    TARGET_METHOD_ABSTRACT("AW1023", Severity.ERROR, Category.DECLARATION,
            "Target method is abstract"),

    /**
     * The target method is compiler-generated: synthetic, or a bridge.
     *
     * <p>Such a method has a body and the injection would work; what it would not do is survive a
     * recompilation that changes the generated shape, which no diagnostic would then report.
     *
     * <p>Name the method the author wrote. For a bridge, that is the one with the specific
     * parameter types; for a lambda body, the method containing the lambda.
     */
    TARGET_METHOD_SYNTHETIC("AW1024", Severity.ERROR, Category.DECLARATION,
            "Target method is synthetic or a bridge"),

    /**
     * The target method is {@code native}.
     *
     * <p>Its implementation is not a class file, so there is nothing here to inject into.
     *
     * <p>Inject into the Java method that calls it, or use {@code @Redirect} at the call site to
     * intercept the transition into native code.
     */
    TARGET_METHOD_NATIVE("AW1025", Severity.ERROR, Category.DECLARATION,
            "Target method is native"),

    /**
     * An instance handler resolved to a position before the constructor's {@code super()} call.
     *
     * <p>An instance handler is dissolved into the target and invoked on {@code this}, which does
     * not exist until the superclass constructor has run; the JVM refuses to load a constructor
     * that touches it earlier. A site at the initialiser's own index counts as being before it,
     * because a site's index is the position code is emitted in front of.
     *
     * <p>Declare the handler {@code static}, or move the point after the {@code super()} call,
     * which is where {@code Point.HEAD} already puts it.
     */
    THIS_UNAVAILABLE_BEFORE_SUPER_CALL("AW1026", Severity.ERROR, Category.DECLARATION,
            "Handler needs this at a point before the super() call"),

    /**
     * One weave attaches to several constructors of a class, and one of them calls another.
     *
     * <p>A single {@code new} runs every constructor in a {@code this()} delegation chain, so the
     * handler is called once for each of them rather than once per object. The diagnostic lists the
     * constructors that are chained.
     *
     * <p>A warning because this is right for a handler that observes and wrong for one that counts,
     * allocates or validates. Where the handler should run once per object, attach it to the
     * constructor the chain ends at, which is the one calling {@code super()} rather than
     * {@code this()}.
     */
    CONSTRUCTOR_DELEGATION_CHAIN("AW1027", Severity.WARNING, Category.DECLARATION,
                    "One weave targets several constructors in the same this() delegation chain — the handler " +
                            "will fire once per constructor (spike 4)"),

    /**
     * The target declares no field of that name.
     *
     * <p>Reported for a {@code @Shadow} field the target does not declare and for an
     * {@code @Accessor} whose field could not be found; at compile time both list the target's own
     * fields in the diagnostic. Also reported by the engine, for the same two shapes, when it
     * merges a weave's members into a target and finds neither the shadowed nor the exposed field;
     * the engine's report carries only the message and the remedy, with no field listing.
     *
     * <p>A {@code @Shadow} declaration is a promise that the target has the member, so check the
     * name or the target's version. For an {@code @Accessor}, name the field explicitly with
     * {@code @Accessor("...")} when the inference from the method name picked the wrong one.
     */
    FIELD_NOT_FOUND("AW1030", Severity.ERROR, Category.DECLARATION,
            "No such field on the target"),

    /**
     * A {@code @Shadow} declares a type the target's member does not have.
     *
     * <p>Raised for a shadowed field whose declared type differs from the target's, comparing
     * erased types. The same code covers a generated accessor whose signature is neither a read nor
     * a write of the field: a getter takes nothing and returns the field's type, a setter takes the
     * field's type and returns {@code void}, and anything else is refused here rather than emitted.
     *
     * <p>Declare the member with the type the target declares.
     */
    SHADOW_TYPE_MISMATCH("AW1031", Severity.ERROR, Category.DECLARATION,
            "@Shadow type does not match the target member"),

    /**
     * A {@code @Shadow} field carries an initialiser, which is never written anywhere.
     *
     * <p>A shadow declares what the target already has, so its own value has no destination. The
     * engine sees this only for the one shape that survives compilation into the class file, a
     * {@code ConstantValue} on a {@code final} field of constant type; every other
     * initialiser is compiled into a constructor or a static initialiser, which a weave may not
     * declare and which are reported as {@code AW1081} and {@code AW1082}.
     *
     * <p>Delete the initialiser. The target's own value is what the weave reads.
     */
    SHADOW_FIELD_INITIALISER_IGNORED("AW1032", Severity.WARNING, Category.DECLARATION,
            "@Shadow field has an initialiser (ignored)"),

    /**
     * A {@code @Shadow(mutable = true)} makes the target's field no longer {@code final}.
     *
     * <p>The target is rewritten with the flag removed. Every other writer of the field, including
     * the target's own constructor, is unaffected: the flag is all that changes. Where the field is
     * {@code static final} and of a constant type, javac has already inlined its value at every
     * call site that was compiled against it, so already-compiled readers keep the old value
     * whatever is written here.
     *
     * <p>Removing a flag is a structural change, so it is unavailable under retransformation and a
     * load-time driver reaching an already-loaded target reports {@code AW2101}.
     *
     * <p>A warning: nothing needs doing, but a target that declared a guarantee no longer has it.
     * Drop {@code mutable = true} where the weave only reads the field.
     */
    SHADOW_REMOVES_FINAL("AW1033", Severity.WARNING, Category.DECLARATION,
            "@Shadow(mutable = true) removes final from a target field"),

    /**
     * A weave shadows a member that another weave adds at a priority that does not run first.
     *
     * <p>A {@code @Shadow} is a promise that the member is there when the shadowing weave is
     * applied. Where the member is contributed by a second weave, that weave has to run first, and
     * the order is decided by {@code @Weave(priority)}.
     *
     * <p>Give the adding weave a strictly higher priority. Equal priority is not enough: the tie is
     * broken by class name, which is stable but arbitrary, so a weave that depended on it would be
     * correct only by coincidence.
     */
    SHADOW_OF_LOWER_PRIORITY_MEMBER("AW1034", Severity.ERROR, Category.DECLARATION,
            "@Shadow of a member added by a lower-priority weave"),

    /**
     * A handler's parameters do not fit the thing it is attached to.
     *
     * <p>One code, three shapes, because in every case the fix is to rewrite the parameter list:
     *
     * <ul>
     *   <li>an {@code @Inject} handler that does not take a prefix of the target's arguments. The
     *       injected call pushes the target's own arguments in order, so a handler may take the
     *       first n of them and nothing else; a parameter has no identity in a compiled method
     *       beyond its position, so there is nothing to name a subset with.
     *   <li>a {@code @Local} capture that is not among the handler's trailing parameters. A
     *       handler's parameters are, in order, the target's argument prefix, then an optional
     *       {@code Callback}, then the captures.
     *   <li>a {@code @Redirect} or {@code @Wrap} handler whose shape is not that of the operation it
     *       matched. Both begin with the operation's own inputs in order, the receiver first for an
     *       instance operation, and return what the operation returned; a wrap then takes one
     *       {@code Operation} parameter, and a redirect may append the enclosing method's own
     *       parameters.
     * </ul>
     *
     * <p>The diagnostic prints both descriptors, the handler's and the thing it was matched
     * against.
     */
    HANDLER_PARAMETERS_NOT_PREFIX("AW1040", Severity.ERROR, Category.DECLARATION,
            "Handler parameters are not a prefix of the target's"),

    /**
     * An {@code @Inject} handler returns something.
     *
     * <p>The injected call is a statement in the middle of the target's own code, so a returned
     * value would have nowhere to go.
     *
     * <p>Declare the handler {@code void}. To change what the target returns, take a
     * {@code ReturnableCallback} and cancel with a value; to substitute an operation and produce a
     * value in its place, use {@code @Redirect} or {@code @Wrap}, whose handlers do return.
     */
    HANDLER_RETURN_TYPE_NOT_VOID("AW1041", Severity.ERROR, Category.DECLARATION,
            "Handler return type is not void"),

    /**
     * The call emitted into the target could not reach the handler.
     *
     * <p>Checked for a {@code @Weave(kind = Kind.STATIC)} weave only. A static weave is never
     * merged, so the injected call is an ordinary cross-class invocation and is subject to ordinary
     * access rules: a {@code private} handler, a handler that is not {@code public} in a different
     * package, or a handler in a class that is not {@code public} is unreachable.
     *
     * <p>An error although the class would verify and load. The failure it prevents is an
     * {@link IllegalAccessError} raised the first time the injected call runs, at a point with
     * nothing to connect it back to the weave.
     */
    HANDLER_NOT_ACCESSIBLE("AW1042", Severity.ERROR, Category.DECLARATION,
            "Handler is not accessible from the target"),

    /**
     * An injection matched fewer positions than it requires, or none at all.
     *
     * <p>The most common code in practice, and it is raised at several stages, each of which adds
     * what it knows:
     *
     * <ul>
     *   <li>the handler declares no {@code @At} at all, so there is no point to resolve;
     *   <li>the point needs a {@code target} and none was given, or takes none and one was given;
     *   <li>the point searched the method and found nothing, in which case the diagnostic lists the
     *       candidates it did find;
     *   <li>the number of matched positions is below the declaration's {@code require};
     *   <li>a {@code @Group}'s total across its members is outside the group's bounds, which is
     *       what is checked instead of each member's own {@code require}.
     * </ul>
     *
     * <p>The point's own diagnostic lists what was found instead. Where several alternatives are
     * written on purpose and any one of them is enough, put them in a {@code @Group} rather than
     * requiring each.
     *
     * <p>An error because a declaration that matched nothing weaves nothing, and a build that
     * accepted it would report success for code that is not there.
     */
    NO_INJECTION_POINT_MATCHED("AW1043", Severity.ERROR, Category.DECLARATION,
            "No injection point matched"),

    /**
     * An injection matched more positions than its {@code allow} permits.
     *
     * <p>An {@code allow} of {@code 0} imposes no upper bound, so this is raised only where a
     * positive bound was written and exceeded.
     *
     * <p>Narrow the declaration with an ordinal or a slice, or raise {@code allow} where the extra
     * matches are wanted. The bound exists so that a target gaining a second matching call is an
     * error rather than a silent doubling of whatever the handler does.
     *
     * <p>A declaration naming a {@code @Group} is not accounted here at all: neither its
     * {@code require} nor its {@code allow} is checked, and the group's total is checked in their
     * place.
     */
    TOO_MANY_INJECTION_POINTS("AW1044", Severity.ERROR, Category.DECLARATION,
            "More points matched than allow permits"),

    /**
     * A {@code @Local} could not be bound to a variable at the injection point.
     *
     * <p>Raised for every resolution strategy that finds nothing usable: a slot that holds a
     * different type there, a name that is not live at that position, an ordinal beyond the number
     * of candidates, no live local of the declared type at all, and a found variable whose type the
     * parameter's declared type does not accept. Every report lists the locals that are live at the
     * point.
     *
     * <p>Liveness is what makes this surprising: a variable declared later in the method, or one
     * whose scope has already ended, does not match even though it exists elsewhere in the local
     * variable table. Pick one from the listing, or inject where the variable is live. Slots are
     * assigned by the compiler and reused once a scope ends, so capturing by name is more robust
     * than capturing by index.
     */
    LOCAL_NOT_RESOLVABLE("AW1050", Severity.ERROR, Category.DECLARATION,
            "@Local could not be resolved"),

    /**
     * A {@code @Local} matches several live variables and does not say which.
     *
     * <p>Raised when the capture is by type alone and more than one variable of that type is live
     * at the injection point. The candidates are listed in the diagnostic.
     *
     * <p>Say which: {@code @Local(name = "...")} is the readable form and
     * {@code @Local(ordinal = n)} the positional one, counted in slot order over the live locals of
     * that type from zero.
     */
    LOCAL_AMBIGUOUS("AW1051", Severity.ERROR, Category.DECLARATION,
            "@Local is ambiguous"),

    /**
     * A {@code @Local} resolves by name, type or ordinal, and the target carries no
     * {@code LocalVariableTable}.
     *
     * <p>Only capture by explicit slot index works without that attribute; nothing is inferred from
     * the method's shape, because a wrong slot reads a different value rather than failing.
     *
     * <p>Recompile the target with {@code -g}, or read its bytecode and capture with
     * {@code @Local(index = <slot>)}.
     */
    LOCAL_VARIABLE_TABLE_MISSING("AW1052", Severity.ERROR, Category.DECLARATION,
            "Target has no LocalVariableTable; recompile with -g"),

    /**
     * A {@code @Local(mutable = true)} is declared on a parameter that is not a reference carrier.
     *
     * <p>A Java parameter is passed by value, so assigning to it would change the handler's own
     * copy and leave the target holding the old one.
     *
     * <p>Declare the parameter as {@code LocalRef<T>}, or {@code LocalIntRef} and its siblings for
     * a primitive; a carrier is what writes the value back into the target's slot.
     */
    LOCAL_MUTABLE_NEEDS_REF("AW1053", Severity.ERROR, Category.DECLARATION,
            "@Local(mutable = true) on a parameter that is not a LocalRef"),

    /**
     * A reference carrier is declared without {@code @Local(mutable = true)}.
     *
     * <p>The opposite mistake to {@code AW1053}: the parameter can write the target's variable and
     * the declaration does not say that it may.
     *
     * <p>Add {@code mutable = true} where the handler means to write the variable, or declare the
     * parameter as the variable's own type where it only reads it. A carrier that may not be
     * written to is a handle nobody should be holding.
     */
    LOCAL_REF_WITHOUT_MUTABLE("AW1054", Severity.ERROR, Category.DECLARATION,
            "A LocalRef parameter without @Local(mutable = true)"),

    /**
     * Two declarations claim the same call site, and at least one of them is a {@code @Redirect}.
     *
     * <p>A call has one callee, so two redirects of it cannot both apply. A redirect mixed with a
     * wrap is refused for a different reason: the redirect removes the operation, and the wrap
     * hands that same operation to its handler, so the wrap would hold a handle to something the
     * woven method no longer does.
     *
     * <p>Any number of wraps on one site is fine and is not reported; they nest, outermost first by
     * descending {@code @Weave(priority)}.
     *
     * <p>Make both declarations {@code @Wrap}, or narrow one with an ordinal or a slice. The
     * diagnostic names every claimant and the site they share.
     */
    DUPLICATE_REDIRECT("AW1060", Severity.ERROR, Category.DECLARATION,
            "Two @Redirects on the same call site"),

    /**
     * A {@code @Redirect} or {@code @Wrap} matched something that is not an operation.
     *
     * <p>Both substitute an operation, so both need one to substitute. Raised when the declaration
     * names a built-in point that locates a position rather than an operation, when the resolved
     * site is the position after an operation rather than the operation itself, when the
     * instruction at the site is not a call, a field access or an instantiation, and when an
     * instantiation's constructor call could not be located in the method's body.
     *
     * <p>Use {@code @Inject} to add code at a position; point the redirect or wrap at the call,
     * field access or instantiation it is meant to take over. A contributed point is not checked
     * against a list and is judged by the shape it resolves to. The last of the four shapes is a
     * body the engine does not understand and is worth reporting with the class file rather than
     * working around.
     */
    OPERATION_TARGET_UNSUPPORTED("AW1061", Severity.ERROR, Category.DECLARATION,
            "The matched position is not an operation that can be replaced or wrapped"),

    /**
     * A {@code @Wrap} handler's last parameter is not an {@code Operation}.
     *
     * <p>The {@code Operation} must be last. This is the rule that holds until a second weave
     * arrives: a handler with trailing parameters works as the outermost wrap, because the
     * enclosing method's arguments are still on the stack, and fails the moment another weave nests
     * inside it, since an inner level receives only what {@code Operation.call} carries. The engine
     * checks this whenever the parameter list is non-empty, including a handler that declares no
     * {@code Operation} at all, in which case this code and {@code AW1063} are both reported for the
     * same handler. The annotation processor instead reports only {@code AW1063} for a handler with
     * no {@code Operation}, and reaches this check exclusively for a handler that has one out of
     * place.
     *
     * <p>Delete the trailing parameters. A {@code @Redirect} handler may append the enclosing
     * method's parameters; a wrap handler may not.
     */
    WRAP_PARAMETERS_AFTER_OPERATION("AW1062", Severity.ERROR, Category.DECLARATION,
            "A @Wrap handler declares parameters after its Operation"),

    /**
     * A {@code @Wrap} handler declares no {@code Operation} parameter.
     *
     * <p>A wrap surrounds the operation it matched, and the {@code Operation} is the handle through
     * which it performs it. Without one there is nothing for the handler to call.
     *
     * <p>Add a trailing {@code Operation<R>} parameter, where {@code R} is the operation's result
     * type boxed, or use {@code @Redirect}, which replaces the operation instead of wrapping it and
     * needs no handle to it.
     */
    WRAP_OPERATION_MISSING("AW1063", Severity.ERROR, Category.DECLARATION,
            "A @Wrap handler declares no Operation parameter"),

    /**
     * A handler takes a plain {@code Callback} and its target returns a value.
     *
     * <p>Cancelling a value-returning method through a plain callback would leave it with nothing
     * to return.
     *
     * <p>Declare {@code ReturnableCallback<T>} with {@code T} the target's return type, boxed.
     */
    CANCEL_ON_NON_VOID_TARGET("AW1070", Severity.ERROR, Category.DECLARATION,
            "Callback.cancel() on a non-void target"),

    /**
     * A {@code ReturnableCallback}'s type argument does not match the target's return type.
     *
     * <p>Two shapes: a {@code ReturnableCallback} on a {@code void} target, which has no value to
     * return instead; and a {@code ReturnableCallback<T>} where {@code T} is not the target's
     * return type. The comparison boxes a primitive return type first, so a target returning
     * {@code int} is matched by {@code ReturnableCallback<Integer>}.
     *
     * <p>Declare the callback with the boxed return type, or a plain {@code Callback} for a
     * {@code void} target.
     */
    CALLBACK_TYPE_MISMATCH("AW1071", Severity.ERROR, Category.DECLARATION,
            "ReturnableCallback<T> where T ≠ the target's return type"),

    /**
     * A handler reads {@code ReturnableCallback.value()} at a point where the target has not
     * computed one.
     *
     * <p>{@code value()} is the value the target is about to return, so it exists only at the
     * points that carry one. The diagnostic names the points that do.
     *
     * <p>Move the injection to one of them, or drop the call. Reading it elsewhere would hand the
     * handler a {@code null} it cannot tell from a real one, which is why this is an error rather
     * than a warning.
     */
    CALLBACK_VALUE_UNAVAILABLE("AW1072", Severity.ERROR, Category.DECLARATION,
            "ReturnableCallback.value() used at a point where no value exists"),

    /**
     * A merged member collides with one that already exists.
     *
     * <p>Three shapes. A merged field whose name the target already declares, or a merged method
     * whose name and descriptor it already declares; a handler of a dissolving weave whose name and
     * descriptor the target already declares; and two or more weaves merging the same member, or
     * the same handler signature, into one target. A field collides on its name alone because a
     * class cannot declare two fields of the same name regardless of type.
     *
     * <p>Declare the member {@code @Unique} to have it renamed instead, or rename it by hand.
     * Marking only some of several colliding members {@code @Unique} does not help: a mangled
     * member and a plainly named one still collide on the plain name. A handler cannot be
     * {@code @Unique}, because the injection sites call it by name.
     *
     * <p>An error because overwriting the target's own member would replace working code with an
     * uninitialised copy.
     */
    MERGED_MEMBER_COLLIDES("AW1080", Severity.ERROR, Category.DECLARATION,
            "Merged member collides"),

    /**
     * A weave class declares a constructor.
     *
     * <p>It cannot be merged, because the target already has its own. The annotation processor
     * reports it only for a constructor written in source; an implicit default constructor is not
     * reported there. The engine has no source to consult and instead treats a constructor that
     * takes no parameters and whose code holds at most three instructions as implicit, so a
     * hand-written no-argument constructor with a trivial body is not reported at weave time either,
     * even though the processor would have reported it at compile time.
     *
     * <p>Initialise merged state from an {@code @Inject} at the target constructor's
     * {@code Point.HEAD}, which is the only code that runs once per instance.
     */
    WEAVE_DECLARES_CONSTRUCTOR("AW1081", Severity.ERROR, Category.DECLARATION,
            "Weave declares a constructor"),

    /**
     * A weave class declares a static initialiser.
     *
     * <p>Merging one into a target that already has its own is not a 0.1.0 capability. A static
     * field's initialiser compiles into a static initialiser, so this is also what a weave that
     * assigns to one of its own static fields produces.
     */
    WEAVE_DECLARES_STATIC_INITIALISER("AW1082", Severity.ERROR, Category.DECLARATION,
            "Weave declares a static initialiser"),

    /**
     * A weave merges {@code toString}, {@code equals}, {@code hashCode} or {@code main} into its
     * target.
     *
     * <p>The merge is performed. The warning exists because these replace behaviour the platform
     * itself calls: collections, debuggers and logging all invoke them without the target's author
     * being able to see it happen.
     *
     * <p>Nothing needs doing beyond confirming that it is meant.
     */
    MERGED_OBJECT_METHOD("AW1083", Severity.WARNING, Category.DECLARATION,
            "Merging toString/equals/hashCode/main"),

    /**
     * A weave class implements an interface.
     *
     * <p>Adding an interface to a target is not a 0.1.0 capability, so the interface would be
     * silently dropped rather than transferred.
     *
     * <p>Declare the weave to implement nothing.
     */
    WEAVE_IMPLEMENTS_INTERFACE("AW1084", Severity.ERROR, Category.DECLARATION,
            "Weave class implements an interface (0.1.0)"),

    /**
     * A reserved number in the declaration range.
     *
     * <p>No code reports it and no build can produce it. The constant holds the number so that it
     * cannot be taken by an unrelated condition.
     */
    RESERVED_1085("AW1085", Severity.WARNING, Category.DECLARATION,
            "(reserved)"),

    /**
     * A reserved number in the declaration range.
     *
     * <p>No code reports it and no build can produce it. The constant holds the number so that it
     * cannot be taken by an unrelated condition.
     */
    RESERVED_1086("AW1086", Severity.ERROR, Category.DECLARATION,
            "(reserved)"),

    /**
     * A weave targets a class that is itself a weave.
     *
     * <p>A weave class is a declaration rather than a runtime class: its members are folded into
     * its own targets and it is never loaded as itself, so by the time anything could be woven into
     * it, it is no longer there.
     *
     * <p>Raised in three places, which is why it appears both as a declaration error and as a
     * policy denial: the annotation processor sees the {@code @Weave} on the named target,
     * conflict detection sees it across the whole plan, and the default weave policy refuses a
     * declared weave class handed to the weaver.
     *
     * <p>Target the class the other weave targets, and order the two with {@code priority}.
     */
    WEAVE_TARGETS_WEAVE("AW1087", Severity.ERROR, Category.DECLARATION,
            "Weave targets a weave class"),

    /**
     * A weave merges an instance field into a record.
     *
     * <p>A record's {@code equals}, {@code hashCode}, {@code toString} and accessors are all
     * derived from its components, so a merged instance field is state that every one of them
     * ignores.
     *
     * <p>Declare the field {@code static}, or keep the state outside the record. A static field is
     * not reported.
     */
    MERGE_FIELD_INTO_RECORD("AW1088", Severity.ERROR, Category.DECLARATION,
            "Merging a field into a record"),

    /**
     * A weave merges an instance field into an enum.
     *
     * <p>The field is added, with the JVM's default value. An enum's constants are constructed in
     * its static initialiser, which has already been compiled, so nothing assigns to the new field
     * for any constant.
     *
     * <p>A warning rather than an error: nothing needs doing where the default value is what is
     * wanted. Otherwise write the field from an {@code @Inject} at the enum constructor's
     * {@code Point.HEAD}, which is the only code that runs per constant. A static field is not
     * reported.
     */
    MERGE_FIELD_INTO_ENUM("AW1089", Severity.WARNING, Category.DECLARATION,
            "Merging a field into an enum"),

    /**
     * A {@code @Shadow} is declared in a {@code @Weave(kind = Kind.STATIC)} weave.
     *
     * <p>A static weave is never merged into its target, so there is nothing for the declaration to
     * bind to; the member would remain an ordinary member of the weave class, reading and writing
     * the weave rather than the target.
     *
     * <p>Declare the weave {@code @Weave(kind = Kind.INSTANCE)}, or reach the member through an
     * {@code @Accessor} or an {@code @Invoker}, which a static weave can use.
     */
    SHADOW_IN_STATIC_WEAVE("AW1090", Severity.ERROR, Category.DECLARATION,
            "@Shadow used in a static weave"),

    /**
     * A {@code @Unique} is declared in a {@code @Weave(kind = Kind.STATIC)} weave.
     *
     * <p>{@code @Unique} asks for a member to be renamed on its way into the target, and a static
     * weave's members never go there, so the declaration has no effect to ask for.
     *
     * <p>Delete the annotation, or declare the weave {@code @Weave(kind = Kind.INSTANCE)}.
     */
    UNIQUE_IN_STATIC_WEAVE("AW1091", Severity.ERROR, Category.DECLARATION,
            "@Unique used in a static weave"),

    /**
     * The target is an anonymous or a local class, recognised by its {@code EnclosingMethod}
     * attribute.
     *
     * <p>Weaving proceeds. The warning is about the name, which the compiler invented: the trailing
     * number counts the anonymous and local classes of the enclosing class in source order, so
     * adding an unrelated lambda or anonymous class earlier in that file renumbers every one after
     * it and this weave would then modify a different class without saying so.
     *
     * <p>Target the enclosing class and narrow with a selector, or give the class a name.
     */
    TARGET_IS_ANONYMOUS_OR_LOCAL("AW1092", Severity.WARNING, Category.DECLARATION,
            "Target is an anonymous or local class"),

    /**
     * A merged field carries an initialiser, which is dropped.
     *
     * <p>The field is copied into the target with the JVM's default value. Raised only for the one
     * shape of initialiser that survives compilation into the class file, a {@code ConstantValue} on
     * a {@code final} field of constant type; every other initialiser is compiled into a constructor
     * or a static initialiser, reported as {@code AW1082} for a static initialiser and as
     * {@code AW1081} for a constructor. The annotation processor does not check an implicit
     * constructor, so an instance field initialiser combined with no hand-written constructor is
     * caught as {@code AW1081} only at weave time.
     *
     * <p>Write the value from an {@code @Inject} at the target constructor's {@code Point.HEAD},
     * which is the only place that runs once per instance.
     */
    MERGED_FIELD_INITIALISER_IGNORED("AW1093", Severity.INFO, Category.DECLARATION,
            "Merged field has an initialiser (ignored; use an @Inject at constructor HEAD)"),

    /**
     * A {@code @Unique} member takes a mangled name, because the target already declares its own.
     *
     * <p>This is {@code @Unique} doing what it was asked to do, reported once so that the new name
     * is not a surprise: it appears in stack traces and profiles of the woven class.
     *
     * <p>Nothing needs doing. Declare {@code @Unique(silent = true)} to stop reporting it.
     */
    UNIQUE_MEMBER_MANGLED("AW1094", Severity.INFO, Category.DECLARATION,
            "@Unique member renamed to avoid a collision with the target's own"),

    /**
     * A generated accessor or invoker would be emitted under a name and descriptor the target
     * already declares.
     *
     * <p>Generating over the target's own method would replace working code.
     *
     * <p>Rename the declaration. A generated member cannot be {@code @Unique}, because callers
     * reach it by the name it is declared under, so renaming it is the only fix.
     */
    GENERATED_MEMBER_COLLIDES("AW1095", Severity.ERROR, Category.DECLARATION,
            "A generated accessor or invoker collides with a member the target already declares"),

    /**
     * A weave that must be dissolved into its target was supplied without its class file.
     *
     * <p>Merging a member copies its body, and a method's body exists only in the class file: the
     * parsed model deliberately does not carry one.
     *
     * <p>Give the weaver a byte source with {@code WeaverBuilder.weaveBytes(...)}. Raised only for
     * an instance weave with a structural effect that needs bodies, so a weave that only injects is
     * unaffected.
     */
    WEAVE_BYTES_UNAVAILABLE("AW1096", Severity.ERROR, Category.DECLARATION,
            "The bytes of a weave class that must be dissolved into its target are not available"),

    /**
     * A generated setter would write a field the target declares {@code final}.
     *
     * <p>An error although the class would verify: a {@code putfield} to a final field from
     * anything but the declaring constructor raises an {@link IllegalAccessError} the first time
     * the setter is called, at a point with nothing to connect it back to the weave.
     *
     * <p>A final field is written once, by the constructor. Use {@code @Shadow(mutable = true)},
     * which removes the flag deliberately and reports {@code AW1033} when it does; an accessor has
     * no way to express that intent.
     */
    ACCESSOR_WRITES_FINAL_FIELD("AW1097", Severity.ERROR, Category.DECLARATION,
            "A generated setter would write a final field"),

    /**
     * No injection point is registered under the identifier a declaration names.
     *
     * <p>Check the spelling. A contributed point is always written {@code namespace:NAME} and needs
     * its plugin on the classpath; a built-in point never contains a colon.
     */
    INJECTION_POINT_UNKNOWN("AW1101", Severity.ERROR, Category.INJECTION_POINT,
            "No injection point is registered under that identifier"),

    /**
     * A shift was declared where it cannot be honoured.
     *
     * <p>Two sources. An injection point may declare which shifts it supports, and one it refuses
     * would land somewhere the verifier rejects. Separately, every {@code @Redirect} and every
     * {@code @Wrap} refuses any shift at all, at every point: both take over the operation they
     * matched, so a shifted position names a neighbouring instruction that the handler's signature
     * does not describe.
     *
     * <p>Remove the shift, or use a point that names the position directly.
     */
    SHIFT_NOT_SUPPORTED("AW1102", Severity.ERROR, Category.INJECTION_POINT,
            "shift not supported by this point"),

    /**
     * A target selector also names something reached through an {@code invokedynamic}.
     *
     * <p>A lambda, a method reference and string concatenation are {@code invokedynamic}
     * instructions, and {@code INVOKE} matches ordinary calls only. The diagnostic lists what was
     * skipped and says how many ordinary calls did match and were woven; where that count is zero,
     * the injection attached to no call at all.
     *
     * <p>The method behind such an instruction is invoked by the JVM rather than by this method, so
     * inject into that method directly.
     */
    SELECTOR_MATCHES_INVOKEDYNAMIC("AW1103", Severity.INFO, Category.INJECTION_POINT,
            "Selector would have matched an invokedynamic (lambda/concat)"),

    /**
     * A handler declares {@code @Result} at a position that leaves nothing on the stack.
     *
     * <p>Raised when the matched position does not follow a call at all, and when the call it
     * follows returns {@code void}.
     *
     * <p>{@code @Result} receives what the matched call produced, so it belongs at the point after
     * a call that returns something. Drop the annotation to inject beside the call instead, or
     * point it at a call with a result.
     */
    INVOKE_AFTER_VOID_CALL("AW1104", Severity.ERROR, Category.INJECTION_POINT,
            "INVOKE_AFTER on a void call, handler expects a value"),

    /**
     * A site falls between a {@code new} and the constructor call that completes it.
     *
     * <p>The stack there holds a reference to an object that does not exist yet, and the JVM
     * refuses code that touches it.
     *
     * <p>Move the point after the constructor call, using an ordinal, a slice, or the point after
     * the constructor itself; or use {@code @Redirect} or {@code @Wrap}, which take the whole
     * instantiation over as one operation.
     */
    SITE_IN_UNINITIALISED_WINDOW("AW1105", Severity.ERROR, Category.INJECTION_POINT,
            "Site falls inside a new/<init> uninitialised window"),

    /**
     * An ordinal names a match beyond the number that were found.
     *
     * <p>Ordinals are zero-based and are counted within the slice, so adding or changing a slice
     * changes the numbering. The diagnostic says how many matches there were.
     */
    ORDINAL_OUT_OF_RANGE("AW1110", Severity.ERROR, Category.INJECTION_POINT,
            "ordinal out of range"),

    /**
     * A shift moves a site outside the range it was found in.
     *
     * <p>The range is the slice where one was declared, and the whole method otherwise; a site
     * shifted past the end of the code is refused for the same reason.
     *
     * <p>Widen the slice, or drop the shift. The diagnostic prints the original index, the shifted
     * index and the range.
     */
    SHIFT_LEAVES_SLICE("AW1111", Severity.ERROR, Category.INJECTION_POINT,
            "shift moves the site out of the slice"),

    /**
     * A {@code shift = BY} declares a large offset.
     *
     * <p>A warning, and the shift is applied. A large offset almost always means a slice or a
     * different point would express the intent better, and it breaks on any recompilation of the
     * target, which is a failure that appears without the declaration having changed.
     */
    SHIFT_OFFSET_LARGE("AW1112", Severity.WARNING, Category.INJECTION_POINT,
            "shift = BY with a large offset"),

    /**
     * A slice's {@code from} bound matched nothing.
     *
     * <p>Raised when the bound names an injection point that is not registered, and when the point
     * searched the method and found nothing.
     *
     * <p>A slice that cannot be located would silently widen to the whole method, so it is refused
     * instead and the whole injection contributes no sites.
     */
    SLICE_FROM_UNRESOLVED("AW1120", Severity.ERROR, Category.INJECTION_POINT,
            "Slice from did not resolve"),

    /**
     * A slice's {@code to} bound matched nothing.
     *
     * <p>The same two situations as {@code AW1120}, and the same consequence: the slice is refused
     * rather than widened to the end of the method.
     */
    SLICE_TO_UNRESOLVED("AW1121", Severity.ERROR, Category.INJECTION_POINT,
            "Slice to did not resolve"),

    /**
     * A slice's {@code to} bound resolves to a position before its {@code from} bound.
     *
     * <p>Both bounds resolved; they are simply in the wrong order, which describes an empty region
     * and matches nothing.
     *
     * <p>The {@code to} bound must resolve at or after {@code from}. Check that both name what they
     * are meant to, remembering that each bound has its own ordinal.
     */
    SLICE_BOUNDS_INVERTED("AW1122", Severity.ERROR, Category.INJECTION_POINT,
            "Slice to precedes from"),

    /**
     * A site resolved to an instruction nothing can reach.
     *
     * <p>A handler injected there would never run, and nothing else would say so. This is usually a
     * selector matching a compiler-generated leftover rather than the code that was meant.
     *
     * <p>The site is dropped, so the declaration may go on to report {@code AW1043} for having
     * matched too few. Narrow the selector with a slice or an ordinal.
     */
    SITE_IN_DEAD_CODE("AW1130", Severity.WARNING, Category.INJECTION_POINT,
            "Site is in dead code"),

    /**
     * A protected range of the target was split around injected code.
     *
     * <p>The target's own {@code catch} blocks no longer cover the handler calls, so an exception
     * thrown by a handler is not silently caught by code written for the target's own failures.
     *
     * <p>Nothing needs doing. It is reported because it changes which exceptions the target
     * observes, which a weave meant to be caught by the target would need to know.
     */
    PROTECTED_RANGE_SPLIT("AW1131", Severity.INFO, Category.INJECTION_POINT,
                    "A protected range was split around the injected code so the handler's exceptions are not " +
                            "caught by the target"),

    /**
     * The annotation processor could not read the target's class file, so it checked no injection
     * points.
     *
     * <p>Nothing needs doing. This is expected when the target is compiled from source in the same
     * round as the weave, and the points are validated at weave time, where the class file always
     * exists.
     *
     * <p>The practical consequence is that the point errors in the {@code AW11xx} range arrive from
     * the weaver rather than from the compiler for that target.
     */
    INJECTION_POINTS_NOT_VALIDATED("AW1200", Severity.INFO, Category.COMPILE_TIME,
                    "Injection points could not be validated at compile time (the target's class file was not " +
                            "readable); they will be validated at weave time"),

    /**
     * An extension class is not {@code final}.
     *
     * <p>An extension class is never instantiated and never subclassed, so a non-final one offers a
     * use it does not have. Declare it {@code final}.
     *
     * <p>A warning: the extension is still contributed.
     */
    EXTENSION_NOT_FINAL("AW1300", Severity.WARNING, Category.EXTENSION,
            "Extension class is not final"),

    /**
     * A {@code public} method of an extension class is not {@code static}.
     *
     * <p>Every {@code public} method of an extension class is contributed, and the receiver is
     * passed as a parameter, so a contributed method has no instance of its own to be called on.
     *
     * <p>Declare it {@code static}, or make it {@code private} where it is a helper: a non-public
     * method is not contributed and is not checked.
     */
    EXTENSION_METHOD_NOT_STATIC("AW1301", Severity.ERROR, Category.EXTENSION,
            "A public method of an extension class is not static"),

    /**
     * A contributed method names no receiver.
     *
     * <p>Every {@code public} method of an extension class is contributed to some type, and
     * {@code @Receiver} is what names it: on the first parameter for an instance extension, on the
     * method itself for a static one.
     *
     * <p>Annotate the first parameter {@code @Receiver}, name one receiver for the whole class with
     * {@code @Extension(Type.class)}, or make the method {@code private}.
     */
    EXTENSION_RECEIVER_MISSING("AW1302", Severity.ERROR, Category.EXTENSION,
            "A contributed method declares no @Receiver"),

    /**
     * A {@code @Receiver} is on a parameter other than the first.
     *
     * <p>The rewrite passes the receiver straight through as argument zero, which is where the JVM
     * has already put it for the virtual call being replaced.
     *
     * <p>Move the {@code @Receiver} parameter to the front.
     */
    EXTENSION_RECEIVER_NOT_FIRST("AW1303", Severity.ERROR, Category.EXTENSION,
            "@Receiver is not on the first parameter"),

    /**
     * A receiver's type cannot carry the member being contributed.
     *
     * <p>Three shapes. A {@code @Receiver} parameter whose type is a primitive, an array or a type
     * variable, none of which has a class file for the compiler to resolve a contributed member
     * against. A {@code @Receiver} on a method or on a field whose {@code value()} is not a class or
     * an interface — a primitive, an array type, or the default of {@code void.class} that
     * {@code value()} takes when it is left unset — none of which can have a member either.
     *
     * <p>Name a class or interface, as in {@code @Receiver(BigDecimal.class)}, boxing the value
     * where a primitive was meant.
     */
    EXTENSION_RECEIVER_NOT_A_TYPE("AW1304", Severity.ERROR, Category.EXTENSION,
            "The receiver's type cannot carry a method"),

    /**
     * The receiver, or one of its supertypes, already declares a member that collides with the
     * extension: a field collides on name alone, a method on name and descriptor.
     *
     * <p>javac resolves the call, or the field read, to the member that genuinely exists, so the
     * extension would never be reached. The diagnostic names the class that declares it, which may
     * be a supertype of the receiver rather than the receiver itself.
     *
     * <p>Rename the extension, or use {@code @Weave} with {@code @Inject} or {@code @Redirect} to
     * change what the existing member does. The compile-time counterpart to {@code AW1309}, which
     * is the same collision found at weave time.
     */
    EXTENSION_COLLIDES_WITH_MEMBER("AW1305", Severity.ERROR, Category.EXTENSION,
            "The extension collides with a member the receiver already has"),

    /**
     * An extension class declares type parameters.
     *
     * <p>Contributed methods are looked up by descriptor, and a type parameter on the holder has
     * nothing to bind to at the call site. Remove the type parameters; nothing else in the class is
     * checked once this is reported.
     */
    EXTENSION_IS_GENERIC("AW1306", Severity.ERROR, Category.EXTENSION,
            "Extension class is generic"),

    /**
     * An extension class has a superclass other than {@link Object}, or implements an interface.
     *
     * <p>Nothing about the holder participates at the call site, so a supertype states a
     * relationship the framework cannot honour.
     *
     * <p>Make it extend {@link Object} and implement nothing. Nothing else in the class is checked
     * once this is reported.
     */
    EXTENSION_HAS_SUPERTYPE("AW1307", Severity.ERROR, Category.EXTENSION,
            "Extension class has a superclass or an interface"),

    /**
     * Two extensions contribute the same member to the same receiver.
     *
     * <p>Both would rewrite the same instruction, and which one won would depend on the order the
     * manifests were found in.
     *
     * <p>Raised within one extension class at compile time, where the usual cause is two overloads
     * that erase to the same descriptor; javac allows that no more than this does, but a generic
     * parameter can make two distinct signatures collide after erasure, and erasure is all the call
     * site has. Raised across classes at weave time, where two artefacts contribute the same call.
     *
     * <p>Rename one of them, or remove one of the two.
     */
    DUPLICATE_EXTENSION("AW1308", Severity.ERROR, Category.EXTENSION,
            "Two extensions contribute the same method to the same receiver"),

    /**
     * A call site names an extension whose receiver, or one of its supertypes, genuinely declares
     * that method.
     *
     * <p>javac resolves the call to the real method, and rewriting it would redirect a call that
     * was already correct. The diagnostic names the class that declares the real method, which may
     * be a supertype of the receiver rather than the receiver itself.
     *
     * <p>Delete the extension, or rename it so it no longer collides. The weave-time counterpart to
     * {@code AW1305}.
     */
    EXTENSION_SHADOWED_AT_CALL_SITE("AW1309", Severity.ERROR, Category.EXTENSION,
            "A call site names an extension whose receiver genuinely declares that method"),

    /**
     * A contributed method declares its own type parameters.
     *
     * <p>The stub the compiler resolves against would carry a type variable with nothing to bind
     * it, so inference at the call site would differ from what the declaration says.
     *
     * <p>Use the erased type, or move the method to an ordinary utility class.
     */
    EXTENSION_METHOD_IS_GENERIC("AW1310", Severity.ERROR, Category.EXTENSION,
            "An extension method declares its own type parameters"),

    /**
     * A receiver is written as a parameterised type.
     *
     * <p>Erasure is all the call site has, so the member would be contributed to every instance of
     * that raw type in the program, whatever its type argument.
     *
     * <p>Name the raw type and check inside the method, or narrow the receiver to a type that is
     * not parameterised, so that the declaration says what will actually happen.
     */
    EXTENSION_RECEIVER_IS_PARAMETERISED("AW1311", Severity.ERROR, Category.EXTENSION,
            "The receiver is a parameterised type, which erasure cannot tell from any other"),

    /**
     * A receiver is {@link Object}.
     *
     * <p>The extension is contributed. Every expression in every module that reads the manifest
     * will then offer the member, including expressions whose type has nothing to do with what it
     * means.
     *
     * <p>A warning rather than an error, because contributing to {@link Object} is occasionally
     * what somebody means and the framework cannot tell that from somebody having written the
     * widest type that happened to compile. Name the narrowest type the member is meaningful on.
     */
    EXTENSION_RECEIVER_IS_OBJECT("AW1312", Severity.WARNING, Category.EXTENSION,
            "The receiver is java.lang.Object, so the method is offered on every expression"),

    /**
     * A contributed method carries {@code @Receiver} both on the method and on a parameter.
     *
     * <p>The two forms mean different things: the method form contributes a {@code static} method
     * to a type, the parameter form contributes an instance method to its values. A declaration
     * that asks for both says nowhere which of the two it is.
     *
     * <p>Keep {@code @Receiver} on the method for a static extension, or on the first parameter for
     * an instance one.
     */
    EXTENSION_RECEIVER_DECLARED_TWICE("AW1313", Severity.ERROR, Category.EXTENSION,
            "A contributed method names a receiver both on the method and on a parameter"),

    /**
     * A field carrying {@code @Receiver} is not {@code public static final}.
     *
     * <p>A contributed constant is read off the receiver as one of its own, and a field that is not
     * final would be shared writable state on a type whose author never gave it one.
     *
     * <p>Declare it {@code public static final}, or drop the {@code @Receiver} and keep it as the
     * extension class's own field. A field without {@code @Receiver} is ordinary state and is not
     * checked at all.
     */
    EXTENSION_CONSTANT_NOT_FINAL("AW1314", Severity.ERROR, Category.EXTENSION,
            "A contributed constant is not static final"),

    /**
     * A {@code nulls} policy is declared where there is no receiver value to check.
     *
     * <p>A static contribution and a constant are read off the type itself, so no receiver
     * reference exists at the call site for a check to look at. Only a {@code @Receiver} on a
     * parameter carries a value.
     *
     * <p>Remove {@code nulls}, or mark a parameter {@code @Receiver} to contribute an instance
     * method instead. This is checked only at compile time: a manifest that asks for the same
     * combination by hand is accepted, and the null check is simply not emitted for it.
     */
    EXTENSION_NULLS_WITHOUT_RECEIVER("AW1315", Severity.ERROR, Category.EXTENSION,
            "nulls is declared where there is no receiver value to check"),

    /**
     * A contributed method does not take the class's declared receiver as its first parameter.
     *
     * <p>Raised only where the class declares a receiver with {@code @Extension(Type.class)}. That
     * makes parameter zero the receiver by position, so every contributed method must take exactly
     * that type first, and a method with no parameters at all is refused too.
     *
     * <p>Nothing is inferred from the type: a method that takes something else is refused rather
     * than quietly left out, because being left out is indistinguishable from being spelled wrong
     * at the call site that then fails to compile.
     *
     * <p>Take the declared type as the first parameter, make the method {@code private}, or name
     * the method's own receiver with {@code @Receiver}.
     */
    EXTENSION_RECEIVER_NOT_THE_CLASSES("AW1316", Severity.ERROR, Category.EXTENSION,
            "A contributed method does not take the class's declared receiver first"),

    /**
     * The target's class file is older than major version 50.
     *
     * <p>Its stack map frames are absent or inferred, and the engine's transforms assume they are
     * present.
     *
     * <p>Recompile the target for a supported release. This is a refusal by the default weave
     * policy, so the class is passed through unwoven.
     */
    CLASS_FILE_VERSION_TOO_OLD("AW2003", Severity.ERROR, Category.TARGET,
            "Class file version < 50"),

    /**
     * The target was compiled with preview features of a different release.
     *
     * <p>A preview class file is accepted by the exact JVM version that produced it and by no
     * other, so nothing could load what weaving it produced. The class is left unwoven.
     *
     * <p>Recompile the target against the JVM that will run it, or run the weaver on the JVM the
     * target was compiled with.
     */
    PREVIEW_CLASS_FILE_MISMATCH("AW2004", Severity.ERROR, Category.TARGET,
            "Preview-feature class file, JVM mismatch"),

    /**
     * A structural weave cannot be applied to a class that is already loaded.
     *
     * <p>The JVM forbids changing a loaded class's member set, so this is a limit of
     * retransformation rather than of the weave. Raised by the agent when it finds already-loaded
     * targets of a structural weave, and when the JVM refuses a retransformation outright.
     *
     * <p>Classes that have not been loaded yet are still woven in full, including this weave, and
     * the agent stays installed. Weave at build time with the Maven plugin, or start the JVM with
     * {@code -javaagent} so that the targets are woven as they load.
     */
    STRUCTURAL_WEAVE_NEEDS_PRELOAD("AW2101", Severity.ERROR, Category.DRIVER,
            "Structural weave cannot be applied by retransformation"),

    /**
     * A class already carries a weave record from a different plan, and is being woven again at
     * build time.
     *
     * <p>Applying a second plan on top would run both, and every weave they have in common would
     * fire twice. The diagnostic prints both plan fingerprints and how many weaves the earlier one
     * held.
     *
     * <p>The usual cause is an output directory woven once already and not rebuilt since a weave
     * changed, which a clean build settles. Otherwise the input is an artefact that has been
     * through this before, such as a shaded jar, and the original classes are what should be woven.
     */
    ALREADY_WOVEN_DIFFERENT_PLAN("AW2201", Severity.ERROR, Category.IDEMPOTENCE,
            "Build-time weaving a class already woven with a different plan"),

    /**
     * A class woven at build time is being woven again at load time, under a different plan.
     *
     * <p>Both plans apply, so any weave they have in common runs twice.
     *
     * <p>A warning rather than an error because the load-time driver proceeds: refusing here would
     * cost a running application a class over a configuration that may be deliberate. Configure the
     * agent and the build plugin with different weaves, or drop one of them.
     */
    LOAD_TIME_OVER_BUILD_TIME_WEAVE("AW2202", Severity.WARNING, Category.IDEMPOTENCE,
            "Load-time weaving over a build-time-woven class"),

    /**
     * A weave manifest is malformed, incomplete or unreadable.
     *
     * <p>The broadest configuration code, raised wherever a manifest cannot be trusted: it is not
     * valid JSON; an entry names no class, no handler or no target method; an extension entry is
     * missing one of the four parts that identify it; an extension names a {@code kind} or a policy
     * this version does not know; the classpath could not be searched; the artefact holding a
     * manifest could not be identified; a manifest names a weave class that is not in the artefact
     * that named it; or the processor could not write one.
     *
     * <p>A manifest is generated, so a malformed one means it was edited by hand, truncated in
     * transit, or has outlived the classes it names. Rebuild the artefact that contains it. Where
     * the problem is an unknown token rather than damage, update Aether Weaver or rebuild the
     * artefact against this version.
     *
     * <p>One unusable manifest does not switch off the others: a reader that refuses a document
     * skips it and carries on with the rest of the classpath.
     */
    MANIFEST_MALFORMED("AW2300", Severity.ERROR, Category.CONFIGURATION,
            "Manifest is malformed or carries an unusable entry"),

    /**
     * A manifest declares a schema version newer than this runtime reads.
     *
     * <p>The whole document is refused rather than read partially: an unknown schema version may
     * give a familiar field a new meaning, so reading it would be guessing.
     *
     * <p>Upgrade Aether Weaver, or rebuild the artefact that holds the manifest against this
     * version.
     */
    MANIFEST_VERSION_TOO_NEW("AW2301", Severity.ERROR, Category.CONFIGURATION,
            "Manifest version newer than this runtime"),

    /**
     * No weave manifest was found on the classpath.
     *
     * <p>The manifest is written by the annotation processor during compilation, so its absence
     * usually means the processor is not on the annotation processor path.
     *
     * <p>Add {@code aether-weaver-processor} as a provided-scope dependency of every module that
     * declares a weave. A warning rather than an error, because a runtime with no weaves to apply
     * is a legitimate configuration.
     */
    MANIFEST_NOT_FOUND("AW2302", Severity.WARNING, Category.CONFIGURATION,
            "No weave manifest found — is the processor configured?"),

    /**
     * The same weave class is declared by two artefacts on the classpath.
     *
     * <p>Each is read from its own artefact, so neither takes the other's bytes, but only one class
     * of that name can be loaded and which one is the classpath's decision rather than this
     * framework's.
     *
     * <p>Remove the duplicate dependency, or rename one of the weaves. Discovery keeps both
     * entries: the diagnostic names the two artefacts and leaves the choice where it already
     * was.
     */
    DUPLICATE_WEAVE_CLASS("AW2303", Severity.WARNING, Category.CONFIGURATION,
            "The same weave class is declared by two artefacts"),

    /**
     * A configuration setting could not be used.
     *
     * <p>Covers an unrecognised key, an agent argument with no {@code =}, a boolean that is neither
     * {@code true} nor {@code false}, a number that does not parse, and an enumerated setting given
     * a value it does not take. A near-miss key is offered as a suggestion, and an enumerated
     * setting lists the values it accepts.
     *
     * <p>The setting is left for a lower-precedence layer to decide rather than guessed at. That is
     * the point of the warning: a value is never coerced, so {@code enabled=ture} does not read as
     * a deliberate {@code false} and silently remove every weave.
     */
    UNKNOWN_CONFIGURATION_KEY("AW2310", Severity.WARNING, Category.CONFIGURATION,
            "Unknown configuration key"),

    /**
     * A weaving class loader was created in a JVM started with an AOT cache.
     *
     * <p>Classes in the cache are loaded eagerly, so any target a parent of this loader can also
     * see is very likely to be defined, unwoven, before this loader gets to it. What this loader
     * does define is still woven correctly, because the JVM rejects a cached copy whose bytes
     * differ, but it rejects it without saying so, so the cache also stops paying for exactly those
     * classes.
     *
     * <p>Weave at build time instead: the woven classes are then the classes, and the cache and the
     * weaving stop competing.
     */
    AOT_CACHE_WITH_WEAVING_CLASS_LOADER("AW2401", Severity.WARNING, Category.ENVIRONMENT,
            "Weaving class loader used with an active AOT cache"),

    /**
     * The application's module graph was changed so that a woven class can reach its weave.
     *
     * <p>Arises only when the weave class lives in a named module; on the classpath the JVM grants
     * the edge itself. Reported on success as well as on failure, because expanding a module graph
     * is a change to what an application's code is permitted to reach and one that left no trace
     * would be invisible to anyone reviewing the deployment.
     *
     * <p>Where the JVM refused the change, the class is still woven and will throw an
     * {@link IllegalAccessError} the first time the injected instruction runs; the diagnostic says
     * so, because saying it then is the only chance to connect the two. Putting the weave class on
     * the classpath avoids the whole question.
     */
    MODULE_GRAPH_EXPANDED("AW2402", Severity.INFO, Category.ENVIRONMENT,
            "Module graph expanded via redefineModule (only when a weave class is in a *named* module)"),

    /**
     * A reserved number in the environment range.
     *
     * <p>No code reports it and no build can produce it. The constant holds the number so that it
     * cannot be taken by an unrelated condition.
     */
    RESERVED_2403("AW2403", Severity.WARNING, Category.ENVIRONMENT,
            "(reserved)"),

    /**
     * Classes belonging to dependencies were rewritten into an output directory.
     *
     * <p>Reported once at the end of a run that wove dependency artefacts, listing every class it
     * touched. The original artefacts are not modified, so deleting the output directory undoes the
     * whole operation.
     *
     * <p>Nothing is on the classpath yet: the directory has to be put ahead of the dependency jars
     * wherever the build assembles classpaths. A warning because a woven dependency that nothing
     * places on a classpath is work that silently has no effect.
     */
    DEPENDENCY_CLASSES_MODIFIED("AW2501", Severity.WARNING, Category.BUILD,
            "A dependency's classes were modified"),

    /**
     * The target is in a JDK package that is not woven.
     *
     * <p>Two rules produce it. {@code java.*} is denied under every configuration: its classes are
     * loaded before any transformer can be installed, so an apparent success would be an accident
     * of load ordering rather than a working weave. The other JDK prefixes are denied unless the
     * exact package has been reopened.
     *
     * <p>For the second rule, reopen the one package with
     * {@code aether.weaver.policy.allowPackage=<package>}; the diagnostic names the setting to
     * write. For the first, there is nothing to reopen.
     */
    POLICY_DENIED_JDK_PACKAGE("AW3001", Severity.ERROR, Category.POLICY,
            "Target is in a denied JDK package"),

    /**
     * The target comes from a signed artefact.
     *
     * <p>Weaving it voids the signature's integrity guarantee while tooling continues to report the
     * artefact as signed, so a consumer verifying it would find a class the signer never saw. The
     * class is left unwoven.
     *
     * <p>Weave before signing. Where the override is intentional, the build plugin's
     * {@code allowSigned} and the runtime's {@code aether.weaver.policy.allowSigned} accept it
     * instead of denying the class. Only the build plugin also reports the use of the override, as
     * {@code AW3020}; the runtime class loader and the engine's default policy accept the override
     * silently and report nothing when it is used.
     */
    POLICY_DENIED_SIGNED_ARTEFACT("AW3002", Severity.ERROR, Category.POLICY,
            "Target comes from a signed artefact"),

    /**
     * The target is a class of Aether Weaver itself.
     *
     * <p>Refused under every configuration, with no setting that reopens it. A framework that can
     * modify its own policy gate, verifier or stamper has no guarantees left to make.
     */
    POLICY_DENIED_SELF_WEAVE("AW3003", Severity.ERROR, Category.POLICY,
            "Target is Aether Weaver itself"),

    /**
     * A weave was discovered in an artefact that this project did not ask for directly.
     *
     * <p>It arrived as a dependency of a dependency. A weave modifies this module's classes, and
     * one that came in transitively is a change nobody here chose and that shows up in no diff.
     *
     * <p>Declare the dependency directly where it is wanted, exclude it where it is not, or run the
     * audit goal to see what it does.
     */
    WEAVE_FROM_TRANSITIVE_DEPENDENCY("AW3010", Severity.WARNING, Category.POLICY,
            "Weave discovered from a non-direct dependency"),

    /**
     * A policy override was used, and something happened that would otherwise have been refused.
     *
     * <p>Reported even though the override permitted the operation: an override that produces no
     * output is one that nobody reviewing the build log would notice was used. The diagnostic names
     * what was permitted and what guarantee it cost.
     *
     * <p>Nothing needs doing beyond confirming the decision is deliberate and documented.
     */
    POLICY_OVERRIDE_ACTIVE("AW3020", Severity.WARNING, Category.POLICY,
            "A policy override is active"),

    /**
     * A namespace registering a contribution is malformed.
     *
     * <p>Raised by the registry when the namespace a contribution is registered under does not have
     * the shape a namespace must have. The reserved namespace is reported as {@code AW3101}
     * instead.
     *
     * <p>Give the plugin a namespace of the required shape; {@link PluginDiagnosticId} states it.
     */
    PLUGIN_NAMESPACE_INVALID("AW3100", Severity.ERROR, Category.PLUGIN,
            "Plugin namespace is malformed"),

    /**
     * A plugin claims the namespace reserved for Aether Weaver.
     *
     * <p>Raised when a plugin's identity reports the built-in namespace, which is the empty one,
     * and when a contribution is registered under the reserved name. Unqualified identifiers such
     * as {@code HEAD} and {@code RETURN} belong to the framework, and a plugin claiming them could
     * shadow a built-in point.
     *
     * <p>Give the plugin its own namespace. The plugin contributes nothing until it has one.
     */
    PLUGIN_NAMESPACE_RESERVED("AW3101", Severity.ERROR, Category.PLUGIN,
            "Plugin claims a namespace reserved for Aether Weaver"),

    /**
     * A plugin registered something under a namespace other than its own.
     *
     * <p>Raised for a factory that declares a foreign namespace, for identifiers that are not
     * prefixed with the registering namespace, and for a built-in identifier that contains a colon.
     *
     * <p>Prefix each identifier with the plugin's own namespace. An identifier that does not name
     * its owner cannot be attributed in a diagnostic. The contribution is refused rather than
     * renamed.
     */
    PLUGIN_CONTRIBUTION_OUTSIDE_NAMESPACE("AW3110", Severity.ERROR, Category.PLUGIN,
            "Plugin registered a contribution outside its own namespace"),

    /**
     * Two contributors claim one identifier or one namespace.
     *
     * <p>Four shapes: two plugins declaring the same namespace, the same identifier registered
     * twice, one alias declared twice pointing at different replacements, and an identifier that is
     * both registered and declared as a deprecated alias.
     *
     * <p>A namespace has exactly one owner and an identifier is either current or retired, not
     * both. Remove one of the jars, ask its author to rename, or drop the duplicate registration.
     * Without a unique owner an identifier has two meanings and neither can be attributed.
     */
    PLUGIN_NAMESPACE_COLLISION("AW3111", Severity.ERROR, Category.PLUGIN,
            "Two plugins claim the same namespace"),

    /**
     * A plugin was built against a newer SPI level than this engine provides.
     *
     * <p>The diagnostic prints both levels. The plugin is not loaded.
     *
     * <p>Upgrade Aether Weaver, or use a build of the plugin made for the level this engine
     * provides.
     */
    PLUGIN_API_LEVEL_TOO_NEW("AW3112", Severity.ERROR, Category.PLUGIN,
            "Plugin requires a newer Aether Weaver than this one"),

    /**
     * A plugin was built against an SPI generation that is no longer supported.
     *
     * <p>The diagnostic prints the plugin's level and the oldest one still supported. The plugin is
     * not loaded.
     *
     * <p>Upgrade the plugin, or pin an older Aether Weaver. An error rather than an attempt:
     * loading it anyway would fail later with a {@link LinkageError} thrown from inside class
     * loading, where it is far harder to attribute.
     */
    PLUGIN_API_LEVEL_TOO_OLD("AW3113", Severity.ERROR, Category.PLUGIN,
            "Plugin was built against an SPI generation that is no longer supported"),

    /**
     * A plugin could not be created.
     *
     * <p>Raised when the service declaration itself cannot be read, in which case nothing on the
     * classpath is loadable, and when a plugin's constructor or static initialiser throws, in which
     * case that plugin contributes nothing.
     *
     * <p>Check every {@code META-INF/services} entry for the plugin interface: each line must name
     * a public class with a public no-argument constructor that implements it.
     */
    PLUGIN_INSTANTIATION_FAILED("AW3114", Severity.ERROR, Category.PLUGIN,
            "Plugin could not be instantiated"),

    /**
     * A plugin threw while registering its contributions.
     *
     * <p>The exception is caught and reported against the plugin rather than propagated, so one
     * misbehaving plugin does not take the weaver down with it.
     *
     * <p>Report problems through the plugin context's diagnostics instead of throwing.
     */
    PLUGIN_CONTRIBUTE_FAILED("AW3115", Severity.ERROR, Category.PLUGIN,
            "Plugin threw while registering its contributions"),

    /**
     * A plugin threw during planning.
     *
     * <p>An injector or an injection point must report a user's mistake through the reporter it was
     * handed and return an empty result, never throw: a thrown exception carries no code, no
     * location and no remedy, so it cannot be attributed to the declaration that caused it.
     */
    PLUGIN_PLANNING_FAILED("AW3116", Severity.ERROR, Category.PLUGIN,
            "Plugin threw during planning"),

    /**
     * A plugin threw while weaving a class.
     *
     * <p>The class was left unmodified rather than half-woven. Emission must be total and
     * deterministic: a transform that can fail partway through cannot be undone once bytes have
     * been written.
     */
    PLUGIN_APPLY_FAILED("AW3117", Severity.ERROR, Category.PLUGIN,
            "Plugin threw while weaving a class; the class was left unmodified"),

    /**
     * A plugin observer threw.
     *
     * <p>A warning rather than an error, and the only plugin failure that is not: an observer
     * cannot change the woven bytes, so weaving continued and nothing was miswoven because of it.
     *
     * <p>Fix the observer.
     */
    PLUGIN_OBSERVER_FAILED("AW3118", Severity.WARNING, Category.PLUGIN,
            "Plugin observer threw; weaving continued"),

    /**
     * A plugin was discovered but the configured allowlist or denylist does not permit it.
     *
     * <p>Add its namespace to {@code aether.weaver.plugins.allow}, or remove the jar from the
     * classpath. A plugin runs with full privileges, so refusing an unreviewed one is the safe
     * default.
     */
    PLUGIN_NOT_ALLOWED("AW3119", Severity.ERROR, Category.PLUGIN,
            "Plugin is not permitted by the configured allowlist or denylist"),

    /**
     * A declaration used an identifier that has been retired in favour of another.
     *
     * <p>The lookup succeeds and resolves to the replacement, so nothing is broken yet. The
     * diagnostic names the replacement and the version the identifier was deprecated in, and says
     * that the alias is removed no earlier than two minor versions after that.
     *
     * <p>Replace the deprecated identifier with the one the alias points at.
     */
    DEPRECATED_ALIAS_USED("AW3120", Severity.WARNING, Category.PLUGIN,
            "A deprecated alias was used"),

    /**
     * An alias points at an identifier that is not registered.
     *
     * <p>Found when the registry is built, so the alias never becomes usable. The diagnostic lists
     * the identifiers that are registered.
     *
     * <p>Register the replacement, or correct the alias. An alias cannot create an identifier, only
     * rename one.
     */
    ALIAS_TARGET_UNKNOWN("AW3121", Severity.ERROR, Category.PLUGIN,
            "An alias points at an identifier that is not registered"),

    /**
     * The woven class does not pass the JVM's bytecode verifier.
     *
     * <p>Raised after weaving, by the engine's own verification pass. The diagnostic lists the first
     * verification errors, up to a limit, and says how many more there were.
     *
     * <p>This is a defect in a weave or in the engine, not in the target. Re-run with class dumps
     * enabled and compare the {@code javap} output before and after; the first error's method is
     * where to look.
     *
     * <p>Under a fatal verification policy the engine throws {@link WeaveException} carrying this
     * diagnostic; otherwise it reports it and hands back the original, unwoven bytes.
     */
    VERIFICATION_FAILED("AW4001", Severity.ERROR, Category.ENGINE,
            "Verification failed"),

    /**
     * A reserved number in the engine range.
     *
     * <p>No code reports it and no build can produce it. The constant holds the number so that it
     * cannot be taken by an unrelated condition.
     */
    RESERVED_4002("AW4002", Severity.ERROR, Category.ENGINE,
            "(reserved)"),

    /**
     * A method no longer fits after weaving.
     *
     * <p>A method's code is capped at 65535 bytes by the class file format, and the target was
     * already close enough that the injection crossed it.
     *
     * <p>The handler's own body costs nothing here, only the call does, so the usual fix is fewer
     * injection points in that method rather than a smaller handler.
     */
    METHOD_TOO_LARGE("AW4003", Severity.ERROR, Category.ENGINE,
            "Generated method exceeds 65535 bytes"),

    /**
     * The woven class is structurally malformed.
     *
     * <p>Checked before the bytecode verifier, because a class whose structure the JVM refuses
     * never reaches dataflow verification at all: asking the verifier first would report a clean
     * result for bytes that cannot be loaded. It catches shapes such as a malformed exception range
     * or an unbound label, which the verifier does not report. The same code is used when the class
     * cannot be written back out for a reason other than size, which is what distinguishes it from
     * {@code AW4003}.
     *
     * <p>This is a defect in the engine rather than in the weave. Re-run with class dumps enabled
     * and report the dump together with the message.
     */
    STRUCTURAL_SELF_CHECK_FAILED("AW4004", Severity.ERROR, Category.ENGINE,
                    "Structural self-check failed (malformed exception range, unbound label) — catches what " +
                            "ClassFile.verify does not (spike 6d)"),

    /**
     * Weaving failed for a reason the engine has no more specific code for.
     *
     * <p>Raised where an exception escaped from weaving a class, where a debugging aid such as a
     * class dump could not be written, and where no injector is registered for a declaration's
     * kind. Every driver reports it against the class it was working on, so the class name in the
     * message is the one to look at.
     *
     * <p>What happens next is the driver's decision, not this code's: the agent leaves the class
     * unwoven or halts according to its error policy, the weaving class loader defines the original
     * bytes or throws {@link ClassNotFoundException}, and the build plugin skips the class.
     *
     * <p>Report it with the message, the exception class named in the details, and a class dump.
     */
    INTERNAL_ERROR("AW4090", Severity.ERROR, Category.ENGINE,
            "Internal engine error — please report");

    /**
     * Every constant keyed by its wire form, in declaration order.
     *
     * <p>Unmodifiable, built once in the static initialiser, and the backing index for
     * {@link #of(String)}.
     */
    private static final Map<String, DiagnosticCode> BY_CODE;

    static {
        final Map<String, DiagnosticCode> map = new LinkedHashMap<>();
        for (final DiagnosticCode value : values()) {
            final DiagnosticCode previous = map.put(value.code, value);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate diagnostic code " + value.code + ": " + previous + " and " + value);
            }
        }
        BY_CODE = Collections.unmodifiableMap(map);
    }

    /** The wire form, {@code AW} followed by four digits. */
    private final String code;

    /** The severity a report of this condition carries unless a site overrides it. */
    private final Severity defaultSeverity;

    /** The part of the system this condition belongs to, following from {@link #code}. */
    private final Category category;

    /** A one-line description of the condition, independent of any occurrence. */
    private final String summary;

    /**
     * Binds a constant to its code, severity, category and summary.
     *
     * @param code            the wire form
     * @param defaultSeverity the severity a report carries unless overridden
     * @param category        the part of the system the condition belongs to
     * @param summary         a one-line description of the condition
     */
    DiagnosticCode(@NotNull final String code,
                   @NotNull final Severity defaultSeverity,
                   @NotNull final Category category,
                   @NotNull final String summary) {
        this.code = code;
        this.defaultSeverity = defaultSeverity;
        this.category = category;
        this.summary = summary;
    }

    /**
     * Looks a constant up by its wire form.
     *
     * <p>Exact and case-sensitive: {@code "AW1043"} resolves and {@code "aw1043"} does not. Every
     * code that is not in the catalogue yields an empty result rather than an exception, which
     * includes every {@link PluginDiagnosticId} wire form, since those contain a colon and no
     * constant here does.
     *
     * @param code the wire form to look up; must not be {@code null}
     * @return the constant with that code, or empty when no constant has it
     * @throws NullPointerException if {@code code} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static Optional<DiagnosticCode> of(@NotNull final String code) {
        return Optional.ofNullable(BY_CODE.get(Objects.requireNonNull(code, "code")));
    }

    /**
     * Returns the wire form.
     *
     * @return {@code AW} followed by four digits
     */
    @Contract(pure = true)
    @NotNull
    @Override
    public String code() {
        return this.code;
    }

    /**
     * Returns the severity a report of this condition carries unless the reporting site overrides
     * it.
     *
     * @return the default severity
     */
    @Contract(pure = true)
    @NotNull
    @Override
    public Severity defaultSeverity() {
        return this.defaultSeverity;
    }

    /**
     * Returns the part of the system this condition belongs to.
     *
     * <p>Follows from the number, in the ranges given in the class description.
     *
     * @return the category
     */
    @Contract(pure = true)
    @NotNull
    @Override
    public Category category() {
        return this.category;
    }

    /**
     * Returns the one-line description of the condition.
     *
     * <p>Never blank, and independent of any particular occurrence: it names no class, member or
     * position. It is what a {@link Diagnostic} built without a message falls back to. For the five
     * reserved constants it is the literal text {@code (reserved)}.
     *
     * @return the summary
     */
    @Contract(pure = true)
    @NotNull
    @Override
    public String summary() {
        return this.summary;
    }

    /**
     * Reports whether a report of this condition may be silenced.
     *
     * <p>Derived from {@link #defaultSeverity()}, so it is false for exactly the
     * {@link Severity#ERROR} constants. A diagnostic whose severity a reporting site changed is
     * governed by {@link Diagnostic#isSuppressible()} rather than by this.
     *
     * @return {@code true} unless {@link #defaultSeverity()} is {@link Severity#ERROR}
     */
    @Contract(pure = true)
    @Override
    public boolean isSuppressible() {
        return this.defaultSeverity.isSuppressible();
    }

    /**
     * Returns the wire form rather than the constant name.
     *
     * <p>So that a code interpolated into a message or a log line is the string a user can search
     * for, which the constant name is not.
     *
     * @return {@code AW} followed by four digits
     */
    @Override
    public String toString() {
        return this.code;
    }

    /**
     * The part of the system a diagnostic belongs to.
     *
     * <p>Shared by both implementations of {@link DiagnosticId}, so a plugin's condition is grouped
     * with the framework's conditions of the same kind rather than into a category of its own. For
     * a {@link DiagnosticCode} the category follows from the number, in the ranges given in the
     * enclosing class description; for a {@link PluginDiagnosticId} it is declared.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Category {

        /**
         * The shape of a weave class, its members and its handlers.
         *
         * <p>Everything a declaration can get wrong about itself or about the target it names,
         * including whether a selector resolves and whether a handler's signature fits. The largest
         * category, and the one most diagnostics a user meets belong to.
         */
        DECLARATION,

        /**
         * Locating a position inside a target method.
         *
         * <p>Injection points, ordinals, slices and shifts. A declaration that is well formed and
         * whose target resolves can still fail here, because the position it describes depends on
         * the target's compiled body.
         */
        INJECTION_POINT,

        /**
         * What the annotation processor could and could not check.
         *
         * <p>Reported only from a compilation, and about the compilation rather than about the
         * weave.
         */
        COMPILE_TIME,

        /**
         * Extension classes and the members they contribute.
         *
         * <p>The declaration rules for an extension holder, its receivers, and collisions between a
         * contributed member and one the receiver already has.
         */
        EXTENSION,

        /**
         * The class file being woven.
         *
         * <p>Properties of the target that make weaving it impossible or pointless, independent of
         * any declaration.
         */
        TARGET,

        /**
         * Limits of the driver applying the weave.
         *
         * <p>What a build-time plugin, a load-time agent or a weaving class loader can and cannot
         * do, where the difference is the driver rather than the weave.
         */
        DRIVER,

        /**
         * Weaving something that has been woven before.
         *
         * <p>Applying a second plan on top of a first, where the risk is a weave running twice
         * rather than a weave failing.
         */
        IDEMPOTENCE,

        /**
         * Manifests and configuration input.
         *
         * <p>Documents and settings the framework reads rather than produces from source.
         */
        CONFIGURATION,

        /**
         * The JVM the weaver is running in.
         *
         * <p>Interactions with class caching and with the module system, which are properties of
         * the deployment rather than of the code.
         */
        ENVIRONMENT,

        /**
         * What a build produced beyond its own classes.
         *
         * <p>Output a build wrote somewhere the build itself has to be told about.
         */
        BUILD,

        /**
         * What weaving is permitted to touch.
         *
         * <p>Most of these are refusals by the weave policy, or the use of an override that lifts
         * one, but not all: {@code AW3010} belongs here too, and is neither — it is a warning about
         * where a weave was discovered from, raised without consulting any {@code WeavePolicy}.
         */
        POLICY,

        /**
         * Loading and isolating plugins.
         *
         * <p>Discovery, namespaces, API levels, and every failure a plugin is prevented from
         * turning into a failure of the weaver.
         */
        PLUGIN,

        /**
         * Output the engine refuses to hand back.
         *
         * <p>Most of these are reported after weaving has produced bytes, about those bytes, and
         * describe a defect in the engine or in a weave rather than a mistake in a declaration.
         * {@code AW4090} is the exception on both counts: it is also reported before any bytes
         * exist, when no injector is registered for a declaration's kind, and again when a
         * debugging aid such as a class dump cannot be written, which is a fact about the
         * filesystem rather than about the woven bytes. {@code AW4003}'s own remedy is fewer
         * injection points in the method that grew too large, which is a change to a declaration.
         */
        ENGINE
    }
}
