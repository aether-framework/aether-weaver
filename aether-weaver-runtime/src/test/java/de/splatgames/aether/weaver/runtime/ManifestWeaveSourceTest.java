package de.splatgames.aether.weaver.runtime;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.WeaveCandidate;
import de.splatgames.aether.weaver.api.spi.DiscoveryContext;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestWeaveSourceTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final List<URLClassLoader> opened = new ArrayList<>();

    @Nested
    @DisplayName("weaves from two artefacts coexist")
    class TwoArtefacts {

        @Test
        @DisplayName("both are found, in classpath order")
        void bothAreFound(@TempDir final Path work) throws Exception {
            final URL first = jar(work, "library.jar", "com.acme.LibraryAudit", "library-bytes");
            final URL second = jar(work, "app.jar", "com.acme.AppTracing", "app-bytes");

            assertThat(discover(first, second))
                    .as("a weave shipped in a library and a weave written in the application must "
                            + "coexist without either knowing about the other")
                    .extracting(WeaveCandidate::className)
                    .containsExactly("com.acme.LibraryAudit", "com.acme.AppTracing");
        }

        @Test
        @DisplayName("each candidate reads its bytes from its own artefact")
        void eachReadsItsOwn(@TempDir final Path work) throws Exception {
            // The same binary name in both jars, with different contents. Nothing forbids this:
            // a package name is a convention, not a namespace the classpath enforces.
            final URL first = jar(work, "one.jar", "com.acme.Audit", "bytes-from-one");
            final URL second = jar(work, "two.jar", "com.acme.Audit", "bytes-from-two");

            final List<WeaveCandidate> found = discover(first, second);

            assertThat(found).hasSize(2);
            assertThat(contentOf(found.get(0)))
                    .as("a classpath-wide source would hand both declarations the bytes of "
                            + "whichever copy came first, and the engine would apply one "
                            + "library's weave under the other's name")
                    .isEqualTo("bytes-from-one");
            assertThat(contentOf(found.get(1))).isEqualTo("bytes-from-two");
        }

        @Test
        @DisplayName("AW2303 — the duplicate name is reported, not silently accepted")
        void duplicatesAreReported(@TempDir final Path work) throws Exception {
            discover(jar(work, "one.jar", "com.acme.Audit", "one"),
                    jar(work, "two.jar", "com.acme.Audit", "two"));

            assertThat(codes()).containsExactly("AW2303");
            assertThat(this.reported().getFirst().message())
                    .as("naming both artefacts is what makes the duplicate findable")
                    .contains("one.jar")
                    .contains("two.jar");
        }

        private List<Diagnostic> reported() {
            return ManifestWeaveSourceTest.this.reported;
        }
    }

    @Nested
    @DisplayName("a directory root")
    class Directories {

        @Test
        @DisplayName("a manifest in a directory works like one in a jar")
        void directoriesAreRootsToo(@TempDir final Path work) throws Exception {
            final Path root = work.resolve("classes");
            writeManifest(root, "com.acme.Audit");
            writeClass(root, "com.acme.Audit", "directory-bytes");

            final List<WeaveCandidate> found = discover(root.toUri().toURL());

            assertThat(found).singleElement()
                    .satisfies(candidate ->
                            assertThat(candidate.className()).isEqualTo("com.acme.Audit"));
            assertThat(contentOf(found.getFirst())).isEqualTo("directory-bytes");
        }
    }

    @Nested
    @DisplayName("when a manifest cannot be used")
    class Broken {

        @Test
        @DisplayName("one bad manifest costs its own root and no other")
        void oneBadManifestDoesNotStopTheRest(@TempDir final Path work) throws Exception {
            final Path broken = work.resolve("broken");
            Files.createDirectories(broken.resolve("META-INF/aether"));
            Files.writeString(broken.resolve(WeaveManifest.RESOURCE), "{ not json");

            final List<WeaveCandidate> found = discover(broken.toUri().toURL(),
                    jar(work, "good.jar", "com.acme.Audit", "good"));

            assertThat(found)
                    .as("a single stale library must not be able to switch off every weave in "
                            + "the application")
                    .extracting(WeaveCandidate::className)
                    .containsExactly("com.acme.Audit");
            assertThat(codes()).contains("AW2300");
        }

        @Test
        @DisplayName("AW2302 — no manifest anywhere says the processor is probably missing")
        void noManifestIsExplained(@TempDir final Path work) throws Exception {
            assertThat(discover(work.toUri().toURL())).isEmpty();

            assertThat(codes()).containsExactly("AW2302");
            assertThat(this.first().remedy().orElseThrow())
                    .as("'nothing found' is useless without the reason it is usually nothing")
                    .contains("aether-weaver-processor");
        }

        @Test
        @DisplayName("a candidate whose class is gone reports absence rather than throwing")
        void aManifestCanOutliveItsClass(@TempDir final Path work) throws Exception {
            final Path root = work.resolve("classes");
            writeManifest(root, "com.acme.Vanished");

            assertThat(discover(root.toUri().toURL()).getFirst().bytes())
                    .as("a manifest is written at compile time and read much later; the class it "
                            + "names may simply not be there any more")
                    .isEmpty();
        }

        private Diagnostic first() {
            return ManifestWeaveSourceTest.this.reported.getFirst();
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("the source is namespaced")
        void theNameIsNamespaced() {
            assertThat(new ManifestWeaveSource().name())
                    .as("a source's name goes into the plan fingerprint and into diagnostics, so "
                            + "two parties' sources must not be able to collide")
                    .isEqualTo("aether:manifest");
        }
    }

    // -------------------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private List<WeaveCandidate> discover(final URL... roots) {
        // A null parent, so the test's own classpath cannot answer for anything: what is under
        // test is which root a candidate came from, and a delegating loader would blur exactly that.
        final URLClassLoader loader = new URLClassLoader(roots, null);
        this.opened.add(loader);
        return new ManifestWeaveSource()
                .candidates(new DiscoveryContext(loader, this.reported::add))
                .toList();
    }

    @AfterEach
    void closeLoaders() throws IOException {
        for (final URLClassLoader loader : this.opened) {
            loader.close();
        }
        this.opened.clear();
    }

    private static String contentOf(final WeaveCandidate candidate) {
        return new String(candidate.bytes().orElseThrow(), StandardCharsets.UTF_8);
    }

    private static URL jar(final Path work, final String name, final String className,
                           final String content) throws IOException {
        final Path jar = work.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(WeaveManifest.RESOURCE));
            out.write(manifestFor(className).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar.toUri().toURL();
    }

    private static void writeManifest(final Path root, final String className) throws IOException {
        final Path file = root.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, manifestFor(className));
    }

    private static void writeClass(final Path root, final String className, final String content)
            throws IOException {
        final Path file = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String manifestFor(final String className) {
        return ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave(className, "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of("com.acme.Target"), List.of(), List.of()))));
    }
}
