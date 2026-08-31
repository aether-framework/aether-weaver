package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import org.jetbrains.annotations.NotNull;

/**
 * Adds {@code final} to an extension holder.
 *
 * <p>Offered by {@link ExtensionDeclarationInspection} beside {@code AW1300}, which it reports on a class annotated
 * {@code @Extension} that does not declare {@code final}. Adding the keyword is the whole remedy: no member is
 * touched and nothing is moved.
 *
 * <p>The inspection anchors its report on the class's name identifier rather than on the class, so this fix accepts
 * either and finds the class from the one it is given.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MakeExtensionFinalFix extends LocalQuickFixOnPsiElement {

    /**
     * Anchors the fix to a class or to the identifier naming it.
     *
     * @param holder the element the report was registered on; either the class itself or a child of it whose parent
     *               is the class, which is what the identifier the inspection uses as its anchor is
     */
    MakeExtensionFinalFix(@NotNull final PsiElement holder) {
        super(holder);
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
     * @return {@code "Declare the extension class final"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Declare the extension class final";
    }

    /**
     * Declares the anchored class {@code final}.
     *
     * <p>Does nothing when the anchor is neither a class nor the child of one — the file may have been edited since
     * the report was made — and nothing when the class has no modifier list to write into.
     *
     * @param project      the project the file belongs to
     * @param file         the file the anchor lives in
     * @param startElement the anchor the fix was created with, or the class it named
     * @param endElement   the end of the anchored range, unused
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        final PsiElement owner = startElement instanceof PsiClass
                ? startElement
                : startElement.getParent();
        if (!(owner instanceof final PsiClass holder)) {
            return;
        }
        final PsiModifierList modifiers = holder.getModifierList();
        if (modifiers != null) {
            modifiers.setModifierProperty(PsiModifier.FINAL, true);
        }
    }
}
