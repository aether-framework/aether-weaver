package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.manifest.ManifestReader;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.parse.WeaveClassParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One module's directory of compiled classes, as the weaving goals need to see it.
 *
 * <p>This is the directory the goal rewrites in place, and it is also the only place the weaves
 * that are applied come from: {@link #manifest(DiagnosticListener)} reads
 * {@code META-INF/aether/weaves.json} from inside it and {@link #weaves(WeaveManifest,
 * DiagnosticListener)} parses each class that manifest names out of the same tree. A manifest that
 * arrived on the classpath is not read here.
 *
 * <p>Every method tolerates a missing file and none of them creates one. What it cannot tolerate is
 * a file that exists and will not open, which leaves as an {@link UncheckedIOException} everywhere
 * except when reading the manifest, where it becomes {@code AW2300} instead.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ClassDirectory {

    /** The directory of compiled classes, which need not exist. */
    private final Path root;

    /**
     * Wraps a directory of compiled classes.
     *
     * @param root the directory, which is not required to exist; must not be {@code null}
     * @throws NullPointerException if {@code root} is {@code null}
     */
    ClassDirectory(@NotNull final Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Reports whether there is anything here to weave.
     *
     * @return {@code true} when the root is a directory, and {@code false} when it is absent or is
     *         a regular file
     */
    boolean exists() {
        return Files.isDirectory(this.root);
    }

    /**
     * Reads the weave manifest this module's own build wrote.
     *
     * <p>The absence of the file is the ordinary case and is not reported: most modules of most
     * builds declare no weave. A file that exists but cannot be opened is reported as
     * {@code AW2300}, and one that opens but does not parse is reported by the manifest reader,
     * again as {@code AW2300}, or as {@code AW2301} when it states a schema version this release
     * does not read. All three return {@code null}, and the caller is expected to go on with no
     * weaves rather than to fail.
     *
     * @param listener where a manifest diagnostic is reported; must not be {@code null}
     * @return the manifest, or {@code null} when there is none, it cannot be read, or it cannot be
     *         parsed
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    @Nullable
    WeaveManifest manifest(@NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(listener, "listener");
        final Path file = this.root.resolve(WeaveManifest.RESOURCE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return ManifestReader.read(Files.readString(file, StandardCharsets.UTF_8),
                    this.root.relativize(file).toString(), listener::report);
        } catch (final IOException unreadable) {
            listener.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the weave manifest could not be read: " + unreadable.getMessage())
                    .build());
            return null;
        }
    }

    /**
     * Parses the weave classes a manifest names out of this directory.
     *
     * <p>Only the class name of each manifest entry is used; everything else about the weave is
     * read back out of the class file itself. A class the manifest names but the directory does not
     * hold is skipped in silence, since a manifest merged from several sources can name classes
     * that were never compiled here. A class that is present but carries no {@code @Weave}
     * annotation is left out of the result in silence; one the parser rejects for any other reason
     * is reported to {@code listener} and likewise left out.
     *
     * @param manifest the manifest naming the weave classes; must not be {@code null}
     * @param listener where the parser reports; must not be {@code null}
     * @return the weaves that were found and parsed, in the manifest's own order
     * @throws NullPointerException if either argument is {@code null}
     * @throws UncheckedIOException if a class file the manifest names exists and cannot be read
     */
    @NotNull
    List<WeaveClass> weaves(@NotNull final WeaveManifest manifest,
                            @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(listener, "listener");

        final WeaveClassParser parser = new WeaveClassParser(listener);
        final List<WeaveClass> weaves = new ArrayList<>();
        for (final WeaveManifest.Weave entry : manifest.weaves()) {
            final byte[] bytes = read(entry.className());
            if (bytes == null) {
                continue;
            }
            parser.parse(ClassFile.of().parse(bytes),
                            Origin.of("the module's own classes", entry.className()))
                    .ifPresent(weaves::add);
        }
        return weaves;
    }


    /**
     * Lists every class file in the tree.
     *
     * <p>Sorted by path, so that a build weaves and reports classes in the same order every time,
     * whatever order the file system walked them in.
     *
     * @return every regular file under the root whose name ends in {@code .class}
     * @throws UncheckedIOException if the tree cannot be walked
     */
    @NotNull
    List<Path> classFiles() {
        try (Stream<Path> paths = Files.walk(this.root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Derives the internal name of a class from where its file sits.
     *
     * <p>The file's own contents are not read, so a class file stored under a path that does not
     * match the name it declares yields the path.
     *
     * @param classFile a file under the root, ending in {@code .class}; must not be {@code null}
     * @return the path relative to the root, with the platform separator replaced by {@code /} and
     *         the extension removed
     * @throws NullPointerException     if {@code classFile} is {@code null}
     * @throws IllegalArgumentException if {@code classFile} cannot be made relative to the root
     */
    @NotNull
    String internalNameOf(@NotNull final Path classFile) {
        final String relative = this.root.relativize(Objects.requireNonNull(classFile, "classFile"))
                .toString().replace(java.io.File.separatorChar, '/');
        return relative.substring(0, relative.length() - ".class".length());
    }

    /**
     * Reads one class file named by its binary name.
     *
     * @param binaryName the class's binary name, such as {@code com.acme.Ledger}
     * @return its bytes, or {@code null} when the directory holds no such file
     * @throws UncheckedIOException if the file exists and cannot be read
     */
    @Nullable
    private byte[] read(@NotNull final String binaryName) {
        final Path file = this.root.resolve(binaryName.replace('.', '/') + ".class");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Returns the directory being woven.
     *
     * @return the root, exactly as it was given
     */
    @NotNull
    Path root() {
        return this.root;
    }

    /**
     * Returns a description naming the directory.
     *
     * @return the root wrapped in the class's own name
     */
    @Override
    @NotNull
    public String toString() {
        return "ClassDirectory[" + this.root + ']';
    }
}
