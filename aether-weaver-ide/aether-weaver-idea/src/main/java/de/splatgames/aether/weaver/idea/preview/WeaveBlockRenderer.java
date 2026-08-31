package de.splatgames.aether.weaver.idea.preview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.swing.Icon;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Draws one {@link WeaveBlock} above the line it belongs to, and owns the gutter control that folds
 * it.
 *
 * <p>Each section is a band: the whole width of the editor filled with a tint of the scheme's diff
 * colour for the section's {@link WeaveBlock.Kind}, a bar of that colour undiluted down the left
 * edge, the section header in italics, and then the handler's code drawn fragment by fragment in
 * the colours the Java highlighter chose. The bands stack downwards in the order of
 * {@link WeaveBlock#sections()}.
 *
 * <h2>Why the colour is a tint</h2>
 *
 * <p>The fill is {@code TINT} of the editor's own background blended with the diff colour, not the
 * diff colour itself. Syntax colours are drawn on top of it and are chosen against the editor's
 * background; over an undiluted diff fill they stop being legible. The bar keeps the full colour,
 * so the section is still identifiable at a glance.
 *
 * <h2>Folding</h2>
 *
 * <p>A block folds when {@link WeaveInlaySettings} reports the feature collapsed or when
 * {@link WeaveCollapsedBlocks} holds this block's {@link WeaveBlock#id()}. Folded, the block is
 * measured at the taller of the chevron and half a line, and paints the first section's bar and
 * tint across that strip and nothing else — no header, no code, and no band for the remaining
 * sections. The chevron that unfolds it is the gutter control from
 * {@link #calcGutterIconRenderer}.
 *
 * <p>Folding is read afresh every time the block is measured, painted or asked for its gutter
 * control; the same renderer answers differently once either source changes.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveBlockRenderer implements EditorCustomElementRenderer {

    /** Width in pixels of the undiluted colour bar down the left edge of every band. */
    private static final int BAR_WIDTH = 3;

    /** Pixels of clear space kept to the right of the text, and the minimum gap left of it. */
    private static final int TEXT_GAP = 8;

    /** The gutter icon shown while the block is folded, and the height a folded strip must reach. */
    private static final Icon COLLAPSED_ICON = AllIcons.General.ChevronRight;

    /** The share of the editor's background in a band's fill; the rest is the diff colour. */
    private static final float TINT = 0.78f;

    /** The band colour used when the scheme names no colour for the section's key. */
    private static final JBColor FALLBACK =
            new JBColor(new Color(0xE5, 0xF5, 0xE5), new Color(0x2A, 0x3A, 0x2A));

    /** The block this renderer draws; also its identity for {@link #equals(Object)}. */
    private final WeaveBlock block;

    /**
     * Creates a renderer for one block.
     *
     * @param block the block to draw; must not be {@code null}
     */
    public WeaveBlockRenderer(@NotNull final WeaveBlock block) {
        this.block = block;
    }

    /**
     * Returns the block this renderer draws.
     *
     * <p>{@link WeaveInlayPass} reads it back off an existing inlay to decide whether that inlay still
     * describes what a new pass found.
     *
     * @return the block
     */
    @NotNull
    public WeaveBlock block() {
        return this.block;
    }

    /**
     * Returns the width the block asks for.
     *
     * <p>The greater of what the text needs and what {@code editorWidth} reports. The text figure
     * is the widest section header or code line in the editor's plain font, plus the block's own
     * indent and {@code TEXT_GAP}.
     *
     * @param inlay the inlay being measured; must not be {@code null}
     * @return the width in pixels, never narrower than the editor
     */
    @Override
    public int calcWidthInPixels(@NotNull final Inlay inlay) {
        final Editor editor = inlay.getEditor();
        final FontMetrics metrics = metrics(editor);
        int widest = 0;
        for (final WeaveBlock.Section section : this.block.sections()) {
            widest = Math.max(widest, metrics.stringWidth(section.header()));
            for (final List<WeaveBlock.Fragment> line : section.lines()) {
                widest = Math.max(widest, metrics.stringWidth(textOf(line)));
            }
        }
        final int text = widest + textIndentOf(inlay) + TEXT_GAP;

        // This figure is the area the platform hands the renderer, so a text-shaped width paints
        // a text-shaped patch — the block stops mid-line and reads as a tooltip rather than as part
        // of the method. Several sources are tried because the obvious one is not necessarily laid
        // out yet when the inlay is created, which is exactly how a first attempt failed: the blocks
        // came out at assorted widths, none of them the editor's.
        return Math.max(text, editorWidth(editor));
    }

    /**
     * Returns a width to fill across.
     *
     * <p>The largest of the content component's width, the editor component's width and the width
     * of the visible area. Three sources rather than one, so that a source which is not yet able to
     * answer cannot narrow the result below what the others report.
     *
     * @param editor the editor being drawn in; must not be {@code null}
     * @return the widest of the three, in pixels
     */
    private static int editorWidth(@NotNull final Editor editor) {
        int width = editor.getContentComponent().getWidth();
        width = Math.max(width, editor.getComponent().getWidth());
        final java.awt.Rectangle visible = editor.getScrollingModel().getVisibleArea();
        return Math.max(width, visible.width);
    }

    /**
     * Returns the height the block asks for.
     *
     * <p>Folded, the greater of the chevron's height and half a line, which keeps the strip tall enough
     * for the gutter control that unfolds it. Unfolded, {@link WeaveBlock#height()} lines of the
     * editor.
     *
     * @param inlay the inlay being measured; must not be {@code null}
     * @return the height in pixels
     */
    @Override
    public int calcHeightInPixels(@NotNull final Inlay inlay) {
        final int lineHeight = inlay.getEditor().getLineHeight();
        return collapsed()
                ? Math.max(COLLAPSED_ICON.getIconHeight(), lineHeight / 2)
                : this.block.height() * lineHeight;
    }

    /**
     * Reports whether this block is to be drawn folded.
     *
     * <p>Either source folds it: the feature-wide {@link WeaveInlaySettings} or this block's identity
     * in {@link WeaveCollapsedBlocks}. Read on every call, never cached.
     *
     * @return {@code true} when the block is folded
     */
    private boolean collapsed() {
        return WeaveInlaySettings.getInstance().isCollapsed()
                || WeaveCollapsedBlocks.getInstance().isCollapsed(this.block.id());
    }

    /**
     * Draws the block into the given region.
     *
     * <p>Bands are drawn top to bottom from {@code region}'s origin. Each is filled from the
     * region's left edge across the greater of {@code region}'s own width and what
     * {@code editorWidth} reports — indent included, so that no strip of ordinary background is
     * left beside a highlighted band — then overdrawn with
     * {@code BAR_WIDTH} pixels of the undiluted colour, then given its header in the italic
     * derivation of the scheme's plain font (see {@link #metrics(Editor)}), in the colour
     * {@link #accent(EditorColorsScheme, Color)} computes for the band. Code lines
     * follow one editor line apart, each fragment drawn in the font and foreground its
     * {@link WeaveBlock.Fragment#key()} resolves to; a fragment with no key, or whose key resolves
     * to no foreground, is drawn in the scheme's default foreground.
     *
     * <p>An unfolded band is as tall as its header and code lines together. A folded block instead
     * gives its first band the whole region's height, draws that band's fill and bar, and returns
     * before any text, so a folded block with several sections shows only the first section's
     * colour.
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
        final int lineHeight = editor.getLineHeight();

        final Color editorBackground = scheme.getDefaultBackground();
        final double width = Math.max(region.getWidth(), editorWidth(editor));
        final boolean folded = collapsed();

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        final Font base = scheme.getFont(EditorFontType.PLAIN);
        final int textX = (int) region.getX() + textIndentOf(inlay);

        double y = region.getY();
        for (final WeaveBlock.Section section : this.block.sections()) {
            final Color mark = colour(scheme, keyOf(section.kind()), FALLBACK);
            // A tint, not the diff colour itself. Those colours are meant to fill a whole diff
            // line where nothing competes with them; here the scheme's syntax colours are drawn on
            // top, and those are tuned for the editor's own background. Used raw they drown them —
            // the code is there and cannot be read, which is worse than not showing it.
            final Color background = blend(mark, editorBackground, TINT);
            final double height = folded
                    ? region.getHeight()
                    : (1 + section.lines().size()) * lineHeight;

            // From the very left to the very right, indent included. A fill that started at the
            // code's indentation left a strip of ordinary background beside a highlighted block,
            // which reads as a box floating over the file rather than a line belonging to it.
            graphics.setColor(background);
            graphics.fill(new Rectangle2D.Double(region.getX(), y, width, height));

            // The bar keeps the undiluted colour: one crisp edge says what happened to this line
            // without the whole block having to shout it.
            graphics.setColor(mark);
            graphics.fill(new Rectangle2D.Double(region.getX(), y, BAR_WIDTH, height));

            if (folded) {
                // The bar and the tint stay. Collapsed means "I do not need to read it right
                // now", not "hide that this method is woven" — losing the mark would take back the
                // one thing this feature exists for. The chevron that opens it again lives in the
                // gutter, where every other disclosure control in the IDE lives.
                return;
            }

            double textY = y + editor.getAscent();
            graphics.setFont(base.deriveFont(Font.ITALIC));
            graphics.setColor(accent(scheme, background));
            graphics.drawString(section.header(), textX, (int) textY);

            for (final List<WeaveBlock.Fragment> line : section.lines()) {
                textY += lineHeight;
                int x = textX;
                for (final WeaveBlock.Fragment fragment : line) {
                    final TextAttributes syntax =
                            fragment.key() == null ? null : scheme.getAttributes(fragment.key());
                    graphics.setFont(syntax == null || syntax.getFontType() == Font.PLAIN
                            ? base
                            : base.deriveFont(syntax.getFontType()));
                    graphics.setColor(syntax == null || syntax.getForegroundColor() == null
                            ? scheme.getDefaultForeground()
                            : syntax.getForegroundColor());
                    graphics.drawString(fragment.text(), x, (int) textY);
                    x += graphics.getFontMetrics().stringWidth(fragment.text());
                }
            }
            y += height;
        }
    }

    /**
     * Returns the scheme colour key a section of the given kind is drawn with.
     *
     * @param kind the section's kind; must not be {@code null}
     * @return {@link EditorColors#MODIFIED_LINES_COLOR} for {@link WeaveBlock.Kind#REDIRECT}, which
     *         replaces what was there, and {@link EditorColors#ADDED_LINES_COLOR} for
     *         {@link WeaveBlock.Kind#INJECT}, which adds to it
     */
    @NotNull
    private static com.intellij.openapi.editor.colors.ColorKey keyOf(
            @NotNull final WeaveBlock.Kind kind) {
        return kind == WeaveBlock.Kind.REDIRECT
                ? EditorColors.MODIFIED_LINES_COLOR
                : EditorColors.ADDED_LINES_COLOR;
    }

    /**
     * Returns the gutter control that folds and unfolds this block.
     *
     * <p>A fresh {@code Handle} on every call, carrying the folded state as it is at that moment.
     * That state is part of the handle's equality, so a handle made before a fold and one made
     * after it do not compare equal.
     *
     * @param inlay the inlay the control belongs to; must not be {@code null}
     * @return the control, never {@code null}
     */
    @Override
    @NotNull
    public GutterIconRenderer calcGutterIconRenderer(@NotNull final Inlay inlay) {
        return new Handle(inlay, this.block, collapsed());
    }

    /**
     * The chevron in the gutter beside a block, and the click that folds it.
     *
     * <p>Holds the inlay it was made for, so that a click can update that inlay alone rather than
     * ask for the whole file to be highlighted again. Equality is the block's identity together
     * with the folded state, so two controls for one block compare equal only while both were made
     * on the same side of a fold.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Handle extends GutterIconRenderer {

        /** The inlay this control belongs to. */
        private final Inlay<?> inlay;

        /** The block the inlay draws, for its identity. */
        private final WeaveBlock block;

        /** The folded state at the moment this control was made. */
        private final boolean collapsed;

        /**
         * Creates a control for one inlay.
         *
         * @param inlay     the inlay to update when clicked; must not be {@code null}
         * @param block     the block that inlay draws; must not be {@code null}
         * @param collapsed whether the block is folded as of now
         */
        Handle(@NotNull final Inlay<?> inlay,
               @NotNull final WeaveBlock block,
               final boolean collapsed) {
            this.inlay = inlay;
            this.block = block;
            this.collapsed = collapsed;
        }

        /**
         * Returns the chevron to draw.
         *
         * @return a right-pointing chevron while the block is folded, a downward one while it is open
         */
        @Override
        @NotNull
        public Icon getIcon() {
            return this.collapsed ? COLLAPSED_ICON : AllIcons.General.ChevronDown;
        }

        /**
         * Returns the hover text for the control.
         *
         * <p>An HTML fragment: each section's {@link WeaveBlock.Section#explanation()} on a line of
         * its own, then in italics either "Click to show it" or "Click to collapse it". The
         * explanations are shown here and not in the block, whose header carries only the handler
         * names and the point's tag.
         *
         * @return the tooltip, as an HTML fragment
         */
        @Override
        @NotNull
        public String getTooltipText() {
            final StringBuilder said = new StringBuilder("<html>");
            for (final WeaveBlock.Section section : this.block.sections()) {
                said.append(section.explanation()).append("<br>");
            }
            said.append("<i>")
                    .append(this.collapsed ? "Click to show it" : "Click to collapse it")
                    .append("</i></html>");
            return said.toString();
        }

        /**
         * Returns the action that folds or unfolds this block.
         *
         * @return an action that toggles the block's identity in {@link WeaveCollapsedBlocks} and
         *         updates this control's inlay
         */
        @Override
        @NotNull
        public AnAction getClickAction() {
            return new AnAction() {
                /**
                 * Toggles the block and refreshes the one inlay that changed.
                 *
                 * <p>The identity is toggled unconditionally; the inlay is updated only when it
                 * is still valid. Nothing else in the editor is touched.
                 *
                 * @param event the click event; must not be {@code null}
                 */
                @Override
                public void actionPerformed(@NotNull final AnActionEvent event) {
                    WeaveCollapsedBlocks.getInstance().toggle(Handle.this.block.id());
                    if (Handle.this.inlay.isValid()) {
                        // Only this inlay: its height changed and nothing else did. Restarting the
                        // daemon would recompute every block in the file to answer a question about
                        // one of them.
                        Handle.this.inlay.update();
                    }
                }
            };
        }

        /**
         * Returns where in the gutter the chevron sits.
         *
         * @return {@link GutterIconRenderer.Alignment#RIGHT}
         */
        @Override
        @NotNull
        public Alignment getAlignment() {
            return Alignment.RIGHT;
        }

        /**
         * Compares controls by block identity and folded state.
         *
         * @param other the object to compare with, possibly {@code null}
         * @return {@code true} when {@code other} is a control for a block with the same
         *         {@link WeaveBlock#id()} and in the same folded state
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof final Handle handle
                    && this.collapsed == handle.collapsed
                    && this.block.id().equals(handle.block.id());
        }

        /**
         * Returns a hash consistent with {@link #equals(Object)}.
         *
         * @return a hash over the block's identity and the folded state
         */
        @Override
        public int hashCode() {
            return this.block.id().hashCode() * 31 + Boolean.hashCode(this.collapsed);
        }
    }

    /**
     * Returns how far from the left edge the block's text starts.
     *
     * <p>The target line's own indentation, but never so little that the text would run into the
     * bar: the floor is {@link #BAR_WIDTH} plus {@link #TEXT_GAP}.
     *
     * @param inlay the inlay being drawn; must not be {@code null}
     * @return the indent in pixels
     */
    private int textIndentOf(@NotNull final Inlay inlay) {
        return Math.max(indentOf(inlay), BAR_WIDTH + TEXT_GAP);
    }

    /**
     * Returns the indentation of the line the inlay is anchored on, in pixels.
     *
     * <p>The inlay's offset chooses the line and bounds nothing else: the scan runs from that
     * line's start offset to its end offset, so it still counts indentation for an inlay anchored
     * at the start of the line. Leading whitespace is counted in columns, a tab counting for
     * {@link #tabSize(Editor)} of them, and the scan stops at the first character that is not
     * whitespace and at a newline. The column count is multiplied by the width of a space in the
     * editor's plain font.
     *
     * @param inlay the inlay being drawn; must not be {@code null}
     * @return the indent in pixels, {@code 0} for a line that starts in column zero
     */
    private int indentOf(@NotNull final Inlay inlay) {
        final Editor editor = inlay.getEditor();
        final CharSequence text = editor.getDocument().getCharsSequence();
        final int offset = Math.min(inlay.getOffset(), text.length());
        final int line = editor.getDocument().getLineNumber(offset);
        final int lineStart = editor.getDocument().getLineStartOffset(line);
        final int lineEnd = editor.getDocument().getLineEndOffset(line);

        // Bounded by the end of the line, not by the inlay's own offset. A block inlay is
        // anchored at the start of the line it sits above, so offset == lineStart — and a loop that
        // stopped there counted nothing and put every block at column zero, however deeply nested
        // the code it describes was.
        int columns = 0;
        for (int index = lineStart; index < lineEnd; index++) {
            final char character = text.charAt(index);
            if (!Character.isWhitespace(character) || character == '\n') {
                break;
            }
            // A tab is one character and several columns; counting it as one indents a block that
            // uses tabs by a single space.
            columns += character == '\t' ? tabSize(editor) : 1;
        }
        return columns * metrics(editor).charWidth(' ');
    }

    /**
     * Returns how many columns a tab is worth in the given editor.
     *
     * @param editor the editor being drawn in; must not be {@code null}
     * @return the editor's configured tab size, never below {@code 1}
     */
    private static int tabSize(@NotNull final Editor editor) {
        return Math.max(1, editor.getSettings().getTabSize(editor.getProject()));
    }

    /**
     * Returns the metrics of the editor's plain font.
     *
     * <p>The font the code lines are drawn in, so measuring with it and drawing with it cannot
     * disagree about how wide a block has to be. Headers are drawn in the italic derivation of the
     * same font and are measured here in the plain one.
     *
     * @param editor the editor being drawn in; must not be {@code null}
     * @return the metrics of the editor's content component for the plain editor font
     */
    @NotNull
    private static FontMetrics metrics(@NotNull final Editor editor) {
        return editor.getContentComponent()
                .getFontMetrics(editor.getColorsScheme().getFont(EditorFontType.PLAIN));
    }

    /**
     * Returns a colour from the scheme, or a stand-in.
     *
     * @param scheme   the scheme in force; must not be {@code null}
     * @param key      the colour to look up; must not be {@code null}
     * @param fallback the colour to use when the scheme names none; must not be {@code null}
     * @return the scheme's colour for the key, or {@code fallback} when it has none
     */
    @NotNull
    private static Color colour(@NotNull final EditorColorsScheme scheme,
                                @NotNull final com.intellij.openapi.editor.colors.ColorKey key,
                                @NotNull final Color fallback) {
        final Color found = scheme.getColor(key);
        return found == null ? fallback : found;
    }

    /**
     * Returns the colour a section header is drawn in.
     *
     * <p>The scheme's default foreground mixed with the band's own fill, weighted {@code 0.45}
     * towards the fill. Both ends come from the scheme, so the header stays legible on a light and
     * on a dark scheme without either being named here.
     *
     * @param scheme     the scheme in force; must not be {@code null}
     * @param background the band's fill the header is drawn on; must not be {@code null}
     * @return the header colour
     */
    @NotNull
    private static Color accent(@NotNull final EditorColorsScheme scheme,
                                @NotNull final Color background) {
        final Color foreground = scheme.getDefaultForeground();
        // Halfway between the text colour and the block's own background: legible on both a light
        // and a dark scheme without either being named here.
        return blend(foreground, background, 0.45f);
    }

    /**
     * Mixes two colours channel by channel.
     *
     * <p>Alpha is not carried: the result is always opaque, whatever the inputs were.
     *
     * @param first  the colour that gets the remainder of the weight; must not be {@code null}
     * @param second the colour that gets {@code weight} of the mix; must not be {@code null}
     * @param weight the share of {@code second}, from {@code 0f} for all of {@code first} to {@code 1f}
     *               for all of {@code second}
     * @return the mixed colour
     */
    @NotNull
    private static Color blend(@NotNull final Color first,
                               @NotNull final Color second,
                               final float weight) {
        final float kept = 1f - weight;
        return new Color(
                Math.round(first.getRed() * kept + second.getRed() * weight),
                Math.round(first.getGreen() * kept + second.getGreen() * weight),
                Math.round(first.getBlue() * kept + second.getBlue() * weight));
    }

    /**
     * Joins a line's fragments back into the text they were split from.
     *
     * @param line the fragments of one line, in order; must not be {@code null}
     * @return the concatenated text, used to measure how wide the line is
     */
    @NotNull
    private static String textOf(@NotNull final List<WeaveBlock.Fragment> line) {
        final StringBuilder text = new StringBuilder();
        for (final WeaveBlock.Fragment fragment : line) {
            text.append(fragment.text());
        }
        return text.toString();
    }

    /**
     * Compares renderers by the block they draw.
     *
     * @param other the object to compare with, possibly {@code null}
     * @return {@code true} when {@code other} is a renderer for an equal {@link WeaveBlock}
     */
    @Override
    public boolean equals(@Nullable final Object other) {
        return other instanceof final WeaveBlockRenderer renderer
                && this.block.equals(renderer.block);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)}.
     *
     * @return the block's hash
     */
    @Override
    public int hashCode() {
        return this.block.hashCode();
    }
}
