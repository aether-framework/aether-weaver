package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import org.jetbrains.annotations.NotNull;

/**
 * Adds {@code static} to a handler declared in a static weave.
 *
 * <p>Offered by {@code WeaveDeclarationInspection} beside {@code AW1005}, which it reports for an
 * instance method carrying {@code @Inject} or {@code @Redirect} in a
 * {@code @Weave(kind = Kind.STATIC)} class.
 *
 * <p>Only the modifier changes. The parameter list and the body are left exactly as written, so a
 * handler that reached the target through {@code this} still has to be given the target as a
 * parameter by hand; the diagnostic this fix accompanies says so.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MakeHandlerStaticFix extends LocalQuickFixOnPsiElement {

    /**
     * Binds the fix to the handler it will modify.
     *
     * @param handler the handler to declare {@code static}; must not be {@code null}
     */
    MakeHandlerStaticFix(@NotNull final PsiMethod handler) {
        super(handler);
    }

    /**
     * Returns the text shown on the individual intention entry.
     *
     * <p>The same text as {@link #getFamilyName()}: the action does not vary with the handler, so a
     * per-occurrence wording would add nothing the family name does not already say.
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
     * @return {@code "Declare the handler static"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Declare the handler static";
    }

    /**
     * Sets {@code static} on the handler.
     *
     * <p>The handler is {@code startElement} when that is a method and its parent otherwise. Nothing
     * happens when neither is a method.
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
        final PsiElement owner = startElement instanceof PsiMethod
                ? startElement
                : startElement.getParent();
        if (!(owner instanceof final PsiMethod handler)) {
            return;
        }
        final PsiModifierList modifiers = handler.getModifierList();
        modifiers.setModifierProperty(PsiModifier.STATIC, true);
    }
}
