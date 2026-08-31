package de.splatgames.aether.weaver.e2e;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicAttachTest {

    private static final int TIMEOUT_SECONDS = 90;

    @Nested
    @DisplayName("attaching to a running JVM")
    class Attached {

        @Test
        @DisplayName("a static weave reaches a class that was already loaded")
        void staticWeavesReachLoadedClasses(@TempDir final Path work) throws Exception {
            final String output = run(work, STATIC_WEAVE, "fixture.Loaded");

            assertThat(wovenAfterAttach(output))
                    .as("installing the transformer is not enough: a class that is already "
                            + "loaded never reaches it again on its own, and being already loaded "
                            + "is the whole reason somebody attached. The agent retransforms the "
                            + "applicable targets explicitly — without that it attaches, reports "
                            + "success, and changes nothing anyone can see: %s", output)
                    .isTrue();
            assertThat(output).doesNotContain("AW2101");
        }

        @Test
        @DisplayName("a static weave applies to a class loaded after the attach")
        void staticWeavesApplyToLaterClasses(@TempDir final Path work) throws Exception {
            final String output = run(work, STATIC_WEAVE, "fixture.Later");

            assertThat(wovenAfterAttach(output))
                    .as("the class is defined after the transformer is installed, so it takes the "
                            + "ordinary load-time path: %s", output)
                    .isTrue();
            assertThat(output).doesNotContain("AW2101");
        }

        @Test
        @DisplayName("a structural weave over an already-loaded class is refused with AW2101")
        void structuralWeavesOverLoadedClassesAreRefused(@TempDir final Path work) throws Exception {
            final String output = run(work, MERGING_WEAVE, "fixture.Loaded");

            assertThat(output)
                    .as("the JVM would have thrown UnsupportedOperationException at "
                            + "retransformation, naming the class and not the weave. This says "
                            + "which weave, which member and what to do instead — at attach time, "
                            + "before anything was attempted: %s", output)
                    .contains("AW2101")
                    .contains("fixture.Merging")
                    .contains("fixture.Loaded")
                    .contains("already loaded")
                    .contains("weave at build time");
            assertThat(wovenAfterAttach(output))
                    .as("and it really is not applied, rather than merely warned about")
                    .isFalse();
        }

        @Test
        @DisplayName("the same structural weave still applies to a class loaded afterwards")
        void structuralWeavesStillApplyToLaterClasses(@TempDir final Path work) throws Exception {
            final String output = run(work, MERGING_WEAVE, "fixture.Later");

            assertThat(wovenAfterAttach(output))
                    .as("this is the case the old implementation broke: an inapplicable weave "
                            + "was dropped from the plan outright, so a class defined for the "
                            + "first time after the attach — which the JVM permits structurally — "
                            + "was not woven either. Its own diagnostic promised otherwise: %s",
                            output)
                    .isTrue();
            assertThat(output)
                    .as("and nothing is refused, because nothing it targets was already loaded")
                    .doesNotContain("AW2101");
        }
    }

    // -------------------------------------------------------------------------------------

    private static boolean wovenAfterAttach(final String output) {
        final int attached = output.indexOf("--- attached ---");
        final int woven = output.indexOf("woven", attached < 0 ? 0 : attached);
        return attached >= 0 && woven > attached;
    }

    private static String run(final Path work, final String weaveSource, final String target)
            throws Exception {
        final Path classes = Files.createDirectories(work.resolve("classes"));
        Fixtures.compile(classes, LOADED, LATER, TRACE,
                weaveSource.replace("PLACEHOLDER", target), MAIN);

        final String weaveName = weaveSource.contains("class Merging")
                ? "fixture.Merging"
                : "fixture.Tracing";
        final Path manifest = classes.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave(weaveName, "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of(target), List.of(), List.of())))));

        final List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                // Self-attach is disabled by default since JDK 9. A real operator attaches from
                // another process; the agent cannot tell the difference, and this saves a JVM.
                "-Djdk.attach.allowAttachSelf=true",
                "-XX:+EnableDynamicAgentLoading",
                "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"),
                "fixture.Main", agentJar(work).toString(), target);

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the child JVM did not finish").isTrue();
        assertThat(process.exitValue()).as("the child failed: %s", output).isZero();
        return output;
    }

    private static Path agentJar(final Path work) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Agent-Class", "de.splatgames.aether.weaver.agent.WeaverAgent");
        main.putValue("Can-Retransform-Classes", "true");
        main.putValue("Can-Redefine-Classes", "true");

        final Path jar = work.resolve("agent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
        }
        return jar;
    }

    private static final String LOADED = """
            package fixture;

            public class Loaded {
                public String greet() {
                    return "plain";
                }
            }
            """;

    private static final String LATER = """
            package fixture;

            public class Later {
                public String greet() {
                    return "plain";
                }
            }
            """;

    private static final String TRACE = """
            package fixture;

            public class Trace {
                public static void say(String what) {
                    System.out.println(what);
                }
            }
            """;

    private static final String STATIC_WEAVE = """
            package fixture;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.Inject;
            import de.splatgames.aether.weaver.api.Point;
            import de.splatgames.aether.weaver.api.Weave;

            @Weave(targets = "PLACEHOLDER", kind = Weave.Kind.STATIC)
            public final class Tracing {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                static void onGreet() {
                    Trace.say("woven");
                }
            }
            """;

    private static final String MERGING_WEAVE = """
            package fixture;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.Inject;
            import de.splatgames.aether.weaver.api.Point;
            import de.splatgames.aether.weaver.api.Weave;

            @Weave(targets = "PLACEHOLDER")
            public final class Merging {

                private long startedAt;

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreet() {
                    this.startedAt = 1L;
                    Trace.say("woven");
                }
            }
            """;

    private static final String MAIN = """
            package fixture;

            import com.sun.tools.attach.VirtualMachine;

            public class Main {
                public static void main(String[] args) throws Exception {
                    // Load the "already there" class BEFORE attaching. Everything this test is
                    // about depends on that ordering.
                    new Loaded().greet();
                    System.out.println("--- attaching ---");

                    VirtualMachine vm = VirtualMachine.attach(
                            String.valueOf(ProcessHandle.current().pid()));
                    vm.loadAgent(args[0]);
                    vm.detach();

                    System.out.println("--- attached ---");
                    if (args[1].equals("fixture.Later")) {
                        new Later().greet();
                    } else {
                        new Loaded().greet();
                    }
                    System.out.println("--- done ---");
                }
            }
            """;
}
