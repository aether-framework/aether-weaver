package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks the fields and methods a weave contributes, other than its handlers.
 *
 * <p>Handlers belong to {@code HandlerChecks}; everything else a weave declares is sorted here into
 * a {@link Disposition} and checked accordingly. The split is by phase rather than by member kind:
 * {@link #declaration(TypeElement, boolean, MessagerReporter)} states what is true of the weave
 * however it is applied and runs once, while
 * {@link #againstTarget(TypeElement, SourceTargets.Resolved, Types, MessagerReporter)} states what
 * is true of one target and runs once per target. A weave with three targets that declares an
 * unusable annotation is told so once; a shadow that does not bind is reported for each target it
 * does not bind to.
 *
 * <p>The two phases are independent, so one member can produce a diagnostic from each. A static
 * weave whose {@code @Shadow} field names nothing on the target reports {@code AW1090} for the
 * annotation being pointless and {@code AW1030} for the field being absent.
 *
 * <p>Everything is resolved against declared members only. {@code javax.lang.model} exposes
 * inherited members separately, and none of the lookups here asks for them: a shadow, an accessor
 * or an invoker naming a member the target inherits rather than declares is reported as missing.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MemberChecks {

    /**
     * The signatures a merged method may not silently take over, as {@code signatureOf} renders
     * them.
     *
     * <p>The three {@link Object} methods a library, a debugger or a collection calls without the
     * target's author asking, plus the launcher's {@code main}. An overload that only shares a name
     * is not one of these: the signature carries the erased parameter types, so {@code
     * equals(java.lang.String)} does not match.
     */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString()",
            "equals(java.lang.Object)",
            "hashCode()",
            "main(java.lang.String[])");

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private MemberChecks() {
        throw new AssertionError("no instances");
    }

    /**
     * Checks what is true of the weave's members whatever target it is applied to.
     *
     * <p>Runs once per weave. Handlers and anything that is neither a field nor a method are
     * skipped; what remains can produce {@code AW1090} or {@code AW1091} for an annotation a static
     * weave cannot honour, {@code AW1032} or {@code AW1093} for a field initialiser that will not
     * survive, and {@code AW1083} for merging a method the platform calls.
     *
     * <p>A member reported as pointless for a static weave is not examined further, so a
     * {@code @Shadow} field in a static weave never also reports {@code AW1032}.
     *
     * @param weave    the weave class; must not be {@code null}
     * @param isStatic whether the weave declares {@code kind = Kind.STATIC}, which is what makes
     *                 {@code @Shadow} and {@code @Unique} unusable
     * @param reporter where to report; must not be {@code null}
     * @throws NullPointerException if {@code weave} or {@code reporter} is {@code null}
     */
    static void declaration(@NotNull final TypeElement weave,
                            final boolean isStatic,
                            @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(weave, "weave");
        Objects.requireNonNull(reporter, "reporter");

        for (final Element member : weave.getEnclosedElements()) {
            final Disposition disposition = Disposition.of(member);
            if (disposition == Disposition.HANDLER || disposition == Disposition.OTHER) {
                continue;
            }
            if (isStatic && reportPointless(weave, member, disposition, reporter)) {
                continue;
            }
            if (member instanceof VariableElement field) {
                checkFieldDeclaration(weave, field, disposition, reporter);
            } else if (member instanceof ExecutableElement method
                    && disposition == Disposition.MERGED) {
                checkMergedMethodName(weave, method, reporter);
            }
        }
    }

    /**
     * Reports a merge-only annotation on a member of a weave that is never merged.
     *
     * <p>{@code AW1090} for {@code @Shadow} and {@code AW1091} for {@code @Unique}. Both say the
     * same thing: a static weave stays where it is, so a declaration whose whole meaning is a
     * binding inside the target has nothing to bind to. The remedy is to declare the weave
     * {@code @Weave(kind = Kind.INSTANCE)}, or to reach the target's state through the handler's
     * parameters.
     *
     * <p>The caret goes on the annotation when the member still carries one, which
     * {@link Disposition#mirrorOn(Element)} supplies, and on the member itself otherwise.
     *
     * @param weave       the weave class
     * @param member      the offending member
     * @param disposition the member's disposition, which decides the code
     * @param reporter    where to report
     * @return {@code true} when a diagnostic was reported and the member needs no further
     *         checking, {@code false} for a disposition this does not cover
     */
    private static boolean reportPointless(@NotNull final TypeElement weave,
                                           @NotNull final Element member,
                                           @NotNull final Disposition disposition,
                                           @NotNull final MessagerReporter reporter) {
        final DiagnosticCode code = switch (disposition) {
            case SHADOW -> DiagnosticCode.SHADOW_IN_STATIC_WEAVE;
            case UNIQUE -> DiagnosticCode.UNIQUE_IN_STATIC_WEAVE;
            default -> null;
        };
        if (code == null) {
            return false;
        }
        reporter.report(Diagnostic.builder(code)
                .message('@' + disposition.spelling() + " member '" + member.getSimpleName()
                        + "' in " + weave.getQualifiedName() + " belongs to a static weave, which "
                        + "is never merged into its target, so there is nothing for the "
                        + "declaration to bind to")
                .remedy("declare the weave @Weave(kind = Kind.INSTANCE) if it is meant to be "
                        + "merged, or reach the target's state through the handler's parameters "
                        + "instead")
                .build(), Anchor.at(member, disposition.mirrorOn(member)));
        return true;
    }

    /**
     * Reports a field initialiser the weaver will not carry into the target.
     *
     * <p>{@code AW1032} for a {@code @Shadow} field, whose value is never written anywhere because
     * the declaration only names something the target already has; {@code AW1093} for any other
     * field that reaches this check, which is copied into the target holding the JVM's default
     * value because the initialising code belongs to a constructor a weave does not have. The
     * remedies differ accordingly: delete the initialiser, or write the value from an
     * {@code @Inject} at the target constructor's {@code HEAD}.
     *
     * <p>Only an initialiser {@link VariableElement#getConstantValue()} can see is reported, which
     * is a constant variable in the JLS sense — a {@code final} field of a primitive type or
     * {@code String} whose initialiser is a compile-time constant expression. The type restriction
     * is easy to miss: measured on Temurin 25.0.3+9 (javac 25.0.3) under {@code javac -proc:only},
     * both {@code final Integer d = 5} and {@code final Object i = "s"} return {@code null} from
     * {@link VariableElement#getConstantValue()} despite an initialiser that looks constant, so
     * neither is reported here. A field initialised with anything else is dropped just as silently
     * and is not reported: neither {@code private long startedAt = System.nanoTime()} nor a
     * {@code final} array initialiser produces a diagnostic, because neither has a constant value
     * for the processor to see.
     *
     * @param weave       the weave class
     * @param field       the field to check
     * @param disposition the field's disposition, which decides the code
     * @param reporter    where to report
     */
    private static void checkFieldDeclaration(@NotNull final TypeElement weave,
                                              @NotNull final VariableElement field,
                                              @NotNull final Disposition disposition,
                                              @NotNull final MessagerReporter reporter) {
        if (field.getConstantValue() == null) {
            return;
        }
        if (disposition == Disposition.SHADOW) {
            reporter.report(Diagnostic.builder(
                            DiagnosticCode.SHADOW_FIELD_INITIALISER_IGNORED)
                    .message("@Shadow field '" + field.getSimpleName() + "' in "
                            + weave.getQualifiedName() + " has an initialiser; a shadow declares "
                            + "what the target already has, so the value is never written anywhere")
                    .remedy("delete the initialiser — the target's own value is what the weave "
                            + "reads")
                    .build(), Anchor.at(field));
            return;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.MERGED_FIELD_INITIALISER_IGNORED)
                .message("merged field '" + field.getSimpleName() + "' in "
                        + weave.getQualifiedName() + " has an initialiser; the field is copied into "
                        + "the target with the JVM's default value instead, because the initialiser "
                        + "belongs to a constructor and a weave has none to merge")
                .remedy("write the value from an @Inject at the target constructor's HEAD, which "
                        + "is the only place that runs once per instance")
                .build(), Anchor.at(field));
    }

    /**
     * Warns when a merged method takes over one of the signatures the platform calls on its own.
     *
     * <p>{@code AW1083}, a warning rather than a refusal: replacing {@code toString},
     * {@code equals}, {@code hashCode} or {@code main} is occasionally the point, and the remedy
     * asks the author to confirm it is, given that collections, debuggers and logging all reach
     * these without the target's author seeing it happen.
     *
     * <p>Matching is on the whole erased signature, so an overload sharing only the name is left
     * alone.
     *
     * @param weave    the weave class
     * @param method   the merged method to check
     * @param reporter where to report
     */
    private static void checkMergedMethodName(@NotNull final TypeElement weave,
                                              @NotNull final ExecutableElement method,
                                              @NotNull final MessagerReporter reporter) {
        if (!OBJECT_METHODS.contains(signatureOf(method))) {
            return;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.MERGED_OBJECT_METHOD)
                .message(weave.getQualifiedName() + " merges '" + signatureOf(method)
                        + "' into its target, replacing behaviour the platform itself calls")
                .remedy("make sure this is meant: collections, debuggers and logging all call "
                        + "these without the target's author being able to see it happen")
                .build(), Anchor.at(method));
    }

    /**
     * Checks the weave's members against one resolved target.
     *
     * <p>Runs once per target, so a weave naming several targets is told about each of them
     * separately. Each member is dispatched on its {@link Disposition}: a shadow is checked for
     * binding, a merged or unique member for what merging into this particular target would do, and
     * an accessor or invoker for the member it names and the name it would be generated under.
     * Handlers are left to {@code HandlerChecks}, and a member that is neither a field nor a method
     * is not something the weave contributes.
     *
     * <p>The weave's kind is not consulted here. A static weave's {@code @Shadow} has already been
     * reported as pointless by {@link #declaration(TypeElement, boolean, MessagerReporter)} and is
     * still checked against the target, so both diagnostics reach the reader.
     *
     * @param weave    the weave class; must not be {@code null}
     * @param target   the target to check against; must not be {@code null}
     * @param types    the type utilities used to compare erasures; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    static void againstTarget(@NotNull final TypeElement weave,
                              @NotNull final SourceTargets.Resolved target,
                              @NotNull final Types types,
                              @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(weave, "weave");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(reporter, "reporter");

        for (final Element member : weave.getEnclosedElements()) {
            switch (Disposition.of(member)) {
                case SHADOW -> checkShadow(member, target, types, reporter);
                case MERGED, UNIQUE -> checkMerged(member, target, reporter);
                case ACCESSOR -> checkAccessor((ExecutableElement) member, target, reporter);
                case INVOKER -> checkInvoker((ExecutableElement) member, target, reporter);
                case HANDLER, OTHER -> {
                    // A handler is checked against the target method it names, which is
                    // HandlerChecks' subject; anything else is not a member the weave contributes.
                }
            }
        }
    }

    /**
     * Checks that a {@code @Shadow} declaration binds to something the target declares.
     *
     * <p>The name searched for is {@code @Shadow("...")} when written and the member's own name
     * otherwise, so a shadow can be spelled under a different local name than the target's.
     *
     * <p>A shadowed method reports {@code AW1020} when the target declares no method of that name
     * and erased parameter types, listing the ones it does declare. A shadowed field reports
     * {@code AW1030} when there is no field of that name, then {@code AW1031} when the erasures
     * differ, then {@code AW1033} when {@code mutable = true} would remove {@code final} from the
     * target's own field. Only the first that applies is reported; each of the first two returns.
     *
     * <p>{@code AW1033} is a warning and needs nothing done — the remedy says so, and adds that
     * dropping {@code mutable = true} where the weave only reads the field leaves the target with
     * the guarantee it declared. What the diagnostic tells the reader is the cost: removing
     * {@code final} is a structural change, and a structural change is unavailable under
     * retransformation.
     *
     * @param member   the shadowed member
     * @param target   the target to bind against
     * @param types    the type utilities used to compare erasures
     * @param reporter where to report
     */
    private static void checkShadow(@NotNull final Element member,
                                    @NotNull final SourceTargets.Resolved target,
                                    @NotNull final Types types,
                                    @NotNull final MessagerReporter reporter) {
        final AnnotationMirror shadow = Anchors.mirrorOf(member, WeaveProcessor.SHADOW);
        final String name = Anchors.stringOf(shadow, "value", member.getSimpleName().toString());
        final Anchor anchor = Anchor.at(member, shadow, Anchors.valueOf(shadow, "value"));

        if (member instanceof ExecutableElement method) {
            if (methodOf(target.element(), method.getSimpleName().toString().equals(name)
                    ? signatureOf(method) : signatureWithName(method, name)) == null) {
                reporter.report(Diagnostic.builder(DiagnosticCode.METHOD_NOT_FOUND)
                        .message(target.element().getQualifiedName() + " declares no method '"
                                + signatureWithName(method, name) + "' for @Shadow to bind to")
                        .details(methodNamesOf(target.element()))
                        .remedy("a @Shadow declaration is a promise that the target has this "
                                + "member; the erased parameter types must match exactly, and an "
                                + "inherited member is not a declared one")
                        .build(), anchor);
            }
            return;
        }

        final VariableElement declared = (VariableElement) member;
        final VariableElement found = fieldOf(target.element(), name);
        if (found == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.FIELD_NOT_FOUND)
                    .message(target.element().getQualifiedName() + " declares no field '" + name
                            + "' for @Shadow to bind to")
                    .details(fieldNamesOf(target.element()))
                    .remedy("check the spelling, or the target's version")
                    .build(), anchor);
            return;
        }
        if (!types.isSameType(types.erasure(declared.asType()), types.erasure(found.asType()))) {
            reporter.report(Diagnostic.builder(DiagnosticCode.SHADOW_TYPE_MISMATCH)
                    .message("@Shadow declares '" + name + "' as " + declared.asType()
                            + ", but " + target.element().getQualifiedName() + " declares it as "
                            + found.asType())
                    .build(), Anchor.at(member));
            return;
        }
        if (Anchors.booleanOf(shadow, "mutable", false)
                && found.getModifiers().contains(Modifier.FINAL)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.SHADOW_REMOVES_FINAL)
                    .message("@Shadow(mutable = true) removes final from '" + name + "' on "
                            + target.element().getQualifiedName())
                    .detail("this is a structural change, so it is unavailable under "
                            + "retransformation")
                    .remedy("nothing needs doing; drop mutable = true if the weave only reads the "
                            + "field, so that the target keeps the guarantee it declared")
                    .build(), Anchor.at(member, shadow, Anchors.valueOf(shadow, "mutable")));
        }
    }

    /**
     * Checks a member the weave adds to the target, whether merged or {@code @Unique}.
     *
     * <p>An instance field is checked against the target's shape first. {@code AW1088} refuses one
     * merged into a record, whose {@code equals}, {@code hashCode}, {@code toString} and accessors
     * are all derived from its components and would ignore the added state; the remedy is to
     * declare the field static or keep the state outside the record. {@code AW1089} warns about one
     * merged into an enum, whose constants are already constructed by the time anything could
     * assign to it; the remedy is to accept the default value or write it from an {@code @Inject}
     * at the enum constructor's {@code HEAD}. The record case returns and the enum case does not,
     * so a field merged into an enum can also collide.
     *
     * <p>{@code AW1080} then refuses a member the target already declares — by name for a field,
     * by erased signature for a method. Overwriting the target's own member is not offered as an
     * option, so the remedy is to declare the member {@code @Unique} and have it renamed, or to
     * rename it in the source. A member that is already {@code @Unique} is exempt: renaming is what
     * that annotation asks for.
     *
     * <p>The record and enum reports are not exempt in the same way. {@code @Unique} changes the
     * member's name and not the shape of the target, so a unique instance field in a record still
     * reports {@code AW1088}.
     *
     * @param member   the field or method the weave adds
     * @param target   the target it would be added to
     * @param reporter where to report
     */
    private static void checkMerged(@NotNull final Element member,
                                    @NotNull final SourceTargets.Resolved target,
                                    @NotNull final MessagerReporter reporter) {
        final TypeElement into = target.element();
        if (member instanceof VariableElement field
                && !field.getModifiers().contains(Modifier.STATIC)) {
            if (into.getKind() == ElementKind.RECORD) {
                reporter.report(Diagnostic.builder(DiagnosticCode.MERGE_FIELD_INTO_RECORD)
                        .message("merging the instance field '" + field.getSimpleName()
                                + "' into the record " + into.getQualifiedName())
                        .remedy("a record's equals, hashCode, toString and accessors are all "
                                + "derived from its components, so a merged field is state that "
                                + "every one of them ignores. Declare the field static, or keep "
                                + "the state outside the record")
                        .build(), Anchor.at(member));
                return;
            }
            if (into.getKind() == ElementKind.ENUM) {
                reporter.report(Diagnostic.builder(DiagnosticCode.MERGE_FIELD_INTO_ENUM)
                        .message("merging the instance field '" + field.getSimpleName()
                                + "' into the enum " + into.getQualifiedName()
                                + ", whose constants are already constructed in <clinit>")
                        .remedy("nothing needs doing if the default value is what you want; "
                                + "otherwise write the field from an @Inject at the enum "
                                + "constructor's HEAD")
                        .build(), Anchor.at(member));
            }
        }

        final boolean collides = member instanceof VariableElement
                ? fieldOf(into, member.getSimpleName().toString()) != null
                : methodOf(into, signatureOf((ExecutableElement) member)) != null;
        if (!collides || Disposition.of(member) == Disposition.UNIQUE) {
            return;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.MERGED_MEMBER_COLLIDES)
                .message(into.getQualifiedName() + " already declares '" + member.getSimpleName()
                        + '\'')
                .remedy("declare the member @Unique to have it renamed instead, or rename it "
                        + "yourself. Overwriting the target's own member is not an option: it "
                        + "would replace working code with an uninitialised copy")
                .build(), Anchor.at(member));
    }

    /**
     * Checks an {@code @Accessor} against the field it would expose.
     *
     * <p>The field name is {@code @Accessor("...")} when written, and otherwise inferred from the
     * method's own name by stripping a {@code get}, {@code set} or {@code is} prefix; a name that
     * carries no such prefix is used as it stands. {@code AW1030} reports a field the target does
     * not declare, listing the fields it does, and the remedy points at the explicit spelling for
     * when the name cannot be inferred.
     *
     * <p>A method that takes a parameter is a setter. Writing a {@code final} field is refused as
     * {@code AW1097}: the woven class verifies and throws {@link IllegalAccessError} the first time
     * the setter is called, which is why neither verification nor class loading catches it. The
     * remedy is {@code @Shadow(mutable = true)}, which removes the flag deliberately and reports
     * {@code AW1033} when it does; an accessor has no way to say that is what was meant. That
     * report returns, so a setter for a final field is not additionally checked for a name
     * collision.
     *
     * @param method   the accessor declaration
     * @param target   the target that would gain the generated method
     * @param reporter where to report
     */
    private static void checkAccessor(@NotNull final ExecutableElement method,
                                      @NotNull final SourceTargets.Resolved target,
                                      @NotNull final MessagerReporter reporter) {
        final AnnotationMirror accessor = Anchors.mirrorOf(method, WeaveProcessor.ACCESSOR);
        final String name = Anchors.stringOf(accessor, "value",
                inferred(method.getSimpleName().toString(), List.of("get", "set", "is")));
        final Anchor anchor = Anchor.at(method, accessor, Anchors.valueOf(accessor, "value"));

        final VariableElement field = fieldOf(target.element(), name);
        if (field == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.FIELD_NOT_FOUND)
                    .message(target.element().getQualifiedName() + " declares no field '" + name
                            + "' for @Accessor to expose")
                    .details(fieldNamesOf(target.element()))
                    .remedy("name the field with @Accessor(\"…\") when it cannot be inferred from "
                            + "the method's name")
                    .build(), anchor);
            return;
        }
        final boolean writes = !method.getParameters().isEmpty();
        if (writes && field.getModifiers().contains(Modifier.FINAL)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.ACCESSOR_WRITES_FINAL_FIELD)
                    .message(method.getSimpleName() + " would write '" + name + "', which "
                            + target.element().getQualifiedName() + " declares final")
                    .detail("the class would verify and throw IllegalAccessError the first time "
                            + "the setter was called")
                    .remedy("a final field is written once, by the constructor. Use "
                            + "@Shadow(mutable = true), which removes the flag deliberately and "
                            + "says so — an accessor has no way to express that intent")
                    .build(), Anchor.at(method));
            return;
        }
        reportGeneratedCollision(method, target, reporter);
    }

    /**
     * Checks an {@code @Invoker} against the method it would call.
     *
     * <p>The target method's name is {@code @Invoker("...")} when written, and otherwise inferred
     * from the declaration's own name by stripping a {@code call} or {@code invoke} prefix. The
     * declaration's erased parameter types are used as they stand, so {@code AW1020} reports both a
     * name the target does not declare and a parameter list that does not match one it does; the
     * remedy says the parameters must match exactly.
     *
     * <p>A resolved invoker still has to be given a name on the target, which is what
     * {@link #reportGeneratedCollision(ExecutableElement, SourceTargets.Resolved,
     * MessagerReporter)} checks.
     *
     * @param method   the invoker declaration
     * @param target   the target that would gain the generated method
     * @param reporter where to report
     */
    private static void checkInvoker(@NotNull final ExecutableElement method,
                                     @NotNull final SourceTargets.Resolved target,
                                     @NotNull final MessagerReporter reporter) {
        final AnnotationMirror invoker = Anchors.mirrorOf(method, WeaveProcessor.INVOKER);
        final String name = Anchors.stringOf(invoker, "value",
                inferred(method.getSimpleName().toString(), List.of("call", "invoke")));
        final Anchor anchor = Anchor.at(method, invoker, Anchors.valueOf(invoker, "value"));

        if (methodOf(target.element(), signatureWithName(method, name)) == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.METHOD_NOT_FOUND)
                    .message(target.element().getQualifiedName() + " declares no method '"
                            + signatureWithName(method, name) + "' for @Invoker to call")
                    .details(methodNamesOf(target.element()))
                    .remedy("the declaration's parameters must match the target method's exactly")
                    .build(), anchor);
            return;
        }
        reportGeneratedCollision(method, target, reporter);
    }

    /**
     * Reports a generated accessor or invoker whose own signature the target already uses.
     *
     * <p>{@code AW1095}. The signature compared is the declaration's, not the member it names: an
     * invoker called {@code run()} that resolves perfectly still collides when the target declares
     * {@code run()} itself. The remedy is to rename the declaration, because a generated member
     * cannot be made {@code @Unique} — callers reach it by the name it is declared under.
     *
     * @param method   the accessor or invoker declaration
     * @param target   the target that would gain the generated method
     * @param reporter where to report
     */
    private static void reportGeneratedCollision(@NotNull final ExecutableElement method,
                                                 @NotNull final SourceTargets.Resolved target,
                                                 @NotNull final MessagerReporter reporter) {
        if (methodOf(target.element(), signatureOf(method)) == null) {
            return;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.GENERATED_MEMBER_COLLIDES)
                .message(target.element().getQualifiedName() + " already declares '"
                        + signatureOf(method) + "', which would be generated onto it")
                .remedy("rename the declaration; a generated member cannot be @Unique, because "
                        + "callers reach it by the name it is declared under")
                .build(), Anchor.at(method));
    }

    // --- the target's members ---------------------------------------------------------------

    /**
     * Finds a field the target declares itself.
     *
     * @param target the type to search
     * @param name   the field name to match exactly
     * @return the field, or {@code null} when the type declares none of that name; a field it only
     *         inherits is not found
     */
    @Contract(pure = true)
    @Nullable
    private static VariableElement fieldOf(@NotNull final TypeElement target,
                                           @NotNull final String name) {
        for (final Element member : target.getEnclosedElements()) {
            if (member instanceof VariableElement field && member.getSimpleName().contentEquals(name)
                    && field.getKind().isField()) {
                return field;
            }
        }
        return null;
    }

    /**
     * Finds a method the target declares itself.
     *
     * <p>Constructors and static initialisers are excluded by the kind test, so a signature that
     * happens to render like one matches nothing.
     *
     * @param target    the type to search
     * @param signature the signature to match, as {@code signatureOf} renders it
     * @return the method, or {@code null} when the type declares none with that signature; a method
     *         it only inherits is not found
     */
    @Contract(pure = true)
    @Nullable
    private static ExecutableElement methodOf(@NotNull final TypeElement target,
                                              @NotNull final String signature) {
        for (final Element member : target.getEnclosedElements()) {
            if (member instanceof ExecutableElement method
                    && method.getKind() == ElementKind.METHOD
                    && signatureOf(method).equals(signature)) {
                return method;
            }
        }
        return null;
    }

    /**
     * Lists the target's own methods as diagnostic detail lines.
     *
     * <p>What makes a "does not exist" message actionable is seeing what does, so the whole list is
     * attached rather than a guess at the nearest match.
     *
     * @param target the type to list
     * @return one {@code "declares: ..."} line per declared method, empty when the type declares
     *         none
     */
    @NotNull
    private static List<String> methodNamesOf(@NotNull final TypeElement target) {
        return target.getEnclosedElements().stream()
                .filter(member -> member.getKind() == ElementKind.METHOD)
                .map(member -> "declares: " + signatureOf((ExecutableElement) member))
                .toList();
    }

    /**
     * Lists the target's own field names as diagnostic detail lines.
     *
     * <p>Names only, without types: the failure these accompany is a name that resolved to nothing.
     *
     * @param target the type to list
     * @return one {@code "declares: ..."} line per declared field, empty when the type declares
     *         none
     */
    @NotNull
    private static List<String> fieldNamesOf(@NotNull final TypeElement target) {
        return target.getEnclosedElements().stream()
                .filter(member -> member.getKind().isField())
                .map(member -> "declares: " + member.getSimpleName())
                .toList();
    }

    /**
     * Renders a method's own signature.
     *
     * @param method the method to render
     * @return the method's name followed by its erased parameter types in parentheses
     */
    @Contract(pure = true)
    @NotNull
    private static String signatureOf(@NotNull final ExecutableElement method) {
        return signatureWithName(method, method.getSimpleName().toString());
    }

    /**
     * Renders a method's signature under a different name.
     *
     * <p>What a {@code @Shadow}, {@code @Accessor} or {@code @Invoker} needs: the declaration
     * supplies the parameter types and the annotation supplies the name the target uses.
     *
     * <p>The return type is not part of the rendering, so two methods of one type differing only
     * there render the same and {@code methodOf} returns whichever it reaches first.
     *
     * @param method the method whose parameters to render
     * @param name   the name to render it under
     * @return {@code name} followed by the method's erased parameter types in parentheses
     */
    @Contract(pure = true)
    @NotNull
    private static String signatureWithName(@NotNull final ExecutableElement method,
                                            @NotNull final String name) {
        final StringBuilder text = new StringBuilder(name).append('(');
        boolean first = true;
        for (final VariableElement parameter : method.getParameters()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append(erasedName(parameter.asType()));
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
    private static String erasedName(@NotNull final TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return erasedName(((javax.lang.model.type.ArrayType) type).getComponentType()) + "[]";
        }
        if (type.getKind() == TypeKind.DECLARED) {
            return ((TypeElement) ((javax.lang.model.type.DeclaredType) type).asElement())
                    .getQualifiedName().toString();
        }
        // A type variable erases to its first bound, and the bound is what the class file records.
        if (type.getKind() == TypeKind.TYPEVAR) {
            return erasedName(((javax.lang.model.type.TypeVariable) type).getUpperBound());
        }
        return type.toString();
    }

    /**
     * Derives a target member's name from a declaration's name by stripping a known prefix.
     *
     * <p>A prefix counts only when something follows it and that something is an upper-case
     * letter, so {@code getName} yields {@code name} while {@code get}, {@code getter} and
     * {@code gettext} are left alone. The first matching prefix wins; the letter after it is
     * lower-cased.
     *
     * @param name     the declaration's own name
     * @param prefixes the prefixes to try, in order of preference
     * @return the derived name, or {@code name} unchanged when no prefix applies
     */
    @Contract(pure = true)
    @NotNull
    private static String inferred(@NotNull final String name,
                                   @NotNull final List<String> prefixes) {
        for (final String prefix : prefixes) {
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Character.toLowerCase(name.charAt(prefix.length()))
                        + name.substring(prefix.length() + 1);
            }
        }
        return name;
    }

    /**
     * What the weaver will do with one of a weave's members.
     *
     * <p>Derived from the member's annotations rather than declared, and derived by the one function
     * below every time it is asked — once at declaration, once per target and again inside a merge
     * check — so the two phases of checking agree because they call the same definition, not
     * because either caches its result. {@link #MERGED} is the default: a field or method of a
     * weave with none of these annotations is copied into the target.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private enum Disposition {

        /** Bound to a member the target already has, and not contributed. */
        SHADOW("Shadow"),

        /** Contributed under a name the weaver mangles, so a collision is not possible. */
        UNIQUE("Unique"),

        /** Contributed under its own name; the default for an unannotated member. */
        MERGED(""),

        /** Not contributed as declared; the target gains a generated field accessor. */
        ACCESSOR("Accessor"),

        /** Not contributed as declared; the target gains a generated call to one of its methods. */
        INVOKER("Invoker"),

        /** An injection handler, whose checking belongs to {@code HandlerChecks}. */
        HANDLER(""),

        /** Neither a field nor a method, and therefore not a member the weave contributes. */
        OTHER("");

        /** The annotation's simple name for use in a message, or empty where there is none. */
        private final String spelling;

        /**
         * Binds a constant to the annotation name a diagnostic quotes for it.
         *
         * @param spelling the annotation's simple name, or an empty string for a disposition that
         *                 no annotation spells
         */
        Disposition(final String spelling) {
            this.spelling = spelling;
        }

        /**
         * Reports the annotation name a diagnostic quotes for this disposition.
         *
         * @return the annotation's simple name without its {@code @}, or an empty string for
         *         {@link #MERGED}, {@link #HANDLER} and {@link #OTHER}
         */
        @Contract(pure = true)
        @NotNull
        String spelling() {
            return this.spelling;
        }

        /**
         * Finds the annotation that gave a member this disposition, so a caret can land on it.
         *
         * @param member the member to read; must not be {@code null}
         * @return the annotation mirror, or {@code null} for a disposition no annotation spells and
         *         for a member that no longer carries the one it was classified by
         */
        @Contract(pure = true)
        @Nullable
        AnnotationMirror mirrorOn(@NotNull final Element member) {
            return switch (this) {
                case SHADOW -> Anchors.mirrorOf(member, WeaveProcessor.SHADOW);
                case UNIQUE -> Anchors.mirrorOf(member, WeaveProcessor.UNIQUE);
                case ACCESSOR -> Anchors.mirrorOf(member, WeaveProcessor.ACCESSOR);
                case INVOKER -> Anchors.mirrorOf(member, WeaveProcessor.INVOKER);
                default -> null;
            };
        }

        /**
         * Classifies one member of a weave.
         *
         * <p>The tests are ordered, and the first that matches wins, so a member carrying more than
         * one of these annotations is classified by the earliest: being a handler beats
         * {@code @Shadow}, which beats {@code @Accessor}, which beats {@code @Invoker}, which beats
         * {@code @Unique}. Nothing reports the redundant annotations, so a combination that reads
         * as two intentions is silently treated as one.
         *
         * @param member the member to classify; must not be {@code null}
         * @return the disposition; {@link #OTHER} for anything that is neither a field nor a
         *         method, and {@link #MERGED} for a field or method carrying none of the
         *         annotations
         */
        @Contract(pure = true)
        @NotNull
        static Disposition of(@NotNull final Element member) {
            final boolean isField = member.getKind().isField();
            if (!isField && member.getKind() != ElementKind.METHOD) {
                return OTHER;
            }
            if (member instanceof ExecutableElement method && WeaveProcessor.isHandler(method)) {
                return HANDLER;
            }
            if (Anchors.mirrorOf(member, WeaveProcessor.SHADOW) != null) {
                return SHADOW;
            }
            if (Anchors.mirrorOf(member, WeaveProcessor.ACCESSOR) != null) {
                return ACCESSOR;
            }
            if (Anchors.mirrorOf(member, WeaveProcessor.INVOKER) != null) {
                return INVOKER;
            }
            return Anchors.mirrorOf(member, WeaveProcessor.UNIQUE) != null ? UNIQUE : MERGED;
        }
    }
}
