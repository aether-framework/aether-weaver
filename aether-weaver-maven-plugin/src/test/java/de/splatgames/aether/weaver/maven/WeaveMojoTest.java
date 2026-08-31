package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.apache.maven.plugin.MojoExecutionException;
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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeaveMojoTest {

    @Nested
    @DisplayName("a woven module loads and behaves")
    class EndToEnd {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("the goal weaves the target, and the woven class runs")
        void theWovenClassRuns() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                        Trace.RECORD.add("wove");
                    }
                    """));

            run(classes);

            // One loader for both. Each loader defines its own fixture.Trace, so calling
            // greet() through one and reading RECORD through another compares a static field to a
            // different static field of the same name — a test that could only ever fail.
            try (URLClassLoader loader = loaderFor(classes)) {
                final Class<?> target = loader.loadClass("fixture.Target");
                assertThat(target.getMethod("greet")
                        .invoke(target.getDeclaredConstructor().newInstance()))
                        .as("the target still returns its own value")
                        .isEqualTo("hello");

                assertThat((List<String>) loader.loadClass("fixture.Trace")
                        .getField("RECORD").get(null))
                        .as("the handler ran, which is only observable because the class the "
                                + "plugin wrote was actually loaded and executed")
                        .containsExactly("wove");
            }
        }

        @Test
        @DisplayName("a class no weave touches keeps its bytes and its timestamp")
        void untouchedClassesAreNotRewritten() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));
            final Path untouched = classes.resolve("fixture/Bystander.class");
            final byte[] before = Files.readAllBytes(untouched);
            final long modified = Files.getLastModifiedTime(untouched).toMillis();

            run(classes);

            assertThat(Files.readAllBytes(untouched)).isEqualTo(before);
            assertThat(Files.getLastModifiedTime(untouched).toMillis())
                    .as("rewriting an unchanged file would change its timestamp on every build, "
                            + "and every downstream step that skips unchanged files would stop "
                            + "skipping")
                    .isEqualTo(modified);
        }

        @Test
        @DisplayName("running the goal twice produces the same bytes")
        void theGoalIsIdempotent() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));

            run(classes);
            final byte[] once = Files.readAllBytes(classes.resolve("fixture/Target.class"));
            run(classes);

            assertThat(Files.readAllBytes(classes.resolve("fixture/Target.class")))
                    .as("the second run must recognise the class as already woven with this plan "
                            + "rather than weaving it again")
                    .isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("when there is nothing to do")
    class Quiet {

        @Test
        @DisplayName("a module with no manifest is left alone")
        void noManifestIsNotAnError() throws Exception {
            final Path classes = newDirectory();
            compileInto(classes, TARGET);

            run(classes);

            assertThat(classes.resolve("fixture/Target.class")).exists();
        }

        @Test
        @DisplayName("a missing classes directory is not an error either")
        void aMissingDirectoryIsNotAnError() throws Exception {
            run(newDirectory().resolve("never-compiled"));
        }

        @Test
        @DisplayName("skip stops the goal before it looks at anything")
        void skipIsHonoured() throws Exception {
            final WeaveMojo mojo = mojo(newDirectory().resolve("never-compiled"));
            set(mojo, "skip", true);

            mojo.execute();
        }
    }

    @Nested
    @DisplayName("the explain report")
    class Explaining {

        @Test
        @DisplayName("the build-time driver prints what each point actually matched")
        void theReportIsComplete() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));
            final WeaveMojo mojo = mojo(classes);
            final Capturing log = new Capturing();
            mojo.setLog(log);
            set(mojo, "explain", true);

            mojo.execute();

            assertThat(log.lines)
                    .as("this is the one driver whose report can be complete: the goal knows every "
                            + "class it will ever be asked about, so by the time it prints, every "
                            + "point has been resolved against real bytes")
                    .anyMatch(line -> line.startsWith("Aether Weaver 0.1.0 — plan "))
                    .anyMatch(line -> line.contains("Weaves (1):"))
                    .anyMatch(line -> line.contains("1 site  @"))
                    .noneMatch(line -> line.contains("not woven yet"));
        }

        @Test
        @DisplayName("counter-probe: without the parameter the goal prints none of it")
        void quietByDefault() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));
            final WeaveMojo mojo = mojo(classes);
            final Capturing log = new Capturing();
            mojo.setLog(log);

            mojo.execute();

            assertThat(log.lines)
                    .as("the report holds one entry per point per target, and a build that printed "
                            + "it unasked would bury its own summary in every module")
                    .noneMatch(line -> line.contains("Weaves ("));
        }
    }

    @Nested
    @DisplayName("statistics")
    class Statistics {

        @Test
        @DisplayName("a planned target the build never saw is a warning, not silence")
        void theGapIsReported() throws Exception {
            final Path classes = newDirectory();
            compileInto(classes, TARGET, TRACE, BYSTANDER, TWO_TARGETS);
            manifestNaming(classes);

            final WeaveMojo mojo = mojo(classes);
            final Capturing log = new Capturing();
            mojo.setLog(log);

            mojo.execute();

            assertThat(log.warnings)
                    .as("at load time a planned target that was never loaded is ordinary. In a "
                            + "build it is a weave that did not apply to an artefact about to be "
                            + "published, and nothing else in the build would say so")
                    .anyMatch(line -> line.contains("1 planned target")
                            && line.contains("were not found"));
        }

        @Test
        @DisplayName("counter-probe: a plan whose targets were all woven says nothing")
        void noGapNoWarning() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));
            final WeaveMojo mojo = mojo(classes);
            final Capturing log = new Capturing();
            mojo.setLog(log);

            mojo.execute();

            assertThat(log.warnings)
                    .as("a warning every build prints is a warning nobody reads")
                    .noneMatch(line -> line.contains("planned target"));
        }
    }

    @Nested
    @DisplayName("class dumps")
    class Dumps {

        @Test
        @DisplayName("the three files appear, and the diff shows the injected call")
        void theDumpIsWritten() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                        Trace.RECORD.add("woven");
                    }
                    """));
            final Path dump = newDirectory().resolve("dump");
            final WeaveMojo mojo = mojo(classes);
            set(mojo, "dumpDirectory", dump.toFile());

            mojo.execute();

            assertThat(dump.resolve("fixture/Target.original.class")).exists();
            assertThat(dump.resolve("fixture/Target.woven.class")).exists();

            final String diff = Files.readString(dump.resolve("fixture/Target.diff.txt"));
            assertThat(diff)
                    .as("the diff is the artefact worth having: anybody can run javap twice, "
                            + "and by the time a woven class is suspect almost nobody does")
                    .contains("invokevirtual")
                    .contains("onGreet:()V");
            assertThat(diff.lines().filter(line -> line.startsWith("- ")))
                    .as("nothing was removed from the method; the injection is pure insertion, "
                            + "and offsets shifting must not be reported as removals")
                    .isEmpty();
        }

        @Test
        @DisplayName("a class no weave touches is not dumped")
        void untouchedClassesAreNotDumped() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));
            final Path dump = newDirectory().resolve("dump");
            final WeaveMojo mojo = mojo(classes);
            set(mojo, "dumpDirectory", dump.toFile());

            mojo.execute();

            assertThat(dump.resolve("fixture/Bystander.woven.class"))
                    .as("a dump directory holding every class in the module would bury the ones "
                            + "that changed")
                    .doesNotExist();
        }

        @Test
        @DisplayName("counter-probe: without the parameter nothing is written")
        void noDumpByDefault() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(Point.HEAD))
                    void onGreet() {
                    }
                    """));
            final Path dump = newDirectory().resolve("dump");

            mojo(classes).execute();

            assertThat(dump).doesNotExist();
        }
    }

    @Nested
    @DisplayName("failing the build")
    class Failures {

        @Test
        @DisplayName("an error fails the build rather than shipping the artefact")
        void errorsFailTheBuild() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(value = Point.INVOKE,
                            target = "#absent"), require = 1)
                    void onGreet() {
                    }
                    """));

            assertThatThrownBy(() -> run(classes))
                    .as("a build-time weaver that reports a problem and produces the artefact "
                            + "anyway ships an application that differs from the reviewed one, "
                            + "and the difference is invisible until it matters")
                    .isInstanceOf(MojoExecutionException.class)
                    .hasMessageContaining("error");
        }

        @Test
        @DisplayName("failOnError = false reports and continues")
        void theFailureCanBeDeclined() throws Exception {
            final Path classes = compile(weave("""
                    @Inject(method = "greet()", at = @At(value = Point.INVOKE,
                            target = "#absent"), require = 1)
                    void onGreet() {
                    }
                    """));
            final WeaveMojo mojo = mojo(classes);
            set(mojo, "failOnError", false);

            mojo.execute();
        }
    }

    // -------------------------------------------------------------------------------------

    private static void run(final Path classes) throws Exception {
        mojo(classes).execute();
    }

    private static WeaveMojo mojo(final Path classes) throws Exception {
        final WeaveMojo mojo = new WeaveMojo();
        mojo.setLog(new SystemStreamLog());
        set(mojo, "classesDirectory", classes.toFile());
        return mojo;
    }

    private static final class Capturing extends SystemStreamLog {

        private final List<String> lines = new ArrayList<>();

        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(final CharSequence content) {
            this.lines.add(content.toString());
            super.info(content);
        }

        @Override
        public void warn(final CharSequence content) {
            this.warnings.add(content.toString());
            super.warn(content);
        }
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

    private static Path compile(final String weaveSource) throws IOException {
        final Path output = newDirectory();
        compileInto(output, TARGET, TRACE, BYSTANDER, weaveSource);

        // Only the weave's class name matters: the plugin parses the weave out of its own class
        // file, exactly as the runtime does, so everything else in the entry would be ignored.
        final Path manifest = output.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave("fixture.Greeting", "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of("fixture.Target"), List.of(), List.of())))));
        return output;
    }

    private static void manifestNaming(final Path output) throws IOException {
        final Path manifest = output.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave("fixture.Greeting", "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of("fixture.Target"), List.of(), List.of())))));
    }

    private static void compileInto(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new java.util.ArrayList<>();
            for (final String source : sources) {
                units.add(new Source("fixture/" + declaredNameOf(source), source));
            }
            final JavaCompiler.CompilationTask task = compiler.getTask(null, files, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"),
                    null, units);
            assertThat(task.call()).as("the fixtures must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }



    private static URLClassLoader loaderFor(final Path classes) throws IOException {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, null) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("fixture.")) {
                    synchronized (getClassLoadingLock(name)) {
                        final Class<?> found = findLoadedClass(name);
                        return found != null ? found : findClass(name);
                    }
                }
                return WeaveMojoTest.class.getClassLoader().loadClass(name);
            }
        };
    }

    private static Path newDirectory() throws IOException {
        final Path directory = Files.createTempDirectory("aether-weaver-mojo");
        directory.toFile().deleteOnExit();
        return directory;
    }

    private static String declaredNameOf(final String source) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^public (?:final |abstract )?class (\\w+)").matcher(source);
        assertThat(matcher.find()).as("every fixture declares one public class").isTrue();
        return matcher.group(1);
    }

    private static String weave(final String handler) {
        return """
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Target.class)
                public final class Greeting {

                    %s
                }
                """.formatted(handler);
    }

    private static final String TARGET = """
            package fixture;

            public class Target {
                public String greet() {
                    return "hello";
                }
            }
            """;

    private static final String TRACE = """
            package fixture;

            import java.util.ArrayList;
            import java.util.List;

            public class Trace {
                public static final List<String> RECORD = new ArrayList<>();
            }
            """;

    private static final String TWO_TARGETS = """
            package fixture;

            import de.splatgames.aether.weaver.api.*;

            @Weave(targets = {"fixture.Target", "fixture.NotHere"})
            public final class Greeting {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreet() {
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
