package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNameValuePair;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Reports a {@code @Weave} whose own declaration cannot work, before the build says so.
 *
 * <p>Registered in {@code plugin.xml} under the short name {@code AetherWeaverWeaveDeclaration},
 * enabled by default and at {@code ERROR} level. Two things are inspected: how the annotation names
 * its targets, and which members a {@code @Weave(kind = Kind.STATIC)} class may declare.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>{@code AW1001} on a {@code @Weave} that names no target. An attribute written as an empty
 *       array counts as absent, so {@code @Weave({})} is reported as well.
 *   <li>{@code AW1002} on a {@code @Weave} that names its targets twice, as class literals and as
 *       {@code targets} names at once. Delete one of the two; the diagnostic says so.
 *   <li>{@code AW1090} on a {@code @Shadow} declared in a static weave, whether on a field or on a
 *       method, with a fix that replaces it with a generated member.
 *   <li>{@code AW1005} on a non-static method of a static weave that carries {@code @Inject} or
 *       {@code @Redirect}, with a fix that adds the modifier.
 *   <li>{@code AW1091} on a {@code @Unique} declared in a static weave.
 * </ul>
 *
 * <p>The three member checks apply only to a class the plugin reads as a static weave, which is a
 * {@code @Weave} whose {@code kind} attribute is written and names {@code STATIC}. A weave that
 * leaves {@code kind} unwritten, or writes it as {@code INSTANCE}, is left alone by all three,
 * since each of the three messages rests on the same premise: a static weave's code is never
 * merged into its target.
 *
 * <p>None of the four reports above resolves a target class, so all of them work on a project whose
 * targets are not on the classpath. A resolved target is what {@code WeaveMemberInspection},
 * {@code SelectorInspection} and {@code PointTargetInspection} each need instead. The
 * {@code AW1090} fix is the one exception: it resolves the weave's targets to decide whether the
 * accessor it generates needs a setter.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveDeclarationInspection extends AbstractBaseJavaLocalInspectionTool {

    /** The annotation asking for a merged member to be renamed on collision. */
    private static final String UNIQUE = "de.splatgames.aether.weaver.api.Unique";

    /** Holds no state: no instance field is declared. */
    public WeaveDeclarationInspection() {
        // Stateless.
    }

    /**
     * Returns the visitor the platform drives over the file being analysed.
     *
     * <p>A class is inspected for how it names its targets, and a field or a method for what a
     * static weave may declare. The two are independent, so a weave that names its targets wrongly
     * and declares a member it may not collects problems from both.
     *
     * @param holder     where problems are registered; must not be {@code null}
     * @param isOnTheFly whether the analysis runs in the editor rather than in a batch run; unused,
     *                   because the same problems are reported either way
     * @return a visitor over classes, fields and methods
     */
    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                          final boolean isOnTheFly) {
        return new JavaElementVisitor() {
            /**
             * Inspects how a class names its targets.
             *
             * @param declared the class being visited
             */
            @Override
            public void visitClass(@NotNull final PsiClass declared) {
                inspectTargets(declared, holder);
            }

            /**
             * Inspects a field for the annotations a static weave may not carry.
             *
             * @param field the field being visited
             */
            @Override
            public void visitField(@NotNull final PsiField field) {
                inspectStaticWeaveMember(field, holder);
            }

            /**
             * Inspects a method for the annotations and the modifiers a static weave may not carry.
             *
             * @param method the method being visited
             */
            @Override
            public void visitMethod(@NotNull final PsiMethod method) {
                inspectStaticWeaveMember(method, holder);
            }
        };
    }

    /**
     * Reports a {@code @Weave} that names no target, or names its targets twice.
     *
     * <p>Both problems are registered on the annotation itself, and never together: a weave that
     * names nothing is reported as {@code AW1001} and returns without reaching the second check.
     *
     * <p>A class carrying no {@code @Weave} is ignored, which covers every ordinary class in the
     * project.
     *
     * @param declared the class being inspected; must not be {@code null}
     * @param holder   where the problem is registered; must not be {@code null}
     */
    private static void inspectTargets(@NotNull final PsiClass declared,
                                       @NotNull final ProblemsHolder holder) {
        final PsiAnnotation weave = WeaveDeclarations.annotation(declared, WeaveDeclarations.WEAVE);
        if (weave == null) {
            return;
        }

        final boolean byLiteral = declares(weave, PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME);
        final boolean byName = declares(weave, WeaveDeclarations.TARGETS_ATTRIBUTE);
        if (!byLiteral && !byName) {
            holder.registerProblem(weave,
                    DiagnosticCode.WEAVE_NO_TARGETS.code()
                            + ": this weave names no target, so nothing would be woven",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            return;
        }
        if (byLiteral && byName) {
            holder.registerProblem(weave,
                    DiagnosticCode.WEAVE_DUPLICATE_TARGET_DECLARATION.code()
                            + ": targets are named twice; use the class literals or the names, "
                            + "not both",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
        }
    }

    /**
     * Reports the three declarations a {@code @Weave(kind = Kind.STATIC)} class may not carry.
     *
     * <p>All three are checked, and a member can collect more than one: a non-static
     * {@code @Inject} method that is also {@code @Unique} is reported twice. Each problem is
     * anchored where the reader can act on it — {@code AW1090} and {@code AW1091} on the annotation
     * that has no effect, {@code AW1005} on the handler's name, or on the whole method where it has
     * no name identifier.
     *
     * <p>{@code AW1005} is reported only for a method carrying {@code @Inject} or
     * {@code @Redirect}, and never for a constructor. An ordinary instance method of a static
     * weave is not a handler and is left alone.
     *
     * @param member the field or method being inspected; must not be {@code null}
     * @param holder where the problems are registered; must not be {@code null}
     */
    private static void inspectStaticWeaveMember(@NotNull final PsiMember member,
                                                 @NotNull final ProblemsHolder holder) {
        final PsiClass weave = member.getContainingClass();
        if (weave == null || !WeaveDeclarations.isStaticWeave(weave)) {
            return;
        }

        final PsiAnnotation shadow =
                WeaveDeclarations.annotation(member, WeaveDeclarations.SHADOW);
        if (shadow != null) {
            holder.registerProblem(shadow,
                    DiagnosticCode.SHADOW_IN_STATIC_WEAVE.code()
                            + ": a static weave's code never moves into the target, so there is "
                            + "nothing to rewrite this reference into — reach the target's state "
                            + "with @Accessor or @Invoker instead",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    new ConvertShadowToAccessorFix(member));
        }
        if (member instanceof final PsiMethod method
                && !method.hasModifierProperty(PsiModifier.STATIC)
                && !method.isConstructor()
                && isHandler(method)) {
            holder.registerProblem(method.getNameIdentifier() == null
                            ? method
                            : method.getNameIdentifier(),
                    DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER.code()
                            + ": a static weave is never merged into its target, so this handler is "
                            + "called from outside it and has no 'this' — declare it static, and "
                            + "take the target as the first parameter if it needs the instance",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    new MakeHandlerStaticFix(method));
        }
        final PsiAnnotation unique = WeaveDeclarations.annotation(member, UNIQUE);
        if (unique != null) {
            holder.registerProblem(unique,
                    DiagnosticCode.UNIQUE_IN_STATIC_WEAVE.code()
                            + ": a static weave merges nothing into the target, so there is no "
                            + "member here to make unique",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
        }
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
     * Reports whether an attribute was written with a value that names something.
     *
     * <p>The written attributes are read rather than the resolved ones, so an attribute left to its
     * declared default does not count as written. The value attribute is matched under its implicit
     * name too, which is how {@code @Weave(Session.class)} is recognised as naming a target.
     *
     * @param annotation the annotation to read; must not be {@code null}
     * @param name       the attribute name, or {@code "value"} for the unnamed form; must not be
     *                   {@code null}
     * @return whether the attribute is present with a value that is not an empty array
     */
    private static boolean declares(@NotNull final PsiAnnotation annotation,
                                    @NotNull final String name) {
        for (final PsiNameValuePair attribute : annotation.getParameterList().getAttributes()) {
            final String written = attribute.getName();
            final boolean matches = written == null
                    ? PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME.equals(name)
                    : written.equals(name);
            if (matches && attribute.getValue() != null && !isEmptyArray(attribute)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether an attribute's value is an array initialiser holding nothing.
     *
     * <p>Decided on the value's text with all whitespace removed, so an initialiser written with
     * spaces or a line break between its braces counts as empty too. An empty array names no
     * target, which is what makes {@code @Weave({})} report {@code AW1001} rather than pass.
     *
     * @param attribute the attribute to read; must not be {@code null}
     * @return whether the value is written as an empty array
     */
    private static boolean isEmptyArray(@NotNull final PsiNameValuePair attribute) {
        final PsiElement value = attribute.getValue();
        return value != null && "{}".equals(value.getText().replaceAll("\\s", ""));
    }

}
