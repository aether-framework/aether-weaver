package de.splatgames.aether.weaver.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleAccessTest {

    private static final int TIMEOUT_SECONDS = 60;

    @Nested
    @DisplayName("the common case needs no module handling")
    class ClasspathWeaver {

        @Test
        @DisplayName("a named-module target runs woven code from the classpath")
        void theJvmGrantsTheEdgeItself(@TempDir final Path work) throws Exception {
            final Result result = run(work);

            assertThat(result.exitCode())
                    .as("the application must finish normally: %s", result.output())
                    .isZero();
            assertThat(result.output())
                    .as("the handler lives on the classpath and the target lives in a named "
                            + "module; without the automatic read edge this line would be an "
                            + "IllegalAccessError instead")
                    .contains("woven from the classpath");
            assertThat(result.output()).contains("in a named module");
        }

        @Test
        @DisplayName("and the agent did not expand the module graph to achieve it")
        void noReadEdgeWasAdded(@TempDir final Path work) throws Exception {
            assertThat(run(work).output())
                    .as("AW2402 is reported whenever redefineModule is called. Its absence here "
                            + "is the assertion: a redefineModule that was never needed would be "
                            + "a no-op dressed as a safeguard, and safeguards nobody needs are "
                            + "how real ones stop being noticed")
                    .doesNotContain("AW2402");
        }

        @Test
        @DisplayName("the target really is in a named module, so the test is not vacuous")
        void theFixtureIsGenuinelyModular(@TempDir final Path work) throws Exception {
            assertThat(run(work).output())
                    .as("if the module path fell back to the classpath, the target would be in "
                            + "the unnamed module and this test would prove nothing")
                    .contains("module=app");
        }
    }

    @Nested
    @DisplayName("deciding whether an edge is needed")
    class Decision {

        @Test
        @DisplayName("an unnamed target reads everything already")
        void unnamedTargetsNeedNothing() {
            assertThat(ModuleAccess.needsReadEdge(unnamed(), unnamed())).isFalse();
        }

        @Test
        @DisplayName("a named target and an unnamed weave: the JVM has already done it")
        void theMeasuredCaseNeedsNothing() {
            assertThat(ModuleAccess.needsReadEdge(named(), unnamed()))
                    .as("this is the arrangement spike 8b measured, and the one essentially "
                            + "every user has")
                    .isFalse();
        }

        @Test
        @DisplayName("a module that already reads the other needs nothing")
        void anExistingEdgeIsNotAddedTwice() {
            assertThat(ModuleAccess.needsReadEdge(named(), named())).isFalse();
        }

        @Test
        @DisplayName("a missing module is not a case at all")
        void nullsAreNotACase() {
            assertThat(ModuleAccess.needsReadEdge(null, unnamed())).isFalse();
            assertThat(ModuleAccess.needsReadEdge(named(), null)).isFalse();
        }

        private static Module named() {
            return String.class.getModule();
        }

        private static Module unnamed() {
            return ModuleAccessTest.class.getModule();
        }
    }

    // -------------------------------------------------------------------------------------

    private static Result run(final Path work) throws Exception {
        // The weave and its trace live on the classpath, exactly as a classpath-deployed weaver
        // does. The target lives in a real named module on the module path.
        final Path classpath = Files.createDirectories(work.resolve("classpath"));
        compileClasspath(classpath);

        final Path modules = Files.createDirectories(work.resolve("modules/app"));
        compileModule(modules, classpath);

        final Path agentJar = agentJar(work);
        final String cp = classpath + File.pathSeparator + System.getProperty("java.class.path");

        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agentJar,
                "-cp", cp,
                "--module-path", work.resolve("modules").toString(),
                "--add-modules", "app",
                "-m", "app/app.Main"));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the child JVM did not finish").isTrue();
        return new Result(process.exitValue(), output);
    }

    private static void compileClasspath(final Path output) throws IOException {
        compile(output, List.of(), TRACE, WEAVE);

        final Path manifest = output.resolve(
                de.splatgames.aether.weaver.api.manifest.WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest,
                de.splatgames.aether.weaver.api.manifest.ManifestWriter.write(
                        de.splatgames.aether.weaver.api.manifest.WeaveManifest.of("test",
                                List.of(new de.splatgames.aether.weaver.api.manifest
                                        .WeaveManifest.Weave("cp.Greeting", "STATIC", 0,
                                        "REQUIRED", "DEFAULT", List.of(), List.of("app.Service"),
                                        List.of(), List.of())))));
    }

    private static void compileModule(final Path output, final Path classpath) {
        // module-info is written into a temporary directory and never committed. The project
        // ships no module descriptors; this one belongs to the fixture application, which is the
        // very thing being tested.
        compile(output, List.of("-cp", classpath + File.pathSeparator
                        + System.getProperty("java.class.path")),
                MODULE_INFO, SERVICE, MAIN);
    }

    private static Path agentJar(final Path work) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Premain-Class", WeaverAgent.class.getName());
        main.putValue("Can-Retransform-Classes", "true");

        final Path jar = work.resolve("agent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
        }
        return jar;
    }

    private static void compile(final Path output, final List<String> options,
                                final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new ArrayList<>();
            for (final String source : sources) {
                units.add(new Source(pathOf(source), source));
            }
            final List<String> all = new ArrayList<>(options);
            if (options.isEmpty()) {
                all.addAll(List.of("-cp", System.getProperty("java.class.path")));
            }
            all.add("-proc:none");
            assertThat(compiler.getTask(null, files, null, all, null, units).call())
                    .as("the fixtures must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String pathOf(final String source) {
        if (source.contains("module app")) {
            return "module-info";
        }
        final java.util.regex.Matcher pkg = java.util.regex.Pattern
                .compile("(?m)^package (\\S+);").matcher(source);
        final java.util.regex.Matcher type = java.util.regex.Pattern
                .compile("(?m)^public (?:final )?class (\\w+)").matcher(source);
        assertThat(pkg.find() && type.find()).as("every fixture has a package and a class").isTrue();
        return pkg.group(1).replace('.', '/') + '/' + type.group(1);
    }

    private record Result(int exitCode, String output) {
    }

    private static final String MODULE_INFO = """
            module app {
                exports app;
            }
            """;

    private static final String SERVICE = """
            package app;

            public class Service {
                public void run() {
                    System.out.println("in a named module");
                }
            }
            """;

    private static final String MAIN = """
            package app;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("module=" + Main.class.getModule().getName());
                    new Service().run();
                }
            }
            """;

    private static final String TRACE = """
            package cp;

            public class Trace {
                public static void say(String what) {
                    System.out.println(what);
                }
            }
            """;

    private static final String WEAVE = """
            package cp;

            import de.splatgames.aether.weaver.api.*;

            @Weave(targets = "app.Service", kind = Weave.Kind.STATIC)
            public final class Greeting {

                @Inject(method = "run()", at = @At(Point.HEAD))
                public static void onRun() {
                    Trace.say("woven from the classpath");
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
