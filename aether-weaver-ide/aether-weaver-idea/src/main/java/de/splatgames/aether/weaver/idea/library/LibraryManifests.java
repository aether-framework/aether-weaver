package de.splatgames.aether.weaver.idea.library;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.manifest.ManifestReader;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the weave manifest out of every library the project depends on.
 *
 * <p>A weave that ships inside a jar modifies this project and appears nowhere in its source.
 * {@value de.splatgames.aether.weaver.api.manifest.WeaveManifest#RESOURCE} is what it leaves
 * behind, and this is the only thing that reads it; {@link LibraryWeaves} and
 * {@link LibraryExtensions} both project their answers out of what comes back here.
 *
 * <p>Library roots only, in the order {@link OrderEnumerator} returns them. A root that is a
 * directory is searched directly and any other is opened as a jar, so an exploded classpath entry
 * and a packaged one are both read.
 *
 * <h2>What a broken manifest costs</h2>
 *
 * <p>Nothing is reported. A root whose manifest cannot be read, cannot be parsed, or is rejected by
 * {@link ManifestReader} is skipped and the remaining roots still answer. A diagnostic here would
 * name a file the reader did not write, in a jar they cannot edit, on every keystroke.
 *
 * <h2>Caching</h2>
 *
 * <p>The result is cached on the project and invalidated by {@link ProjectRootManager}, so it is
 * re-read when the dependencies change and not when a file is edited. No index is consulted, no
 * symbol is resolved and no dumb-mode check is made.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class LibraryManifests {

    /**
     * Prevents instantiation.
     *
     * @throws AssertionError always
     */
    private LibraryManifests() {
        throw new AssertionError("no instances");
    }

    /**
     * One library's manifest, with the root it was read from.
     *
     * @param manifest the parsed manifest
     * @param origin   the presentable path of the library root, which is what a reader is shown and
     *                 what {@link ManifestReader} was given to name in a diagnostic
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Parsed(@NotNull WeaveManifest manifest, @NotNull String origin) {
    }

    /**
     * Returns the manifest of every library that has one.
     *
     * <p>One entry per library root, in dependency order. Two libraries shipping the same weave
     * both appear here; deduplication, where it happens at all, happens in the caller.
     *
     * @param project the project whose dependencies are read
     * @return the manifests, empty when no library ships one
     */
    @Unmodifiable
    @NotNull
    public static List<Parsed> of(@NotNull final Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project,
                () -> CachedValueProvider.Result.create(read(project),
                        ProjectRootManager.getInstance(project)));
    }

    /**
     * Reads every library root once.
     *
     * @param project the project whose dependencies are read
     * @return the manifests that were found and parsed, in the order the roots were enumerated
     */
    @Unmodifiable
    @NotNull
    private static List<Parsed> read(@NotNull final Project project) {
        final List<Parsed> found = new ArrayList<>();
        for (final VirtualFile root
                : OrderEnumerator.orderEntries(project).librariesOnly().classes().getRoots()) {
            final VirtualFile manifest = insideOf(root);
            if (manifest == null || !manifest.isValid()) {
                continue;
            }
            final String origin = root.getPresentableUrl();
            final WeaveManifest parsed = parse(manifest, origin);
            if (parsed != null) {
                found.add(new Parsed(parsed, origin));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Locates the manifest inside one library root.
     *
     * @param root the library root, a directory or an archive
     * @return the manifest file, or {@code null} when the root is not a directory and cannot be
     *         opened as a jar, or holds no manifest at
     *         {@value de.splatgames.aether.weaver.api.manifest.WeaveManifest#RESOURCE}
     */
    @Nullable
    private static VirtualFile insideOf(@NotNull final VirtualFile root) {
        final VirtualFile contents = root.isDirectory()
                ? root
                : JarFileSystem.getInstance().getJarRootForLocalFile(root);
        return contents == null ? null : contents.findFileByRelativePath(WeaveManifest.RESOURCE);
    }

    /**
     * Parses one manifest file as UTF-8.
     *
     * <p>Every exception is swallowed, which covers a file that cannot be read as well as a
     * document {@link ManifestReader} refuses outright.
     *
     * @param manifest the manifest file
     * @param origin   the presentable path of the root, passed on as the document's origin
     * @return the manifest, or {@code null} when it could not be read or parsed
     */
    @Nullable
    private static WeaveManifest parse(@NotNull final VirtualFile manifest,
                                       @NotNull final String origin) {
        try {
            final String text = new String(manifest.contentsToByteArray(), StandardCharsets.UTF_8);
            return ManifestReader.read(text, origin, new Ignored());
        } catch (final Exception unreadable) {
            return null;
        }
    }

    /**
     * The reporter given to {@link ManifestReader}, which drops everything it is handed.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Ignored implements Reporter {

        /** Creates the reporter, which holds no state. */
        Ignored() {
            // Stateless.
        }

        /**
         * Discards the diagnostic.
         *
         * @param diagnostic the diagnostic to discard
         */
        @Override
        public void report(@NotNull final Diagnostic diagnostic) {
            // Deliberately nothing; see this class's documentation.
        }
    }
}
