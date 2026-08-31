package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;
import de.splatgames.aether.weaver.idea.index.WeaveTargetIndex;
import de.splatgames.aether.weaver.idea.psi.CaretAnchors;
import de.splatgames.aether.weaver.idea.psi.SourceSpots;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Function;

/**
 * Offers "Weave here" inside a method body, and generates a handler for whatever the caret is on.
 *
 * <p>The caret is read into a set of anchors, the anchors are matched against the target's
 * instructions, and what comes back is a list of positions ordered by how well each answers
 * "here". {@code WeaveHereDialog} shows that list; this class decides where the intention is
 * offered, runs the search, and hands the result to {@code HandlerInsertion}.
 *
 * <p>Without a class file the source answers instead, naming members but numbering no
 * instruction. That is the ordinary state of a project that has not been built - every sample,
 * every scratch file, every module mid-refactor - and refusing there would reduce the feature to
 * a handler at the head of the method.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveHereIntention extends PsiElementBaseIntentionAction {

    /** Creates the intention, which holds no state between invocations. */
    public WeaveHereIntention() {
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
        return "Weave here";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Weave here";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when there is an editor and the caret is inside a method that can be woven
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return editor != null && targetAt(element) != null;
    }

    /**
     * Searches from the caret and opens the dialog.
     *
     * <p>The class file is looked up once and its reason kept, so that the dialog can say why it is
     * offering only positions instead of silently falling back to the head of the method.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none, in which case nothing happens
     * @param element the element under the caret
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @Nullable final Editor editor,
                       @NotNull final PsiElement element) {
        final PsiMethod target = targetAt(element);
        if (editor == null || target == null) {
            return;
        }
        // Looked up once, and its reason kept. Asking for the compiled method and throwing away
        // why there was none is what made this action appear to know only one injection point: it
        // fell back to the head of the method, every time, and said nothing about a class file.
        final CompiledClasses.MethodLookup lookup = CompiledClasses.methodOf(target);
        open(project, editor, target, lookup,
                spelling -> spotsFor(lookup.method(), target, element, editor.getDocument(),
                        spelling),
                HandlerOptions.load());
    }

    /**
     * Runs the search, opens the dialog and writes what it returns.
     *
     * <p>Shared with {@link CaptureLocalIntention}, which passes a search of its own. A search that
     * finds nothing is reported as a hint rather than an empty dialog.
     *
     * @param project the project the file belongs to
     * @param editor  the editor the result is written into
     * @param target  the method the caret was in
     * @param lookup  the class file lookup for that method
     * @param search  the search, run once now and again whenever the dialog changes the spelling
     * @param initial the choices to open the dialog with
     */
    static void open(@NotNull final Project project,
                     @NotNull final Editor editor,
                     @NotNull final PsiMethod target,
                     @NotNull final CompiledClasses.MethodLookup lookup,
                     @NotNull final Function<TargetOperations.Spelling, List<WeaveSpot>> search,
                     @NotNull final HandlerOptions initial) {
        final List<WeaveSpot> spots = search.apply(spellingOf(initial));
        if (spots.isEmpty()) {
            HandlerInsertion.say(editor, "there is nothing at the caret a handler could attach to");
            return;
        }
        final PsiClass owner = target.getContainingClass();
        final WeaveHereDialog dialog = new WeaveHereDialog(project, target, lookup, spots,
                owner == null ? List.of() : WeaveTargetIndex.weavesOf(owner), search, initial);
        if (!dialog.showAndGet()) {
            return;
        }
        final WeaveSpot chosen = dialog.spot();
        if (chosen == null) {
            return;
        }
        final HandlerOptions options = dialog.options();
        options.save();
        HandlerInsertion.into(project, editor, dialog.weave(), target, chosen.operation(),
                dialog.captures(), chosen.slice(), options);
    }

    /**
     * Searches for the positions at the caret, looking the class file up itself.
     *
     * @param target   the method the caret is in
     * @param element  the element under the caret
     * @param document the document the caret's line is read from
     * @param spelling the spelling to write targets in, or {@code null} for the stored one
     * @return the positions found, best first
     */
    @Unmodifiable
    @NotNull
    static List<WeaveSpot> spotsFor(@NotNull final PsiMethod target,
                                    @NotNull final PsiElement element,
                                    @NotNull final Document document,
                                    @Nullable final TargetOperations.Spelling spelling) {
        return spotsFor(CompiledClasses.methodOf(target).method(), target, element, document,
                spelling);
    }

    /**
     * Searches for the positions at the caret.
     *
     * <p>With a class file the search is over instructions and the results carry ordinals. Without
     * one it is over the source, which names members exactly and numbers nothing.
     *
     * @param compiled the target's compiled form, or {@code null} when there is none
     * @param target   the method the caret is in
     * @param element  the element under the caret
     * @param document the document the caret's line is read from
     * @param spelling the spelling to write targets in, or {@code null} for the stored one
     * @return the positions found, best first
     */
    @Unmodifiable
    @NotNull
    private static List<WeaveSpot> spotsFor(@Nullable final MethodView compiled,
                                            @NotNull final PsiMethod target,
                                            @NotNull final PsiElement element,
                                            @NotNull final Document document,
                                            @Nullable final TargetOperations.Spelling spelling) {
        final TargetOperations.Spelling chosen =
                spelling == null ? spellingOf(HandlerOptions.load()) : spelling;
        final SpotFinder.Reading reading = CaretAnchors.at(element, target, document);
        // Without a class file the source answers instead, and it answers without an ordinal.
        // Refusing here is what made this feature come down to "a handler at the head of the
        // method" for every project that has not been built — which is every sample, every scratch
        // file, and every module in the middle of a refactor.
        return compiled == null
                ? SourceSpots.at(target, reading, document, chosen)
                : SpotFinder.at(compiled, reading, chosen);
    }

    /**
     * Shows the handler the best position would produce, as a diff against a weave.
     *
     * <p>Against the target's own class when it has no weave yet. Against the first of several
     * candidates when it has more than one - an arbitrary choice, since the dialog itself preselects
     * none in that case - so the weave shown here need not be the one the handler is eventually
     * written into.
     *
     * <p>Rendered against the original file rather than the copy the platform hands a preview,
     * because a copy has no compiler output behind it. The preview is of an inject with no captured
     * locals and no group, whatever the dialog would go on to offer, and it carries {@link #noteFor}'s
     * comment about a missing class file, which is worded differently from the note
     * {@code WeaveHereDialog} shows in that case.
     *
     * @param project the project the file belongs to
     * @param editor  the editor the preview is shown in
     * @param file    the file the preview is generated for
     * @return the diff, or {@link IntentionPreviewInfo#EMPTY} when there is nothing to show
     */
    @Override
    @NotNull
    public IntentionPreviewInfo generatePreview(@NotNull final Project project,
                                                @NotNull final Editor editor,
                                                @NotNull final PsiFile file) {
        final PsiMethod target = targetForPreview(file, editor.getCaretModel().getOffset());
        final PsiElement element = target == null
                ? null
                : originalOf(file).findElementAt(editor.getCaretModel().getOffset());
        final PsiClass owner = target == null ? null : target.getContainingClass();
        if (target == null || element == null || owner == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        final CompiledClasses.MethodLookup lookup = CompiledClasses.methodOf(target);
        final List<WeaveSpot> spots =
                spotsFor(lookup.method(), target, element, editor.getDocument(), null);
        final List<PsiClass> weaves = WeaveTargetIndex.weavesOf(owner);
        final PsiClass into = weaves.isEmpty() ? owner : weaves.getFirst();
        if (spots.isEmpty()) {
            return IntentionPreviewInfo.EMPTY;
        }
        final WeaveSpot best = spots.getFirst();
        final HandlerOptions remembered = HandlerOptions.load();
        final PsiMethod handler = AddHandlerHandler.handlerFor(into, target, best.operation(),
                List.of(), best.slice(),
                new HandlerOptions(HandlerOptions.Kind.INJECT,
                        HandlerOptions.Point.of(best.point()), remembered.match(),
                        remembered.selector(), remembered.visibility(), remembered.prefix(), "",
                        remembered.callback(), false, remembered.javadoc(), remembered.todo()));
        return handler == null
                ? IntentionPreviewInfo.EMPTY
                : new IntentionPreviewInfo.CustomDiff(JavaFileType.INSTANCE,
                        nameOf(into) + ".java", "", noteFor(lookup) + handler.getText());
    }

    /**
     * Returns the method a preview at the given offset would be generated for.
     *
     * @param file   the file the preview is generated for, copy or original
     * @param offset the caret offset
     * @return the method, or {@code null} when the offset is not inside one that can be woven
     */
    @Nullable
    static PsiMethod targetForPreview(@NotNull final PsiFile file, final int offset) {
        final PsiElement element = originalOf(file).findElementAt(offset);
        return element == null ? null : targetAt(element);
    }

    /**
     * Returns the file the editor is showing, given the one a preview was handed.
     *
     * @param file the file to resolve
     * @return the original, or the argument itself when it is not a preview copy
     */
    @NotNull
    private static PsiFile originalOf(@NotNull final PsiFile file) {
        final PsiFile original = IntentionPreviewUtils.getOriginalFile(file);
        return original == null ? file : original;
    }

    /**
     * Returns the comment a preview carries when only positions could be offered.
     *
     * @param lookup the class file lookup, whose reason is quoted
     * @return the comment line, or an empty string when the class file was read
     */
    @NotNull
    private static String noteFor(@NotNull final CompiledClasses.MethodLookup lookup) {
        return lookup.isAvailable()
                ? ""
                : "// Only positions can be offered: " + lookup.reason() + ".\n";
    }

    /**
     * Returns the name the preview's file is titled with.
     *
     * @param weave the weave the handler would go into
     * @return its name, or a placeholder when it has none
     */
    @NotNull
    private static String nameOf(@NotNull final PsiClass weave) {
        return weave.getName() == null ? "Weave" : weave.getName();
    }

    /**
     * Translates the stored selector form into the spelling the search uses.
     *
     * @param options the options to read
     * @return the matching spelling
     */
    @NotNull
    private static TargetOperations.Spelling spellingOf(@NotNull final HandlerOptions options) {
        return switch (options.selector()) {
            case QUALIFIED -> TargetOperations.Spelling.QUALIFIED;
            case SIMPLE -> TargetOperations.Spelling.SIMPLE;
            case DESCRIPTOR -> TargetOperations.Spelling.DESCRIPTOR;
        };
    }

    /**
     * Returns the target's compiled form.
     *
     * @param target the method to look up
     * @return the compiled method, or {@code null} when there is no usable class file
     */
    @Nullable
    static MethodView compiledFor(@NotNull final PsiMethod target) {
        return CompiledClasses.methodOf(target).method();
    }

    /**
     * Returns the method a handler at this element would be generated for.
     *
     * <p>Four things are refused. A caret outside a body, because a signature is not a position. A
     * caret inside a lambda, because its body compiles into a synthetic method of its own, so the
     * instructions at that caret are not in the enclosing method at all. A class with no qualified
     * name, an interface, or an annotation type. And a weave's own handler, which is not somebody's
     * target but something moved into one.
     *
     * @param element the element under the caret
     * @return the target method, or {@code null} when any of those refusals applies
     */
    @Nullable
    static PsiMethod targetAt(@NotNull final PsiElement element) {
        final PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method == null || method.getBody() == null
                || !insideBody(element, method.getBody())) {
            return null;
        }
        // A lambda body compiles into a synthetic method of its own, so the instructions at this
        // caret are not in the enclosing method at all. Offering that method's operations here
        // would answer a question nobody asked, on a line that looks exactly like one this works
        // for.
        if (PsiTreeUtil.getParentOfType(element, PsiLambdaExpression.class, false,
                PsiMethod.class) != null) {
            return null;
        }
        final PsiClass owner = method.getContainingClass();
        if (owner == null || owner.getQualifiedName() == null || owner.isInterface()
                || owner.isAnnotationType()) {
            return null;
        }
        // A weave's own handlers are not somebody's target: they are moved into one.
        return WeaveDeclarations.annotation(owner, WeaveDeclarations.WEAVE) == null ? method : null;
    }

    /**
     * Reports whether the element starts inside the given body.
     *
     * @param element the element under the caret
     * @param body    the method body to test against
     * @return {@code true} when the element's start offset lies within the body
     */
    private static boolean insideBody(@NotNull final PsiElement element,
                                      @NotNull final PsiCodeBlock body) {
        return body.getTextRange().contains(element.getTextRange().getStartOffset());
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
