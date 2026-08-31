package de.splatgames.aether.weaver.e2e;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TwoArtefactsTest {

    @Nested
    @DisplayName("weaves from two different jars")
    class Coexisting {

        @Test
        @DisplayName("both are applied, and they run in priority order")
        void bothApplyInPriorityOrder(@TempDir final Path work) throws Exception {
            final Path shared = Files.createDirectories(work.resolve("shared"));
            Fixtures.compile(shared, TARGET, TRACE);

            final URL low = jar(work, "low.jar", shared, LOW_PRIORITY, "fixture.Low", 0);
            final URL high = jar(work, "high.jar", shared, HIGH_PRIORITY, "fixture.High", 100);

            final List<Diagnostic> reported = new ArrayList<>();
            try (WeavingClassLoader loader = WeavingClassLoader.create(
                    new URL[]{shared.toUri().toURL(), low, high},
                    TwoArtefactsTest.class.getClassLoader(), WeaverConfig.defaults(),
                    reported::add)) {
                run(loader);

                assertThat(marks(loader))
                        .as("both weaves apply, and the higher priority runs first. Emission "
                                + "order is execution order, with no inversion anywhere")
                        .containsExactly("high", "low");
            }
            assertThat(reported)
                    .as("two artefacts declaring weaves over one method is ordinary, not a "
                            + "conflict: %s", reported)
                    .isEmpty();
        }

        @Test
        @DisplayName("the order comes from priority, not from classpath order")
        void classpathOrderDoesNotDecide(@TempDir final Path work) throws Exception {
            final Path shared = Files.createDirectories(work.resolve("shared"));
            Fixtures.compile(shared, TARGET, TRACE);

            // The same two jars, in the other order on the classpath.
            final URL low = jar(work, "low.jar", shared, LOW_PRIORITY, "fixture.Low", 0);
            final URL high = jar(work, "high.jar", shared, HIGH_PRIORITY, "fixture.High", 100);

            try (WeavingClassLoader loader = WeavingClassLoader.create(
                    new URL[]{shared.toUri().toURL(), high, low},
                    TwoArtefactsTest.class.getClassLoader(), WeaverConfig.defaults(),
                    diagnostic -> {
                    })) {
                run(loader);

                assertThat(marks(loader))
                        .as("if classpath order decided, this would be the reverse of the test "
                                + "above and both would pass. An order that depends on how a "
                                + "deployment happened to be assembled is not an order")
                        .containsExactly("high", "low");
            }
        }
    }

    @Nested
    @DisplayName("when two weaves would merge the same handler")
    class Colliding {

        @Test
        @DisplayName("AW1080 at plan time, instead of a verification failure at load time")
        void handlerCollisionsAreReported(@TempDir final Path work) throws Exception {
            final Path shared = Files.createDirectories(work.resolve("shared"));
            Fixtures.compile(shared, TARGET, TRACE);

            // The same handler name in both, which is what two independent libraries produce.
            final URL first = jar(work, "a.jar", shared,
                    LOW_PRIORITY.replace("onGreetLow", "onGreet"), "fixture.Low", 0);
            final URL second = jar(work, "b.jar", shared,
                    HIGH_PRIORITY.replace("onGreetHigh", "onGreet"), "fixture.High", 100);

            final List<Diagnostic> reported = new ArrayList<>();
            try (WeavingClassLoader ignored = WeavingClassLoader.create(
                    new URL[]{shared.toUri().toURL(), first, second},
                    TwoArtefactsTest.class.getClassLoader(), WeaverConfig.defaults(),
                    reported::add)) {
                assertThat(reported)
                        .as("reported when the plan is built, before any class is offered. "
                                + "Without this the target failed verification with \"Duplicate "
                                + "method name onGreet\" — under a message that blames the weave "
                                + "or the engine, which is neither: two libraries whose handlers "
                                + "share a name is nobody's defect")
                        .anyMatch(diagnostic -> diagnostic.code().code().equals("AW1080"));
            }

            final String message = reported.stream()
                    .filter(diagnostic -> diagnostic.code().code().equals("AW1080"))
                    .findFirst().orElseThrow().format();
            assertThat(message)
                    .as("and it names both weaves and says what to do")
                    .contains("fixture.Low")
                    .contains("fixture.High")
                    .contains("onGreet()V")
                    .contains("rename all but one");
        }
    }

    // -------------------------------------------------------------------------------------

    private static void run(final WeavingClassLoader loader) throws Exception {
        final Class<?> target = loader.loadClass("fixture.Target");
        target.getMethod("greet").invoke(target.getDeclaredConstructor().newInstance());
    }

    @SuppressWarnings("unchecked")
    private static List<String> marks(final WeavingClassLoader loader) throws Exception {
        // The loader's own Trace, not this test's. The handlers were woven into classes it defined,
        // so they resolved Trace through it — reading this test's copy would find it empty and the
        // failure would send the reader looking for a weaving bug that is not there.
        return (List<String>) loader.loadClass("fixture.Trace")
                .getDeclaredField("RECORD").get(null);
    }

    private static URL jar(final Path work, final String name, final Path against,
                           final String source, final String weaveName, final int priority)
            throws IOException {
        final Path staging = Files.createDirectories(work.resolve(name + ".classes"));
        Fixtures.compile(staging, List.of(against), source);

        final Path jar = work.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            final String entry = weaveName.replace('.', '/') + ".class";
            out.putNextEntry(new ZipEntry(entry));
            out.write(Files.readAllBytes(staging.resolve(entry)));
            out.closeEntry();

            out.putNextEntry(new ZipEntry(WeaveManifest.RESOURCE));
            out.write(ManifestWriter.write(WeaveManifest.of("test", List.of(
                            new WeaveManifest.Weave(weaveName, "INSTANCE", priority, "REQUIRED",
                                    "DEFAULT", List.of(), List.of("fixture.Target"), List.of(),
                                    List.of()))))
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar.toUri().toURL();
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

    private static final String LOW_PRIORITY = """
            package fixture;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.Inject;
            import de.splatgames.aether.weaver.api.Point;
            import de.splatgames.aether.weaver.api.Weave;

            @Weave(targets = "fixture.Target", priority = 0)
            public final class Low {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreetLow() {
                    Trace.RECORD.add("low");
                }
            }
            """;

    private static final String HIGH_PRIORITY = """
            package fixture;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.Inject;
            import de.splatgames.aether.weaver.api.Point;
            import de.splatgames.aether.weaver.api.Weave;

            @Weave(targets = "fixture.Target", priority = 100)
            public final class High {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreetHigh() {
                    Trace.RECORD.add("high");
                }
            }
            """;
}
