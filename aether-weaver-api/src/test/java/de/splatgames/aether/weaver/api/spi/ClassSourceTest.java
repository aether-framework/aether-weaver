package de.splatgames.aether.weaver.api.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the three {@link ClassSource} factories and the chaining default against the distinction the
 * whole interface rests on: an absence is empty, a breakage throws.
 *
 * <p>A class source hands the weaver the bytes of a class it needs to look at -- a superclass, an
 * interface, a type mentioned in a signature. The weaver asks for classes it may not need, so a miss
 * has to be cheap and ordinary. A classpath entry that exists and cannot be read is the opposite: it
 * is a broken deployment, and reporting it as a miss would send the weaver on to the next source and
 * produce a plan built on the wrong version of a class.
 *
 * <p>The second theme is that reading bytes never runs code. Loading a class to look at it triggers its
 * static initialiser and fixes its shape before anything has decided to weave it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class ClassSourceTest {

    /**
     * Fixes that {@link ClassSource#ofClassLoader(ClassLoader)} goes through the loader's resource
     * methods and never through {@code loadClass}.
     *
     * <p>Both cases use {@link #refusingLoader(java.util.concurrent.atomic.AtomicBoolean)}, whose
     * {@code loadClass} throws, so the guarantee is enforced rather than observed: a source that loaded
     * a class would fail the case with an error naming the class it tried to load.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("reading from a class loader never loads")
    class FromClassLoader {

        /**
         * Asserts that the bytes of a class the loader can serve as a resource come back, and that no class
         * was loaded on the way.
         *
         * <p>The content assertion pins that the stream is read to the end rather than partly, and the flag
         * assertion pins the mechanism. Loading is irreversible in a way a miss is not: a class initialised
         * to look at it stays initialised, and it happens before anyone has decided the class matters.
         *
         * @throws Exception if the loader cannot be built or closed
         */
        @Test
        @DisplayName("the bytes come back, and loadClass was not called")
        void resourcesOnly() throws Exception {
            final AtomicBoolean loaded = new AtomicBoolean();
            try (URLClassLoader loader = refusingLoader(loaded)) {
                final Optional<byte[]> found =
                        ClassSource.ofClassLoader(loader).find("fixture/Sample");

                assertThat(found).isPresent();
                assertThat(new String(found.orElseThrow(), StandardCharsets.UTF_8))
                        .isEqualTo("bytes-of-Sample");
                assertThat(loaded)
                        .as("loading a class to look at it runs its static initialiser and "
                                + "fixes its shape — irreversibly, and before anyone notices")
                        .isFalse();
            }
        }

        /**
         * Asserts that a class the loader has no resource for is an empty result.
         *
         * <p>The loader answers {@code null} from both resource methods, and the source turns that into an
         * absence rather than an exception, which is what lets a caller chain sources and try the next one.
         *
         * @throws Exception if the loader cannot be built or closed
         */
        @Test
        @DisplayName("a class the loader does not have is a miss, not a failure")
        void absenceIsEmpty() throws Exception {
            try (URLClassLoader loader = refusingLoader(new AtomicBoolean())) {
                assertThat(ClassSource.ofClassLoader(loader).find("fixture/Absent")).isEmpty();
            }
        }
    }

    /**
     * Fixes what {@link ClassSource#ofPath(java.nio.file.Path)} treats as a hit.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("reading from a directory")
    class FromPath {

        /**
         * Asserts that an internal name resolves to {@code root/com/acme/Session.class} and that its bytes
         * come back.
         *
         * <p>The mapping is the internal name with {@code .class} appended, resolved against the root, so a
         * caller passes the name the class file itself uses and never a path.
         *
         * @param root a fresh directory standing for a classpath entry
         * @throws IOException if the fixture cannot be written
         */
        @Test
        @DisplayName("a class file under the root comes back")
        void classesAreFound(@TempDir final Path root) throws IOException {
            Files.createDirectories(root.resolve("com/acme"));
            Files.writeString(root.resolve("com/acme/Session.class"), "bytes");

            assertThat(ClassSource.ofPath(root).find("com/acme/Session"))
                    .get()
                    .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Asserts that a name with no file under the root is an empty result.
         *
         * @param root an empty directory standing for a classpath entry
         */
        @Test
        @DisplayName("a missing file is a miss")
        void absenceIsEmpty(@TempDir final Path root) {
            assertThat(ClassSource.ofPath(root).find("com/acme/Absent")).isEmpty();
        }

        /**
         * Asserts that a directory named {@code Session.class} is a miss rather than a read.
         *
         * <p>The check is for a regular file, not for existence. Testing existence alone would send the
         * source into a read that fails with an exception, which would be reported as a broken classpath
         * entry for what is only a directory with an unusual name.
         *
         * @param root a fresh directory standing for a classpath entry
         * @throws IOException if the fixture cannot be created
         */
        @Test
        @DisplayName("a directory where a class file should be is a miss, not a read")
        void directoriesAreNotClassFiles(@TempDir final Path root) throws IOException {
            Files.createDirectories(root.resolve("com/acme/Session.class"));

            assertThat(ClassSource.ofPath(root).find("com/acme/Session"))
                    .as("reading it would fail with an IOException for what is plainly an absence")
                    .isEmpty();
        }

        /**
         * Asserts that an internal name no file system can express is a miss.
         *
         * <p>The name carries a NUL character, written into the string literal as a raw byte rather than
         * as an escape. Resolving it against the root throws {@link java.nio.file.InvalidPathException}
         * before any file is looked at, so this is the case that exercises the source's path-exception
         * catch rather than its regular-file check.
         *
         * <p>An internal name is whatever a class file carries and is not required to be a legal path
         * anywhere, and the weaver asks for classes it may not need, so letting the exception out would
         * turn a lookup that should have missed into a failed run.
         *
         * @param root an empty directory standing for a classpath entry
         */
        @Test
        @DisplayName("a name that cannot be a path is a miss, not a crash")
        void impossibleNamesAreMisses(@TempDir final Path root) {
            assertThat(ClassSource.ofPath(root).find("com/acme/Bad Name"))
                    .as("an internal name is not required to be a legal path on every file "
                            + "system, and letting the exception out would turn a miss into a "
                            + "crash for a class the caller may not even want")
                    .isEmpty();
        }
    }

    /**
     * Fixes that {@link ClassSource#ofMap(java.util.Map)} is a snapshot rather than a view.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("reading from a map")
    class FromMap {

        /**
         * Asserts that an entry put into the map comes back under the same internal name.
         */
        @Test
        @DisplayName("what was put in comes back")
        void classesAreFound() {
            assertThat(ClassSource.ofMap(Map.of("com/acme/Session", "bytes".getBytes(
                    StandardCharsets.UTF_8))).find("com/acme/Session"))
                    .get()
                    .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Asserts that changes made to the argument map after construction are invisible to the source,
         * whether they replace an entry or add one.
         *
         * <p>A weaver holds a source for the length of a run and asks it for the same class more than once.
         * A source that read through to a live map could answer differently on the second ask, and the plan
         * built from the first answer would already have been written.
         */
        @Test
        @DisplayName("the map is copied, so it cannot change under a running weaver")
        void theSourceIsIndependentOfItsInput() {
            final Map<String, byte[]> classes = new HashMap<>();
            classes.put("com/acme/Session", "original".getBytes(StandardCharsets.UTF_8));
            final ClassSource source = ClassSource.ofMap(classes);

            classes.put("com/acme/Session", "swapped".getBytes(StandardCharsets.UTF_8));
            classes.put("com/acme/Other", "new".getBytes(StandardCharsets.UTF_8));

            assertThat(source.find("com/acme/Session"))
                    .get()
                    .isEqualTo("original".getBytes(StandardCharsets.UTF_8));
            assertThat(source.find("com/acme/Other")).isEmpty();
        }

        /**
         * Asserts that neither mutating the array handed to the factory nor mutating the array handed back
         * changes what a later lookup returns.
         *
         * <p>Both directions matter and only one is obvious. The copy on the way in stops the caller keeping
         * a handle on the source's own bytes; the copy on the way out stops one caller's edit reaching every
         * later one, which would be a change no later caller could detect or attribute.
         */
        @Test
        @DisplayName("the arrays are copied too, in both directions")
        void theBytesCannotBeMutatedThroughTheSource() {
            final byte[] given = "original".getBytes(StandardCharsets.UTF_8);
            final ClassSource source = ClassSource.ofMap(Map.of("com/acme/Session", given));

            given[0] = 'X';
            source.find("com/acme/Session").orElseThrow()[1] = 'Y';

            assertThat(source.find("com/acme/Session"))
                    .as("a caller that mutated the array it was handed would change what every "
                            + "later lookup returns, and the next caller would have no way to tell")
                    .get()
                    .isEqualTo("original".getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Asserts that a {@code null} value in the map is refused at construction with a message naming the
         * key.
         *
         * <p>Failing here attributes the fault to the entry. Deferring it to lookup would surface a
         * {@code null} as an empty result for one class, somewhere else entirely, with nothing pointing back
         * to the map that was built wrong.
         */
        @Test
        @DisplayName("a null key or value is refused where it is introduced")
        void nullsAreRefusedEarly() {
            final Map<String, byte[]> withNull = new HashMap<>();
            withNull.put("com/acme/Session", null);

            assertThatThrownBy(() -> ClassSource.ofMap(withNull))
                    .as("failing at construction names the entry; failing at lookup would "
                            + "surface it as a mysterious miss somewhere else entirely")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("com/acme/Session");
        }
    }

    /**
     * Fixes {@link ClassSource#orElse(ClassSource)} as an ordering rather than a preference.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("chaining")
    class Chaining {

        /**
         * Asserts that when both sources hold a class, the first one's bytes are returned.
         *
         * <p>Shadowing is how a weaver models a classpath: an entry earlier in the order wins, and the
         * chain has to reproduce that or a weave would be planned against a class the run will not load.
         */
        @Test
        @DisplayName("the first source wins")
        void theFirstSourceWins() {
            final ClassSource chain = ClassSource
                    .ofMap(Map.of("a", "first".getBytes(StandardCharsets.UTF_8)))
                    .orElse(ClassSource.ofMap(Map.of("a", "second".getBytes(
                            StandardCharsets.UTF_8))));

            assertThat(chain.find("a")).get()
                    .isEqualTo("first".getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Asserts that a class only the fallback holds is found through the chain.
         */
        @Test
        @DisplayName("the fallback answers what the first does not have")
        void theFallbackFillsGaps() {
            final ClassSource chain = ClassSource.ofMap(Map.of("a", new byte[]{1}))
                    .orElse(ClassSource.ofMap(Map.of("b", new byte[]{2})));

            assertThat(chain.find("b")).get().isEqualTo(new byte[]{2});
        }

        /**
         * Asserts that the fallback is not asked at all when the first source answers.
         *
         * <p>The fallback is recorded through a lambda that appends every name it is asked for, so the
         * assertion is on the calls made rather than on the result. A fallback is often the expensive one --
         * a jar to open, a directory to walk -- and asking it anyway would make the chain a preference
         * between two answers instead of a decision that stops at the first.
         */
        @Test
        @DisplayName("the fallback is not consulted when the first has an answer")
        void theFallbackIsNotAsked() {
            final List<String> asked = new ArrayList<>();
            final ClassSource chain = ClassSource.ofMap(Map.of("a", new byte[]{1}))
                    .orElse(internalName -> {
                        asked.add(internalName);
                        return Optional.empty();
                    });

            chain.find("a");

            assertThat(asked)
                    .as("a fallback is often the expensive one — a jar, a network fetch — and "
                            + "asking it anyway would make the ordering a preference rather than "
                            + "a decision")
                    .isEmpty();
        }

        /**
         * Asserts that {@link ClassSource#NONE} answers empty for any name, and that a chain beginning with
         * it still reaches its fallback.
         *
         * <p>The second assertion is the one with content: the constant is an ordinary link rather than a
         * terminator, so a caller can start a chain with it unconditionally and append the sources it
         * actually has.
         */
        @Test
        @DisplayName("NONE has nothing and terminates a chain")
        void noneIsEmpty() {
            assertThat(ClassSource.NONE.find("anything")).isEmpty();
            assertThat(ClassSource.NONE.orElse(ClassSource.ofMap(Map.of("a", new byte[]{1})))
                    .find("a")).isPresent();
        }
    }

    /**
     * Fixes the line between an absence and a breakage.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("when a source has a class it cannot read")
    class Broken {

        /**
         * Asserts that a class file that exists and cannot be read throws an {@link UncheckedIOException}.
         *
         * <p>This is the case the whole empty-means-absent convention has to be reconciled with. A miss sends
         * the caller to the next source, so an unreadable entry reported as a miss would be woven around
         * silently; an exception names the file and stops the run.
         *
         * <p>The case does not always execute. It aborts when the file system has no POSIX permissions to
         * revoke, and again when the file stays readable after they are revoked, which is what happens for a
         * user that permissions do not constrain.
         *
         * @param root a fresh directory standing for a classpath entry
         * @throws IOException if the fixture cannot be written
         */
        @Test
        @DisplayName("it throws rather than reporting a miss")
        void unreadableIsNotAbsent(@TempDir final Path root) throws IOException {
            final Path file = root.resolve("com/acme/Session.class");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "bytes");
            assumeUnreadable(file);

            assertThatThrownBy(() -> ClassSource.ofPath(root).find("com/acme/Session"))
                    .as("a miss sends the caller looking elsewhere; an unreadable file is a "
                            + "broken classpath entry and has to say so")
                    .isInstanceOf(UncheckedIOException.class);
        }

        /**
         * Makes a file unreadable, aborting the calling case when that cannot be arranged.
         *
         * <p>Both outcomes are aborts rather than failures, because neither says anything about
         * {@link ClassSource}: a file system without POSIX permissions cannot express the state, and a user
         * that bypasses permissions cannot observe it.
         *
         * @param file the file to make unreadable
         * @throws IOException if the permissions cannot be set
         */
        private static void assumeUnreadable(final Path file) throws IOException {
            try {
                Files.setPosixFilePermissions(file, java.util.Set.of());
            } catch (final UnsupportedOperationException notPosix) {
                org.junit.jupiter.api.Assumptions.abort(
                        "the file system cannot make a file unreadable");
            }
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file),
                    "running as a user that ignores file permissions");
        }
    }

    // -------------------------------------------------------------------------------------

    /**
     * Returns a loader that serves one class as a resource and treats loading it as an error.
     *
     * <p>Built with no URLs and no parent, so it can answer nothing except what its overrides answer,
     * and the resource it does serve is text rather than a class file: nothing here parses the bytes, so
     * a readable string makes a failure easier to read than a valid class would.
     *
     * @param loaded set when {@code loadClass} is reached, so that a case can assert it was not
     * @return the loader, which the caller closes
     */
    private static URLClassLoader refusingLoader(final AtomicBoolean loaded) {
        return new URLClassLoader(new URL[0], null) {
            /**
             * Answers a fabricated URL for the one fixture resource and {@code null} for everything else.
             *
             * @param name the resource name
             * @return the fixture URL, or {@code null}
             */
            @Override
            public URL getResource(final String name) {
                return "fixture/Sample.class".equals(name) ? sampleUrl() : null;
            }

            /**
             * Answers a stream over the fixture bytes for the one fixture resource, and {@code null}
             * otherwise.
             *
             * @param name the resource name
             * @return a stream over the fixture bytes, or {@code null}
             */
            @Override
            public InputStream getResourceAsStream(final String name) {
                return "fixture/Sample.class".equals(name)
                        ? new java.io.ByteArrayInputStream(
                                "bytes-of-Sample".getBytes(StandardCharsets.UTF_8))
                        : null;
            }

            /**
             * Fails the calling case: a class source has no reason to load anything.
             *
             * @param name    the class being loaded
             * @param resolve whether the caller asked for resolution
             * @return never returns
             */
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) {
                loaded.set(true);
                throw new AssertionError("a class source must never load a class: " + name);
            }
        };
    }

    /**
     * Returns a URL for the fixture resource.
     *
     * <p>Nothing dereferences it. {@link ClassSource#ofClassLoader(ClassLoader)} uses the URL only to
     * decide that the resource exists and to name it in a failure message, so the location need not be
     * one that resolves.
     *
     * @return the fixture URL
     */
    private static URL sampleUrl() {
        try {
            return java.net.URI.create("file:/fixture/Sample.class").toURL();
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }
}
