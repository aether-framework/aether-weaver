package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;

/**
 * Rewrites the member name inside a selector literal, leaving the rest of the selector alone.
 *
 * <p>Offered by {@link SelectorInspection} beside {@code AW1020}, in the case where no target declares or inherits
 * a method of the name written. The inspection picks the nearest name it can find on the targets by edit distance
 * and offers it here; a name further away than its budget is not offered at all, so this fix exists only where a
 * plausible correction was found.
 *
 * <p>Only the span the name occupies is replaced. An owner, a parameter list and a descriptor written around the
 * name survive the fix, which is why the replacement is described by an offset and a length rather than by a whole
 * new literal.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ChangeSelectorNameFix extends LocalQuickFixOnPsiElement {

    /** Where the name starts, counted from the start of the literal element and so including its opening quote. */
    private final int nameStart;

    /** How many characters the name occupies. */
    private final int nameLength;

    /** The name to put in its place. */
    private final String replacement;

    /**
     * Anchors the fix to a literal and records the span to overwrite.
     *
     * @param literal     the string literal holding the selector
     * @param nameStart   the offset of the name within the literal element, quote included
     * @param nameLength  the length of the name being replaced
     * @param replacement the name to write instead
     */
    ChangeSelectorNameFix(@NotNull final PsiLiteralExpression literal,
                          final int nameStart,
                          final int nameLength,
                          @NotNull final String replacement) {
        super(literal);
        this.nameStart = nameStart;
        this.nameLength = nameLength;
        this.replacement = replacement;
    }

    /**
     * Returns the text of this action, naming the member it would select.
     *
     * @return {@code Change selector to '...'}, quoting the replacement name
     */
    @Override
    @NotNull
    public String getText() {
        return "Change selector to '" + this.replacement + "'";
    }

    /**
     * Returns the name this fix is grouped and looked up under.
     *
     * @return {@code "Change selector to an existing member"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Change selector to an existing member";
    }

    /**
     * Replaces the recorded span with the replacement name.
     *
     * <p>Does nothing when the anchor is no longer a string literal, and nothing when the recorded span no longer
     * fits inside it: the literal can be edited between the report and the invocation, and the inspection will offer
     * the fix again against whatever is there now.
     *
     * @param project      the project the file belongs to
     * @param file         the file the literal lives in
     * @param startElement the literal the fix was created with
     * @param endElement   the end of the anchored range, unused
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        if (!(startElement instanceof final PsiLiteralExpression literal)) {
            return;
        }
        if (this.nameStart < 0
                || this.nameStart + this.nameLength > literal.getTextLength()) {
            // The literal changed under the fix. Doing nothing is the only safe answer; the
            // inspection will offer the fix again against whatever is there now.
            return;
        }
        // The range is element-relative, and the content replaces only that range. Handing the
        // manipulator the literal's file range instead — which is what `getTextRange()` returns —
        // asks it to overwrite a span far longer than the literal, and it says so with an
        // out-of-bounds rather than quietly corrupting the file.
        ElementManipulators.handleContentChange(literal,
                TextRange.from(this.nameStart, this.nameLength), this.replacement);
    }
}
