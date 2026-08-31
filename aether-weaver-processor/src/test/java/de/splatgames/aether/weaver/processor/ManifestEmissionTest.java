package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.manifest.ManifestReader;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestEmissionTest {

    private static final Reporter QUIET = diagnostic -> { };

    @Nested
    @DisplayName("what a build writes")
    class Emission {

        @Test
        @DisplayName("the manifest lands at META-INF/aether/weaves.json")
        void theManifestIsWritten() throws IOException {
            final Path output = compile(newOutput(), auditing("Audit", "run()"));

            assertThat(output.resolve(WeaveManifest.RESOURCE))
                    .as("the runtime discovers weaves by finding this exact resource on every "
                            + "classpath root; anywhere else is nowhere")
                    .exists();
        }

        @Test
        @DisplayName("it records what the weave declared")
        void theEntryIsComplete() throws IOException {
            final WeaveManifest manifest = manifestOf(compile(newOutput(), """
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = Target.class, priority = 50, tags = {"audit"},
                           kind = Weave.Kind.STATIC, require = Require.OPTIONAL,
                           phase = Phase.EARLY)
                    public final class Detailed {

                        @Inject(method = "run()", at = @At(Point.HEAD), id = "onRun", require = 1)
                        static void onRun(Target self) {
                        }
                    }
                    """));

            assertThat(manifest.weaves()).singleElement().satisfies(weave -> {
                assertThat(weave.className()).isEqualTo("fixture.Detailed");
                assertThat(weave.kind()).isEqualTo("STATIC");
                assertThat(weave.priority()).isEqualTo(50);
                assertThat(weave.require()).isEqualTo("OPTIONAL");
                assertThat(weave.phase()).isEqualTo("EARLY");
                assertThat(weave.tags()).containsExactly("audit");
                assertThat(weave.targets())
                        .as("targets are stored as binary names, which is what the runtime "
                                + "matches a class against")
                        .containsExactly("fixture.Target");
                assertThat(weave.injectors()).singleElement().satisfies(injector -> {
                    assertThat(injector.kind()).isEqualTo("INJECT");
                    assertThat(injector.id()).isEqualTo("onRun");
                    assertThat(injector.handler())
                            .as("a handler needs no resolution, so it is stored exactly")
                            .isEqualTo("onRun(Lfixture/Target;)V");
                    assertThat(injector.require()).isEqualTo(1);
                    assertThat(injector.points()).singleElement().satisfies(point ->
                            assertThat(point.point()).isEqualTo("HEAD"));
                });
            });
        }

        @Test
        @DisplayName("members are recorded with their descriptors and target names")
        void membersAreRecorded() throws IOException {
            final WeaveManifest manifest = manifestOf(compile(newOutput(), """
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public abstract class Members {

                        @Shadow("name") private String local;
                        @Unique private long startedAt;
                        @Accessor abstract String getName();
                    }
                    """));

            assertThat(manifest.weaves().getFirst().members())
                    .extracting(WeaveManifest.Member::disposition,
                            WeaveManifest.Member::name,
                            WeaveManifest.Member::descriptor,
                            WeaveManifest.Member::targetName,
                            WeaveManifest.Member::unique)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    "SHADOW", "local", "Ljava/lang/String;", "name", false),
                            org.assertj.core.api.Assertions.tuple(
                                    "MERGE", "startedAt", "J", "startedAt", true),
                            org.assertj.core.api.Assertions.tuple(
                                    "ACCESSOR", "getName", "()Ljava/lang/String;", "name", false));
        }

        @Test
        @DisplayName("a handler appears once, as an injector rather than a member")
        void handlersAreNotAlsoMembers() throws IOException {
            final WeaveManifest manifest = manifestOf(compile(newOutput(),
                    auditing("Audit", "run()")));

            assertThat(manifest.weaves().getFirst().members())
                    .as("recording a handler in both places would put one method in the manifest "
                            + "twice under two spellings that could drift apart")
                    .isEmpty();
            assertThat(manifest.weaves().getFirst().injectors()).hasSize(1);
        }

        @Test
        @DisplayName("a module with no weaves writes no manifest")
        void nothingIsWrittenWhenThereIsNothingToSay() throws IOException {
            final Path output = compile(newOutput(), """
                    package fixture;

                    public final class Plain {
                    }
                    """);

            assertThat(output.resolve(WeaveManifest.RESOURCE))
                    .as("an empty manifest in every module would turn 'no manifest' — a useful "
                            + "signal that the processor is not configured — into a file that "
                            + "says nothing")
                    .doesNotExist();
        }

        @Test
        @DisplayName("the manifest is byte-identical between two identical builds")
        void emissionIsReproducible() throws IOException {
            final String source = auditing("Audit", "run()");

            assertThat(Files.readString(compile(newOutput(), source)
                    .resolve(WeaveManifest.RESOURCE)))
                    .as("a jar carrying a manifest that varies between builds is not reproducible")
                    .isEqualTo(Files.readString(compile(newOutput(), source)
                            .resolve(WeaveManifest.RESOURCE)));
        }
    }

    @Nested
    @DisplayName("incremental compilation")
    class Incremental {

        @Test
        @DisplayName("recompiling one weave keeps the others in the manifest")
        void untouchedWeavesSurvive() throws IOException {
            final Path output = newOutput();
            compile(output, auditing("First", "run()"), auditing("Second", "stop()"));

            // The second build sees one file, exactly as an incremental build would.
            compile(output, auditing("Second", "run()"));

            final WeaveManifest manifest = manifestOf(output);
            assertThat(manifest.weaves())
                    .as("a processor that writes what THIS round saw drops every weave it did "
                            + "not recompile — the 'works after clean, broken otherwise' bug")
                    .extracting(WeaveManifest.Weave::className)
                    .containsExactlyInAnyOrder("fixture.First", "fixture.Second");
        }

        @Test
        @DisplayName("the recompiled weave is the new version, not the old one")
        void theEditIsReflected() throws IOException {
            final Path output = newOutput();
            compile(output, auditing("First", "run()"), auditing("Second", "stop()"));
            compile(output, auditing("Second", "run()"));

            assertThat(manifestOf(output).weaves())
                    .filteredOn(weave -> weave.className().equals("fixture.Second"))
                    .singleElement()
                    .satisfies(weave -> assertThat(weave.injectors().getFirst().method())
                            .as("merging must let the newer entry win; keeping both would leave "
                                    + "the runtime to pick, and picking would make the plan "
                                    + "depend on classpath order")
                            .contains("run"));
        }

        @Test
        @DisplayName("a rebuild of everything produces the same manifest as a clean build")
        void afterAFullRebuildNothingLingers() throws IOException {
            final Path incremental = newOutput();
            compile(incremental, auditing("First", "run()"));
            compile(incremental, auditing("First", "run()"), auditing("Second", "stop()"));

            final Path clean = newOutput();
            compile(clean, auditing("First", "run()"), auditing("Second", "stop()"));

            assertThat(manifestOf(incremental).weaves())
                    .extracting(WeaveManifest.Weave::className)
                    .containsExactlyInAnyOrderElementsOf(manifestOf(clean).weaves().stream()
                            .map(WeaveManifest.Weave::className).toList());
        }
    }

    // -------------------------------------------------------------------------------------

    private static Path newOutput() throws IOException {
        final Path output = Files.createTempDirectory("aether-weaver-manifest");
        output.toFile().deleteOnExit();
        return output;
    }

    private static Path compile(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));

            final List<JavaFileObject> units = new java.util.ArrayList<>();
            if (!Files.exists(output.resolve("fixture/Target.class"))) {
                units.add(new Source("fixture/Target", TARGET));
            }
            for (final String source : sources) {
                units.add(new Source("fixture/" + declaredNameOf(source), source));
            }

            final JavaCompiler.CompilationTask task = compiler.getTask(null, files, null,
                    List.of("-classpath", output + java.io.File.pathSeparator
                            + System.getProperty("java.class.path")),
                    null, units);
            task.setProcessors(List.of(new WeaveProcessor()));
            assertThat(task.call()).as("the fixtures must compile").isTrue();
            return output;
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static WeaveManifest manifestOf(final Path output) throws IOException {
        final WeaveManifest manifest = ManifestReader.read(
                Files.readString(output.resolve(WeaveManifest.RESOURCE)), "test", QUIET);
        assertThat(manifest).as("the manifest must be readable by its own reader").isNotNull();
        return manifest;
    }

    private static String auditing(final String name, final String selector) {
        return """
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Target.class)
                public final class %s {

                    @Inject(method = "%s", at = @At(Point.HEAD))
                    void onEvent() {
                    }
                }
                """.formatted(name, selector);
    }

    private static String declaredNameOf(final String source) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^public (?:final |abstract )?class (\\w+)")
                .matcher(source);
        assertThat(matcher.find()).as("every fixture declares one public class").isTrue();
        return matcher.group(1);
    }

    private static final String TARGET = """
            package fixture;

            public class Target {
                private String name;
                public void run() { }
                public void stop() { }
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
