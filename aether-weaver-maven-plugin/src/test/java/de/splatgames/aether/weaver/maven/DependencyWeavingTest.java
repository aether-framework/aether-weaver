package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.parse.WeaveClassParser;
import de.splatgames.aether.weaver.api.model.Origin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyWeavingTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("weaving a dependency")
    class Weaving {

        @Test
        @DisplayName("the woven class is written out, and the jar is untouched")
        void theOriginalArtefactStaysAsPublished() throws Exception {
            final Path jar = library(false);
            final byte[] before = Files.readAllBytes(jar);
            final Path output = newDirectory();

            final int written = weaver(output, false).weave(List.of(jar));

            assertThat(written).isEqualTo(1);
            assertThat(output.resolve("vendor/Library.class"))
                    .as("the woven copy lives in its own directory, so deleting it undoes "
                            + "everything")
                    .exists();
            assertThat(Files.readAllBytes(jar))
                    .as("the artefact in the local repository must stay bit-for-bit what its "
                            + "publisher shipped")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("AW2501 — every modified third-party class is named")
        void modifiedClassesAreListed() throws Exception {
            weaver(newDirectory(), false).weave(List.of(library(false)));

            assertThat(codes()).contains("AW2501");
            assertThat(DependencyWeavingTest.this.reported.stream()
                    .filter(diagnostic -> diagnostic.code().code().equals("AW2501"))
                    .findFirst().orElseThrow().details())
                    .as("shipping a modified copy of someone else's library is a decision, and a "
                            + "decision nobody sees is not one")
                    .anyMatch(detail -> detail.contains("vendor.Library"));
        }

        @Test
        @DisplayName("a class the plan does not touch is not written")
        void untouchedClassesAreNotCopied() throws Exception {
            final Path output = newDirectory();

            weaver(output, false).weave(List.of(library(false)));

            assertThat(output.resolve("vendor/Untouched.class"))
                    .as("copying every class would turn the output into a second copy of the "
                            + "library, and the classpath into a guessing game")
                    .doesNotExist();
        }

        @Test
        @DisplayName("nothing is reported when nothing was modified")
        void silenceWhenNothingChanges() throws Exception {
            weaver(newDirectory(), false).weave(List.of());

            assertThat(DependencyWeavingTest.this.reported).isEmpty();
        }
    }

    @Nested
    @DisplayName("signed artefacts")
    class Signed {

        @Test
        @DisplayName("AW3002 — a signed jar is refused, and nothing is written")
        void signedArtefactsAreRefused() throws Exception {
            final Path output = newDirectory();

            final int written = weaver(output, false).weave(List.of(library(true)));

            assertThat(written).isZero();
            assertThat(codes()).containsExactly("AW3002");
            assertThat(output.resolve("vendor/Library.class")).doesNotExist();
        }

        @Test
        @DisplayName("the refusal explains the consequence and names the signer")
        void theRefusalExplainsItself() throws Exception {
            weaver(newDirectory(), false).weave(List.of(library(true)));

            final Diagnostic refusal = DependencyWeavingTest.this.reported.getFirst();
            assertThat(refusal.message())
                    .as("naming the signer is what turns a rule into something a person can act on")
                    .contains("VENDOR");
            assertThat(refusal.details())
                    .as("the message must say what breaks, not merely that a rule was hit")
                    .anyMatch(detail -> detail.contains("integrity guarantee"));
            assertThat(refusal.remedy().orElseThrow()).contains("allowSigned");
        }

        @Test
        @DisplayName("allowSigned permits it — and says so, loudly")
        void theOverrideIsReported() throws Exception {
            final Path output = newDirectory();

            final int written = weaver(output, true).weave(List.of(library(true)));

            assertThat(written).isEqualTo(1);
            assertThat(codes())
                    .as("an override that produces no output is an override nobody reviewing the "
                            + "build log will notice was used")
                    .contains("AW3020");
            assertThat(output.resolve("vendor/Library.class")).exists();
        }

        @Test
        @DisplayName("an unsigned jar is not mistaken for a signed one")
        void unsignedArtefactsAreNotRefused() throws Exception {
            weaver(newDirectory(), false).weave(List.of(library(false)));

            assertThat(codes())
                    .as("a signature block is a .SF plus a matching .DSA/.RSA/.EC, not any file "
                            + "under META-INF")
                    .doesNotContain("AW3002");
        }
    }

    // -------------------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private DependencyWeaver weaver(final Path output, final boolean allowSigned)
            throws IOException {
        final Path classes = newDirectory();
        compileInto(classes, WEAVE, LIBRARY, UNTOUCHED);

        final byte[] weaveBytes = Files.readAllBytes(classes.resolve("app/Patch.class"));
        final List<WeaveClass> weaves = new ArrayList<>();
        new WeaveClassParser(diagnostic -> {
            throw new AssertionError("the fixture weave must parse: " + diagnostic.format());
        }).parse(ClassFile.of().parse(weaveBytes), Origin.of("test", null)).ifPresent(weaves::add);
        assertThat(weaves).as("the fixture weave must parse").hasSize(1);

        final Map<ClassDesc, byte[]> bytes = Map.of(weaves.getFirst().weaveType(), weaveBytes);
        final Weaver engine = Weaver.builder()
                .weaves(weaves)
                .weaveBytes(bytes::get)
                .diagnostics(this.listener)
                .build();
        return new DependencyWeaver(engine, output, allowSigned, this.listener);
    }

    private static Path library(final boolean signed) throws IOException {
        final Path classes = newDirectory();
        compileInto(classes, LIBRARY, UNTOUCHED);

        final Path jar = newDirectory().resolve("vendor-library.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(out, "vendor/Library.class",
                    Files.readAllBytes(classes.resolve("vendor/Library.class")));
            write(out, "vendor/Untouched.class",
                    Files.readAllBytes(classes.resolve("vendor/Untouched.class")));
            write(out, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            if (signed) {
                write(out, "META-INF/VENDOR.SF",
                        "Signature-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                write(out, "META-INF/VENDOR.RSA", new byte[]{0x30, (byte) 0x82});
            }
        }
        return jar;
    }

    private static void write(final ZipOutputStream out, final String name, final byte[] bytes)
            throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static void compileInto(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new ArrayList<>();
            for (final String source : sources) {
                units.add(new Source(pathOf(source), source));
            }
            assertThat(compiler.getTask(null, files, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"),
                    null, units).call())
                    .as("the fixtures must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String pathOf(final String source) {
        final java.util.regex.Matcher pkg = java.util.regex.Pattern
                .compile("(?m)^package (\\S+);").matcher(source);
        final java.util.regex.Matcher type = java.util.regex.Pattern
                .compile("(?m)^public (?:final )?class (\\w+)").matcher(source);
        assertThat(pkg.find() && type.find()).as("every fixture has a package and a class").isTrue();
        return pkg.group(1).replace('.', '/') + '/' + type.group(1);
    }

    private static Path newDirectory() throws IOException {
        final Path directory = Files.createTempDirectory("aether-weaver-dependency");
        directory.toFile().deleteOnExit();
        return directory;
    }

    private static final String LIBRARY = """
            package vendor;

            public class Library {
                public String compute() {
                    return "original";
                }
            }
            """;

    private static final String UNTOUCHED = """
            package vendor;

            public class Untouched {
                public String value() {
                    return "untouched";
                }
            }
            """;

    private static final String WEAVE = """
            package app;

            import de.splatgames.aether.weaver.api.*;

            @Weave(targets = "vendor.Library")
            public final class Patch {

                @Inject(method = "compute()", at = @At(Point.HEAD))
                void onCompute() {
                }
            }
            """;

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(final String path, final String code) {
            super(URI.create("string:///" + path + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.code;
        }
    }
}
