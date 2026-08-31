package de.splatgames.aether.weaver.idea.selector;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import de.splatgames.aether.weaver.idea.psi.PointDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolves the {@code target} of an {@code @At} to the members it names.
 *
 * <p>The range covers the member name alone rather than the whole literal, because
 * {@link PsiReferenceBase} renames by rewriting exactly the range: a reference spanning
 * {@code "Ledger.flush(int)"} would drop owner and signature the moment the target method is
 * renamed, in a refactoring the user believed was safe.
 *
 * <p>The reference is soft, so the platform does not mark an unresolved target as an error. It
 * cannot: a target names something the woven method calls, which is regularly a class the weave's
 * own module never sees. Deciding when that is a mistake belongs to
 * {@link de.splatgames.aether.weaver.idea.inspection.PointTargetInspection}, which reports
 * {@code AW1043} for a member the owner does not declare and stays silent when the owner itself
 * does not resolve.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see SelectorReferenceContributor
 */
public final class PointTargetReference extends PsiReferenceBase.Poly<PsiLiteralExpression> {

    /**
     * Creates a reference over the member name written in an {@code @At} target.
     *
     * @param literal the target literal
     * @param range   the range covering the member name, relative to the literal's text and
     *                therefore counting the opening quote as position zero
     */
    PointTargetReference(@NotNull final PsiLiteralExpression literal,
                         @NotNull final TextRange range) {
        super(literal, range, true);
    }

    /**
     * Resolves to every member the written target names.
     *
     * <p>What a target may name follows from the point it was written beside rather than from the
     * text, so this delegates to {@code PointDeclarations}: a method, a field or a class depending
     * on the point, and nothing at all for a point that names a position. A target that names no
     * owner, such as {@code "#flush"}, also resolves to nothing — the class it binds to is whatever
     * the woven method happens to call, and answering with the weave's own target would be right
     * occasionally and confidently wrong otherwise.
     *
     * @param incompleteCode ignored; the members a target names do not depend on whether the
     *                       surrounding code compiles
     * @return the members named, empty when the target names none
     */
    @Override
    public ResolveResult @NotNull [] multiResolve(final boolean incompleteCode) {
        final List<PsiElement> members = PointDeclarations.membersNamedBy(getElement());
        final ResolveResult[] results = new ResolveResult[members.size()];
        for (int index = 0; index < members.size(); index++) {
            results[index] = new PsiElementResolveResult(members.get(index));
        }
        return results;
    }

    /**
     * Returns the member name the reference covers.
     *
     * @return the text of the range, which is the member name without owner or signature
     */
    @Override
    @NotNull
    public String getCanonicalText() {
        return getRangeInElement().substring(getElement().getText());
    }

    /**
     * Reports whether the given declaration is one this target names.
     *
     * <p>Answered by resolving rather than by comparing text, so that Find Usages on a member
     * reaches the targets that name it. The kind check in front is a cheap veto: resolution can only
     * ever produce a method, a field or a class, and the platform asks this question for every
     * declaration in the file.
     *
     * @param element the declaration to test
     * @return {@code true} when resolution yields an element the project manager considers
     *         equivalent to {@code element}
     */
    @Override
    public boolean isReferenceTo(@NotNull final PsiElement element) {
        if (!(element instanceof PsiMethod || element instanceof PsiField
                || element instanceof PsiClass)) {
            return false;
        }
        for (final ResolveResult result : multiResolve(false)) {
            if (getElement().getManager().areElementsEquivalent(result.getElement(), element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the reference for a target literal, when there is a name in it to point at.
     *
     * @param literal the literal to inspect
     * @return the reference, or {@code null} when the literal is not an {@code @At} target, is not a
     *         constant string, or carries no name for its point: a point that names a position
     *         rather than an operation, a selector that does not parse, a constructor or static
     *         initialiser, and a bare {@code "*"} all leave nothing to cover
     */
    @Nullable
    static PointTargetReference of(@NotNull final PsiLiteralExpression literal) {
        final TextRange range = PointDeclarations.nameRangeIn(literal);
        return range == null ? null : new PointTargetReference(literal, range);
    }
}
