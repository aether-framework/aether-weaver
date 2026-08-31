package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiLiteralExpression;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.select.FieldSelector;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.SelectorSyntaxException;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.parse.PointTargets;
import de.splatgames.aether.weaver.idea.psi.PointDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reports an {@code @At} whose {@code target} does not agree with the point it is written on.
 *
 * <p>Registered in {@code plugin.xml} under the short name {@code AetherWeaverPointTarget}, enabled
 * by default and at {@code ERROR} level. Everything it reports carries {@code AW1043}, except a
 * selector the parser refuses, which carries the parser's own code.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>{@code AW1043} on an {@code @At} whose point names an operation and whose {@code target} is
 *       absent or blank. That is {@code Point.INVOKE}, {@code Point.INVOKE_AFTER},
 *       {@code Point.FIELD} and {@code Point.NEW}, the points whose target requirement is required.
 *       Name the operation to match, or move to a point that needs no target.
 *   <li>{@code AW1043} on a {@code target} written on a point that names a position rather than an
 *       operation. That is {@code Point.HEAD}, {@code Point.RETURN} and {@code Point.TAIL}, the
 *       points whose target requirement is forbidden. Delete the attribute.
 *   <li>The parser's own code — {@code AW1015}, {@code AW1017}, {@code AW1018} or {@code AW1019} —
 *       where the target does not parse as a selector, with the parser's message and, where the
 *       parser offers a corrected spelling that itself parses, a fix applying it.
 *   <li>{@code AW1043} where the target names an owner class that resolves to exactly one class and
 *       neither that class nor a supertype has a member of the written name. The message names the
 *       class.
 * </ul>
 *
 * <h2>What silences it</h2>
 *
 * <p>{@code Point.CONSTANT} and {@code Point.THROW} take an optional target, so for them a missing
 * target is never reported and a written one is never reported as forbidden. A point identifier
 * that is no built-in point's — a custom point named through {@code At.custom()} — has no
 * requirement to check either way.
 *
 * <p>Only {@code Point.INVOKE}, {@code Point.INVOKE_AFTER}, {@code Point.FIELD} and
 * {@code Point.CONSTANT} have a selector kind, so those four are the only points whose written
 * target is parsed and looked up at all. {@code Point.NEW} names a class rather than a member,
 * {@code Point.THROW} accepts a target and ignores it, and a custom point defines its own grammar;
 * none of the three is parsed here. A target that parses to a constant rather than a member names a
 * value and is not looked up.
 *
 * <p>The member lookup is skipped unless the selector names an owner that resolves to exactly one
 * class: an unqualified target, a name this module cannot see, and a simple name matching several
 * classes all leave nothing known about what should have been found. A name written as a wildcard,
 * and a selector naming a constructor or the static initialiser, are not looked up either. The
 * lookup asks only whether a member of the name exists, and it walks supertypes, so neither a
 * written parameter list nor a written field type is compared against what the class declares.
 *
 * <h2>What this inspection is not</h2>
 *
 * <p>The target names the member an operation inside the woven method refers to, which need not be
 * a member of the class the weave targets; nothing here is compared against {@code @Weave}. Whether
 * the woven method performs such an operation at all is settled at weave time and reported as
 * {@code AW1043} there.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PointTargetInspection extends AbstractBaseJavaLocalInspectionTool {

    /** Holds no state: no instance field is declared. */
    public PointTargetInspection() {
        // Stateless.
    }

    /**
     * Returns the visitor the platform drives over the file being analysed.
     *
     * <p>The annotation and the literal are visited separately because the two halves of the check
     * are about different elements: a missing target has no literal to underline and is reported on
     * the {@code @At}, while everything about a written target is reported on the literal itself.
     *
     * @param holder     where problems are registered; must not be {@code null}
     * @param isOnTheFly whether the analysis runs in the editor rather than in a batch run; unused,
     *                   because the same problems are reported either way
     * @return a visitor over annotations and string literals
     */
    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                          final boolean isOnTheFly) {
        return new JavaElementVisitor() {
            /**
             * Inspects an annotation that may be an {@code @At} missing its target.
             *
             * @param annotation the annotation being visited
             */
            @Override
            public void visitAnnotation(@NotNull final PsiAnnotation annotation) {
                inspectAnnotation(annotation, holder);
            }

            /**
             * Inspects a literal that may be an {@code @At} target.
             *
             * @param literal the literal being visited
             */
            @Override
            public void visitLiteralExpression(@NotNull final PsiLiteralExpression literal) {
                inspectTarget(literal, holder);
            }
        };
    }

    /**
     * Reports an {@code @At} that needs a target and has none.
     *
     * <p>The attribute is read as resolved rather than as written, so an {@code @At} that omits
     * {@code target} sees the element's declared default and reaches the same blank test as one
     * that writes an empty string. A value that is not a string literal counts as blank too.
     *
     * <p>Only a point whose requirement is exactly required is reported. A custom point, whose
     * requirement is unknown, and the two optional points are both left alone.
     *
     * @param annotation the annotation being inspected; must not be {@code null}
     * @param holder     where the problem is registered; must not be {@code null}
     */
    private static void inspectAnnotation(@NotNull final PsiAnnotation annotation,
                                          @NotNull final ProblemsHolder holder) {
        if (!PointDeclarations.AT.equals(annotation.getQualifiedName())) {
            return;
        }
        final PsiAnnotationMemberValue written =
                annotation.findAttributeValue(PointDeclarations.TARGET_ATTRIBUTE);
        if (written != null && !isBlank(written)) {
            return;
        }
        final String point = PointDeclarations.pointOf(annotation);
        if (BuiltInPoints.requirementOf(point) == InjectionPoint.TargetRequirement.REQUIRED) {
            holder.registerProblem(annotation,
                    DiagnosticCode.NO_INJECTION_POINT_MATCHED.code() + ": " + point
                            + " names an operation and needs a target to say which one",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
        }
    }

    /**
     * Reports what a written {@code target} fails to name.
     *
     * <p>A literal is a target only when it is the {@code target} attribute of an {@code @At}, which
     * is decided by three fixed steps up the tree and a name comparison rather than by a search, so a
     * literal in an annotation nested inside an {@code @At} is not mistaken for one.
     *
     * <p>Runs in order: the point forbids a target, then the text parses, then the owner resolves,
     * then the owner declares the member. Each step that cannot answer returns rather than guessing.
     *
     * @param literal the literal being inspected; must not be {@code null}
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void inspectTarget(@NotNull final PsiLiteralExpression literal,
                                      @NotNull final ProblemsHolder holder) {
        final PsiAnnotation at = PointDeclarations.atOf(literal);
        if (at == null || isBlank(literal)
                || !(literal.getValue() instanceof final String text)) {
            return;
        }
        final String point = PointDeclarations.pointOf(at);
        if (BuiltInPoints.requirementOf(point) == InjectionPoint.TargetRequirement.FORBIDDEN) {
            holder.registerProblem(literal,
                    DiagnosticCode.NO_INJECTION_POINT_MATCHED.code() + ": " + point
                            + " names a position rather than an operation, so it takes no target",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            return;
        }

        final MemberKind kind = PointTargets.selectorKindFor(point);
        if (kind == null) {
            // NEW names a class and a custom point defines its own grammar. Neither is parsed here,
            // and neither is this inspection's to second-guess.
            return;
        }
        final MemberSelector selector = reported(literal, text, kind, holder);
        if (selector == null) {
            return;
        }
        final PsiClass owner = soleOwnerOf(selector, literal);
        if (owner == null) {
            // No owner written, a name that resolves to nothing this module can see, or several
            // classes of one simple name. In each case nothing is known about what should have been
            // found, and silence is the only honest answer.
            return;
        }
        reportMissingMember(literal, selector, owner, holder);
    }

    /**
     * Parses the target, reporting a syntax failure rather than returning one.
     *
     * <p>The code and the message are the parser's, so the code shown in the editor is the code the
     * build would print. Where the parser carries a corrected spelling, and that spelling parses in
     * its turn, {@link ApplySelectorSuggestionFix} offers it; where it does not, the message stands
     * on its own.
     *
     * <p>A runtime failure that is not a {@link SelectorSyntaxException} carries no code and is not
     * reported at all.
     *
     * @param literal the literal the target was written in; must not be {@code null}
     * @param text    the target text; must not be {@code null}
     * @param kind    the kind of member a bare name names at this point; must not be {@code null}
     * @param holder  where a syntax problem is registered; must not be {@code null}
     * @return the parsed selector, or {@code null} when the text does not parse
     */
    @Nullable
    private static MemberSelector reported(@NotNull final PsiLiteralExpression literal,
                                           @NotNull final String text,
                                           @NotNull final MemberKind kind,
                                           @NotNull final ProblemsHolder holder) {
        try {
            return MemberSelector.parse(text, kind);
        } catch (final SelectorSyntaxException malformed) {
            // The parser knows what the author meant far more often than a plugin could guess —
            // AW1017 arrives carrying the same selector with "desc:" in front of it.
            final ApplySelectorSuggestionFix fix = ApplySelectorSuggestionFix.of(literal,
                    malformed.suggestion().orElse(null));
            holder.registerProblem(literal,
                    malformed.code().code() + ": " + malformed.getMessage(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fix == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[]{fix});
            return null;
        } catch (final RuntimeException unusable) {
            // Not a syntax failure this inspection understands; the build will say what it is.
            return null;
        }
    }

    /**
     * Reports {@code AW1043} where neither the named owner nor a supertype has a member of the
     * written name.
     *
     * <p>The name alone decides it. A method selector is answered by whether the owner or a
     * supertype declares any method of the name, and a field selector by the same question for
     * fields, so a written parameter list or field type that no member has is not reported here.
     *
     * <p>A method selector naming a constructor or the static initialiser is not looked up, and
     * neither is a name written as the wildcard. A constant selector names a value rather than a
     * member of anything and is passed over.
     *
     * @param literal  the literal the target was written in; must not be {@code null}
     * @param selector the parsed target; must not be {@code null}
     * @param owner    the one class the selector's owner resolved to; must not be {@code null}
     * @param holder   where the problem is registered; must not be {@code null}
     */
    private static void reportMissingMember(@NotNull final PsiLiteralExpression literal,
                                            @NotNull final MemberSelector selector,
                                            @NotNull final PsiClass owner,
                                            @NotNull final ProblemsHolder holder) {
        final String name;
        final String what;
        switch (selector) {
            case final MethodSelector method -> {
                if (method.isInitialiser() || "*".equals(method.name())
                        || owner.findMethodsByName(method.name(), true).length > 0) {
                    return;
                }
                name = method.name();
                what = "method";
            }
            case final FieldSelector field -> {
                if ("*".equals(field.name()) || owner.findFieldByName(field.name(), true) != null) {
                    return;
                }
                name = field.name();
                what = "field";
            }
            // A constant names a value rather than a member of anything.
            case null, default -> {
                return;
            }
        }
        holder.registerProblem(literal,
                DiagnosticCode.NO_INJECTION_POINT_MATCHED.code() + ": " + owner.getQualifiedName()
                        + " declares no " + what + " named '" + name
                        + "', so no operation in the target can match",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }

    /**
     * Returns the class the selector's owner names, when exactly one is found.
     *
     * <p>Several classes of one simple name is treated the same as none: which of them the target
     * meant is not knowable from the target, and reporting against the wrong one would underline
     * correct code.
     *
     * @param selector the parsed target; must not be {@code null}
     * @param context  the element whose module and scope the lookup runs in; must not be
     *                 {@code null}
     * @return the class, or {@code null} when the selector names no owner or the owner does not
     *         resolve to exactly one class
     */
    @Nullable
    private static PsiClass soleOwnerOf(@NotNull final MemberSelector selector,
                                        @NotNull final PsiElement context) {
        final List<PsiClass> owners = PointDeclarations.ownersOf(selector, context);
        return owners.size() == 1 ? owners.getFirst() : null;
    }

    /**
     * Reports whether an annotation value names nothing.
     *
     * <p>Anything that is not a string literal counts as blank, so a constant reference or an array
     * written where a target belongs is treated as no target rather than as an unparseable one.
     *
     * @param value the value to test; must not be {@code null}
     * @return whether the value is not a string literal, or is one whose text is blank
     */
    private static boolean isBlank(@NotNull final PsiElement value) {
        return !(value instanceof final PsiLiteralExpression literal)
                || !(literal.getValue() instanceof final String text)
                || text.isBlank();
    }
}
