package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.StringJoiner;

/**
 * Writes the parameter types of a {@code method = "..."} selector out in full.
 *
 * <p>{@code charge(Money)} becomes {@code charge(fixture.Money)}, taking the types from the one
 * method the selector currently resolves to among the weave's targets. A simple name in a selector
 * is matched by simple name, so it can be read by more than one class; the qualified spelling
 * names one.
 *
 * <p>The rewrite is the target method's own name followed by its parameter types, and nothing
 * else. An owner or a return type written in the original text is not carried over, and neither is
 * a type argument: the types come from {@code HandlerSignature.erasedNameOf}, so
 * {@code charge(List<Money>)} is rewritten as {@code charge(java.util.List)} and a varargs
 * parameter as an array. The result is written only when it still resolves to the same method.
 *
 * <p>A selector that names no parameter list is left alone. {@code charge} names every overload
 * deliberately, and turning it into a signature is a change of meaning rather than of spelling.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class QualifySelectorIntention extends PsiElementBaseIntentionAction {

    /** Creates the intention, which holds no state between invocations. */
    public QualifySelectorIntention() {
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
        return "Qualify the selector's parameter types";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Qualify selector";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * <p>The rewrite is computed rather than predicted: the entry appears only when the text that
     * {@link #invoke(Project, Editor, PsiElement)} would write has already been produced, which
     * means the target resolved and the result differs from what is written.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when the caret is on a selector that can be qualified
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return qualifiedFrom(SelectorLiterals.at(element)) != null;
    }

    /**
     * Replaces the literal's text with the qualified selector.
     *
     * <p>The rewrite is computed again rather than carried over from
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
        final PsiLiteralExpression literal = SelectorLiterals.at(element);
        final String qualified = qualifiedFrom(literal);
        if (literal == null || qualified == null) {
            return;
        }
        ElementManipulators.handleContentChange(literal,
                ElementManipulators.getValueTextRange(literal), qualified);
    }

    /**
     * Builds the qualified spelling of the literal's selector.
     *
     * <p>Four things have to hold: the literal holds a non-blank string, it sits in a class
     * annotated {@code @Weave}, the selector names a parameter list, and
     * {@link SelectorTargets#exact(PsiClass, String)} finds exactly one method for it. The
     * parameter types are then taken from that method, and the result is resolved once more and
     * returned only when it names the same method again.
     *
     * @param literal the selector literal, or {@code null}
     * @return the qualified selector, or {@code null} when any of that fails, when a parameter's
     *         type does not resolve, or when the result equals what is already written
     */
    @Nullable
    private static String qualifiedFrom(@Nullable final PsiLiteralExpression literal) {
        if (literal == null || !(literal.getValue() instanceof final String text) || text.isBlank()) {
            return null;
        }
        final PsiClass weave = WeaveDeclarations.enclosingWeave(literal);
        if (weave == null || !namesItsParameters(text)) {
            return null;
        }
        final PsiMethod target = SelectorTargets.exact(weave, text);
        if (target == null) {
            return null;
        }

        final StringJoiner parameters = new StringJoiner(", ", "(", ")");
        for (final PsiParameter parameter : target.getParameterList().getParameters()) {
            final String type = HandlerSignature.erasedNameOf(parameter.getType());
            if (type == null) {
                return null;
            }
            parameters.add(type);
        }
        final String qualified = target.getName() + parameters;
        if (qualified.equals(text)) {
            return null;
        }
        // The result has to name the same method. Anything else is one working selector quietly
        // replaced by another, which the author accepted and will not re-read.
        return target.equals(SelectorTargets.exact(weave, qualified)) ? qualified : null;
    }

    /**
     * Reports whether the text is a method selector that writes a parameter list.
     *
     * @param text the selector text
     * @return {@code true} when the text parses as a {@link MethodSelector} whose parameter list is
     *         present, and {@code false} for a bare name, a field selector, a constant selector and
     *         a text that does not parse
     */
    private static boolean namesItsParameters(@NotNull final String text) {
        try {
            return MemberSelector.parse(text) instanceof final MethodSelector selector
                    && selector.parameters().isPresent();
        } catch (final RuntimeException malformed) {
            return false;
        }
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
