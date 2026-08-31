package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies the bytes of a class without loading it.
 *
 * <p>The weaver reads class files for purposes that must not disturb the program being woven:
 * fetching the body of a weave class whose members are merged into a target, and walking a
 * receiver's supertypes while an extension call is resolved. Both need the bytes and neither may
 * define a type, because defining one runs its static initialiser and fixes its shape before the
 * weaver has had a chance to change it. A class source is the seam that makes that possible: it
 * answers with bytes and never with a {@link Class}.
 *
 * <h2>How a class is named</h2>
 *
 * <p>Every method here takes an internal name: the binary name with each dot replaced by a slash,
 * and without a {@code .class} suffix — {@code com/acme/Session}, not {@code com.acme.Session} and
 * not {@code com/acme/Session.class}. The factories below append the suffix themselves when they
 * turn the name into a resource path or a file name.
 *
 * <h2>The three answers</h2>
 *
 * <p>A lookup has three outcomes, and the difference between the second and the third is the whole
 * reason the return type is an {@link Optional} rather than a nullable array:
 *
 * <ul>
 *   <li><b>Present.</b> The bytes of that class file, as read.
 *   <li><b>Empty.</b> This source does not have that class. A caller is free to look elsewhere, and
 *       {@link #orElse(ClassSource)} does exactly that.
 *   <li><b>{@link UncheckedIOException}.</b> Reading the class file itself failed with an
 *       {@link IOException} — a broken classpath entry rather than an absence. {@link
 *       #ofClassLoader(ClassLoader)} cannot always tell the two apart: a resource whose URL
 *       resolves but whose stream comes back {@code null} is reported as a miss instead, because a
 *       class loader gives no other way to distinguish "not there" from "there but unreadable".
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>The runtime driver's weaving class loader is registered parallel-capable and consults the
 * weaver — and through it the class source it was built with — from inside {@code findClass}, so a
 * source handed to that loader can be asked for classes on several threads at the same time. An
 * implementation used there must tolerate concurrent calls to {@link #find(String)}. The three
 * factories below each answer from an immutable snapshot, a class loader or the file system, and
 * hold no mutable state of their own.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * ClassSource source = ClassSource.ofMap(Map.of("com/acme/AuditWeave", weaveBytes))
 *         .orElse(ClassSource.ofPath(Path.of("target/classes")))
 *         .orElse(ClassSource.ofClassLoader(Thread.currentThread().getContextClassLoader()));
 *
 * byte[] bytes = source.find("com/acme/Session").orElse(null);   // null when nothing has it
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@FunctionalInterface
public interface ClassSource {

    /**
     * A source that has nothing.
     *
     * <p>Every lookup is empty, whatever the name. Useful as the end of a chain built with
     * {@link #orElse(ClassSource)} and as the argument to a weaver that must not read anything
     * beyond what it was given directly.
     */
    ClassSource NONE = internalName -> Optional.empty();

    /**
     * Returns the bytes of the named class, if this source has it.
     *
     * <p>An implementation returns an empty result for a class it does not have, and throws for one
     * it has and cannot read. It must not define, load or initialise the class in order to answer.
     *
     * <p>The name is not optional: the sources returned by {@link #ofClassLoader(ClassLoader)},
     * {@link #ofPath(Path)} and {@link #ofMap(Map)} each reject a {@code null} name with a
     * {@link NullPointerException}, while {@link #NONE} answers empty whatever it is given.
     *
     * @param internalName the class to look for, as an internal name such as
     *                     {@code com/acme/Session}; must not be {@code null}
     * @return the class file bytes, or an empty {@link Optional} when this source does not have
     *         that class
     * @throws UncheckedIOException if this source has the class and it cannot be read
     */
    @NotNull
    Optional<byte[]> find(@NotNull String internalName);

    /**
     * Returns a source that answers from this one and falls back to another.
     *
     * <p>The fallback is consulted only when this source is empty, and it is not consulted at all
     * when this source has an answer — which makes the order a decision rather than a preference,
     * since a fallback is often the expensive one. An exception from either source propagates
     * rather than being treated as a miss, so a broken classpath entry in front of a working one
     * still fails.
     *
     * @param fallback the source to ask when this one has nothing; must not be {@code null}
     * @return a new source consulting this one first
     * @throws NullPointerException if {@code fallback} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    default ClassSource orElse(@NotNull final ClassSource fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return internalName -> {
            final Optional<byte[]> found = find(internalName);
            return found.isPresent() ? found : fallback.find(internalName);
        };
    }

    /**
     * Returns a source reading class files as resources of a class loader.
     *
     * <p>The loader is asked for {@code internalName + ".class"} through
     * {@link ClassLoader#getResource(String)} and {@link ClassLoader#getResourceAsStream(String)}
     * only. Nothing is loaded, so a class read this way still has its static initialiser ahead of
     * it and can still be woven. Resource lookup delegates to the loader's parent exactly as class
     * loading would, so a source built from an application loader can see the platform's classes
     * as well.
     *
     * <p>A name for which the loader has no resource is a miss, and so is one whose URL resolves but
     * whose {@link ClassLoader#getResourceAsStream(String)} returns {@code null} — the loader gives
     * no way to tell that apart from a resource that is simply not there. Only a stream that opens
     * and then fails while being read throws an {@link UncheckedIOException}, naming both the
     * resource path and the URL it was found at.
     *
     * @param loader the loader whose resources hold the class files; must not be {@code null}
     * @return a new source backed by that loader
     * @throws NullPointerException if {@code loader} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    static ClassSource ofClassLoader(@NotNull final ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return internalName -> {
            final String resource = resourceOf(internalName);
            final URL url = loader.getResource(resource);
            if (url == null) {
                return Optional.empty();
            }
            try (InputStream stream = loader.getResourceAsStream(resource)) {
                // getResourceAsStream returns null both for a resource that is not there and for one
                // it fails to open, so a null here cannot be told apart from an absent class and is
                // reported as a miss rather than as a broken classpath entry.
                return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(
                        "could not read " + resource + " from " + url, unreadable);
            }
        };
    }

    /**
     * Returns a source reading class files from a directory tree.
     *
     * <p>The class {@code com/acme/Session} is looked for at {@code root/com/acme/Session.class}.
     * The directory is read on every lookup rather than indexed once, so a class written after the
     * source was created is found.
     *
     * <p>Three situations are misses rather than failures: no file at that path, a directory where
     * the class file would be, and a name that is not a legal path on this file system. The last
     * matters because an internal name is not required to be one — a name containing a character
     * the file system refuses simply is not in this directory, and letting
     * {@link InvalidPathException} out would turn that into a crash for a class the caller may not
     * even want. A regular file that cannot be read throws an {@link UncheckedIOException} naming
     * it.
     *
     * @param root the directory the package structure starts at; must not be {@code null}
     * @return a new source backed by that directory
     * @throws NullPointerException if {@code root} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    static ClassSource ofPath(@NotNull final Path root) {
        Objects.requireNonNull(root, "root");
        return internalName -> {
            final Path file;
            try {
                file = root.resolve(resourceOf(internalName));
            } catch (final InvalidPathException impossibleName) {
                // An internal name is not required to be a valid path on every file system, and
                // a name that cannot be one is simply not in this directory. Letting the exception
                // out would turn a miss into a crash for a class the caller may not even want.
                return Optional.empty();
            }
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("could not read " + file, unreadable);
            }
        };
    }

    /**
     * Returns a source answering from class files already in memory.
     *
     * <p>Keyed by internal name, and copied twice over. The map is copied when the source is built,
     * so later changes to the caller's map are not visible to the source; each array is cloned on
     * the way in and again on every lookup, so neither the caller who supplied the bytes nor a
     * caller who received them can change what a later lookup returns. A name the map does not hold
     * is a miss, and nothing here can fail with an {@link UncheckedIOException}.
     *
     * <p>A {@code null} key or a {@code null} value is refused here, at construction, with a
     * message naming the entry. Deferring the check to the lookup would surface it as a mysterious
     * miss somewhere else entirely.
     *
     * @param classes the class files by internal name; must not be {@code null}, and must hold
     *                neither a {@code null} key nor a {@code null} value
     * @return a new source over a private copy of that map
     * @throws NullPointerException if {@code classes} is {@code null}, or holds a {@code null} key
     *                              or value
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    static ClassSource ofMap(@NotNull final Map<String, byte[]> classes) {
        Objects.requireNonNull(classes, "classes");
        final Map<String, byte[]> copy = new HashMap<>(classes.size());
        classes.forEach((name, bytes) -> copy.put(
                Objects.requireNonNull(name, "a class source key"),
                Objects.requireNonNull(bytes, "the bytes of " + name).clone()));
        return internalName -> Optional.ofNullable(copy.get(
                        Objects.requireNonNull(internalName, "internalName")))
                // Cloned on the way out as well: a caller that mutated the array it was handed
                // would change what every later lookup returns, and the second caller would have no
                // way of telling.
                .map(byte[]::clone);
    }

    /**
     * Turns an internal name into the resource path of its class file.
     *
     * @param internalName the class to name; must not be {@code null}
     * @return the internal name with {@code .class} appended
     * @throws NullPointerException if {@code internalName} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    private static String resourceOf(@NotNull final String internalName) {
        return Objects.requireNonNull(internalName, "internalName") + ".class";
    }
}
