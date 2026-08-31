package de.splatgames.aether.weaver.idea.preview;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * The highlighting pass that draws woven code into the file it is woven into.
 *
 * <p>Registered through {@link Factory}, which {@code plugin.xml} declares as a
 * {@code highlightingPassFactory}. {@link #doCollectInformation} asks {@link WeaveBlocks} for the
 * blocks of the file and keeps them; {@link #doApplyInformationToEditor} reconciles the editor's
 * block inlays against that list without recomputing anything.
 *
 * <p>Reconciled, not rebuilt. An existing inlay at a block's offset whose renderer holds sections
 * equal to that block's is kept as the same object; only a block whose sections differ costs a
 * dispose and an add. Matching is by offset first, so a block that has moved is never compared with
 * the inlay at its old offset: that inlay is disposed as unclaimed and a new one is added.
 *
 * <p>The index of existing inlays is keyed by offset and not by {@link WeaveBlock#id()}. An
 * identity is what survives an edit, which is what {@link WeaveCollapsedBlocks} needs, but it is
 * not unique within a file: two blocks in one method can carry the same handlers at the same point
 * and share one. There is one block per offset by construction, because {@link WeaveBlocks} keys
 * its own result by the start of the line.
 *
 * <p>The whole feature is switched off from {@link WeaveInlaySettings}: when it reports collapsed,
 * no blocks are collected at all, and reconciliation then disposes every one of this plugin's block
 * inlays in the editor.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveInlayPass extends TextEditorHighlightingPass {

    /** The file the blocks are computed from. */
    private final PsiFile file;

    /** The editor the blocks are drawn in. */
    private final Editor editor;

    /** What {@link #doCollectInformation} found, read by {@link #doApplyInformationToEditor}. */
    private List<WeaveBlock> blocks = List.of();

    /**
     * Creates a pass over one file in one editor.
     *
     * <p>Hands the file's project and the editor's document to the superclass, and keeps both arguments
     * for the two halves of the pass.
     *
     * @param file   the file to scan; must not be {@code null}
     * @param editor the editor to draw blocks in; must not be {@code null}
     */
    WeaveInlayPass(@NotNull final PsiFile file, @NotNull final Editor editor) {
        super(file.getProject(), editor.getDocument(), false);
        this.file = file;
        this.editor = editor;
    }

    /**
     * Computes the blocks for the file, or none at all when the feature is switched off.
     *
     * <p>{@link WeaveInlaySettings#isCollapsed()} is checked first and short-circuits the whole
     * computation, so the setting removes the blocks rather than shortening them: an empty list
     * leaves {@link #doApplyInformationToEditor} disposing every one of this plugin's block inlays
     * in the editor, bar and tint included. Collapsing one block is the separate
     * {@link WeaveCollapsedBlocks}, which this pass never consults and which leaves a strip behind.
     *
     * <p>The indicator is not consulted.
     *
     * @param progress the indicator for this pass; must not be {@code null}
     */
    @Override
    public void doCollectInformation(@NotNull final ProgressIndicator progress) {
        // Nothing at all when the feature is switched off. "Show injected code" is a checkbox,
        // and unchecking a checkbox means the thing goes away — all of it. An earlier version kept
        // the coloured bar and its tint when collapsed, reasoning that hiding them would take back
        // the one thing the feature exists for; that argument holds for collapsing a single block,
        // which is a disclosure control, and not for turning the feature off, which is an answer to
        // a different question.
        this.blocks = WeaveInlaySettings.getInstance().isCollapsed()
                ? List.of()
                : WeaveBlocks.of(this.file);
    }

    /**
     * Brings the editor's block inlays into line with what was collected.
     *
     * <p>The block inlays of the whole document are indexed by offset, keeping only those whose
     * renderer is a {@link WeaveBlockRenderer}. Each collected block then claims its offset: an indexed
     * inlay there that {@code unchanged} accepts is left as it is, and otherwise it is disposed and a
     * fresh inlay is added at that offset, above the line and not relating to the preceding text. Every
     * indexed inlay no block claimed is disposed once the blocks are exhausted, which is what removes a
     * block whose injection no longer applies.
     *
     * <p>A block whose offset is past the end of the document is skipped rather than clamped, so a
     * document that shrank since {@link #doCollectInformation} ran loses the block instead of receiving
     * it somewhere else.
     */
    @Override
    public void doApplyInformationToEditor() {
        final var model = this.editor.getInlayModel();

        // Kept, not recreated. The daemon runs on every caret move and again on a timer, and in
        // the steady state it produces exactly the blocks that are already there. Disposing them and
        // adding them back changes the document's rendered height twice, and the editor loses its
        // scroll position doing it — so the view jumped to the top of the file every few seconds,
        // for as long as the file had injections in it. Which is not an annoyance: it is the file
        // becoming unusable to work in.
        // Keyed by offset, not by the block's identity. The identity is what survives an edit,
        // which is what the collapsed set needs — but it is not unique: two `return` statements in
        // one method yield two blocks alike in everything an identity can see. Keyed by it, the map
        // held one inlay for both, the first block kept it and the second added a fresh one — on
        // every pass, for ever. There is exactly one block per offset by construction, so the offset
        // is the key that is actually unique.
        final Map<Integer, Inlay<?>> existing = new HashMap<>();
        for (final Inlay<?> inlay
                : model.getBlockElementsInRange(0, this.editor.getDocument().getTextLength())) {
            if (inlay.getRenderer() instanceof WeaveBlockRenderer) {
                existing.put(inlay.getOffset(), inlay);
            }
        }

        for (final WeaveBlock block : this.blocks) {
            if (block.offset() > this.editor.getDocument().getTextLength()) {
                // The document shrank between collecting and applying; the next pass catches up.
                continue;
            }
            final Inlay<?> previous = existing.remove(block.offset());
            if (previous != null && unchanged(previous, block)) {
                continue;
            }
            if (previous != null) {
                previous.dispose();
            }
            model.addBlockElement(block.offset(),
                    new InlayProperties().showAbove(true).relatesToPrecedingText(false),
                    new WeaveBlockRenderer(block));
        }

        // Whatever is left described something that is no longer there.
        for (final Inlay<?> stale : existing.values()) {
            stale.dispose();
        }
    }

    /**
     * Reports whether an existing inlay already draws the given block.
     *
     * <p>Compares {@link WeaveBlock#sections()} and nothing else; the offsets are equal already,
     * because the inlay was looked up under the block's own offset.
     *
     * @param inlay the inlay found at the block's offset; must not be {@code null}
     * @param block the block that wants that offset; must not be {@code null}
     * @return {@code true} when the inlay's renderer is a {@link WeaveBlockRenderer} whose block has
     *         equal sections
     */
    private static boolean unchanged(@NotNull final Inlay<?> inlay,
                                     @NotNull final WeaveBlock block) {
        return inlay.getRenderer() instanceof final WeaveBlockRenderer renderer
                && renderer.block().sections().equals(block.sections());
    }

    /**
     * Registers the pass and creates one per file and editor.
     *
     * <p>Declared in {@code plugin.xml} as a {@code highlightingPassFactory}. One class implements both
     * the registrar and the factory, and {@link #registerHighlightingPassFactory} passes {@code this}
     * as the factory to register.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Factory
            implements TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {

        /**
         * Creates the factory.
         *
         * <p>Carries no state; every pass it makes gets its own file and editor.
         */
        public Factory() {
            // Stateless.
        }

        /**
         * Registers this factory with the given registrar.
         *
         * <p>Calls {@code registerTextEditorHighlightingPass} on the registrar with {@code this},
         * {@code null} for both pass-order arguments, {@code false}, and {@code -1}. The project is not
         * otherwise used.
         *
         * @param registrar the registrar to register with; must not be {@code null}
         * @param project   the project the registration is for; must not be {@code null}
         */
        @Override
        public void registerHighlightingPassFactory(
                @NotNull final TextEditorHighlightingPassRegistrar registrar,
                @NotNull final Project project) {
            registrar.registerTextEditorHighlightingPass(this, null, null, false, -1);
        }

        /**
         * Creates a pass for one file in one editor.
         *
         * <p>Always creates one; whether there is anything to draw is decided when the pass collects.
         *
         * @param file   the file to be scanned; must not be {@code null}
         * @param editor the editor to draw blocks in; must not be {@code null}
         * @return a new {@code WeaveInlayPass} over that file and editor
         */
        @Override
        @Nullable
        public TextEditorHighlightingPass createHighlightingPass(@NotNull final PsiFile file,
                                                                 @NotNull final Editor editor) {
            return new WeaveInlayPass(file, editor);
        }
    }

    /**
     * Returns the blocks currently drawn in the given editor.
     *
     * <p>Reads the editor rather than recomputing: every block inlay of the document whose renderer is
     * a {@link WeaveBlockRenderer} contributes the block that renderer holds, in the order the inlay
     * model yields them. An editor no pass has applied to yet answers empty.
     *
     * @param editor the editor to inspect; must not be {@code null}
     * @return the blocks the editor is showing, empty when it is showing none
     */
    @NotNull
    public static List<WeaveBlock> shownIn(@NotNull final Editor editor) {
        final List<WeaveBlock> shown = new ArrayList<>();
        for (final Inlay<?> inlay : editor.getInlayModel()
                .getBlockElementsInRange(0, editor.getDocument().getTextLength())) {
            if (inlay.getRenderer() instanceof final WeaveBlockRenderer renderer) {
                shown.add(renderer.block());
            }
        }
        return shown;
    }
}
