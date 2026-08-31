package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates an empty weave class for the class the caret is in.
 *
 * <p>The generated file is written beside the target, in the target's own directory and package,
 * and holds nothing but the declaration:
 *
 * <pre>{@code
 * package com.acme;
 *
 * import de.splatgames.aether.weaver.api.Weave;
 *
 * @Weave(Payment.class)
 * public final class PaymentWeave {
 * }
 * }</pre>
 *
 * <p>Two properties of that text are not incidental. The class is {@code final}, which is what
 * {@code AW1008} asks of a weave. The target is named with a class literal rather than as a
 * string, which is the spelling {@code AW1009} asks for when the class is on the compile
 * classpath: a literal is checked by the compiler and followed by a rename.
 *
 * <p>Offered inside any named class that is not itself annotated {@code @Weave}, and not on an
 * interface, an annotation type, an anonymous class or a local class. The innermost enclosing
 * class wins, so the caret inside a method body creates a weave for the class that method belongs
 * to.
 *
 * <p>{@code createFor} is also called from {@code HandlerInsertion}, which creates a weave on the
 * way to generating a handler for a target that has none.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CreateWeaveIntention extends PsiElementBaseIntentionAction {

    /** Appended to the target's simple name to name the weave class. */
    private static final String SUFFIX = "Weave";

    /** Creates the intention, which holds no state between invocations. */
    public CreateWeaveIntention() {
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
        return "Create a weave for this class";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Create weave";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when the caret is inside a class a weave can be created for
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return targetAt(element) != null;
    }

    /**
     * Creates the weave and opens it.
     *
     * <p>The package name is the containing file's, so the weave can reach the target's
     * package-private members. Nothing is created when the caret is not in a Java file with a
     * directory to write into. The new file is opened only when the action came from an editor.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none, in which case the weave is
     *                still created but not opened
     * @param element the element under the caret
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @Nullable final Editor editor,
                       @NotNull final PsiElement element) {
        final PsiClass target = targetAt(element);
        final PsiFile file = element.getContainingFile();
        final PsiDirectory directory = file == null ? null : file.getContainingDirectory();
        if (target == null || directory == null || !(file instanceof final PsiJavaFile java)) {
            return;
        }

        final PsiClass weave = createFor(target, directory, java.getPackageName());
        // Opened only when the action came from an editor. A weave nobody can see is a file the
        // author has to go looking for; a navigation request with no editor to serve it is not.
        if (editor != null && weave != null && weave.getContainingFile() != null) {
            weave.getContainingFile().navigate(true);
        }
    }

    /**
     * Writes a weave class for the given target into the given directory.
     *
     * <p>The file is named after the target with {@value #SUFFIX} appended, and numbered when a
     * file of that name is already in the directory: {@code PaymentWeave}, then
     * {@code PaymentWeave2}. Only the directory is consulted for that, not the project's classes.
     *
     * <p>The package name is written verbatim and is not checked against the directory. An empty
     * string omits the package statement altogether; any other text is written out as
     * {@code package <text>;}. {@code HandlerInsertion} passes {@code String.valueOf} of the
     * directory's package for a file that is not a Java file, which is the four characters
     * {@code null} when the directory is outside a source root, and the created file then declares
     * {@code package null;}.
     *
     * <p>Must be called inside a write action; it adds a file to the directory.
     *
     * @param target      the class to weave, named by its qualified name in the generated
     *                    {@code @Weave}
     * @param directory   the directory to create the file in
     * @param packageName the package the created file declares, or an empty string for none
     * @return the created weave class, or {@code null} when what was added is not a Java file or
     *         does not declare exactly one class
     */
    @Nullable
    public static PsiClass createFor(@NotNull final PsiClass target,
                                     @NotNull final PsiDirectory directory,
                                     @NotNull final String packageName) {
        final Project project = target.getProject();
        final String name = nameFor(directory, String.valueOf(target.getName()));
        final PsiFile created = PsiFileFactory.getInstance(project).createFileFromText(
                name + ".java", JavaFileType.INSTANCE, sourceOf(packageName, name, target));
        final PsiElement added = directory.add(created);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
        if (!(added instanceof final PsiJavaFile weave)) {
            return null;
        }
        final PsiClass[] declared = weave.getClasses();
        return declared.length == 1 ? declared[0] : null;
    }

    /**
     * Returns the class a weave would be created for at the given element.
     *
     * <p>The innermost enclosing class is taken, and the element itself counts as one.
     *
     * @param element the element under the caret
     * @return the class, or {@code null} when there is none, when it has no name or no qualified
     *         name, when it is an interface or an annotation type, or when it already carries
     *         {@code @Weave}
     */
    @Nullable
    private static PsiClass targetAt(@NotNull final PsiElement element) {
        final PsiClass declared = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (declared == null || declared.getName() == null || declared.getQualifiedName() == null) {
            // An anonymous or local class has no qualified name, and a weave cannot name one:
            // that is AW1092, and offering the action would generate a file that cannot work.
            return null;
        }
        if (declared.isInterface() || declared.isAnnotationType()) {
            // Weaving merges members into a class body and injects into method bodies; an interface
            // has neither to offer.
            return null;
        }
        return WeaveDeclarations.annotation(declared, WeaveDeclarations.WEAVE) == null
                ? declared
                : null;
    }

    /**
     * Returns a file name for the weave that no file in the directory already uses.
     *
     * <p>A second weave on one target is ordinary, so an existing file is a reason to number rather
     * than to refuse.
     *
     * @param directory the directory the file will be created in
     * @param target    the target's simple name
     * @return the target's name with {@value #SUFFIX} appended, and a counter from {@code 2}
     *         upwards where that name is taken
     */
    @NotNull
    private static String nameFor(@NotNull final PsiDirectory directory,
                                  @NotNull final String target) {
        final String base = target + SUFFIX;
        String name = base;
        for (int suffix = 2; directory.findFile(name + ".java") != null; suffix++) {
            // A second weave on one target is ordinary — a different concern, a different priority —
            // so an existing file is a reason to number, not to refuse.
            name = base + suffix;
        }
        return name;
    }

    /**
     * Renders the source of the weave file.
     *
     * <p>The {@code @Weave} is written fully qualified next to an import of the same name, which
     * the caller's {@code shortenClassReferences} then reduces to the simple form.
     *
     * @param packageName the package to declare, or an empty string to declare none
     * @param name        the weave class's simple name
     * @param target      the class named in the generated {@code @Weave}
     * @return the file's text
     */
    @NotNull
    private static String sourceOf(@NotNull final String packageName,
                                   @NotNull final String name,
                                   @NotNull final PsiClass target) {
        final String header = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
        return header
                + "import " + WeaveDeclarations.WEAVE + ";\n\n"
                + '@' + WeaveDeclarations.WEAVE + '(' + target.getQualifiedName() + ".class)\n"
                + "public final class " + name + " {\n}\n";
    }

    /**
     * Reports that the platform is to open a write action around
     * {@link #invoke(Project, Editor, PsiElement)}.
     *
     * @return {@code true}, since a file is added to the directory
     */
    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
