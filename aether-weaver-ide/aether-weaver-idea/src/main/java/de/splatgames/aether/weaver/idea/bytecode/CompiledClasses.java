package de.splatgames.aether.weaver.idea.bytecode;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.LibraryOrSdkOrderEntry;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.ClassUtil;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds the class file behind a class in the editor and parses it into the views the engine reads.
 *
 * <p>Everything the plugin says about instructions is said about compiled bytes, so every feature
 * that names an operation starts here. The class in the editor is not the evidence: it is only the
 * handle used to find the artefact, which may be a compiler output file, a class file inside a
 * library, or the file behind a decompiled class the user is reading.
 *
 * <h2>Refusing rather than answering</h2>
 *
 * <p>A lookup fails as often as it succeeds, and it carries a {@code reason} written for the user
 * rather than a {@code null} for the caller to explain. The refusals are distinct because the
 * actions they ask for are:
 *
 * <ul>
 *   <li>the element is a non-physical copy, which has no compiler output behind it in any project;
 *   <li>the source has unsaved changes, so no class file describes what is on screen;
 *   <li>nothing is compiled, and the project needs building;
 *   <li>the source is newer than the class file, so the operations would be the previous build's;
 *   <li>the class file exists and cannot be parsed.
 * </ul>
 *
 * <p>Staleness and unsaved changes are checked only for a class the user could have edited. A class
 * from a library or one read as decompiled text has no source to be newer than it, and attached
 * library sources are treated the same way: a timestamp inside a sources jar records when it was
 * packaged, so comparing it would report a stale build for a pair that simply came out of two
 * different archives.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CompiledClasses {

    /** The extension every artefact searched for on disk and in a library root carries. */
    private static final String CLASS_SUFFIX = ".class";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private CompiledClasses() {
        throw new AssertionError("no instances");
    }

    /**
     * The outcome of looking for one class's compiled form.
     *
     * <p>Exactly one component carries information: a lookup that found the class file has a view
     * and a blank reason, and one that did not has no view and a reason phrased for the user.
     *
     * @param view   the parsed class, or {@code null} when it could not be obtained
     * @param reason why not, phrased as a clause that can be shown to the user; empty when
     *               {@code view} is present
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Lookup(@Nullable TargetView view, @NotNull String reason) {

        /**
         * Reports whether the class file was found and parsed.
         *
         * @return {@code true} when {@link #view()} is present
         */
        @Contract(pure = true)
        public boolean isAvailable() {
            return this.view != null;
        }
    }

    /**
     * The outcome of looking for one method's compiled form.
     *
     * @param method the parsed method, or {@code null} when it could not be obtained
     * @param reason why not, phrased as a clause that can be shown to the user; empty when
     *               {@code method} is present
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record MethodLookup(@Nullable MethodView method, @NotNull String reason) {

        /**
         * Reports whether the method was found in the class file.
         *
         * @return {@code true} when {@link #method()} is present
         */
        @Contract(pure = true)
        public boolean isAvailable() {
            return this.method != null;
        }
    }

    /**
     * Finds the compiled form of one method.
     *
     * <p>Matched on name and descriptor together, never on name alone: two overloads hold entirely
     * different instructions, and answering with the wrong one would place every later offer in a
     * method the author is not looking at.
     *
     * <p>A method the class file does not declare is reported as the artefact predating the source,
     * which is reported separately from an absent class file because saying "build the project"
     * would be advice the user has already followed. The match is by name, and a constructor's
     * {@link PsiMethod#getName()} is the class's simple name rather than {@code <init>}, so this
     * reason is also given for a caret in an up-to-date constructor, where it is wrong; nothing here
     * distinguishes that case from a genuinely stale signature.
     *
     * @param target the method in the editor; must not be {@code null}
     * @return the parsed method, or a lookup carrying the reason it could not be obtained, which is
     *         the containing class's reason when that is what failed
     * @throws NullPointerException if {@code target} is {@code null}
     */
    @NotNull
    public static MethodLookup methodOf(@NotNull final PsiMethod target) {
        final PsiClass owner = target.getContainingClass();
        if (owner == null) {
            return new MethodLookup(null, "the method is not declared in a class");
        }
        final Lookup lookup = of(owner);
        if (!lookup.isAvailable()) {
            return new MethodLookup(null, lookup.reason());
        }
        // Name and descriptor together: two overloads hold entirely different instructions.
        final String descriptor = ClassUtil.getAsmMethodSignature(target);
        for (final MethodView candidate : lookup.view().methods()) {
            if (candidate.name().equals(target.getName())
                    && candidate.type().descriptorString().equals(descriptor)) {
                return new MethodLookup(candidate, "");
            }
        }
        return new MethodLookup(null, "the class file for " + nameOf(owner) + " declares no "
                + target.getName() + descriptor + " — it was compiled before this method existed, "
                + "or before its signature changed");
    }

    /**
     * Finds and parses the class file behind a class in the editor.
     *
     * <p>The checks are made in the order a user would want them reported: a non-physical copy
     * first, then unsaved changes, then a missing artefact, then a stale one, and only then the
     * parse. Each answers with its own reason, so the caller shows the first thing that is actually
     * wrong rather than the last thing that failed.
     *
     * <p>Where the bytes are found depends on what the class is. A class read as decompiled text
     * and a class whose source belongs to a library are taken from the library root; a class of the
     * project is taken from its module's compiler output, production first and then tests, because
     * a weave targeting a test class is unusual and legal and looking only in the production output
     * would report it as not compiled.
     *
     * <p>Nothing thrown by the class file parser escapes: a file that is truncated or newer than
     * this runtime becomes a refusal carrying the parser's message rather than an exception in a
     * dialog.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return the parsed class, or a lookup carrying the reason it could not be obtained
     * @throws NullPointerException if {@code target} is {@code null}
     */
    @NotNull
    public static Lookup of(@NotNull final PsiClass target) {
        // A copy of a file is not the file. The platform hands non-physical copies to intention
        // previews and to a few refactorings, and such a copy has no VirtualFile beneath it and no
        // module around it — so every lookup below answers "not compiled", for a project that is.
        // That is exactly what happened: the preview resolved the caret in the copy it was given
        // and could therefore never find a class file, in any project, ever. Said plainly here
        // rather than folded into "build the project", which is advice that would not have helped.
        if (!target.isPhysical()) {
            return new Lookup(null, "this is a copy of " + nameOf(target) + " rather than the file "
                    + "the project holds, and a copy has no compiler output behind it");
        }
        final String unsaved = unsavedReasonFor(target);
        if (unsaved != null) {
            return new Lookup(null, unsaved);
        }
        final Artefact compiled = artefactOf(target);
        if (compiled == null) {
            return new Lookup(null, "no compiled class file for " + nameOf(target)
                    + " — build the project and try again");
        }
        if (isStale(target, compiled)) {
            return new Lookup(null, nameOf(target) + " has been edited since it was last compiled"
                    + " — the operations in it would be the previous build's");
        }
        try {
            final ClassModel model = ClassFile.of().parse(compiled.bytes());
            return new Lookup(ModelViews.of(model), "");
        } catch (final Exception unreadable) {
            // A class file the platform can hand over but the ClassFile API cannot parse is either
            // truncated or newer than this runtime. Neither is the user's to fix here, and neither
            // is worth an exception escaping into a dialog.
            return new Lookup(null, "the class file for " + nameOf(target) + " could not be read: "
                    + unreadable.getMessage());
        }
    }

    /**
     * The bytes of a class file and when they were last written.
     *
     * <p>The timestamp travels with the bytes because the two come from either the virtual file
     * system or the file system, and only the reader knows which; a caller comparing a source's
     * timestamp against a class file's must not have to ask again where the class file was found.
     *
     * @param bytes     the class file's contents
     * @param timestamp the artefact's modification time in milliseconds, on the same scale as
     *                  {@link VirtualFile#getTimeStamp()}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Artefact(byte @NotNull [] bytes, long timestamp) {
    }

    /**
     * Reads the bytes behind a class, from wherever they are.
     *
     * <p>The virtual file system is asked first, and the file system is consulted for anything else
     * except a class read as decompiled text: such a class is the artefact, so if the platform's own
     * file for it cannot be read there is nowhere else to look. A class whose source the project
     * index reports as library source reaches the file-system fallback along with an ordinary
     * project class, even though it has no source in the project either.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return the artefact, or {@code null} when none was found or it could not be read
     */
    @Nullable
    private static Artefact artefactOf(@NotNull final PsiClass target) {
        final VirtualFile known = fileOf(target);
        if (known != null && known.isValid()) {
            try {
                return new Artefact(known.contentsToByteArray(), known.getTimeStamp());
            } catch (final IOException unreadable) {
                return null;
            }
        }
        return target instanceof PsiCompiledElement ? null : onDisk(target);
    }

    /**
     * Reads the class file from the module's compiler output through the file system.
     *
     * <p>The fallback for an output the virtual file system does not show. Production output is
     * tried before test output, matching the search {@code fileOf} makes.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return the artefact, or {@code null} when the class has no binary name, its module has no
     *         compiler output, no output holds the file, or reading one throws
     */
    @Nullable
    private static Artefact onDisk(@NotNull final PsiClass target) {
        final String binaryName = ClassUtil.getJVMClassName(target);
        final Module module = ModuleUtilCore.findModuleForPsiElement(target);
        final CompilerModuleExtension output =
                module == null ? null : CompilerModuleExtension.getInstance(module);
        if (binaryName == null || output == null) {
            return null;
        }
        final String relative = binaryName.replace('.', '/') + CLASS_SUFFIX;
        for (final String url : new String[]{output.getCompilerOutputUrl(),
                output.getCompilerOutputUrlForTests()}) {
            if (url == null) {
                continue;
            }
            final Path candidate = Path.of(VfsUtilCore.urlToPath(url)).resolve(relative);
            try {
                if (Files.isRegularFile(candidate)) {
                    return new Artefact(Files.readAllBytes(candidate),
                            Files.getLastModifiedTime(candidate).toMillis());
                }
            } catch (final IOException | RuntimeException unreadable) {
                // An output directory that cannot be read is one this cannot answer from. The next
                // one might, and if neither can the caller is told there is no class file.
                return null;
            }
        }
        return null;
    }

    /**
     * Finds the virtual file holding the class's compiled form.
     *
     * <p>A class the user is reading as decompiled text is its own artefact, so its containing
     * file is returned directly. Anything else is looked for by binary name: first in the roots of
     * the libraries that own it, then in its module's compiler output.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return the class file, or {@code null} when the class has no binary name, no library or
     *         output root holds it, or its module has no compiler output
     */
    @Nullable
    private static VirtualFile fileOf(@NotNull final PsiClass target) {
        if (target instanceof PsiCompiledElement) {
            final PsiFile file = target.getContainingFile();
            return file == null ? null : file.getVirtualFile();
        }

        final String binaryName = ClassUtil.getJVMClassName(target);
        if (binaryName == null) {
            return null;
        }
        final VirtualFile fromLibrary = inLibraryOf(target, binaryName);
        if (fromLibrary != null) {
            return fromLibrary;
        }
        final Module module = ModuleUtilCore.findModuleForPsiElement(target);
        final CompilerModuleExtension output =
                module == null ? null : CompilerModuleExtension.getInstance(module);
        if (output == null) {
            return null;
        }

        // Both outputs, production first. A weave targeting a test class is unusual and entirely
        // legal, and looking only in the production output would answer "not compiled" for it.
        final String relative = binaryName.replace('.', '/') + CLASS_SUFFIX;
        for (final VirtualFile root
                : new VirtualFile[]{output.getCompilerOutputPath(), output.getCompilerOutputPathForTests()}) {
            final VirtualFile found = root == null ? null : root.findFileByRelativePath(relative);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Finds the class file in the classes roots of the libraries a library source belongs to.
     *
     * <p>Applies only to a class whose source file the project index reports as library source: a
     * dependency the user opened with sources attached is an ordinary source file to the platform
     * and would otherwise be looked for in a compiler output that never held it.
     *
     * @param target     the class in the editor; must not be {@code null}
     * @param binaryName the class's binary name, with {@code .} separators; must not be
     *                   {@code null}
     * @return the class file, or {@code null} when the source is not library source or no classes
     *         root of an owning library or SDK holds it
     */
    @Nullable
    private static VirtualFile inLibraryOf(@NotNull final PsiClass target,
                                           @NotNull final String binaryName) {
        final PsiFile source = target.getContainingFile();
        final VirtualFile file = source == null ? null : source.getVirtualFile();
        if (file == null) {
            return null;
        }
        final ProjectFileIndex index = ProjectFileIndex.getInstance(target.getProject());
        if (!index.isInLibrarySource(file)) {
            return null;
        }

        final String relative = binaryName.replace('.', '/') + CLASS_SUFFIX;
        for (final OrderEntry entry : index.getOrderEntriesForFile(file)) {
            if (!(entry instanceof final LibraryOrSdkOrderEntry libraryOrSdk)) {
                continue;
            }
            for (final VirtualFile root : libraryOrSdk.getRootFiles(OrderRootType.CLASSES)) {
                final VirtualFile found = root.findFileByRelativePath(relative);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Reports whether the class is something the user cannot have edited.
     *
     * <p>Decides which of the freshness checks apply. Both forms count: a class read as decompiled
     * text, and a class whose source file the index reports as library source.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return {@code true} when the class comes from a dependency rather than from the project's
     *         own sources
     */
    private static boolean isReadOnlyDependency(@NotNull final PsiClass target) {
        if (target instanceof PsiCompiledElement) {
            return true;
        }
        final PsiFile source = target.getContainingFile();
        final VirtualFile file = source == null ? null : source.getVirtualFile();
        return file != null
                && ProjectFileIndex.getInstance(target.getProject()).isInLibrarySource(file);
    }

    /**
     * Reports why an unsaved editor makes the class file irrelevant.
     *
     * <p>Asked before the artefact is looked for, because an up-to-date class file for the last
     * saved text would be found and would describe something other than what is on screen. Only a
     * document the platform has already cached is consulted; a file nobody has opened cannot hold
     * unsaved changes.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return the reason to show the user, or {@code null} when the class is a dependency, has no
     *         file, or has no unsaved document
     */
    @Nullable
    private static String unsavedReasonFor(@NotNull final PsiClass target) {
        if (isReadOnlyDependency(target)) {
            return null;
        }
        final PsiFile source = target.getContainingFile();
        final VirtualFile file = source == null ? null : source.getVirtualFile();
        if (file == null) {
            return null;
        }
        final FileDocumentManager documents = FileDocumentManager.getInstance();
        final Document open = documents.getCachedDocument(file);
        return open != null && documents.isDocumentUnsaved(open)
                ? nameOf(target) + " has unsaved changes, so no class file describes what is on "
                        + "screen — save it and build"
                : null;
    }

    /**
     * Reports whether the source has been edited since the artefact was written.
     *
     * <p>A strict comparison of modification times, so a class file written in the same
     * millisecond as its source counts as current.
     *
     * @param target   the class in the editor; must not be {@code null}
     * @param compiled the artefact found for it; must not be {@code null}
     * @return {@code true} when the source is newer; always {@code false} for a dependency and for
     *         a class with no source file
     */
    private static boolean isStale(@NotNull final PsiClass target,
                                   @NotNull final Artefact compiled) {
        if (isReadOnlyDependency(target)) {
            // There is no source to be newer than it; the dependency ships what it ships. Attached
            // library source counts here too: a timestamp inside a sources jar says when it was
            // packaged, not when anyone edited it, and comparing it with the class file's would
            // report a stale build for a pair that simply came out of two different archives.
            return false;
        }
        final PsiFile source = target.getContainingFile();
        final VirtualFile file = source == null ? null : source.getVirtualFile();
        return file != null && file.getTimeStamp() > compiled.timestamp();
    }

    /**
     * Names the class for a message shown to the user.
     *
     * @param target the class in the editor; must not be {@code null}
     * @return the qualified name, falling back to the simple name and then to the text
     *         {@code null} for an anonymous or unnamed class
     */
    @NotNull
    private static String nameOf(@NotNull final PsiClass target) {
        final String qualified = target.getQualifiedName();
        return qualified != null ? qualified : String.valueOf(target.getName());
    }
}
