package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import org.jetbrains.annotations.NotNull;

/**
 * Moves the parameter carrying {@link de.splatgames.aether.weaver.api.experimental.Receiver} to the
 * front of the parameter list.
 *
 * <p>Offered by {@code ExtensionDeclarationInspection} beside {@code AW1303}, which it reports for
 * a contributed method whose {@code @Receiver} is on a parameter other than the first.
 *
 * <p>The parameter is copied in front of the current first one and the original is then deleted, so
 * the annotation, the modifiers and the type all travel with it. The other parameters keep their
 * order and shift one place right; every call already written against the method has to be
 * reordered by hand, because nothing outside the parameter list is touched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MoveReceiverParameterFirstFix extends LocalQuickFixOnPsiElement {

    /**
     * Binds the fix to the parameter it will move.
     *
     * @param receiver the parameter carrying {@code @Receiver}; must not be {@code null}
     */
    MoveReceiverParameterFirstFix(@NotNull final PsiParameter receiver) {
        super(receiver);
    }

    /**
     * Returns the text shown on the individual intention entry.
     *
     * <p>The same text as {@link #getFamilyName()}: the action does not vary with the parameter, so
     * a per-occurrence wording would add nothing the family name does not already say.
     *
     * @return the intention text
     */
    @Override
    @NotNull
    public String getText() {
        return getFamilyName();
    }

    /**
     * Returns the family this fix is grouped and suppressed under.
     *
     * @return {@code "Move the @Receiver parameter first"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Move the @Receiver parameter first";
    }

    /**
     * Reinserts the parameter at position zero.
     *
     * <p>Nothing happens when {@code startElement} is not a parameter, when its parent is not a
     * parameter list, when that list holds fewer than two parameters, or when the parameter is
     * already the first.
     *
     * @param project      the project the file belongs to; must not be {@code null}
     * @param file         the file being modified; must not be {@code null}
     * @param startElement the element the fix was created for; must not be {@code null}
     * @param endElement   the end of the range the fix was created for; must not be {@code null}
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        if (!(startElement instanceof final PsiParameter receiver)
                || !(receiver.getParent() instanceof final PsiParameterList list)) {
            return;
        }
        final PsiParameter[] parameters = list.getParameters();
        if (parameters.length < 2 || parameters[0] == receiver) {
            return;
        }
        list.addBefore(receiver.copy(), parameters[0]);
        receiver.delete();
    }
}
