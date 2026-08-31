package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;
import de.splatgames.aether.weaver.idea.psi.CaretAnchors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Offers "Weave where this variable is live", and generates a handler capturing that variable.
 *
 * <p>The same dialog as {@link WeaveHereIntention}, over a different list: every position in the
 * method the chosen local is live at, rather than the positions the caret is nearest to. The
 * question it answers is not where the caret is but where the value can still be read.
 *
 * <p>Liveness is asked of the target's own local variable table, so this action needs a class file
 * compiled with names in it. Neither a missing class file nor a missing table hides the entry;
 * both are reported as hints when it is invoked, since a hidden entry says nothing about what
 * would make it appear.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CaptureLocalIntention extends PsiElementBaseIntentionAction {

    /** Creates the intention, which holds no state between invocations. */
    public CaptureLocalIntention() {
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
        return "Weave where this variable is live";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Weave capturing a local";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * <p>Only the source is read here: the caret has to be inside a weavable method and on a local
     * variable. Whether that variable can be captured is answered in
     * {@link #invoke(Project, Editor, PsiElement)}, because the answer names something to do about
     * it.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when there is an editor, a weavable method and a local variable
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return editor != null
                && WeaveHereIntention.targetAt(element) != null
                && localAt(element) != null;
    }

    /**
     * Finds where the variable is live and opens the dialog on those positions.
     *
     * <p>Three refusals, each with a hint saying what to do: no usable class file, a target compiled
     * without a local variable table - where every capture would be {@code AW1052} - and a variable
     * that is live at no position a handler can attach to.
     *
     * <p>The filter is part of the search rather than applied to its result, so that the dialog
     * changing the spelling re-runs it and does not come back with every position in the method.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none, in which case nothing happens
     * @param element the element under the caret
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @Nullable final Editor editor,
                       @NotNull final PsiElement element) {
        final PsiMethod target = WeaveHereIntention.targetAt(element);
        final PsiLocalVariable local = localAt(element);
        if (editor == null || target == null || local == null) {
            return;
        }
        final MethodView compiled = WeaveHereIntention.compiledFor(target);
        if (compiled == null) {
            HandlerInsertion.say(editor, "there is no usable class file for the target — build it "
                    + "and try again");
            return;
        }
        if (!TargetLocals.isAvailable(compiled)) {
            HandlerInsertion.say(editor, "the target carries no local variable table, so @Local has "
                    + "no name to bind to — recompile it with -g");
            return;
        }

        final SpotFinder.Reading reading =
                CaretAnchors.at(element, target, editor.getDocument());
        final String name = local.getName();
        if (offersFor(compiled, reading, name, TargetOperations.Spelling.QUALIFIED).isEmpty()) {
            HandlerInsertion.say(editor, '\'' + name + "' is not live at any call, field access, "
                    + "allocation or constant load in this method");
            return;
        }
        // The filter is part of the search, not applied to its result once. Changing the spelling
        // re-runs the search from inside the dialog, and a filter left outside it would come back
        // with every position in the method the second time — offering captures the engine cannot
        // resolve, which is the one thing this action exists to prevent.
        WeaveHereIntention.open(project, editor, target,
                new CompiledClasses.MethodLookup(compiled, ""),
                spelling -> offersFor(compiled, reading, name, spelling),
                capturing(HandlerOptions.load()));
    }

    /**
     * Returns every position in the method where the named local can be captured.
     *
     * <p>Liveness is asked per position through the target's own table, which answers "live at every
     * site this resolves to" rather than "live somewhere": a capture that holds at one site and not
     * at the next is {@code AW1050} on a handler that looked fine.
     *
     * @param compiled the target's compiled form
     * @param reading  the caret, which orders the result
     * @param name     the variable's recorded name
     * @param spelling the spelling to write targets in
     * @return the positions, empty when the name is live at none of them
     */
    @Unmodifiable
    @NotNull
    static List<WeaveSpot> offersFor(@NotNull final MethodView compiled,
                                     @NotNull final SpotFinder.Reading reading,
                                     @NotNull final String name,
                                     @NotNull final TargetOperations.Spelling spelling) {
        final List<WeaveSpot> offers = new ArrayList<>();
        for (final WeaveSpot spot : SpotFinder.everywhere(compiled, reading, spelling)) {
            // Asked of the target's own table through TargetLocals, which answers "live at every
            // site this resolves to" rather than "live somewhere". A capture that holds at one site
            // and not the next is AW1050 on a handler that looked fine.
            if (capturesAt(compiled, spot).stream()
                    .anyMatch(capture -> capture.name().equals(name))) {
                offers.add(spot);
            }
        }
        return List.copyOf(offers);
    }

    /**
     * Returns the locals live at every site the given spot resolves to.
     *
     * @param compiled the target's compiled form
     * @param spot     the position to ask about
     * @return the captures, empty when the spot resolves to no site or nothing is live at all of them
     */
    @Unmodifiable
    @NotNull
    static List<TargetLocals.Capture> capturesAt(@NotNull final MethodView compiled,
                                                 @NotNull final WeaveSpot spot) {
        return TargetLocals.at(compiled, TargetOperations.sitesOf(compiled, spot.point(),
                spot.operation(), spot.slice()));
    }

    /**
     * Returns the remembered options with capturing forced on.
     *
     * <p>Also forced to an inject, which is the only kind that takes captures, and to no group.
     *
     * @param remembered the stored options
     * @return the options the dialog opens with
     */
    @NotNull
    private static HandlerOptions capturing(@NotNull final HandlerOptions remembered) {
        return new HandlerOptions(HandlerOptions.Kind.INJECT, remembered.point(),
                remembered.match(), remembered.selector(), remembered.visibility(),
                remembered.prefix(), "", remembered.callback(), true, remembered.javadoc(),
                remembered.todo());
    }

    /**
     * Describes what the action would do, in words.
     *
     * <p>A diff would have to name one position, and choosing which is the whole of what the dialog
     * asks.
     *
     * @param project the project the file belongs to
     * @param editor  the editor the preview is shown in
     * @param file    the file the preview is generated for
     * @return the description, or {@link IntentionPreviewInfo#EMPTY} when the caret is on no local
     */
    @Override
    @NotNull
    public IntentionPreviewInfo generatePreview(@NotNull final Project project,
                                                @NotNull final Editor editor,
                                                @NotNull final PsiFile file) {
        final PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        final PsiLocalVariable local = element == null ? null : localAt(element);
        return local == null
                ? IntentionPreviewInfo.EMPTY
                : new IntentionPreviewInfo.Html(HtmlChunk.text("Lists the positions where '"
                        + local.getName() + "' is live in the compiled target, and generates a "
                        + "handler capturing it at the one you choose."));
    }

    /**
     * Returns the local variable the caret is on.
     *
     * <p>Either its declaration or a reference that resolves to it. A parameter is not one: a
     * handler's parameter list already begins with its target's, so the value is there without being
     * captured.
     *
     * @param element the element under the caret
     * @return the local variable, or {@code null} when the caret is on something else or on a
     *         variable with no name
     */
    @Nullable
    private static PsiLocalVariable localAt(@NotNull final PsiElement element) {
        final PsiElement parent = element.getParent();
        if (parent instanceof final PsiLocalVariable declared) {
            return declared.getName() == null ? null : declared;
        }
        if (parent instanceof final PsiReferenceExpression reference
                && reference.resolve() instanceof final PsiVariable resolved) {
            return resolved instanceof final PsiLocalVariable local && local.getName() != null
                    ? local
                    : null;
        }
        return null;
    }

    /**
     * Reports that the intention runs outside a write action.
     *
     * <p>It opens a dialog, and the write it eventually performs is a command of its own.
     *
     * @return {@code false}
     */
    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
