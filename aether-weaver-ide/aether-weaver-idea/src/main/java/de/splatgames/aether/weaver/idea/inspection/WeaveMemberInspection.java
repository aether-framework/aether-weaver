package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNamedElement;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reports a weave member that does not fit the target it will be woven into.
 *
 * <p>Registered in {@code plugin.xml} under the short name {@code AetherWeaverWeaveMember}, enabled
 * by default and at {@code ERROR} level. Every check here compares a member of a {@code @Weave}
 * class against the targets that weave names, so all of them need the target resolved: a weave
 * whose targets are named as strings that this module cannot see is passed over in silence rather
 * than guessed at. A weave that names no target at all is {@code AW1001}, which
 * {@code WeaveDeclarationInspection} owns.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>{@code AW1031} on a {@code @Shadow} field whose declared type is not the erased type the
 *       target gave the field of that name. A field the target does not declare is not reported
 *       here; that is {@code AW1030}, raised by the same shadow check at build time and not by
 *       any inspection in this plugin.
 *   <li>{@code AW1042} on a handler of a static weave that the call injected into the target could
 *       not reach, with a fix that widens the handler and its class. Checked only for a
 *       {@code @Weave(kind = Kind.STATIC)}, whose handler is never merged and is therefore called
 *       across classes, subject to ordinary access rules.
 *   <li>{@code AW1095} on an {@code @Accessor} or {@code @Invoker} whose name and erased parameter
 *       types are those of a method the target already declares. Renaming the declaration is the
 *       only fix: a generated member cannot be {@code @Unique}, because callers reach it by the
 *       name it is declared under.
 *   <li>{@code AW1080} on a member of an instance weave that would be merged onto a member the
 *       target already declares. Declaring it {@code @Unique} silences this, which is what
 *       {@code @Unique} is for.
 * </ul>
 *
 * <h2>What counts as a target's own member</h2>
 *
 * <p>The three checks that look up a member — the shadow's type, the generated collision and the
 * merged collision — read only what the target class declares itself. An inherited member is not
 * one of them, so a weave field named like a field of the target's superclass is not reported as a
 * collision, and a {@code @Shadow} of an inherited field is not type-checked.
 *
 * <p>Every check walks the named targets in turn and registers at most one problem: the first
 * target that disagrees is reported and the rest are not examined.
 *
 * <h2>Which members are merged</h2>
 *
 * <p>{@code AW1080} is the check with the most exclusions, and each of them is a member that does
 * not arrive in the target under its own name. A static weave merges nothing, so none of its
 * members is considered. A constructor is excluded, as is an {@code @Accessor} or {@code @Invoker},
 * which {@code AW1095} covers instead. {@code @Unique} is excluded because it asks to be renamed on
 * collision, and {@code @Shadow} because it declares a member the target is expected to have
 * already. Everything else a weave class declares is merged, including a handler: an
 * {@code @Inject} method whose name and erased parameter types match one of the target's own is
 * reported.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveMemberInspection extends AbstractBaseJavaLocalInspectionTool {

    /** The annotation asking for a merged member to be renamed on collision. */
    private static final String UNIQUE = "de.splatgames.aether.weaver.api.Unique";

    /** Holds no state: no instance field is declared. */
    public WeaveMemberInspection() {
        // Stateless.
    }

    /**
     * Returns the visitor the platform drives over the file being analysed.
     *
     * @param holder     where problems are registered; must not be {@code null}
     * @param isOnTheFly whether the analysis runs in the editor rather than in a batch run; unused,
     *                   because the same problems are reported either way
     * @return a visitor over fields and methods
     */
    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                          final boolean isOnTheFly) {
        return new JavaElementVisitor() {
            /**
             * Inspects a field against the enclosing weave's targets.
             *
             * @param field the field being visited
             */
            @Override
            public void visitField(@NotNull final PsiField field) {
                inspect(field, holder);
            }

            /**
             * Inspects a method against the enclosing weave's targets.
             *
             * @param method the method being visited
             */
            @Override
            public void visitMethod(@NotNull final PsiMethod method) {
                inspect(method, holder);
            }
        };
    }

    /**
     * Runs every check that applies to one member of a weave class.
     *
     * <p>A member whose containing class carries no {@code @Weave}, and a weave none of whose
     * targets resolve, are both left alone. The checks are independent, so one member can collect
     * several problems: a method is offered to the accessibility check, the generated-collision
     * check and the merge-collision check in turn.
     *
     * @param member the field or method being inspected; must not be {@code null}
     * @param holder where the problems are registered; must not be {@code null}
     */
    private static void inspect(@NotNull final PsiMember member,
                                @NotNull final ProblemsHolder holder) {
        final PsiClass weave = member.getContainingClass();
        if (weave == null || WeaveDeclarations.annotation(weave, WeaveDeclarations.WEAVE) == null) {
            return;
        }
        final List<PsiClass> targets = WeaveDeclarations.targetsOf(weave);
        if (targets.isEmpty()) {
            // Nothing resolved to compare against; that is AW1001's business, not this one's.
            return;
        }

        if (member instanceof final PsiField field
                && WeaveDeclarations.annotation(field, WeaveDeclarations.SHADOW) != null) {
            reportShadowType(field, targets, holder);
        }
        if (member instanceof final PsiMethod method) {
            reportAccessibility(method, weave, targets, holder);
            reportGeneratedCollision(method, targets, holder);
        }
        reportMergedCollision(member, weave, targets, holder);
    }

    /**
     * Reports {@code AW1031} where a {@code @Shadow} field's type is not the target's.
     *
     * <p>Types are compared as erased canonical names, which is the comparison the weaver makes:
     * a {@code List<String>} shadowing a {@code List<Integer>} is accepted here because the class
     * file records neither type argument.
     *
     * <p>Three things silence the check, and each of them is a case where nothing is known rather
     * than a case where the declaration is right: the shadow's own type does not resolve, the
     * target does not declare a field of that name, or the target's field type does not resolve.
     * The problem is anchored on the type element, or on the whole field where there is none.
     *
     * @param field   the shadowed field; must not be {@code null}
     * @param targets the classes the weave names; must not be {@code null}
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void reportShadowType(@NotNull final PsiField field,
                                         @NotNull final List<PsiClass> targets,
                                         @NotNull final ProblemsHolder holder) {
        final String name = field.getName();
        final String written = HandlerSignature.erasedNameOf(field.getType());
        if (written == null) {
            return;
        }
        for (final PsiClass target : targets) {
            final PsiField declared = TargetMembers.fieldNamed(target, name);
            final String actual = declared == null
                    ? null
                    : HandlerSignature.erasedNameOf(declared.getType());
            if (declared == null || actual == null || actual.equals(written)) {
                // Absent is AW1030 and belongs to the selector inspection; unresolved is silence.
                continue;
            }
            holder.registerProblem(field.getTypeElement() == null ? field : field.getTypeElement(),
                    DiagnosticCode.SHADOW_TYPE_MISMATCH.code()
                            + ": the target declares '" + name + "' as " + actual
                            + " — a @Shadow is a promise about a member that already exists, so its "
                            + "type has to be the one the target gave it",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            return;
        }
    }

    /**
     * Reports {@code AW1042} where the call injected into a target could not reach the handler.
     *
     * <p>Applies only to a {@code @Weave(kind = Kind.STATIC)} whose method carries {@code @Inject}
     * or {@code @Redirect}. A static weave is never merged, so the injected call is an ordinary
     * cross-class invocation and is subject to ordinary access rules.
     *
     * <p>The handler is treated as reachable from one target when it is not {@code private} and
     * either it and its weave class are both {@code public}, or the weave and the target are
     * declared in the same package. Package-private in the same package therefore passes, and a
     * {@code private} handler is reported even there. Across packages both declarations have to be
     * {@code public}, so a {@code public} handler in a package-private weave is reported. Both
     * packages are read off the enclosing Java file, and a class whose file is not one is skipped
     * rather than reported.
     *
     * <p>The problem is anchored on the handler's name, or on the whole method where there is no
     * name identifier, and carries {@code MakeHandlerReachableFix}.
     *
     * @param handler the method to check; must not be {@code null}
     * @param weave   the class declaring it; must not be {@code null}
     * @param targets the classes the weave names; must not be {@code null}
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void reportAccessibility(@NotNull final PsiMethod handler,
                                            @NotNull final PsiClass weave,
                                            @NotNull final List<PsiClass> targets,
                                            @NotNull final ProblemsHolder holder) {
        if (!WeaveDeclarations.isStaticWeave(weave) || !isHandler(handler)) {
            return;
        }
        final String weavePackage = packageOf(weave);
        if (weavePackage == null) {
            return;
        }

        for (final PsiClass target : targets) {
            final String targetPackage = packageOf(target);
            if (targetPackage == null) {
                continue;
            }
            final boolean reachable = !handler.hasModifierProperty(PsiModifier.PRIVATE)
                    && (handler.hasModifierProperty(PsiModifier.PUBLIC)
                            && weave.hasModifierProperty(PsiModifier.PUBLIC)
                            || weavePackage.equals(targetPackage));
            if (reachable) {
                continue;
            }
            holder.registerProblem(handler.getNameIdentifier() == null
                            ? handler
                            : handler.getNameIdentifier(),
                    DiagnosticCode.HANDLER_NOT_ACCESSIBLE.code()
                            + ": the call emitted into " + target.getName() + " could not reach "
                            + handler.getName() + " — a static weave is never merged, so the call "
                            + "is an ordinary cross-class invocation subject to ordinary access "
                            + "rules. The class would verify and load; IllegalAccessError arrives "
                            + "at the first execution of the injected call",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    new MakeHandlerReachableFix(handler));
            return;
        }
    }

    /**
     * Reports {@code AW1095} where a generated member would land on one the target already has.
     *
     * <p>Applies to a method carrying {@code @Accessor} or {@code @Invoker}. The signature compared
     * is the method's name followed by its erased parameter types, so two methods that differ only
     * in a return type or in a type argument collide. A method with a parameter whose type does not
     * resolve produces no signature and is passed over.
     *
     * <p>The problem is anchored on the method's name, or on the whole method where there is no
     * name identifier.
     *
     * @param method  the generated member's declaration; must not be {@code null}
     * @param targets the classes the weave names; must not be {@code null}
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void reportGeneratedCollision(@NotNull final PsiMethod method,
                                                 @NotNull final List<PsiClass> targets,
                                                 @NotNull final ProblemsHolder holder) {
        if (!isGenerated(method)) {
            return;
        }
        final String signature = TargetMembers.signatureOf(method);
        if (signature == null) {
            return;
        }
        for (final PsiClass target : targets) {
            if (TargetMembers.methodWithSignature(target, signature) == null) {
                continue;
            }
            holder.registerProblem(method.getNameIdentifier() == null
                            ? method
                            : method.getNameIdentifier(),
                    DiagnosticCode.GENERATED_MEMBER_COLLIDES.code() + ": " + target.getName()
                            + " already declares '" + signature + "', which would be generated onto "
                            + "it — rename the declaration; a generated member cannot be @Unique, "
                            + "because callers reach it by the name it is declared under",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            return;
        }
    }

    /**
     * Reports {@code AW1080} where a merged member would land on one the target already declares.
     *
     * <p>A method is matched by its name and erased parameter types, a field by its name alone. A
     * method with a parameter whose type does not resolve produces no signature and is passed over.
     *
     * <p>The problem is anchored on the member's name, or on the whole member where there is no
     * name identifier. No fix is offered; the message names the two ways out, renaming the member
     * or declaring it {@code @Unique}.
     *
     * @param member  the member being merged; must not be {@code null}
     * @param weave   the class declaring it; must not be {@code null}
     * @param targets the classes the weave names; must not be {@code null}
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void reportMergedCollision(@NotNull final PsiMember member,
                                              @NotNull final PsiClass weave,
                                              @NotNull final List<PsiClass> targets,
                                              @NotNull final ProblemsHolder holder) {
        if (!isMerged(member, weave)) {
            return;
        }
        final String signature = member instanceof final PsiMethod method
                ? TargetMembers.signatureOf(method)
                : member.getName();
        if (signature == null) {
            return;
        }

        for (final PsiClass target : targets) {
            final PsiElement declared = member instanceof PsiMethod
                    ? TargetMembers.methodWithSignature(target, signature)
                    : TargetMembers.fieldNamed(target, signature);
            if (declared == null) {
                continue;
            }
            holder.registerProblem(anchorFor(member),
                    DiagnosticCode.MERGED_MEMBER_COLLIDES.code() + ": " + target.getName()
                            + " already declares '" + signature + "' — declare the member @Unique "
                            + "to have it renamed instead, or rename it yourself. Overwriting the "
                            + "target's own member is not an option: it would replace working code "
                            + "with an uninitialised copy",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            return;
        }
    }

    /**
     * Reports whether a member arrives in the target under the name it is declared with.
     *
     * <p>The exclusions are listed on the class. A member of a static weave is never merged, a
     * constructor and a generated member never arrive under their own name, and {@code @Unique} and
     * {@code @Shadow} both mean the collision the caller is about to look for is expected.
     *
     * @param member the member to test; must not be {@code null}
     * @param weave  the class declaring it; must not be {@code null}
     * @return whether a collision with the target's own member would be a fault
     */
    private static boolean isMerged(@NotNull final PsiMember member, @NotNull final PsiClass weave) {
        if (WeaveDeclarations.isStaticWeave(weave)) {
            // A static weave merges nothing at all, so nothing of its can collide.
            return false;
        }
        if (member instanceof final PsiMethod method
                && (method.isConstructor() || isGenerated(method))) {
            return false;
        }
        // @Unique is excluded because it is precisely the declaration that says "rename me on
        // collision" — the framework mangles it and reports AW1094. @Shadow is excluded because it
        // declares a member the target already has; that the target has it is the point.
        return member instanceof PsiModifierListOwner owner
                && WeaveDeclarations.annotation(owner, UNIQUE) == null
                && WeaveDeclarations.annotation(owner, WeaveDeclarations.SHADOW) == null;
    }

    /**
     * Reports whether a method is a declaration the weaver writes a body for.
     *
     * @param method the method to test; must not be {@code null}
     * @return whether the method carries {@code @Accessor} or {@code @Invoker}
     */
    private static boolean isGenerated(@NotNull final PsiMethod method) {
        return WeaveDeclarations.annotation(method, WeaveDeclarations.ACCESSOR) != null
                || WeaveDeclarations.annotation(method, WeaveDeclarations.INVOKER) != null;
    }

    /**
     * Reports whether a method is called from an injected site rather than from the weave itself.
     *
     * @param method the method to test; must not be {@code null}
     * @return whether the method carries {@code @Inject} or {@code @Redirect}
     */
    private static boolean isHandler(@NotNull final PsiMethod method) {
        return WeaveDeclarations.annotation(method, WeaveDeclarations.INJECT) != null
                || WeaveDeclarations.annotation(method, WeaveDeclarations.REDIRECT) != null;
    }

    /**
     * Returns the element a collision on a member is underlined on.
     *
     * <p>The name alone, so the highlight covers the identifier the reader has to change rather
     * than the whole declaration with its annotations and its body.
     *
     * @param member the member being reported; must not be {@code null}
     * @return the member's name identifier, or the member itself when it is neither a method nor a
     *         field, or has no name identifier
     */
    @NotNull
    private static PsiElement anchorFor(@NotNull final PsiMember member) {
        final PsiElement identifier = member instanceof final PsiNamedElement named
                && named instanceof PsiMethod method
                ? method.getNameIdentifier()
                : member instanceof final PsiField field ? field.getNameIdentifier() : null;
        return identifier == null ? member : identifier;
    }

    /**
     * Returns the package a class is declared in.
     *
     * <p>Read off the enclosing file rather than from the qualified name, so a class in the default
     * package answers the empty string and two classes in the default package compare equal.
     *
     * @param declared the class to locate; must not be {@code null}
     * @return the package name, or {@code null} when the class is not in a Java file
     */
    @Nullable
    private static String packageOf(@NotNull final PsiClass declared) {
        return declared.getContainingFile() instanceof final PsiJavaFile java
                ? java.getPackageName()
                : null;
    }
}
