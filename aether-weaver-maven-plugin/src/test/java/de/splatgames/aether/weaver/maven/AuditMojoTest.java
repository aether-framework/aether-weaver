package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.apache.maven.plugin.logging.SystemStreamLog;
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
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMojoTest {

    @Nested
    @DisplayName("auditing a woven artefact")
    class Woven {

        @Test
        @DisplayName("every modification is named, with its weave, handler and target")
        void theReportNamesWhatHappened() throws Exception {
            final List<String> report = AuditMojo.report(wovenClasses());

            assertThat(report)
                    .as("an audit exists so that a reviewer can see what modified this class "
                            + "without running it")
                    .anyMatch(line -> line.equals("fixture/Target.class"))
                    .anyMatch(line -> line.contains("fixture.Greeting")
                            && line.contains("INJECT")
                            && line.contains("onGreet")
                            && line.contains("greet"));
        }

        @Test
        @DisplayName("the summary counts classes and modifications and names the fingerprint")
        void theSummaryIsComplete() throws Exception {
            assertThat(AuditMojo.report(wovenClasses()).getLast())
                    .contains("1 class")
                    .contains("1 modification")
                    .contains("fingerprint ")
                    .contains("no policy overrides");
        }

        @Test
        @DisplayName("a class that was not woven does not appear")
        void untouchedClassesAreNotListed() throws Exception {
            assertThat(AuditMojo.report(wovenClasses()))
                    .as("listing every class would bury the ones that were modified, which are "
                            + "the entire point")
                    .noneMatch(line -> line.contains("Bystander"));
        }

        @Test
        @DisplayName("a jar is audited exactly like a directory")
        void jarsAndDirectoriesAgree() throws Exception {
            final Path classes = wovenClasses();

            assertThat(AuditMojo.report(jarOf(classes)))
                    .as("an artefact is usually audited after packaging, and the two forms must "
                            + "not tell different stories")
                    .isEqualTo(AuditMojo.report(classes));
        }

        @Test
        @DisplayName("the report is stable between runs")
        void theReportIsDiffable() throws Exception {
            final Path classes = wovenClasses();

            assertThat(AuditMojo.report(classes))
                    .as("an audit is compared against the last one; an ordering that followed the "
                            + "file system would make every comparison noisy")
                    .isEqualTo(AuditMojo.report(classes));
        }
    }

    @Nested
    @DisplayName("auditing something that was not woven")
    class Untouched {

        @Test
        @DisplayName("it says so plainly rather than printing an empty report")
        void nothingWovenIsSaidOutLoud() throws Exception {
            final Path classes = newDirectory();
            compileInto(classes, TARGET);

            assertThat(AuditMojo.report(classes))
                    .as("an empty report reads like a tool that failed; saying 'nothing' is a "
                            + "result")
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("no woven classes");
        }
    }

    @Nested
    @DisplayName("the goal")
    class Goal {

        @Test
        @DisplayName("a missing artefact fails with something actionable")
        void aMissingArtefactIsRefused() throws Exception {
            final AuditMojo mojo = new AuditMojo();
            mojo.setLog(new SystemStreamLog());
            set(mojo, "artifact", newDirectory().resolve("absent.jar").toFile());

            org.assertj.core.api.Assertions.assertThatThrownBy(mojo::execute)
                    .hasMessageContaining("aether.weaver.artifact");
        }

        @Test
        @DisplayName("it runs over a real directory")
        void theGoalRuns() throws Exception {
            final AuditMojo mojo = new AuditMojo();
            mojo.setLog(new SystemStreamLog());
            set(mojo, "artifact", wovenClasses().toFile());

            mojo.execute();
        }
    }

    // -------------------------------------------------------------------------------------

    private static Path wovenClasses() throws Exception {
        final Path classes = newDirectory();
        compileInto(classes, TARGET, BYSTANDER, WEAVE);

        final Path manifest = classes.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave("fixture.Greeting", "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of("fixture.Target"), List.of(), List.of())))));

        final WeaveMojo mojo = new WeaveMojo();
        mojo.setLog(new SystemStreamLog());
        set(mojo, "classesDirectory", classes.toFile());
        mojo.execute();
        return classes;
    }

    private static Path jarOf(final Path classes) throws IOException {
        final Path jar = newDirectory().resolve("app.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (final Path file : paths.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry(classes.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static void set(final Object mojo, final String name, final Object value)
            throws Exception {
        Class<?> type = mojo.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(mojo, value);
                return;
            } catch (final NoSuchFieldException notHere) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void compileInto(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new java.util.ArrayList<>();
            for (final String source : sources) {
                units.add(new Source("fixture/" + nameOf(source), source));
            }
            assertThat(compiler.getTask(null, files, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"),
                    null, units).call())
                    .as("the fixtures must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String nameOf(final String source) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^public (?:final )?class (\\w+)").matcher(source);
        assertThat(matcher.find()).as("every fixture declares one public class").isTrue();
        return matcher.group(1);
    }

    private static Path newDirectory() throws IOException {
        final Path directory = Files.createTempDirectory("aether-weaver-audit");
        directory.toFile().deleteOnExit();
        return directory;
    }

    private static final String TARGET = """
            package fixture;

            public class Target {
                public String greet() {
                    return "hello";
                }
            }
            """;

    private static final String BYSTANDER = """
            package fixture;

            public class Bystander {
                public String value() {
                    return "untouched";
                }
            }
            """;

    private static final String WEAVE = """
            package fixture;

            import de.splatgames.aether.weaver.api.*;

            @Weave(Target.class)
            public final class Greeting {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreet() {
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
