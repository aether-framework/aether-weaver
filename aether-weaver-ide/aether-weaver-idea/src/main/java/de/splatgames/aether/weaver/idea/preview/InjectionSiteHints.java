package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Works out the end-of-line hints that say which lines of the target an injection lands on.
 *
 * <p>Read from the weave's side. Where {@code WeaveBlocks} draws a handler's code into the file
 * being woven, this answers the opposite question in one line of text: which lines of the target an
 * injection such as
 * {@code @Inject(method = "charge()", at = @At(value = Point.INVOKE, target = "#audit"))} reaches.
 * {@link InjectionSiteHintPass} places the answer at the end of the line the annotation starts on,
 * and {@code InjectionSiteHintRenderer} draws it.
 *
 * <p>The lines named in the text are one-based lines of the target's own file, which need not be
 * the file the hint is drawn in. {@link Hint#line()} is a zero-based line of the file that was
 * scanned. The two numbering schemes belong to different documents and are never compared.
 *
 * <h2>What produces no hint</h2>
 *
 * <p>Every failure is silent, and they are not distinguishable in the result. An annotation is
 * skipped when it is not one of the three this class knows, when it has no {@code method} attribute
 * holding a string literal, when {@code WeaveBlocks.targetLinesOf} finds no line for it — which
 * covers a selector that resolves to nothing, a selector that resolves to several methods, a point
 * that matches no position, and a point that needs a compiled target where none is available — or
 * when the annotation's own start offset lies outside the scanned document. A file
 * {@link PsiDocumentManager} has no document for yields no hints at all.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class InjectionSiteHints {

    /** The annotations a hint is offered for, by qualified name. */
    private static final Set<String> INJECTIONS = Set.of(
            "de.splatgames.aether.weaver.api.Inject",
            "de.splatgames.aether.weaver.api.Redirect",
            "de.splatgames.aether.weaver.api.Wrap");

    /** How many line numbers are spelled out before the rest are counted instead. */
    private static final int MAX_LISTED = 6;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private InjectionSiteHints() {
        throw new AssertionError("no instances");
    }

    /**
     * One line of hint text and the line it belongs to.
     *
     * <p>Carries no PSI and no document, so it stays meaningful after the pass that produced it has
     * finished. {@link InjectionSiteHintPass} compares the text of an existing inlay against
     * {@link #text()} to decide whether that inlay may be left alone.
     *
     * @param line the zero-based line of the scanned file that the annotation starts on
     * @param text the text to draw at the end of that line
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Hint(int line, @NotNull String text) {

        /**
         * Checks that the text is present.
         *
         * @throws NullPointerException if {@code text} is {@code null}
         */
        public Hint {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Returns a hint for every injection in the given file whose target sites can be located.
     *
     * <p>Every {@link PsiAnnotation} in the file is visited, in the order the tree yields them, and the
     * hints come back in that order. Nothing is merged: two injections that start on one line produce
     * two hints carrying that same line.
     *
     * @param file the file to scan; must not be {@code null}
     * @return the hints, in tree order; empty when the file has no document or nothing in it resolves
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @NotNull
    @Unmodifiable
    public static List<Hint> of(@NotNull final PsiFile file) {
        Objects.requireNonNull(file, "file");
        final Document document =
                PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) {
            return List.of();
        }

        final List<Hint> hints = new ArrayList<>();
        for (final PsiAnnotation annotation
                : PsiTreeUtil.findChildrenOfType(file, PsiAnnotation.class)) {
            if (!INJECTIONS.contains(annotation.getQualifiedName())) {
                // A statement of intent rather than the thing that makes the answer right.
                // Anything without an @At inside it resolves to nothing anyway, so removing this
                // check changes no output — which a counter-probe duly showed. It stays because
                // walking every annotation in the file to discover that is work, and because a
                // reader should not have to derive "this is about injections" from what fails
                // three calls later.
                continue;
            }
            final PsiLiteralExpression selector = selectorOf(annotation);
            if (selector == null) {
                continue;
            }
            final List<Integer> lines = WeaveBlocks.targetLinesOf(selector);
            if (lines.isEmpty()) {
                continue;
            }
            final int at = annotation.getTextRange().getStartOffset();
            if (at >= 0 && at <= document.getTextLength()) {
                hints.add(new Hint(document.getLineNumber(at), describe(lines)));
            }
        }
        return List.copyOf(hints);
    }

    /**
     * Returns the annotation's {@code method} attribute, when it is written as a string literal.
     *
     * <p>Found by name rather than by position. An {@code @Inject} may declare {@code id} before
     * {@code method}, and both are strings, so the first literal in the annotation is not necessarily
     * the selector.
     *
     * @param annotation the annotation to read; must not be {@code null}
     * @return the literal holding the selector, or {@code null} when the attribute is absent or is not
     *         a string literal
     */
    @Contract(pure = true)
    @Nullable
    private static PsiLiteralExpression selectorOf(@NotNull final PsiAnnotation annotation) {
        for (final PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
            if ("method".equals(pair.getAttributeName())
                    && pair.getValue() instanceof final PsiLiteralExpression literal
                    && literal.getValue() instanceof String) {
                return literal;
            }
        }
        return null;
    }

    /**
     * Renders the located lines as the text of a hint.
     *
     * <p>A single line reads {@code → line 5}. Several read {@code → 3 sites: 5, 6, 7}: the count
     * first, then up to {@link #MAX_LISTED} of the lines. Beyond that the remainder is counted rather
     * than listed, so nine lines starting at 5 read {@code → 9 sites: 5, 6, 7, 8, 9, 10, … +3}.
     *
     * @param lines the one-based target lines, in the order they are to be listed; must not be
     *              {@code null}
     * @return the hint text
     */
    @Contract(pure = true)
    @NotNull
    private static String describe(@NotNull final List<Integer> lines) {
        if (lines.size() == 1) {
            return "→ line " + lines.getFirst();
        }
        final StringBuilder text = new StringBuilder("→ ")
                .append(lines.size()).append(" sites: ");
        for (int index = 0; index < Math.min(lines.size(), MAX_LISTED); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(lines.get(index));
        }
        if (lines.size() > MAX_LISTED) {
            text.append(", … +").append(lines.size() - MAX_LISTED);
        }
        return text.toString();
    }
}
