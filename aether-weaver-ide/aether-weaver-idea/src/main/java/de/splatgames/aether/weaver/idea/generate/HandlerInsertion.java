package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.intention.CreateWeaveIntention;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Writes a generated handler into a weave, under a write command of its own.
 *
 * <p>The two dialogs of this package decide what to generate; this is where the result reaches
 * the file. Everything it can fail at is reported to the editor as a hint rather than thrown,
 * because it is reached from a dialog the user has just closed and there is no other surface
 * left to report on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class HandlerInsertion {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private HandlerInsertion() {
        throw new AssertionError("no instances");
    }

    /**
     * Adds a handler for the given target to the given weave and navigates to it.
     *
     * <p>Runs as a write command named "Weave Here", so the caller need not hold a write action
     * itself. A {@code null} weave means the target has none yet and one is created beside its
     * class; when that is impossible, or when the handler cannot be written because a type
     * involved does not resolve, the reason is shown through
     * {@link #say(Editor, String)} and nothing is inserted.
     *
     * <p>Qualified names in the inserted method are shortened to imports, every document is
     * committed, and the method is navigated to.
     *
     * @param project   the project the write command belongs to
     * @param editor    the editor a failure is reported in
     * @param weave     the weave to write into, or {@code null} to create one for the target's class
     * @param target    the method the handler is being generated for
     * @param operation the operation the handler attaches to, or {@code null} for a positional point
     * @param captures  the locals to capture, empty for none
     * @param bounds    the slice to narrow the search to, or {@code null} for the whole method
     * @param options   the choices the dialog collected
     */
    static void into(@NotNull final Project project,
                     @NotNull final Editor editor,
                     @Nullable final PsiClass weave,
                     @NotNull final PsiMethod target,
                     @Nullable final TargetOperations.Operation operation,
                     @NotNull final List<TargetLocals.Capture> captures,
                     @Nullable final TargetOperations.Bounds bounds,
                     @NotNull final HandlerOptions options) {
        WriteCommandAction.writeCommandAction(project)
                .withName("Weave Here")
                .run(() -> {
                    final PsiClass into = weave == null ? createWeaveFor(target) : weave;
                    if (into == null) {
                        say(editor, "there is nowhere to put a weave for the target's class");
                        return;
                    }
                    final PsiMethod handler = AddHandlerHandler.handlerFor(into, target, operation,
                            captures, bounds, options);
                    if (handler == null) {
                        say(editor, "a type involved cannot be written into a weave");
                        return;
                    }
                    final PsiElement added = into.add(handler);
                    JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                    if (added instanceof final PsiMethod written) {
                        written.navigate(true);
                    }
                });
    }

    /**
     * Creates a weave for the target's own class, in the target's own directory.
     *
     * <p>The package name is taken from the file when it is a Java file. Otherwise it is
     * {@code String.valueOf} of whatever {@link JavaDirectoryService#getPackage(PsiDirectory)} returns
     * for the directory: the {@link com.intellij.psi.PsiPackage}'s own text when the directory is under
     * a source root, or the literal string {@code "null"} when it is not, since the platform then
     * returns {@code null} and there is no package name to fall back to.
     *
     * @param target the method whose class the weave is created for
     * @return the created weave, or {@code null} when the target has no containing class, no file or
     *         no directory to create it in
     */
    @Nullable
    private static PsiClass createWeaveFor(@NotNull final PsiMethod target) {
        final PsiClass owner = target.getContainingClass();
        final PsiFile file = owner == null ? null : owner.getContainingFile();
        final PsiDirectory directory = file == null ? null : file.getContainingDirectory();
        if (owner == null || directory == null) {
            return null;
        }
        final String packageName = file instanceof final PsiJavaFile java
                ? java.getPackageName()
                : String.valueOf(JavaDirectoryService.getInstance().getPackage(directory));
        return CreateWeaveIntention.createFor(owner, directory, packageName);
    }

    /**
     * Shows the reason nothing was generated, as an error hint over the editor.
     *
     * <p>The message is the second half of a sentence: it is prefixed with "Cannot weave here: ".
     *
     * @param editor  the editor to show the hint over
     * @param message the reason, phrased to follow the prefix
     */
    static void say(@NotNull final Editor editor, @NotNull final String message) {
        HintManager.getInstance().showErrorHint(editor, "Cannot weave here: " + message);
    }
}
