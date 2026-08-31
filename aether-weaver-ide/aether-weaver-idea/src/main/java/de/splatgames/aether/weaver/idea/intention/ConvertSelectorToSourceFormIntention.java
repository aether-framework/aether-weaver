package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rewrites a {@code method = "..."} selector from the descriptor form into the source form.
 *
 * <p>{@code desc:charge(Ljava/math/BigDecimal;)V} becomes
 * {@code charge(java.math.BigDecimal):void}. Only a selector that was parsed from the descriptor
 * form is offered, so the entry never appears where it would rewrite the text to itself.
 *
 * <p>Nothing is resolved: the selector is parsed, re-rendered and checked against itself, and no
 * target class is consulted. The literal still has to be a selector literal, which
 * {@code SelectorLiterals} decides, so an ordinary string inside a weave is not touched and a
 * {@code method} attribute outside a weave is not either.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConvertSelectorToSourceFormIntention extends PsiElementBaseIntentionAction {

    /** Creates the intention, which holds no state between invocations. */
    public ConvertSelectorToSourceFormIntention() {
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
        return "Convert selector to source form";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Convert selector form";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * <p>The conversion is computed rather than predicted: the entry appears only when the text
     * that {@link #invoke(Project, Editor, PsiElement)} would write has already been produced.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when the caret is on a selector that converts
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return convertedFrom(SelectorLiterals.at(element)) != null;
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
        final PsiLiteralExpression literal = SelectorLiterals.at(element);
        final String converted = convertedFrom(literal);
        if (literal == null || converted == null) {
            return;
        }
        ElementManipulators.handleContentChange(literal,
                ElementManipulators.getValueTextRange(literal), converted);
    }

    /**
     * Renders the literal's selector in the source form.
     *
     * <p>{@link MemberSelector#render(MemberSelector.Form)} is documented to always answer for
     * {@link MemberSelector.Form#SOURCE}, but not to always reproduce the selector it started
     * from: a member named after one of the seven unsafe keywords and written without an owner
     * renders differently from how it was written. The rendered text is therefore parsed again
     * and re-rendered, and it is returned only when the two agree. Every {@link RuntimeException}
     * from parsing is caught, which covers a half-written selector as well as one whose shape does
     * not survive the trip.
     *
     * @param literal the selector literal, or {@code null}
     * @return the source form, or {@code null} when the literal holds no non-blank string, was not
     *         written in the descriptor form, does not parse, or does not survive being read back
     */
    @Nullable
    private static String convertedFrom(@Nullable final PsiLiteralExpression literal) {
        if (literal == null || !(literal.getValue() instanceof final String text) || text.isBlank()) {
            return null;
        }
        try {
            final MemberSelector parsed = MemberSelector.parse(text);
            if (parsed.form() != MemberSelector.Form.DESCRIPTOR) {
                return null;
            }
            final String source = parsed.render(MemberSelector.Form.SOURCE);
            // The conversion has to survive being read back. Rendering is documented to produce
            // "the best available approximation rather than failing", and an approximation written
            // into somebody's source is a working selector replaced by a plausible one.
            return source.equals(MemberSelector.parse(source).render(MemberSelector.Form.SOURCE))
                    ? source
                    : null;
        } catch (final RuntimeException unusable) {
            // Malformed, or a shape that does not survive the trip. Offering nothing is the answer.
            return null;
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
