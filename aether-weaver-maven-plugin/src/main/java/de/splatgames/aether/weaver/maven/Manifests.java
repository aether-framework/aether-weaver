package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.manifest.ManifestReader;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads and merges the weave manifests a classpath carries.
 *
 * <p>Every goal of this plugin that has to know what other artefacts declare reads them here. A
 * classpath entry is either a directory or an archive, and the resource looked for in it is
 * {@code META-INF/aether/weaves.json}. An entry that holds no such resource, that is not an archive
 * at all, or that cannot be opened contributes nothing and reports nothing, since a compile
 * classpath routinely holds entries that are not archives.
 *
 * <p>The result is never {@code null}. A classpath that declares nothing yields an empty manifest
 * whose generator is {@code aether-weaver-maven-plugin}, and that generator survives every merge.
 * Where two entries declare the same weave class, or extensions on the same holder, the one earlier
 * on the classpath is kept.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class Manifests {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private Manifests() {
        throw new AssertionError("no instances");
    }

    /**
     * Reads and merges the manifests of a classpath, checking nothing about where a weave came
     * from.
     *
     * <p>Used by a caller that passes no {@code direct} set. This overload delegates to the
     * three-argument one with {@code direct} set to {@code null}, which skips the {@code AW3010}
     * check entirely.
     *
     * @param entries  the classpath entries to read, in the order they are to be searched; must not
     *                 be {@code null}
     * @param listener where a manifest diagnostic is reported; must not be {@code null}
     * @return the merged manifest, empty when nothing on the classpath declared anything
     * @throws NullPointerException if either argument is {@code null}
     */
    @NotNull
    static WeaveManifest of(@NotNull final List<Path> entries,
                            @NotNull final DiagnosticListener listener) {
        return of(entries, null, listener);
    }

    /**
     * Reads and merges the manifests of a classpath, naming any weave from an entry {@code direct}
     * does not name.
     *
     * <p>An entry that declares at least one weave and is not a member of {@code direct} is
     * reported as {@code AW3010}. This is a membership test alone, not a check of how the entry
     * actually reached the classpath: an entry the caller's own {@code direct} set fails to name is
     * reported the same way whether it truly arrived transitively or was simply left out of that
     * set. The declaration is still read and still merged; what the diagnostic changes is that it is
     * named. Declare that dependency directly if the weave is wanted, exclude it if it is not, or
     * run the {@code audit} goal to see what it does.
     *
     * <p>An extension from an entry outside {@code direct} is not reported. Only the weave count of
     * an entry is consulted, so an entry carrying extensions alone is silent however it arrived.
     *
     * @param entries  the classpath entries to read, in the order they are to be searched; must not
     *                 be {@code null}
     * @param direct   the entries this project asked for by name, or {@code null} to skip the check
     *                 entirely
     * @param listener where a manifest diagnostic is reported; must not be {@code null}
     * @return the merged manifest, empty when nothing on the classpath declared anything
     * @throws NullPointerException if {@code entries} or {@code listener} is {@code null}
     */
    @NotNull
    static WeaveManifest of(@NotNull final List<Path> entries,
                            @Nullable final Set<Path> direct,
                            @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(listener, "listener");

        WeaveManifest merged = WeaveManifest.of("aether-weaver-maven-plugin", List.of());
        for (final Path entry : entries) {
            final String text = read(entry);
            if (text == null) {
                continue;
            }
            final WeaveManifest found = ManifestReader.read(text, entry.toString(),
                    listener::report);
            if (found != null) {
                if (direct != null && !found.weaves().isEmpty() && !direct.contains(entry)) {
                    listener.report(Diagnostic.builder(
                                    DiagnosticCode.WEAVE_FROM_TRANSITIVE_DEPENDENCY)
                            .message(entry.getFileName() + " declares " + found.weaves().size()
                                    + " weave" + (found.weaves().size() == 1 ? "" : "s")
                                    + ", and nothing in this project asked for it")
                            .detail("it arrived as a dependency of a dependency: " + entry)
                            .remedy("a weave modifies this module's classes, and one that came in "
                                    + "transitively is a change nobody here chose and that shows "
                                    + "up in no diff. Declare the dependency directly if it is "
                                    + "wanted, exclude it if it is not, or run the audit goal to "
                                    + "see what it does")
                            .build());
                }
                // The accumulator wins, so the first entry on the classpath keeps its declaration.
                merged = found.merge(merged);
            }
        }
        return merged;
    }

    /**
     * Reads the manifest resource out of one classpath entry.
     *
     * <p>Silent on every failure. A directory without the resource, a path that is neither a
     * directory nor a regular file, an archive without the entry and an archive that will not open
     * are all answered the same way.
     *
     * @param entry the classpath entry to look in
     * @return the manifest text, or {@code null} when the entry carries none that can be read
     */
    private static String read(@NotNull final Path entry) {
        if (Files.isDirectory(entry)) {
            final Path file = entry.resolve(WeaveManifest.RESOURCE);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (final IOException unreadable) {
                return null;
            }
        }
        if (!Files.isRegularFile(entry)) {
            return null;
        }
        // Silent on failure, and deliberately. A compile classpath routinely contains entries
        // that are not jars at all, and an artefact this build cannot open is a problem javac is
        // about to report far better than a weaving plugin could.
        try (JarFile jar = new JarFile(entry.toFile())) {
            final JarEntry found = jar.getJarEntry(WeaveManifest.RESOURCE);
            if (found == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(found)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (final IOException notAJar) {
            return null;
        }
    }

    /**
     * Turns classpath elements into paths, dropping the ones that are not there.
     *
     * <p>An element naming nothing on disk is dropped here rather than tested for again at every
     * later stage.
     *
     * @param elements the classpath elements as Maven resolved them; must not be {@code null}
     * @return the elements that exist, as paths, in their original order
     * @throws NullPointerException if {@code elements} is {@code null}
     * @throws java.nio.file.InvalidPathException if an element cannot be converted to a path
     */
    @NotNull
    static List<Path> pathsOf(@NotNull final List<String> elements) {
        Objects.requireNonNull(elements, "elements");
        final List<Path> paths = new ArrayList<>();
        for (final String element : elements) {
            final Path path = Path.of(element);
            if (Files.exists(path)) {
                paths.add(path);
            }
        }
        return paths;
    }
}
