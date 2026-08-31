package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import org.jetbrains.annotations.NotNull;

/**
 * Declares a handler {@code public}, and the class declaring it {@code public} with it.
 *
 * <p>Offered by {@code WeaveMemberInspection} beside {@code AW1042}, which it reports when the call
 * a static weave injects into a target could not reach the handler. Both modifiers are widened
 * because either one alone leaves the call unreachable: a {@code public} method of a
 * package-private class is no more visible from another package than a package-private method is.
 *
 * <p>Widening is all this does, and it is applied to both declarations whatever made the call
 * unreachable. A {@code private} handler is reported even where the weave and the target share a
 * package, and there dropping {@code private} is by itself enough; the class is widened along with
 * it regardless.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MakeHandlerReachableFix extends LocalQuickFixOnPsiElement {

    /**
     * Binds the fix to the handler it will widen.
     *
     * @param handler the handler the injected call could not reach; must not be {@code null}
     */
    MakeHandlerReachableFix(@NotNull final PsiMethod handler) {
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
     * @return {@code "Make the handler and its weave public"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Make the handler and its weave public";
    }

    /**
     * Widens the handler and its declaring class to {@code public}.
     *
     * <p>The handler is {@code startElement} when that is a method, and {@code startElement}'s
     * parent otherwise. Only the class that directly declares the handler is widened, so a handler
     * in a nested weave leaves the class enclosing that weave as written.
     *
     * @param project      the project the file belongs to; must not be {@code null}
     * @param file         the file being modified; must not be {@code null}
     * @param startElement the element the fix was created for; must not be {@code null}
     * @param endElement   the end of the range the fix was created for; must not be {@code null}
     * @throws ClassCastException if {@code startElement} is not a method and has a parent that is not
     *                            a method either
     * @throws NullPointerException if {@code startElement} is not a method and has no parent
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        final PsiMethod handler = startElement instanceof final PsiMethod method
                ? method
                : (PsiMethod) startElement.getParent();
        final PsiClass weave = handler.getContainingClass();
        widen(handler.getModifierList());
        if (weave != null) {
            widen(weave.getModifierList());
        }
    }

    /**
     * Adds {@code public} to a modifier list that does not already carry it.
     *
     * <p>A {@code null} list and a declaration that is already {@code public} are both left alone,
     * so {@link #invoke(Project, PsiFile, PsiElement, PsiElement)} can call this for the handler and
     * the class without testing either first.
     *
     * @param modifiers the modifier list to widen, or {@code null} for a declaration that has none
     */
    private static void widen(final PsiModifierList modifiers) {
        if (modifiers != null && !modifiers.hasModifierProperty(PsiModifier.PUBLIC)) {
            modifiers.setModifierProperty(PsiModifier.PUBLIC, true);
        }
    }
}
