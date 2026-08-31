package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.spi.ClassSource;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads classes from a classpath, and from the running JVM's runtime image when the classpath has
 * none.
 *
 * <p>This is what the extension goals hand the engine as its class source: an extension names a
 * receiver, and a receiver is very often a JDK type that no classpath entry holds. Falling back to
 * {@code jrt:/} is what lets {@code java.lang.String} be read, checked for a method that would
 * shadow the extension, and patched.
 *
 * <p>{@link #moduleOf(String)} answers the other question the stub goal has, which is whether the
 * class came out of a module of that image and therefore needs {@code --patch-module} rather than a
 * classpath entry placed ahead of the dependencies.
 *
 * <p>Every failure to read is answered as absence. An entry that has gone missing, an archive that
 * will not open and a runtime image that cannot be walked are indistinguishable here from a place
 * that simply does not hold the class, and none of them is reported.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class Receivers implements ClassSource {

    /** The directory of the runtime image whose immediate children are the modules. */
    private static final String MODULES = "/modules";

    /** The entries to search first, in order; each is a directory or an archive. */
    private final List<Path> classpath;

    /** The runtime image of this JVM, or {@code null} when it could not be opened. */
    private final @Nullable FileSystem image;

    /** Which image module supplied a class, by internal name, with {@code null} for none. */
    private final Map<String, String> modules = new LinkedHashMap<>();

    /**
     * Prepares a source over the given classpath, opening the runtime image once.
     *
     * @param classpath the entries to search, in the order they are to be searched; copied, and
     *                  must not be {@code null}
     * @throws NullPointerException if {@code classpath} is {@code null} or holds a {@code null}
     */
    Receivers(@NotNull final List<Path> classpath) {
        this.classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
        this.image = runtimeImage();
    }

    /**
     * Opens the runtime image of the JVM this build runs on.
     *
     * <p>An already-open {@code jrt:/} file system is reused; one is created only when there is
     * none, and any failure of either step is answered with {@code null} rather than raised.
     *
     * @return the image, or {@code null} when it can be neither found nor opened
     */
    @Nullable
    private static FileSystem runtimeImage() {
        try {
            return FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (final FileSystemNotFoundException notOpen) {
            try {
                return FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
            } catch (final IOException | RuntimeException unavailable) {
                return null;
            }
        } catch (final RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Finds a class on the classpath, and then in the runtime image.
     *
     * <p>The first classpath entry that holds it wins, so an entry earlier in the list shadows a
     * later one. The image is consulted only after every entry has been tried.
     *
     * @param internalName the class's internal name, such as {@code java/lang/String}; must not be
     *                     {@code null}
     * @return its bytes, or empty when no entry and no module holds it
     * @throws NullPointerException if {@code internalName} is {@code null}
     */
    @Override
    @NotNull
    public Optional<byte[]> find(@NotNull final String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        final String relative = internalName + ".class";

        for (final Path entry : this.classpath) {
            final Optional<byte[]> found = readFrom(entry, relative);
            if (found.isPresent()) {
                return found;
            }
        }
        return fromImage(internalName, relative);
    }

    /**
     * Names the module of the runtime image that holds a class.
     *
     * <p>The answer describes the image alone. A class the classpath supplied is still looked for
     * in the image, and one the image does not hold answers {@code null} whether it was found on
     * the classpath, found nowhere, or unreachable because the image could not be opened.
     *
     * <p>The answer is remembered per name, {@code null} included, so the image is walked at most
     * once for each class asked about here. {@link #find(String)} does not consult that memory and
     * walks the image again on every call the classpath does not answer.
     *
     * @param internalName the class's internal name; must not be {@code null}
     * @return the module's name, or {@code null} when the image does not hold the class
     * @throws NullPointerException if {@code internalName} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    String moduleOf(@NotNull final String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        if (!this.modules.containsKey(internalName)) {
            // Resolved by find(…), which fills the map as a side effect of looking in the image.
            // Asked separately it would have to walk the modules twice for every receiver.
            fromImage(internalName, internalName + ".class");
        }
        return this.modules.get(internalName);
    }

    /**
     * Reads one file from one classpath entry.
     *
     * @param entry    the entry to look in, a directory or an archive
     * @param relative the path within it, such as {@code java/lang/String.class}
     * @return the bytes, or empty when the entry does not hold the file, is neither a directory nor
     *         a regular file, or cannot be read
     */
    @NotNull
    private static Optional<byte[]> readFrom(@NotNull final Path entry,
                                             @NotNull final String relative) {
        if (Files.isDirectory(entry)) {
            final Path file = entry.resolve(relative);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (final IOException unreadable) {
                return Optional.empty();
            }
        }
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            final JarEntry found = jar.getJarEntry(relative);
            if (found == null) {
                return Optional.empty();
            }
            try (InputStream in = jar.getInputStream(found)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (final IOException notAJar) {
            return Optional.empty();
        }
    }

    /**
     * Reads one class out of the runtime image and records which module it came from.
     *
     * <p>The modules are searched in the order the image's directory listing yields them and the
     * first holding the file wins, so a class present in two modules is attributed to whichever
     * that listing named first. The outcome is recorded either way, which is what makes
     * {@link #moduleOf(String)} free after a lookup.
     *
     * @param internalName the class's internal name, under which the outcome is recorded
     * @param relative     that name with {@code .class} appended
     * @return the bytes, or empty when no module holds the class or the image is absent or
     *         unreadable
     */
    @NotNull
    private Optional<byte[]> fromImage(@NotNull final String internalName,
                                       @NotNull final String relative) {
        if (this.image == null) {
            this.modules.put(internalName, null);
            return Optional.empty();
        }
        try (DirectoryStream<Path> modules =
                     Files.newDirectoryStream(this.image.getPath(MODULES))) {
            for (final Path module : modules) {
                final Path file = module.resolve(relative);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                this.modules.put(internalName, module.getFileName().toString());
                return Optional.of(Files.readAllBytes(file));
            }
        } catch (final IOException unreadable) {
            // Fall through: an unreadable image is indistinguishable, from here, from one that
            // simply does not contain the receiver.
        }
        this.modules.put(internalName, null);
        return Optional.empty();
    }
}
