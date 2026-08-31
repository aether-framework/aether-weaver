package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.TypePattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks an injection handler's signature, both on its own and against the method it names.
 *
 * <p>Split in two by what the check needs. {@link #declaration(ExecutableElement, AnnotationMirror,
 * MessagerReporter)} looks only at the handler and its annotation and runs once per declaration;
 * {@link #againstTarget(ExecutableElement, AnnotationMirror, MemberSelector, SourceTargets.Resolved,
 * Types, Elements, MessagerReporter)} needs a resolved target and runs once for each, so a weave
 * with three targets is told three times which of them is missing the method.
 *
 * <p>The API types a handler's signature can mention are recognised by name, against the constants
 * below, and the name compared is the erased one. A parameter's type arguments play no part in
 * recognising it, so nothing here rejects an {@code Operation} for the type argument it was written
 * with. One type argument is looked at anywhere here: a {@code ReturnableCallback}'s, compared
 * against what the target method returns.
 *
 * <p>Injection points are only examined for the shape a {@code @Redirect} or {@code @Wrap} needs.
 * Whether a point matches anything in the target, and whether a slice resolves, belong to
 * {@code PointChecks}, which needs the target's compiled bytes rather than its source elements.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class HandlerChecks {

    /**
     * The whole-name wildcard this class matches a selector's method name against: a whole member
     * name, never part of one. The grammar also accepts {@code *} as a parameter type, but this
     * class does not implement that use: {@link #matches(List, ExecutableElement)} compares a
     * parameter pattern's {@link TypePattern#renderSource()} against the parameter's erased name by
     * string equality, and the wildcard pattern renders as {@code "*"}, which equals no erased name.
     * So within this class {@code charge(*)} matches no method at all, and {@code resolve} reports
     * {@code AW1020} for it the same as for a name that matches nothing.
     */
    private static final String WILDCARD = "*";

    /** The handle a {@code @Wrap} handler receives, matched on the erased type. */
    private static final String OPERATION = "de.splatgames.aether.weaver.api.callback.Operation";

    /**
     * The injection points that name an operation a {@code @Redirect} or {@code @Wrap} can take
     * over.
     *
     * <p>Compared against the point's enum constant name as the source wrote it. Every other point
     * names a position in the method rather than an operation, which is what {@code AW1061}
     * reports; the set is also quoted verbatim in that diagnostic's detail line.
     */
    private static final Set<String> REDIRECTABLE = Set.of("INVOKE", "FIELD", "NEW");

    /** The callback carrier for a target that returns nothing. */
    private static final String CALLBACK =
            "de.splatgames.aether.weaver.api.callback.Callback";

    /** The callback carrier that can also supply a return value. */
    private static final String RETURNABLE_CALLBACK =
            "de.splatgames.aether.weaver.api.callback.ReturnableCallback";

    /** The annotation marking a handler parameter as a capture of a target local variable. */
    private static final String LOCAL = "de.splatgames.aether.weaver.api.Local";

    /**
     * The carrier types a writable {@code @Local} capture must be declared as.
     *
     * <p>The reference carrier and one per primitive. A parameter of any other type is passed by
     * value, so assigning to it changes the handler's own copy and leaves the target holding the
     * old one — which is what {@code AW1053} refuses and {@code AW1054} refuses the converse of.
     */
    private static final Set<String> REFS = Set.of(
            "de.splatgames.aether.weaver.api.callback.LocalRef",
            "de.splatgames.aether.weaver.api.callback.LocalIntRef",
            "de.splatgames.aether.weaver.api.callback.LocalLongRef",
            "de.splatgames.aether.weaver.api.callback.LocalFloatRef",
            "de.splatgames.aether.weaver.api.callback.LocalDoubleRef",
            "de.splatgames.aether.weaver.api.callback.LocalBooleanRef",
            "de.splatgames.aether.weaver.api.callback.LocalByteRef",
            "de.splatgames.aether.weaver.api.callback.LocalShortRef",
            "de.splatgames.aether.weaver.api.callback.LocalCharRef");

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private HandlerChecks() {
        throw new AssertionError("no instances");
    }

    /**
     * Checks what is true of a handler declaration without resolving its target.
     *
     * <p>Runs once per injection annotation, so a repeated {@code @Inject} on one method is checked
     * once for each. Which checks apply is decided by the annotation's own type: the point shapes
     * are checked only for {@code @Redirect} and {@code @Wrap}, and the wrap signature rules only
     * for {@code @Wrap}. Mutable captures are checked for every kind of handler.
     *
     * <p>Reports {@code AW1061} for a point that names no operation, {@code AW1005},
     * {@code AW1063} and {@code AW1062} for a wrap signature that cannot nest, and {@code AW1053}
     * or {@code AW1054} for a {@code @Local} capture whose type does not match what it claims. None
     * of them stops the others: a non-static wrap handler with no {@code Operation} reports both
     * {@code AW1005} and {@code AW1063}.
     *
     * @param handler   the annotated method; must not be {@code null}
     * @param injection the {@code @Inject}, {@code @Redirect} or {@code @Wrap} mirror on it; must
     *                  not be {@code null}
     * @param reporter  where to report; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    static void declaration(@NotNull final ExecutableElement handler,
                            @NotNull final AnnotationMirror injection,
                            @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(injection, "injection");
        Objects.requireNonNull(reporter, "reporter");

        final String named = Anchors.nameOf(injection);
        final boolean wrap = WeaveProcessor.WRAP.equals(named);
        checkPoints(handler, injection,
                wrap || WeaveProcessor.REDIRECT.equals(named), wrap, reporter);
        if (wrap) {
            checkWrapSignature(handler, injection, reporter);
        }
        checkMutableCaptures(handler, reporter);
    }

    /**
     * Checks that each {@code @Local} capture's type agrees with whether it means to write.
     *
     * <p>Two symmetrical refusals. {@code AW1053} for {@code mutable = true} on a parameter that is
     * not one of the carrier types: Java passes a parameter by value, so the assignment would
     * change the handler's copy and leave the target holding the old value, with nothing to notice
     * it. {@code AW1054} for a carrier without {@code mutable = true}: the parameter can write the
     * target's variable and the declaration did not ask to. {@code AW1053}'s remedy is one
     * direction, declaring the carrier type; {@code AW1054}'s leads with the other correction,
     * adding {@code mutable = true}, but also offers declaring the parameter as the variable's own
     * type instead, for a handler that only reads it.
     *
     * <p>Only a parameter carrying {@code @Local} is examined, and {@code mutable} counts only when
     * the source wrote it as {@code true}. A carrier-typed parameter with no {@code @Local} at all
     * is not a capture and is left alone here.
     *
     * @param handler  the handler whose parameters to check
     * @param reporter where to report
     */
    private static void checkMutableCaptures(@NotNull final ExecutableElement handler,
                                             @NotNull final MessagerReporter reporter) {
        for (final VariableElement parameter : handler.getParameters()) {
            final AnnotationMirror local = Anchors.mirrorOf(parameter, LOCAL);
            if (local == null) {
                continue;
            }
            final boolean mutable = Boolean.TRUE.equals(Anchors.valueOf(local, "mutable") == null
                    ? Boolean.FALSE
                    : Anchors.valueOf(local, "mutable").getValue());
            final boolean carrier = REFS.contains(erasedNameOf(parameter));
            if (mutable && !carrier) {
                reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_MUTABLE_NEEDS_REF)
                        .message("@Local(mutable = true) on " + parameter.getSimpleName()
                                + ", which is not a LocalRef")
                        .remedy("a Java parameter is passed by value, so assigning to it would "
                                + "change the handler's own copy and leave the target holding the "
                                + "old one. Declare it as LocalRef<T>, or LocalIntRef and friends "
                                + "for a primitive")
                        .build(), Anchor.at(parameter));
            } else if (!mutable && carrier) {
                reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_REF_WITHOUT_MUTABLE)
                        .message("@Local on " + parameter.getSimpleName()
                                + " takes a carrier without mutable = true")
                        .remedy("add mutable = true if the handler means to write the variable, "
                                + "or declare the parameter as the variable's own type if it only "
                                + "reads it")
                        .build(), Anchor.at(parameter));
            }
        }
    }

    /**
     * Checks the three rules a {@code @Wrap} handler's signature has to satisfy.
     *
     * <p>{@code AW1005} when the handler is not {@code static}. This is the code's second use: it
     * is also what {@code WeaveProcessor} reports for any non-static handler in a static weave, and
     * the wrap path raises it in a weave of any kind, because a wrap can end up nested inside
     * another weave's wrap and an inner level is reached through {@code Operation.call}, which
     * carries the operation's own arguments and no receiver. The two sites do not share a remedy:
     * this one asks to declare the handler {@code static} and put what it needs beyond the
     * operation's arguments in a static field of the weave, while {@code WeaveProcessor}'s asks to
     * declare it static and take the target as the first parameter — the shape that check expects
     * of a non-wrap handler. A non-static {@code @Wrap} handler in a static weave trips both checks
     * and reports {@code AW1005} twice, once with each remedy.
     *
     * <p>{@code AW1063} when no parameter is an {@code Operation}. That is a {@code @Redirect}
     * wearing the wrong annotation, so the remedy offers both: add a trailing {@code Operation<R>},
     * or use {@code @Redirect}, which replaces the operation and needs no handle to it. This report
     * returns, so a handler with no {@code Operation} never also reports {@code AW1062}.
     *
     * <p>{@code AW1062} when the last {@code Operation} is not the last parameter. The rule holds
     * until a second weave arrives: such a handler works as the outermost wrap, because the
     * enclosing method's arguments are still on the stack, and fails as soon as another weave nests
     * inside it. The caret goes on the first offending parameter rather than on the method, because
     * deleting that parameter is the fix.
     *
     * <p>The {@code Operation} looked for is the last one declared, so a handler taking two of them
     * satisfies the rule as long as the second is final; the first counts as an ordinary parameter
     * and nothing here objects to it.
     *
     * @param handler   the wrap handler
     * @param injection the {@code @Wrap} mirror, used to place the first two reports
     * @param reporter  where to report
     */
    private static void checkWrapSignature(@NotNull final ExecutableElement handler,
                                           @NotNull final AnnotationMirror injection,
                                           @NotNull final MessagerReporter reporter) {
        if (!handler.getModifiers().contains(Modifier.STATIC)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER)
                    .message("@Wrap handler " + handler.getSimpleName() + " must be static")
                    .detail("a wrap may end up nested inside another weave's wrap, and an inner "
                            + "level is reached through Operation.call — which carries the "
                            + "operation's own arguments and no receiver")
                    .remedy("declare the handler static; state it needs beyond the operation's "
                            + "own arguments belongs in a static field of the weave")
                    .build(), Anchor.at(handler, injection, null));
        }

        final List<? extends VariableElement> parameters = handler.getParameters();
        int last = -1;
        for (int index = 0; index < parameters.size(); index++) {
            if (OPERATION.equals(erasedNameOf(parameters.get(index)))) {
                last = index;
            }
        }
        if (last < 0) {
            reporter.report(Diagnostic.builder(DiagnosticCode.WRAP_OPERATION_MISSING)
                    .message("@Wrap handler " + handler.getSimpleName()
                            + " declares no Operation parameter")
                    .remedy("add a trailing Operation<R> parameter, where R is the operation's "
                            + "result type boxed — or use @Redirect, which replaces the operation "
                            + "instead of wrapping it and needs no handle to it")
                    .build(), Anchor.at(handler, injection, null));
            return;
        }
        if (last != parameters.size() - 1) {
            reporter.report(Diagnostic.builder(DiagnosticCode.WRAP_PARAMETERS_AFTER_OPERATION)
                    .message("@Wrap handler " + handler.getSimpleName()
                            + " declares parameters after its Operation")
                    .remedy("the Operation must be last. A @Redirect handler may append the "
                            + "enclosing method's parameters, and a wrap handler may not: an inner "
                            + "level receives only what Operation.call carries, so such a handler "
                            + "would work as the outermost wrap and fail as a nested one")
                    // On the offending parameter rather than on the method: the caret belongs where
                    // the fix is, and the fix is to delete that parameter.
                    .build(), Anchor.at(parameters.get(last + 1)));
        }
    }

    /**
     * Renders a parameter's declared type with any type arguments cut off.
     *
     * <p>Textual: everything from the first {@code <} is dropped, so
     * {@code ...callback.Operation<java.lang.Void>} becomes {@code ...callback.Operation}. That is
     * enough to recognise the API types by name and is not a substitute for
     * {@link Types#erasure(javax.lang.model.type.TypeMirror)}, which is what the parameter-matching
     * checks use.
     *
     * @param parameter the parameter whose type to render
     * @return the type's textual name up to its first type argument
     */
    @NotNull
    private static String erasedNameOf(@NotNull final VariableElement parameter) {
        final String named = parameter.asType().toString();
        final int arguments = named.indexOf('<');
        return arguments < 0 ? named : named.substring(0, arguments);
    }

    /**
     * Checks a handler against one resolved target.
     *
     * <p>Runs once per target, so a fact about a target is stated for each target it is true of.
     * Accessibility is checked first and independently of everything else; the rest is a chain that
     * stops at the first thing that cannot be answered. A selector whose owner does not resolve
     * produces its diagnostic and nothing further is checked against that target; one that matches
     * no method does the same, and one that matches several does too — unless it is a wildcard with
     * a positive {@code allow}, which suppresses the diagnostic regardless of whether that number
     * matches how many were found, in which case {@code resolve} reports nothing and nothing further
     * is checked either. A selector that does not name a method at all
     * is passed over in silence, the caller having already refused anything that did not parse as
     * one.
     *
     * <p>Once one method is resolved it is checked for being usable at all — {@code AW1025} for a
     * {@code native} method, whose implementation is not a class file, and {@code AW1023} for an
     * {@code abstract} one, which has no body — and then, for an {@code @Inject} only, the
     * handler's signature is checked against it. {@code @Redirect} and {@code @Wrap} skip that:
     * their handlers mirror the operation the point matched rather than the enclosing method, so
     * the prefix rule does not describe them.
     *
     * <p>{@code checkTargetMethod}'s early return exits only that method, not this one. Whichever
     * of {@code AW1025} or {@code AW1023} it reports, control returns here afterwards, and the
     * signature check that follows still runs for an {@code @Inject} handler — a native target's
     * handler is checked exactly as an abstract one's is.
     *
     * @param handler   the annotated method; must not be {@code null}
     * @param injection the injection annotation's mirror, used to place the reports
     * @param selector  the parsed target-method selector; must not be {@code null}
     * @param target    the target to resolve against; must not be {@code null}
     * @param types     the type utilities used to compare erasures; must not be {@code null}
     * @param elements  the element utilities used to resolve an explicit selector owner and the
     *                  packages of the weave and the target
     * @param reporter  where to report; must not be {@code null}
     * @throws NullPointerException if {@code handler}, {@code selector}, {@code target},
     *                              {@code types} or {@code reporter} is {@code null}
     */
    static void againstTarget(@NotNull final ExecutableElement handler,
                              @NotNull final AnnotationMirror injection,
                              @NotNull final MemberSelector selector,
                              @NotNull final SourceTargets.Resolved target,
                              @NotNull final Types types,
                              @NotNull final Elements elements,
                              @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(reporter, "reporter");

        checkAccessibility(handler, target, elements, reporter);

        if (!(selector instanceof MethodSelector method)
                || !ownerResolves(method, handler, injection, elements, reporter)) {
            return;
        }
        final ExecutableElement found = resolve(method, handler, injection, target, reporter);
        if (found == null) {
            return;
        }
        checkTargetMethod(found, handler, injection, target, reporter);
        final String named = Anchors.nameOf(injection);
        if (!WeaveProcessor.REDIRECT.equals(named) && !WeaveProcessor.WRAP.equals(named)) {
            // Both of the operational injectors mirror the operation rather than the enclosing
            // method, so the prefix rule an @Inject handler obeys does not apply to them.
            checkInjectSignature(handler, found, types, reporter);
        }
    }

    /**
     * Checks that every point a {@code @Redirect} or {@code @Wrap} names is an operation.
     *
     * <p>Reports {@code AW1061} once per offending {@code @At}, naming the annotation the author
     * wrote and the point that cannot carry it, listing {@code INVOKE}, {@code FIELD} and
     * {@code NEW} as the ones that can. The remedy is to use {@code @Inject} for a position, or to
     * aim the declaration at the call, field access or instantiation it means to take over.
     *
     * <p>An {@code @At} whose {@code value} the source did not write is treated as {@code HEAD},
     * that being the annotation's own default, and reported like any other position — an unwritten
     * point is exactly as wrong here as a wrong one. An entry of the {@code at} array that is not an
     * annotation is skipped.
     *
     * <p>The caret goes on the whole {@code at} element rather than on the offending entry, so a
     * declaration with several points underlines all of them.
     *
     * @param handler     the handler, used to place the report
     * @param injection   the injection annotation's mirror
     * @param operational whether the annotation is one that takes over an operation; this returns
     *                    at once when it is not
     * @param wrap        whether it is {@code @Wrap} rather than {@code @Redirect}, which decides
     *                    only the wording
     * @param reporter    where to report
     */
    private static void checkPoints(@NotNull final ExecutableElement handler,
                                    @NotNull final AnnotationMirror injection,
                                    final boolean operational,
                                    final boolean wrap,
                                    @NotNull final MessagerReporter reporter) {
        if (!operational) {
            return;
        }
        final String what = wrap ? "@Wrap" : "@Redirect";
        final String verb = wrap ? "wrap" : "replace";
        for (final AnnotationValue at : Anchors.arrayOf(injection, "at")) {
            if (!(at.getValue() instanceof AnnotationMirror point)) {
                continue;
            }
            final String named = Anchors.enumOf(point, "value");
            // An omitted point means HEAD, which is the annotation's default and is not an
            // operation — so an unwritten value is exactly as wrong as a wrong one.
            final String name = named == null ? "HEAD" : named;
            if (REDIRECTABLE.contains(name)) {
                continue;
            }
            reporter.report(Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                    .message(what + " cannot " + verb + ' ' + name + ": it names a position in the "
                            + "method, not an operation to take over")
                    .detail("the points naming an operation are " + String.join(", ", REDIRECTABLE))
                    .remedy("use @Inject for a position, or point the " + what + " at the call, "
                            + "field access or instantiation you mean to " + verb)
                    .build(), Anchor.at(handler, injection, Anchors.valueOf(injection, "at")));
        }
    }

    /**
     * Checks that the call woven into the target could reach the handler.
     *
     * <p>Applies only to a weave declaring {@code kind = Kind.STATIC}, and returns at once for any
     * other kind, including one whose {@code @Weave} mirror is not there to read. A static weave is
     * never merged, so the injected call is an ordinary cross-class invocation subject to ordinary
     * access rules; an instance weave's handler moves into the target, where even {@code private}
     * is reachable.
     *
     * <p>The handler is reachable when it is not {@code private}, and either it and its class are
     * both {@code public} or the weave and the target share a package. A {@code private} handler is
     * unreachable whatever the packages, being reachable only from within its own top-level class,
     * of which the target is never a nestmate; and a {@code protected} handler across packages is
     * unreachable too, because the target does not extend the weave.
     *
     * <p>{@code AW1042} otherwise. The message names the target, the handler and why it is
     * unreachable; a first detail line names both packages, and a second says that the woven class
     * would verify and load — the failure is an {@link IllegalAccessError} at the first execution of
     * the injected call, which is why nothing earlier catches it. The remedy is to make the handler
     * and its class {@code public}, or to declare the weave {@code @Weave(kind = Kind.INSTANCE)} so
     * that it moves into the target.
     *
     * @param handler  the handler, whose enclosing element is taken to be the weave class
     * @param target   the target the call would be emitted into
     * @param elements the element utilities used to find each side's package
     * @param reporter where to report
     */
    private static void checkAccessibility(@NotNull final ExecutableElement handler,
                                           @NotNull final SourceTargets.Resolved target,
                                           @NotNull final Elements elements,
                                           @NotNull final MessagerReporter reporter) {
        final TypeElement weave = (TypeElement) handler.getEnclosingElement();
        if (!"STATIC".equals(Anchors.enumOf(
                Anchors.mirrorOf(weave, WeaveProcessor.WEAVE), "kind"))) {
            return;
        }

        final String weavePackage = elements.getPackageOf(weave).getQualifiedName().toString();
        final String targetPackage =
                elements.getPackageOf(target.element()).getQualifiedName().toString();

        // private first, and separately. A private member is reachable only from within its own
        // top-level class, and the target is never a nestmate — so the package makes no difference
        // to it. Folding it into the package test passed a private handler whenever the two
        // happened to share a package, which a test caught immediately.
        final boolean reachable = handler.getModifiers().contains(Modifier.PRIVATE)
                ? false
                : (handler.getModifiers().contains(Modifier.PUBLIC)
                        && weave.getModifiers().contains(Modifier.PUBLIC))
                        || weavePackage.equals(targetPackage);
        if (reachable) {
            return;
        }

        final String what = handler.getModifiers().contains(Modifier.PRIVATE)
                ? "private"
                : weave.getModifiers().contains(Modifier.PUBLIC)
                        ? "not public"
                        : "in a class that is not public";
        reporter.report(Diagnostic.builder(DiagnosticCode.HANDLER_NOT_ACCESSIBLE)
                .message("the call emitted into " + target.element().getQualifiedName()
                        + " could not reach " + weave.getQualifiedName() + '#'
                        + handler.getSimpleName() + ", which is " + what)
                .detail("a static weave is never merged, so the call is an ordinary cross-class "
                        + "invocation subject to ordinary access rules — and the target is in "
                        + "package " + (targetPackage.isEmpty() ? "<default>" : targetPackage)
                        + " while the handler is in "
                        + (weavePackage.isEmpty() ? "<default>" : weavePackage))
                .detail("the class would verify and load; IllegalAccessError arrives at the first "
                        + "execution of the injected call")
                .remedy("make the handler and its class public, or declare the weave "
                        + "@Weave(kind = Kind.INSTANCE) so that it moves into the target")
                .build(), Anchor.at(handler));
    }

    /**
     * Checks that a selector's explicit owner names a type on the compile classpath.
     *
     * <p>{@code AW1010} when it does not, with the remedy that the spelling may be wrong or the
     * owner may be superfluous — a member of the weave's own target needs none.
     *
     * <p>An owner written without a dot is a simple name, which cannot be resolved without the
     * file's imports and is accepted unchecked. Reporting one as missing would be wrong far more
     * often than right.
     *
     * @param selector  the parsed selector
     * @param handler   the handler, used to place the report
     * @param injection the injection annotation's mirror, whose {@code method} element the caret
     *                  lands on
     * @param elements  the element utilities used to resolve the name
     * @param reporter  where to report
     * @return {@code true} when the selector names no owner, names an unqualified one, or names one
     *         that resolves; {@code false} when {@code AW1010} was reported
     */
    private static boolean ownerResolves(@NotNull final MethodSelector selector,
                                         @NotNull final ExecutableElement handler,
                                         @NotNull final AnnotationMirror injection,
                                         @NotNull final Elements elements,
                                         @NotNull final MessagerReporter reporter) {
        final TypePattern owner = selector.owner().orElse(null);
        if (owner == null) {
            return true;
        }
        final String name = owner.renderSource();
        // An unqualified owner is a simple name, which cannot be resolved without the file's
        // imports — and reporting one as missing would be wrong far more often than right.
        if (name.indexOf('.') < 0 || elements.getTypeElement(name) != null) {
            return true;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.SELECTOR_OWNER_UNRESOLVABLE)
                .message("the selector names '" + name + "' as the owner, which is not on the "
                        + "compile classpath")
                .remedy("check the spelling, or drop the owner when the member belongs to the "
                        + "weave's own target")
                .build(), Anchor.at(handler, injection, Anchors.valueOf(injection, "method")));
        return false;
    }

    /**
     * Resolves a selector against the target's own methods.
     *
     * <p>Only methods the target declares are considered, and only methods: a constructor is
     * excluded by kind, and an inherited method is not enumerated at all — which the {@code AW1020}
     * remedy says in as many words. A name matches when it is equal, or when the selector's name is
     * the whole-name wildcard; a partial form such as {@code charge*} is not in the grammar, so
     * comparing the text literally would make {@code *} match a method actually called {@code *}. A
     * selector that states parameter types must match them all, each written either fully qualified
     * or by simple name.
     *
     * <p>Matching nothing is {@code AW1020}, carrying every method the target does declare so that
     * the reader can see what was available.
     *
     * <p>Matching several is two different mistakes wearing one symptom, and they get different
     * codes because their remedies have nothing in common. A wildcard that matched several did what
     * it was written to do and the fault is that nothing said how many were expected:
     * {@code AW1022}, whose remedy is to set {@code allow} to that number so that matching a
     * different number later is caught rather than woven silently. A plain name that matched several
     * is an overload the author did not know about: {@code AW1021}, whose remedy is to add the
     * parameter types. A wildcard with {@code allow} already set is neither, and is passed over
     * without a diagnostic.
     *
     * @param selector  the parsed selector
     * @param handler   the handler, used to place the reports
     * @param injection the injection annotation's mirror, whose {@code method} element the caret
     *                  lands on
     * @param target    the target whose methods to search
     * @param reporter  where to report
     * @return the one method matched, or {@code null} when none matched, several did, or several
     *         did and {@code allow} made that acceptable — in which case the caller checks nothing
     *         further against this target
     */
    @Nullable
    private static ExecutableElement resolve(@NotNull final MethodSelector selector,
                                             @NotNull final ExecutableElement handler,
                                             @NotNull final AnnotationMirror injection,
                                             @NotNull final SourceTargets.Resolved target,
                                             @NotNull final MessagerReporter reporter) {
        final Anchor anchor = Anchor.at(handler, injection,
                Anchors.valueOf(injection, "method"));
        final List<ExecutableElement> matches = new ArrayList<>();
        final List<String> declared = new ArrayList<>();

        for (final var member : target.element().getEnclosedElements()) {
            if (!(member instanceof ExecutableElement method)
                    || method.getKind() != ElementKind.METHOD) {
                continue;
            }
            declared.add("declares: " + render(method));
            // The grammar's only wildcard is a whole name: "*" matches every member, and a partial
            // form such as "charge*" is not in the language. Comparing the text literally would
            // make "*" match a method actually called "*", which is to say nothing.
            if (!WILDCARD.equals(selector.name())
                    && !method.getSimpleName().contentEquals(selector.name())) {
                continue;
            }
            if (selector.parameters().map(patterns -> matches(patterns, method)).orElse(true)) {
                matches.add(method);
            }
        }

        if (matches.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.METHOD_NOT_FOUND)
                    .message(target.element().getQualifiedName() + " declares no method matching '"
                            + selector.render(MemberSelector.Form.SOURCE) + '\'')
                    .details(declared)
                    .remedy("an inherited method is not a declared one; name the class that "
                            + "declares it, or add the parameter types to pick an overload")
                    .build(), anchor);
            return null;
        }
        if (matches.size() > 1) {
            // Two different mistakes wearing the same symptom. A wildcard that matched several
            // methods did what it was written to do, and the fault is that nothing said how many
            // were expected; a plain name that matched several is an overload the author did not
            // know about. The remedies have nothing in common, so neither does the code.
            final boolean wildcard = WILDCARD.equals(selector.name());
            if (wildcard && allowOf(injection) <= 0) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SELECTOR_WILDCARD_TOO_BROAD)
                        .message("'" + selector.render(MemberSelector.Form.SOURCE) + "' matches "
                                + matches.size() + " methods on "
                                + target.element().getQualifiedName() + ", and allow is not set")
                        .details(matches.stream()
                                .map(match -> "matches: " + render(match)).toList())
                        .remedy("set allow = " + matches.size() + " to say that matching several "
                                + "is intended, so that matching a different number later is "
                                + "caught rather than silently woven")
                        .build(), anchor);
                return null;
            }
            if (!wildcard) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SELECTOR_AMBIGUOUS)
                        .message("'" + selector.render(MemberSelector.Form.SOURCE) + "' matches "
                                + matches.size() + " methods on "
                                + target.element().getQualifiedName())
                        .details(matches.stream()
                                .map(match -> "matches: " + render(match)).toList())
                        .remedy("add the parameter types — run(java.lang.String) — so that "
                                + "exactly one overload is named")
                        .build(), anchor);
            }
            return null;
        }
        return matches.getFirst();
    }

    /**
     * Checks that the resolved target method has a body to weave into.
     *
     * <p>{@code AW1025} for a {@code native} method: its implementation is not a class file, so
     * there is nothing to inject into. The remedy is to inject into the Java method that calls it,
     * or to use {@code @Redirect} at the call site to intercept the transition. This report
     * returns, and it is the only one of the two that does.
     *
     * <p>{@code AW1023} for an {@code abstract} method, which has no body either. The refusals are
     * kept separate because their remedies are: an abstract declaration says what happens and not
     * how, so the fix is to name an implementing method, and telling a {@code native} method's
     * author to look for an implementation would send them after one that does not exist.
     *
     * @param method    the resolved target method
     * @param handler   the handler, used to place the reports
     * @param injection the injection annotation's mirror, whose {@code method} element the caret
     *                  lands on
     * @param target    the target, named in the messages
     * @param reporter  where to report
     */
    private static void checkTargetMethod(@NotNull final ExecutableElement method,
                                          @NotNull final ExecutableElement handler,
                                          @NotNull final AnnotationMirror injection,
                                          @NotNull final SourceTargets.Resolved target,
                                          @NotNull final MessagerReporter reporter) {
        final Anchor anchor = Anchor.at(handler, injection, Anchors.valueOf(injection, "method"));
        final Set<Modifier> modifiers = method.getModifiers();

        if (modifiers.contains(Modifier.NATIVE)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.TARGET_METHOD_NATIVE)
                    .message(render(method) + " on " + target.element().getQualifiedName()
                            + " is native; its implementation is not a class file, so there is "
                            + "nothing to inject into")
                    .remedy("inject into the Java method that calls it, or use @Redirect at the "
                            + "call site to intercept the transition")
                    .build(), anchor);
            return;
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.TARGET_METHOD_ABSTRACT)
                    .message(render(method) + " on " + target.element().getQualifiedName()
                            + " has no body to inject into")
                    .remedy("name an implementing method instead; an abstract declaration says "
                            + "what happens, not how")
                    .build(), anchor);
        }
    }

    /**
     * Checks an {@code @Inject} handler's return type and parameters against the target method.
     *
     * <p>{@code AW1041} when the handler returns anything but {@code void}: the injected call is a
     * statement in the middle of the target's own code, so a returned value would have nowhere to
     * go. The remedy points at {@code ReturnableCallback}, which is how a handler changes what the
     * target returns. The report does not stop the parameter check, so a handler can collect both
     * {@code AW1041} and a parameter diagnostic.
     *
     * @param handler  the handler to check
     * @param method   the resolved target method
     * @param types    the type utilities used to compare erasures
     * @param reporter where to report
     */
    private static void checkInjectSignature(@NotNull final ExecutableElement handler,
                                             @NotNull final ExecutableElement method,
                                             @NotNull final Types types,
                                             @NotNull final MessagerReporter reporter) {
        if (handler.getReturnType().getKind() != TypeKind.VOID) {
            reporter.report(Diagnostic.builder(DiagnosticCode.HANDLER_RETURN_TYPE_NOT_VOID)
                    .message("an @Inject handler returns void; " + handler.getSimpleName()
                            + " returns " + handler.getReturnType())
                    .detail("the injected call is a statement in the middle of the target's own "
                            + "code, so a returned value would have nowhere to go")
                    .remedy("to change what the target returns, take a ReturnableCallback and "
                            + "cancel with a value")
                    .build(), Anchor.at(handler));
        }
        checkParameters(handler, method, types, reporter);
    }

    /**
     * Checks that the handler's own parameters are a prefix of the target method's arguments.
     *
     * <p>Three kinds of parameter are set aside before the comparison, because none of them stands
     * for one of the target's arguments: a receiver the handler takes for a static weave, a
     * {@code @Local} capture, and a {@code Callback} or {@code ReturnableCallback}. Everything left
     * is compared against the target's parameters position by position, on erasures.
     *
     * <p>{@code AW1040} for too many parameters and {@code AW1040} again for one at the wrong type,
     * with the message saying which of the two it was and the caret on the offending parameter in
     * the second case. Only the first failure is reported; both paths return. The remedy is to
     * drop the parameters that do not correspond, or to capture them with {@code @Local} where they
     * are locals rather than arguments.
     *
     * <p>A callback is checked for its type argument as well, which is {@code AW1071}, and that
     * happens before the prefix comparison and so whether or not the comparison then fails. Where a
     * handler declares more than one callback parameter, the last is the one checked.
     *
     * <p>Fewer parameters than the target has is not a failure. A prefix is what the injected call
     * can supply, and a handler wanting only the first of several arguments is taking one.
     *
     * @param handler  the handler to check
     * @param method   the resolved target method
     * @param types    the type utilities used to compare erasures
     * @param reporter where to report
     */
    private static void checkParameters(@NotNull final ExecutableElement handler,
                                        @NotNull final ExecutableElement method,
                                        @NotNull final Types types,
                                        @NotNull final MessagerReporter reporter) {
        final List<? extends VariableElement> parameters = handler.getParameters();
        final List<VariableElement> arguments = new ArrayList<>();
        VariableElement callback = null;

        for (final VariableElement parameter : skipReceiver(handler, method, parameters, types)) {
            if (Anchors.mirrorOf(parameter, LOCAL) != null) {
                continue;
            }
            final String type = erasedNameOf(parameter.asType());
            if (CALLBACK.equals(type) || RETURNABLE_CALLBACK.equals(type)) {
                callback = parameter;
                continue;
            }
            arguments.add(parameter);
        }

        if (callback != null) {
            checkCallback(callback, method, types, reporter);
        }

        final List<? extends VariableElement> expected = method.getParameters();
        if (arguments.size() > expected.size()) {
            reporter.report(prefixFailure(handler, method,
                    "it takes " + arguments.size() + " argument(s) where the target has only "
                            + expected.size()), Anchor.at(handler));
            return;
        }
        for (int i = 0; i < arguments.size(); i++) {
            final TypeMirror declared = types.erasure(arguments.get(i).asType());
            final TypeMirror actual = types.erasure(expected.get(i).asType());
            if (!types.isSameType(declared, actual)) {
                reporter.report(prefixFailure(handler, method,
                        "parameter " + (i + 1) + " is " + declared + " where the target's is "
                                + actual), Anchor.at(arguments.get(i)));
                return;
            }
        }
    }

    /**
     * Drops the leading parameter that stands for the target instance, where there is one.
     *
     * <p>A static weave is never merged, so its handler has no {@code this} and takes the target as
     * its first parameter instead — which is what {@code AW1005}'s own remedy asks for. Counting
     * that parameter as an argument would make every correct static handler look like it took one
     * too many.
     *
     * <p>Recognised by type rather than by position: the first parameter is dropped only when the
     * handler is {@code static}, the target method is not, and the parameter's erasure is the
     * erasure of the type declaring the target method. Dropping it unconditionally would stop the
     * prefix rule catching anything at all in a static weave.
     *
     * @param handler    the handler whose parameters these are
     * @param method     the resolved target method
     * @param parameters the handler's parameters
     * @param types      the type utilities used to compare erasures
     * @return the parameters without the receiver, or the same list when there is none to drop
     */
    @NotNull
    private static List<? extends VariableElement> skipReceiver(
            @NotNull final ExecutableElement handler,
            @NotNull final ExecutableElement method,
            @NotNull final List<? extends VariableElement> parameters,
            @NotNull final Types types) {
        if (parameters.isEmpty() || !handler.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.STATIC)) {
            return parameters;
        }
        final TypeMirror declaring = method.getEnclosingElement().asType();
        return types.isSameType(types.erasure(parameters.getFirst().asType()),
                types.erasure(declaring))
                ? parameters.subList(1, parameters.size())
                : parameters;
    }

    /**
     * Builds the {@code AW1040} diagnostic, whose two causes differ only in the message.
     *
     * <p>The detail line and the remedy are the same however the prefix rule was broken; the
     * message carries {@code detail}, which says whether there were too many parameters or one of
     * the wrong type. The caret is chosen by the caller, not here.
     *
     * @param handler the handler that broke the rule
     * @param method  the resolved target method
     * @param detail  the phrase naming what went wrong, appended to the message
     * @return the diagnostic, not yet reported
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic prefixFailure(@NotNull final ExecutableElement handler,
                                            @NotNull final ExecutableElement method,
                                            @NotNull final String detail) {
        return Diagnostic.builder(DiagnosticCode.HANDLER_PARAMETERS_NOT_PREFIX)
                .message("handler " + handler.getSimpleName() + " does not take a prefix of "
                        + render(method) + "'s arguments: " + detail)
                .detail("the injected call pushes the target's own arguments in order, so a "
                        + "handler may take the first n of them and nothing else")
                .remedy("drop the parameters that do not correspond, or capture them with @Local "
                        + "when they are locals rather than arguments")
                .build();
    }

    /**
     * Checks a callback parameter's type argument against what the target returns.
     *
     * <p>{@code AW1071} when the two disagree, with the correct declaration spelled out in the
     * remedy. The comparison boxes the target's return type first, a type argument not being able
     * to be a primitive: a target returning {@code int} takes {@code ReturnableCallback<Integer>},
     * and comparing the two directly would refuse every primitive-returning target.
     *
     * <p>Three things pass unchecked. A raw declaration, or one whose type is not a declared type,
     * has no type argument to compare. A target returning {@code void} is accepted whatever the
     * argument says. And the comparison is on erasures, so a nested type argument is not examined.
     *
     * @param callback the callback parameter
     * @param method   the resolved target method
     * @param types    the type utilities used to box and to compare erasures
     * @param reporter where to report
     */
    private static void checkCallback(@NotNull final VariableElement callback,
                                      @NotNull final ExecutableElement method,
                                      @NotNull final Types types,
                                      @NotNull final MessagerReporter reporter) {
        if (!(callback.asType() instanceof DeclaredType declared)
                || declared.getTypeArguments().isEmpty()) {
            return;
        }
        final TypeMirror argument = declared.getTypeArguments().getFirst();
        final TypeMirror returned = method.getReturnType();
        // Boxed, because a type argument cannot be a primitive: a target returning int is
        // ReturnableCallback<Integer>, and comparing the two directly would refuse every primitive.
        final TypeMirror boxed = returned.getKind().isPrimitive()
                ? types.boxedClass((javax.lang.model.type.PrimitiveType) returned).asType()
                : returned;
        if (returned.getKind() == TypeKind.VOID
                || types.isSameType(types.erasure(argument), types.erasure(boxed))) {
            return;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.CALLBACK_TYPE_MISMATCH)
                .message("ReturnableCallback<" + argument + "> does not match " + render(method)
                        + ", which returns " + returned)
                .remedy("declare ReturnableCallback<" + boxed + '>')
                .build(), Anchor.at(callback));
    }

    /**
     * Reads the {@code allow} element of an injection annotation.
     *
     * @param injection the injection annotation's mirror
     * @return the declared maximum, or {@code 0} when the source did not write {@code allow} or
     *         wrote something that is not an {@code int}; since {@code 0} is both the element's
     *         default and its way of saying "no upper bound", a wildcard with an unwritten
     *         {@code allow} and one written as {@code 0} are alike here and both report
     *         {@code AW1022}
     */
    @Contract(pure = true)
    private static int allowOf(@NotNull final AnnotationMirror injection) {
        final AnnotationValue value = Anchors.valueOf(injection, "allow");
        return value != null && value.getValue() instanceof Integer allow ? allow : 0;
    }

    /**
     * Tests a selector's parameter patterns against a candidate method's parameters.
     *
     * <p>Arity first, then each position. A pattern matches when its source rendering equals the
     * parameter's erased name or that name's simple part, because a selector may name a type the
     * way a user writes it while the class file has only the qualified form. Both spellings are
     * therefore accepted, and a pattern written as a simple name matches every type of that simple
     * name whatever its package.
     *
     * @param patterns the selector's parameter patterns
     * @param method   the candidate method
     * @return {@code true} when the arity and every position match
     */
    @Contract(pure = true)
    private static boolean matches(@NotNull final List<TypePattern> patterns,
                                   @NotNull final ExecutableElement method) {
        final List<? extends VariableElement> parameters = method.getParameters();
        if (patterns.size() != parameters.size()) {
            return false;
        }
        for (int i = 0; i < patterns.size(); i++) {
            final String rendered = patterns.get(i).renderSource();
            final String actual = erasedNameOf(parameters.get(i).asType());
            // A selector may name a type by its simple name, which is what users write; the class
            // file has only the qualified one, so both spellings have to be accepted.
            if (!actual.equals(rendered) && !simpleNameOf(actual).equals(rendered)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a method for a diagnostic message.
     *
     * <p>Name and erased parameter types only; no return type and no modifiers. Every detail line
     * listing a target's methods uses this, so a message stays readable when a target declares many
     * of them.
     *
     * @param method the method to render
     * @return the method's name followed by its erased parameter types in parentheses, separated by
     *         commas without spaces
     */
    @Contract(pure = true)
    @NotNull
    private static String render(@NotNull final ExecutableElement method) {
        final StringBuilder text = new StringBuilder(method.getSimpleName()).append('(');
        boolean first = true;
        for (final VariableElement parameter : method.getParameters()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append(erasedNameOf(parameter.asType()));
        }
        return text.append(')').toString();
    }

    /**
     * Renders a type as the erased name a class file would record.
     *
     * <p>An array becomes its component's erased name with {@code []} appended, recursively; a
     * declared type becomes its fully qualified name, dropping any type arguments; a type variable
     * becomes its upper bound, erased in turn. Anything else — a primitive, {@code void} — is
     * rendered by the mirror itself.
     *
     * @param type the type to render
     * @return the erased name
     */
    @Contract(pure = true)
    @NotNull
    private static String erasedNameOf(@NotNull final TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> erasedNameOf(((javax.lang.model.type.ArrayType) type).getComponentType())
                    + "[]";
            case DECLARED -> ((TypeElement) ((DeclaredType) type).asElement())
                    .getQualifiedName().toString();
            case TYPEVAR -> erasedNameOf(
                    ((javax.lang.model.type.TypeVariable) type).getUpperBound());
            default -> type.toString();
        };
    }

    /**
     * Takes the part of a name after its last dot.
     *
     * <p>Textual, so a nested type rendered as {@code com.acme.Outer.Inner} yields {@code Inner}
     * and an array rendered as {@code java.lang.String[]} yields {@code String[]}.
     *
     * @param qualified the name to shorten
     * @return the part after the last dot, or the whole name when it has none
     */
    @Contract(pure = true)
    @NotNull
    private static String simpleNameOf(@NotNull final String qualified) {
        final int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
