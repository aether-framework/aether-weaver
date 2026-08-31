package de.splatgames.aether.weaver.idea.preview;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The highlighting pass that puts an injection-site hint at the end of each injection's first line.
 *
 * <p>Registered through {@link Factory}, which {@code plugin.xml} declares as a
 * {@code highlightingPassFactory}. The work is split over the two methods the supertype declares:
 * the hints are computed from PSI in {@link #doCollectInformation} and held in a field, and
 * {@link #doApplyInformationToEditor} turns them into inlays without recomputing anything. The
 * second half treats what it was handed as possibly out of date and bounds every line it uses
 * against the document as it finds it.
 *
 * <p>Inlays are reconciled rather than rebuilt. An existing hint inlay at the offset a hint wants,
 * whose renderer already carries the same text, is left in place; anything else is disposed and
 * replaced. Only inlays whose renderer is an {@code InjectionSiteHintRenderer} are considered at
 * all, so nothing another plugin put in the editor is inspected or disposed.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class InjectionSiteHintPass extends TextEditorHighlightingPass {

    /** The file the hints are computed from. */
    private final PsiFile file;

    /** The editor the hints are placed in. */
    private final Editor editor;

    /** What {@link #doCollectInformation} found, read by {@link #doApplyInformationToEditor}. */
    private List<InjectionSiteHints.Hint> hints = List.of();

    /**
     * Creates a pass over one file in one editor.
     *
     * <p>Hands the file's project and the editor's document to the superclass, and keeps both
     * arguments for the two halves of the pass.
     *
     * @param file   the file to scan; must not be {@code null}
     * @param editor the editor to place hints in; must not be {@code null}
     */
    InjectionSiteHintPass(@NotNull final PsiFile file, @NotNull final Editor editor) {
        super(file.getProject(), editor.getDocument(), false);
        this.file = file;
        this.editor = editor;
    }

    /**
     * Computes the hints for the file and keeps them until the editor is updated.
     *
     * <p>Delegates wholly to {@link InjectionSiteHints#of(PsiFile)}; the indicator is not consulted.
     *
     * @param progress the indicator for this pass; must not be {@code null}
     */
    @Override
    public void doCollectInformation(@NotNull final ProgressIndicator progress) {
        this.hints = InjectionSiteHints.of(this.file);
    }

    /**
     * Brings the editor's hint inlays into line with what was collected.
     *
     * <p>The after-line-end inlays of the whole document are indexed by offset, keeping only those
     * whose renderer is an {@code InjectionSiteHintRenderer}. Each collected hint then claims the
     * end offset of its line: an indexed inlay there whose renderer carries the same text is left
     * untouched, and otherwise it is disposed and a fresh inlay is added at that offset. Every
     * indexed inlay no hint claimed is disposed once the hints are exhausted.
     *
     * <p>A hint whose line is beyond the document's current line count is skipped rather than
     * clamped, so a document that shrank since {@link #doCollectInformation} ran loses the hint
     * instead of receiving it on a line it does not describe.
     */
    @Override
    public void doApplyInformationToEditor() {
        final var model = this.editor.getInlayModel();
        final var document = this.editor.getDocument();

        final Map<Integer, Inlay<?>> existing = new HashMap<>();
        for (final Inlay<?> inlay
                : model.getAfterLineEndElementsInRange(0, document.getTextLength())) {
            if (inlay.getRenderer() instanceof InjectionSiteHintRenderer) {
                existing.put(inlay.getOffset(), inlay);
            }
        }

        for (final InjectionSiteHints.Hint hint : this.hints) {
            if (hint.line() >= document.getLineCount()) {
                // The document shrank between collecting and applying; the next pass catches up.
                continue;
            }
            final int offset = document.getLineEndOffset(hint.line());
            final Inlay<?> previous = existing.remove(offset);
            if (previous != null
                    && previous.getRenderer() instanceof final InjectionSiteHintRenderer renderer
                    && renderer.text().equals(hint.text())) {
                continue;
            }
            if (previous != null) {
                previous.dispose();
            }
            model.addAfterLineEndElement(offset, false, new InjectionSiteHintRenderer(hint.text()));
        }

        for (final Inlay<?> stale : existing.values()) {
            stale.dispose();
        }
    }

    /**
     * Registers the pass and creates one per file and editor.
     *
     * <p>Declared in {@code plugin.xml} as a {@code highlightingPassFactory}. One class implements
     * both the registrar and the factory, and {@link #registerHighlightingPassFactory} passes
     * {@code this} as the factory to register.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Factory
            implements TextEditorHighlightingPassFactory,
            TextEditorHighlightingPassFactoryRegistrar {

        /**
         * Creates the factory.
         *
         * <p>Carries no state; every pass it makes gets its own file and editor.
         */
        public Factory() {
            // Nothing: the platform constructs this and calls registerHighlightingPassFactory.
        }

        /**
         * Registers this factory with the given registrar.
         *
         * <p>Calls {@code registerTextEditorHighlightingPass} on the registrar with {@code this},
         * {@code null} for both pass-order arguments, {@code false}, and {@code -1}. The project is
         * not otherwise used.
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
         * <p>Always creates one: whether there is anything to show is decided by
         * {@link InjectionSiteHints#of(PsiFile)} when the pass collects.
         *
         * @param file   the file to be scanned; must not be {@code null}
         * @param editor the editor to place hints in; must not be {@code null}
         * @return a new {@code InjectionSiteHintPass} over that file and editor
         */
        @Override
        @Nullable
        public TextEditorHighlightingPass createHighlightingPass(@NotNull final PsiFile file,
                                                                 @NotNull final Editor editor) {
            return new InjectionSiteHintPass(file, editor);
        }
    }
}
