package de.splatgames.aether.weaver.idea.intention;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiNameValuePair;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Recognises the string literal a selector is written in.
 *
 * <p>Shared by the intentions that rewrite a selector, so that all of them are offered on exactly
 * the same literals. Three conditions have to hold together: the literal is the value of an
 * attribute named {@value WeaveDeclarations#METHOD_ATTRIBUTE}, that attribute belongs to an
 * annotation, and the enclosing class carries {@code @Weave}. The annotation itself is not
 * examined, so {@code @Inject}, {@code @Redirect} and any other declaration spelling its target as
 * {@code method = "..."} are all recognised, while a selector-shaped string elsewhere in the same
 * class is not.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class SelectorLiterals {

    /**
     * Prevents instantiation.
     *
     * @throws AssertionError always
     */
    private SelectorLiterals() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the selector literal at the given element.
     *
     * <p>The element itself is taken when it is already a literal, and otherwise its parent is,
     * which is the token the caret sits on inside the quotes. Only that one step is taken: an
     * element deeper inside the annotation, or the attribute name, names no literal here.
     *
     * @param element the element under the caret
     * @return the literal, or {@code null} when it is not the value of a
     *         {@value WeaveDeclarations#METHOD_ATTRIBUTE} attribute of an annotation inside a class
     *         annotated {@code @Weave}
     */
    @Nullable
    static PsiLiteralExpression at(@NotNull final PsiElement element) {
        final PsiElement literal = element instanceof PsiLiteralExpression
                ? element
                : element.getParent();
        if (!(literal instanceof final PsiLiteralExpression selector)
                || !(selector.getParent() instanceof final PsiNameValuePair pair)
                || !WeaveDeclarations.METHOD_ATTRIBUTE.equals(pair.getName())) {
            return null;
        }
        return WeaveDeclarations.enclosingWeave(selector) == null ? null : selector;
    }
}
