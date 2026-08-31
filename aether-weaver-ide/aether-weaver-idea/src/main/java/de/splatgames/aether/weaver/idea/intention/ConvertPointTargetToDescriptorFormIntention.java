package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rewrites an {@code @At} target into the descriptor form.
 *
 * <p>{@code Gateway.charge(java.math.BigDecimal)} becomes
 * {@code desc:com/acme/Gateway.charge(Ljava/math/BigDecimal;)V}, taking the owner and the
 * signature from the method the target currently resolves to. The conversion is
 * {@code PointTargetLiterals.descriptorFormOf}, which requires the target to name exactly one
 * method and re-resolves its own output, so the intention is not offered for a target that names
 * a field, several methods, none, or that has no owner written on it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConvertPointTargetToDescriptorFormIntention extends PsiElementBaseIntentionAction {

    /** Creates the intention, which holds no state between invocations. */
    public ConvertPointTargetToDescriptorFormIntention() {
        // Stateless.
    }

    /**
     * Returns the text of the intention entry.
     *
     * @return the entry's text
     */
    @Override
    @NotNull
    public String getText() {
        return "Convert the target to descriptor form";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * <p>Shared with the intention that converts the other way, so that a single setting turns both
     * directions on or off.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Convert @At target form";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * <p>The conversion is computed rather than predicted: the entry appears only when
     * {@code PointTargetLiterals} has already produced the text that
     * {@link #invoke(Project, Editor, PsiElement)} would write.
     * A target already beginning with {@code desc:} converts to nothing and is not offered.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when the caret is on an {@code @At} target that converts
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return PointTargetLiterals.descriptorFormOf(PointTargetLiterals.at(element)) != null;
    }

    /**
     * Replaces the literal's text with the converted selector.
     *
     * <p>The conversion is computed again rather than carried over from
     * {@link #isAvailable(Project, Editor, PsiElement)}, and nothing happens when it no longer
     * answers. Only the value range of the literal is replaced, so the quotes stay where they
     * were.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none; not used
     * @param element the element under the caret
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @Nullable final Editor editor,
                       @NotNull final PsiElement element) {
        final PsiLiteralExpression literal = PointTargetLiterals.at(element);
        final String converted = PointTargetLiterals.descriptorFormOf(literal);
        if (literal == null || converted == null) {
            return;
        }
        ElementManipulators.handleContentChange(literal,
                ElementManipulators.getValueTextRange(literal), converted);
    }

    /**
     * Reports that the platform is to open a write action around
     * {@link #invoke(Project, Editor, PsiElement)}.
     *
     * @return {@code true}, since the literal is edited in place
     */
    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
