package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import org.jetbrains.annotations.NotNull;

/**
 * Replaces a selector literal with the corrected spelling the selector parser handed back.
 *
 * <p>The suggestion is never invented here. It arrives on
 * {@link de.splatgames.aether.weaver.api.select.SelectorSyntaxException#suggestion()}, and the parser attaches one
 * to a single diagnostic: {@code AW1017}, reported when a text that failed the source grammar reads as a JVM
 * descriptor. The suggestion is not the reported text with a prefix added — it is that text trimmed, with any
 * leading {@code src:} removed, and {@code desc:} put in front instead, so {@code "src:(I)V"} is reported as
 * written but suggested as {@code desc:(I)V}. Every other selector failure arrives with an empty suggestion and is
 * offered no fix.
 *
 * <p>{@link SelectorInspection} and {@link PointTargetInspection} both build this fix, each from the exception their
 * own call to {@link MemberSelector} threw.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ApplySelectorSuggestionFix extends LocalQuickFixOnPsiElement {

    /** The corrected selector to write, already checked to parse. */
    private final String suggestion;

    /**
     * Anchors the fix to the literal it will rewrite.
     *
     * @param literal    the string literal holding the selector
     * @param suggestion the text to put in its place
     */
    private ApplySelectorSuggestionFix(@NotNull final PsiLiteralExpression literal,
                                       @NotNull final String suggestion) {
        super(literal);
        this.suggestion = suggestion;
    }

    /**
     * Builds the fix for a suggestion, or reports that there is nothing worth offering.
     *
     * <p>The suggestion is parsed before it is accepted. A suggestion is not guaranteed to be a valid selector — the
     * descriptor form the {@code AW1017} suggestion prepends {@code desc:} to refuses constructs the source form
     * allows, so a text that failed as source can fail again as a descriptor — and a fix that trades one diagnostic
     * for another is worse than no fix at all.
     *
     * @param literal    the string literal the fix would rewrite
     * @param suggestion the corrected spelling the parser offered, or {@code null} when it offered none
     * @return the fix, or {@code null} when the suggestion is absent, blank, or does not itself parse
     */
    static ApplySelectorSuggestionFix of(@NotNull final PsiLiteralExpression literal,
                                         final String suggestion) {
        if (suggestion == null || suggestion.isBlank()) {
            return null;
        }
        try {
            MemberSelector.parse(suggestion);
        } catch (final RuntimeException stillWrong) {
            return null;
        }
        return new ApplySelectorSuggestionFix(literal, suggestion);
    }

    /**
     * Returns the text of this action, naming the selector it would write.
     *
     * @return {@code Change selector to '...'}, quoting the suggestion
     */
    @Override
    @NotNull
    public String getText() {
        return "Change selector to '" + this.suggestion + '\'';
    }

    /**
     * Returns the name this fix is grouped and looked up under.
     *
     * @return {@code "Apply the selector parser's suggestion"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Apply the selector parser's suggestion";
    }

    /**
     * Writes the suggestion into the literal.
     *
     * <p>Only the literal's value is replaced, so the quotes and any escaping the manipulator applies are the
     * platform's business rather than this fix's. Does nothing when the anchor is no longer a string literal.
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
        ElementManipulators.handleContentChange(literal,
                ElementManipulators.getValueTextRange(literal), this.suggestion);
    }
}
