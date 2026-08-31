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
 * Adds {@code static} to a method of an extension holder.
 *
 * <p>Offered by {@link ExtensionDeclarationInspection} beside {@code AW1301}, which it reports on a {@code public}
 * method declared by an {@code @Extension} class without {@code static}. Only the modifier is written; the
 * {@code @Receiver} parameter and the body are left as the author wrote them.
 *
 * <p>The inspection anchors its report on the method's name identifier rather than on the method, so this fix
 * accepts either and finds the method from the one it is given.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MakeContributedMethodStaticFix extends LocalQuickFixOnPsiElement {

    /**
     * Anchors the fix to a method or to the identifier naming it.
     *
     * @param method the element the report was registered on; either the method itself or a child of it whose parent
     *               is the method, which is what the identifier the inspection uses as its anchor is
     */
    MakeContributedMethodStaticFix(@NotNull final PsiElement method) {
        super(method);
    }

    /**
     * Returns the text of this single action, which is the family name.
     *
     * @return the same text as {@link #getFamilyName()}, there being one form of this fix
     */
    @Override
    @NotNull
    public String getText() {
        return getFamilyName();
    }

    /**
     * Returns the name this fix is grouped and looked up under.
     *
     * @return {@code "Declare the contributed method static"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Declare the contributed method static";
    }

    /**
     * Declares the anchored method {@code static}.
     *
     * <p>Does nothing when the anchor is neither a method nor the child of one, which is the state a file edited
     * since the report was made can be in.
     *
     * @param project      the project the file belongs to
     * @param file         the file the anchor lives in
     * @param startElement the anchor the fix was created with, or the method it named
     * @param endElement   the end of the anchored range, unused
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        final PsiElement owner = startElement instanceof PsiMethod
                ? startElement
                : startElement.getParent();
        if (!(owner instanceof final PsiMethod method)) {
            return;
        }
        final PsiModifierList modifiers = method.getModifierList();
        modifiers.setModifierProperty(PsiModifier.STATIC, true);
    }
}
