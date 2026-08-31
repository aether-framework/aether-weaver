package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.util.Objects;

/**
 * Draws one injection-site hint at the end of a line.
 *
 * <p>A single string in the scheme's italic editor font, coloured like an inline parameter hint and
 * preceded by a fixed gap. The width reported is that string's width in that font plus the gap.
 *
 * <p>The renderer is also the identity of the hint. {@link InjectionSiteHintPass} looks for an
 * existing inlay whose renderer is one of these and compares {@link #text()} against the text it
 * has just computed; equal text means the inlay is left alone.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class InjectionSiteHintRenderer implements EditorCustomElementRenderer {

    /** Pixels of clear space between the end of the line's code and the first character of the hint. */
    private static final int LEADING_GAP = 8;

    /** The text drawn, exactly as {@link InjectionSiteHints} rendered it. */
    private final String text;

    /**
     * Creates a renderer for one hint.
     *
     * @param text the text to draw; must not be {@code null}
     * @throws NullPointerException if {@code text} is {@code null}
     */
    InjectionSiteHintRenderer(@NotNull final String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /**
     * Returns the text this renderer draws.
     *
     * @return the hint text
     */
    @Contract(pure = true)
    @NotNull
    String text() {
        return this.text;
    }

    /**
     * Returns the width the hint needs.
     *
     * <p>Measured in the same italic font {@link #paint} draws with, taken from the editor the inlay
     * belongs to, so a change of editor font or scheme is reflected the next time this is asked.
     *
     * @param inlay the inlay being measured; must not be {@code null}
     * @return the text's width in that font, plus {@link #LEADING_GAP}
     */
    @Override
    public int calcWidthInPixels(@NotNull final Inlay inlay) {
        return metrics(inlay.getEditor()).stringWidth(this.text) + LEADING_GAP;
    }

    /**
     * Draws the hint into the given region.
     *
     * <p>Text antialiasing is switched on, the font is the scheme's italic editor font and the
     * colour is the scheme's inline parameter hint foreground. The baseline is computed from
     * {@code region} and not from the editor's line height: the font's own height is centred inside
     * the region's height, and the ascent is added to it.
     *
     * @param inlay      the inlay being painted; must not be {@code null}
     * @param graphics   the graphics to draw into; must not be {@code null}
     * @param region     the area allotted to this inlay; must not be {@code null}
     * @param attributes the attributes offered for the inlay, which this renderer does not use; must
     *                   not be {@code null}
     */
    @Override
    public void paint(@NotNull final Inlay inlay,
                      @NotNull final Graphics2D graphics,
                      @NotNull final Rectangle2D region,
                      @NotNull final TextAttributes attributes) {
        final Editor editor = inlay.getEditor();
        final EditorColorsScheme scheme = editor.getColorsScheme();

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font(scheme));
        graphics.setColor(colour(scheme));

        final FontMetrics metrics = metrics(editor);
        // Baseline from the region rather than from the line height: an inlay after a line end is
        // given the line's own box, and the platform may have made it taller than the font.
        final int baseline = (int) (region.getY()
                + (region.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        graphics.drawString(this.text, (int) region.getX() + LEADING_GAP, baseline);
    }

    /**
     * Returns the colour to draw the hint in.
     *
     * @param scheme the scheme in force; must not be {@code null}
     * @return the foreground of {@link DefaultLanguageHighlighterColors#INLINE_PARAMETER_HINT}, or the
     *         scheme's default foreground when the scheme defines no such attributes or leaves their
     *         foreground unset
     */
    @Contract(pure = true)
    @NotNull
    private static Color colour(@NotNull final EditorColorsScheme scheme) {
        final TextAttributes hint =
                scheme.getAttributes(DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT);
        return hint != null && hint.getForegroundColor() != null
                ? hint.getForegroundColor()
                // Every scheme defines that key, and a scheme that did not would still have to
                // produce something readable rather than nothing.
                : scheme.getDefaultForeground();
    }

    /**
     * Returns the font the hint is measured and drawn in.
     *
     * <p>One place, called by both {@link #calcWidthInPixels} and {@link #paint}, so the width reported
     * and the width drawn cannot disagree.
     *
     * @param scheme the scheme in force; must not be {@code null}
     * @return the scheme's {@link EditorFontType#ITALIC} font
     */
    @Contract(pure = true)
    @NotNull
    private static Font font(@NotNull final EditorColorsScheme scheme) {
        return scheme.getFont(EditorFontType.ITALIC);
    }

    /**
     * Returns the metrics of {@link #font} for the given editor.
     *
     * @param editor the editor the inlay belongs to; must not be {@code null}
     * @return the metrics of the editor's content component for the italic editor font
     */
    @Contract(pure = true)
    @NotNull
    private static FontMetrics metrics(@NotNull final Editor editor) {
        return editor.getContentComponent()
                .getFontMetrics(font(editor.getColorsScheme()));
    }
}
