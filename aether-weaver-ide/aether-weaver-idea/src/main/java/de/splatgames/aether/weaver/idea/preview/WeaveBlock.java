package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * One preview block: everything that is drawn above a single line of a woven method.
 *
 * <p>Produced by {@code WeaveBlocks} and rendered by {@link WeaveBlockRenderer}. A block is per
 * line rather than per handler: every handler that applies anywhere on the anchored line ends up in
 * this one block, grouped into a {@link Section} per injection point and kind, and the sections are
 * ordered by point in a fixed list ({@code HEAD}, {@code FIELD}, {@code NEW}, {@code INVOKE},
 * {@code INVOKE_AFTER}, {@code CONSTANT}, {@code THROW}, {@code RETURN}, {@code TAIL}), not by when
 * the underlying code actually runs.
 *
 * <p>Two records nest below it. A {@link Section} is a point's worth of handlers, and its
 * {@link Section#lines()} are the handler bodies split into lines; each line is a list of
 * {@link Fragment}s, which is a run of text with the colour key the Java highlighter gave it.
 *
 * <p>Values only: nothing here refers to PSI, to a document or to an editor.
 * {@link WeaveInlayPass} compares one pass's sections against the previous pass's to decide
 * whether an inlay it already added may be kept.
 *
 * @param offset   the document offset the block is drawn above; {@code WeaveBlocks} anchors it at
 *                 the start of the line the injection applies on
 * @param id       the identity used to remember that the reader collapsed this block, built from
 *                 the target's signature and the section headers rather than from {@code offset};
 *                 see {@link WeaveCollapsedBlocks}
 * @param sections the sections to draw, top to bottom, ordered by the fixed point order this
 *                 class stacks sections in, which is not the order the sections' code executes
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record WeaveBlock(int offset,
                         @NotNull String id,
                         @Unmodifiable @NotNull List<Section> sections) {

    /**
     * Returns the block's height in lines of the editor.
     *
     * <p>One line per section for its header, plus one for each line of that section's rendered
     * code. A section whose handlers have empty bodies still costs its header's line.
     *
     * @return the number of editor lines the expanded block occupies
     */
    public int height() {
        int lines = 0;
        for (final Section section : this.sections) {
            lines += 1 + section.lines().size();
        }
        return lines;
    }

    /**
     * The handlers that apply at one injection point of one kind, and their code.
     *
     * <p>Handlers are listed once each, in execution order, however often they apply on the line;
     * the count is folded into {@link #header()} instead. The header is the handler names joined by
     * commas, then two spaces and the point's tag, as in {@code Audit.onCharge()  @HEAD}; a handler
     * that applies more than once on the line carries a multiplication sign and the count after its
     * name.
     *
     * @param kind        whether the handlers add code at the point or replace the operation there
     * @param header      the one-line title drawn above the code
     * @param explanation a sentence saying what happens here, drawn on the gutter control's tooltip
     *                    rather than in the block
     * @param lines       the handler bodies, one entry per line, each split into coloured fragments
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Section(@NotNull Kind kind,
                          @NotNull String header,
                          @NotNull String explanation,
                          @Unmodifiable @NotNull List<List<Fragment>> lines) {
    }

    /**
     * What a section's handlers do to the operation at their point.
     *
     * <p>Chooses the section's colour: {@link WeaveBlockRenderer} paints an {@link #INJECT} section
     * with the scheme's added-lines colour and a {@link #REDIRECT} section with its modified-lines
     * colour.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Kind {

        /** Code that runs in addition to what the target already does. */
        INJECT,

        /** Code that takes the place of the operation the point named. */
        REDIRECT
    }

    /**
     * A run of handler text that the Java highlighter gave one colour.
     *
     * <p>Fragments never span a line: {@code WeaveBlocks} splits every highlighter token on
     * newlines before making them, so concatenating a line's fragments reproduces that line.
     *
     * @param text the text to draw, never containing a newline
     * @param key  the attributes key to draw it with, or {@code null} when the highlighter offered
     *             none, in which case {@link WeaveBlockRenderer} uses the scheme's default
     *             foreground
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Fragment(@NotNull String text, TextAttributesKey key) {
    }
}
