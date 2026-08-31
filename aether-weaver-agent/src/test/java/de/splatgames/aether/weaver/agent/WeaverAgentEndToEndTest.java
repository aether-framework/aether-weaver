package de.splatgames.aether.weaver.agent;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
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

class WeaverAgentEndToEndTest {

    private static final int TIMEOUT_SECONDS = 60;

    @Nested
    @DisplayName("an application runs woven under -javaagent")
    class EndToEnd {

        @Test
        @DisplayName("the handler runs inside the application's own method")
        void theWeaveApplies(@TempDir final Path work) throws Exception {
            final Result result = run(work, null);

            assertThat(result.exitCode())
                    .as("the application must finish normally: %s", result.output())
                    .isZero();
            assertThat(result.output())
                    .as("'woven' is printed by the handler, from inside Target.greet() — it "
                            + "can only appear if the JVM loaded a class this agent rewrote")
                    .contains("woven")
                    .contains("hello");
            assertThat(result.output().indexOf("woven"))
                    .as("the handler is at HEAD, so it runs before the target's own body")
                    .isLessThan(result.output().indexOf("hello"));
        }

        @Test
        @DisplayName("the startup line names the plan and the configuration")
        void theStartupLineIsPrinted() throws Exception {
            final Result result = run(Files.createTempDirectory("aether-agent"), null);

            assertThat(result.output())
                    .as("a run whose configuration is not visible in its own log is a run whose "
                            + "behaviour has to be reconstructed from the deployment")
                    .contains("Aether Weaver 0.1.0")
                    .contains("1 weave")
                    .contains("fingerprint ")
                    .contains("verification=strict");
        }

        @Test
        @DisplayName("a class no weave names is left alone")
        void bystandersAreUntouched(@TempDir final Path work) throws Exception {
            assertThat(run(work, null).output())
                    .as("the transformer returns null for a class nothing applies to, which is "
                            + "what stops an agent re-verifying every class in the application")
                    .contains("untouched");
        }
    }

    @Nested
    @DisplayName("configuration reaches the agent")
    class Configuration {

        @Test
        @DisplayName("enabled=false switches everything off, and says so")
        void theAgentCanBeSwitchedOff(@TempDir final Path work) throws Exception {
            final Result result = run(work, "enabled=false");

            assertThat(result.output())
                    .as("an agent that silently did nothing would be indistinguishable from one "
                            + "that failed")
                    .contains("disabled by configuration")
                    .doesNotContain("woven");
            assertThat(result.exitCode()).isZero();
        }

        @Test
        @DisplayName("a tag exclusion keeps the weave from applying")
        void tagsSelect(@TempDir final Path work) throws Exception {
            final Result result = run(work, "tags.exclude=audit");

            assertThat(result.output())
                    .contains("no weaves to apply")
                    .doesNotContain("woven");
            assertThat(result.output()).contains("hello");
        }

        @Test
        @DisplayName("an unknown agent argument is reported rather than ignored")
        void unknownArgumentsAreReported(@TempDir final Path work) throws Exception {
            assertThat(run(work, "verifcation=report").output())
                    .contains("AW2310")
                    .contains("did you mean");
        }

        @Test
        @DisplayName("explain=true prints the plan, and names the layer that asked for it")
        void explainPrintsThePlan(@TempDir final Path work) throws Exception {
            final String output = run(work, "explain=true").output();

            assertThat(output)
                    .as("a load-time driver has no end — classes keep arriving for as long as the "
                            + "application runs — so its report is printed at startup and is "
                            + "complete about the plan rather than about what was matched")
                    .contains("Weaves (1):")
                    .contains("app.Greeting  [INSTANCE, priority 0, origin: weave manifest")
                    .contains("→ app.Target")
                    .contains("not woven yet")
                    .contains("explain            ← agent arguments")
                    .contains("verification       ← default");
        }

        @Test
        @DisplayName("a console that cannot carry the arrows still gets a readable plan")
        void explainDegradesForANarrowConsole(@TempDir final Path work) throws Exception {
            final String output = run(work, "explain=true", List.of(), "US-ASCII").output();

            assertThat(output)
                    .as("cp850 is the default for a German cmd.exe and carries none of the "
                            + "report's typography. Left to the stream, every arrow is written as "
                            + "a literal question mark and the plan becomes unreadable — which is "
                            + "how the whole thing was found, as a test failure whose expected and "
                            + "actual strings printed identically")
                    .contains("-> app.Target")
                    .contains("explain            <- agent arguments")
                    .doesNotContain("? app.Target");
        }

        @Test
        @DisplayName("counter-probe: an ordinary run prints none of it")
        void quietByDefault(@TempDir final Path work) throws Exception {
            assertThat(run(work, null).output())
                    .as("the report would otherwise appear in every application's startup log")
                    .doesNotContain("Weaves (");
        }
    }

    @Nested
    @DisplayName("Flight Recorder")
    class Flight {

        @Test
        @DisplayName("a class woven inside premain appears in the JVM's own recording")
        void theEventSurvivesTheRealDriver(@TempDir final Path work) throws Exception {
            final Path recording = work.resolve("run.jfr");
            final Result result = run(work, null, List.of(
                    "-XX:StartFlightRecording=filename=" + recording + ",dumponexit=true"));

            assertThat(result.exitCode()).as("the child must finish: %s", result.output()).isZero();
            assertThat(result.output()).contains("woven");
            assertThat(recording).exists();

            final List<RecordedEvent> events = RecordingFile.readAllEvents(recording).stream()
                    .filter(event -> event.getEventType().getName()
                            .equals("de.splatgames.aether.weaver.ClassWoven"))
                    .toList();

            assertThat(events)
                    .as("the engine's own test proves the event type is registered correctly. "
                            + "This proves it survives the environment the design worried about: "
                            + "emitted from inside a premain transformer, on the class-loading "
                            + "path, with a real recording running")
                    .hasSize(1);
            assertThat(events.getFirst().getString("wovenClass")).isEqualTo("app.Target");
            assertThat(events.getFirst().getInt("modifications")).isOne();
        }
    }

    // -------------------------------------------------------------------------------------

    private static Result run(final Path work, final String agentArgs) throws Exception {
        return run(work, agentArgs, List.of());
    }

    private static Result run(final Path work, final String agentArgs,
                              final List<String> flags) throws Exception {
        return run(work, agentArgs, flags, "UTF-8");
    }

    private static Result run(final Path work, final String agentArgs,
                              final List<String> flags, final String encoding) throws Exception {
        final Path classes = Files.createDirectories(work.resolve("app"));
        compile(classes, TARGET, TRACE, BYSTANDER, WEAVE, MAIN);

        final Path manifest = classes.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave("app.Greeting", "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of("audit"), List.of("app.Target"), List.of(), List.of())))));

        final Path agentJar = agentJar(work);
        final String classpath = classes + File.pathSeparator + System.getProperty("java.class.path");

        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                // The child's stdout encoding, not the parent's. It defaults to the platform's
                // native charset when stdout is a pipe, so on Windows every non-ASCII glyph in the
                // report is written as a literal '?' — the bytes are already lost, and no decoding
                // here can recover them. Reproduced on Linux with -Dstdout.encoding=US-ASCII.
                "-Dstdout.encoding=" + encoding,
                "-Dstderr.encoding=" + encoding,
                "-javaagent:" + agentJar + (agentArgs == null ? "" : "=" + agentArgs)));
        command.addAll(flags);
        command.addAll(List.of("-cp", classpath, "app.Main"));

        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the child JVM did not finish; a hang here usually means the agent loaded a "
                        + "class inside transform and deadlocked on a class-loading lock")
                .isTrue();
        return new Result(process.exitValue(), output);
    }

    private static Path agentJar(final Path work) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Premain-Class", WeaverAgent.class.getName());
        main.putValue("Agent-Class", WeaverAgent.class.getName());
        main.putValue("Can-Retransform-Classes", "true");
        main.putValue("Can-Redefine-Classes", "true");

        final Path jar = work.resolve("agent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // Nothing but the manifest: the JVM appends the agent jar to the system class path and
            // loads Premain-Class from there, and the framework's classes are already on -cp.
            out.flush();
        }
        return jar;
    }

    private static void compile(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new ArrayList<>();
            for (final String source : sources) {
                units.add(new Source("app/" + nameOf(source), source));
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

    private record Result(int exitCode, String output) {
    }

    private static final String TARGET = """
            package app;

            public class Target {
                public String greet() {
                    Trace.say("hello");
                    return "hello";
                }
            }
            """;

    private static final String TRACE = """
            package app;

            public class Trace {
                public static void say(String what) {
                    System.out.println(what);
                }
            }
            """;

    private static final String BYSTANDER = """
            package app;

            public class Bystander {
                public void report() {
                    Trace.say("untouched");
                }
            }
            """;

    private static final String WEAVE = """
            package app;

            import de.splatgames.aether.weaver.api.*;

            @Weave(value = Target.class, tags = {"audit"})
            public final class Greeting {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreet() {
                    Trace.say("woven");
                }
            }
            """;

    private static final String MAIN = """
            package app;

            public class Main {
                public static void main(String[] args) {
                    new Target().greet();
                    new Bystander().report();
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
