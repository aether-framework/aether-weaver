package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Annotates a contributed method's first parameter with
 * {@link de.splatgames.aether.weaver.api.experimental.Receiver}.
 *
 * <p>Offered by {@code ExtensionDeclarationInspection} beside {@code AW1302}, which it reports for
 * a contributed method that names no receiver at all. The fix is offered only where the method has
 * a first parameter and that parameter's type is a class type; a method taking nothing, or taking a
 * primitive or an array first, is reported with no fix.
 *
 * <p>The annotation is added under its fully qualified name and then shortened, which imports it
 * where the file has no {@code Receiver} in scope and leaves the reference qualified where a
 * different {@code Receiver} is already imported.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MarkReceiverParameterFix extends LocalQuickFixOnPsiElement {

    /**
     * Binds the fix to the method whose first parameter will be annotated.
     *
     * <p>Takes a {@link PsiElement} rather than a {@link PsiMethod} because
     * {@link #invoke(Project, PsiFile, PsiElement, PsiElement)} accepts
     * either the method or a child of it.
     *
     * @param method the contributed method, or a child of it; must not be {@code null}
     */
    MarkReceiverParameterFix(@NotNull final PsiElement method) {
        super(method);
    }

    /**
     * Returns the text shown on the individual intention entry.
     *
     * <p>The same text as {@link #getFamilyName()}: the action does not vary with the method, so a
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
     * @return {@code "Mark the first parameter @Receiver"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Mark the first parameter @Receiver";
    }

    /**
     * Adds the annotation to the first parameter.
     *
     * <p>The method is {@code startElement} when that is a method and its parent otherwise. Nothing
     * happens when neither is a method, when the method takes no parameters, when the first
     * parameter has no modifier list, or when that parameter already carries a
     * {@link de.splatgames.aether.weaver.api.experimental.Receiver}.
     *
     * @param project      the project the file belongs to, and the one whose code style decides how
     *                     the added reference is shortened; must not be {@code null}
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
        if (!(owner instanceof final PsiMethod method)) {
            return;
        }
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0) {
            return;
        }
        final PsiModifierList modifiers = parameters[0].getModifierList();
        if (modifiers == null
                || modifiers.findAnnotation(ExtensionDeclarations.RECEIVER) != null) {
            return;
        }

        final PsiAnnotation added = modifiers.addAnnotation(ExtensionDeclarations.RECEIVER);
        // Written qualified and then shortened, which adds the import when the file has none and
        // leaves the reference qualified when a different Receiver is already imported. Writing the
        // simple name directly would bind to whichever one that is.
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
    }
}
