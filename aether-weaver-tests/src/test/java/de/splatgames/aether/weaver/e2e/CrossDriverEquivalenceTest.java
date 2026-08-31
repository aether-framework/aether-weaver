package de.splatgames.aether.weaver.e2e;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.maven.WeaveMojo;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.ConfigLayer;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class CrossDriverEquivalenceTest {

    private static final int TIMEOUT_SECONDS = 60;

    private static final String TARGET_INTERNAL = "fixture/Target";

    @Test
    @DisplayName("build time, -javaagent and WeavingClassLoader produce the same bytes")
    void allThreeDriversAgree(@TempDir final Path work) throws Exception {
        final Path classes = fixture(work);

        final byte[] atBuildTime = buildTime(work, classes);
        final byte[] underAgent = underAgent(work, classes);
        final byte[] throughLoader = throughClassLoader(work, classes);

        // Digests first, arrays second. Two class files compared as byte arrays produce a
        // failure message that prints both of them, which is thousands of numbers and no
        // information; the digest says "these differ" in one line. The array comparison stays
        // underneath so that a bug in the digest could not make the test vacuous — it is only ever
        // reached when the digests already agree.
        assertThat(digest(atBuildTime))
                .as("the build-time driver and the agent must agree, or a class woven in a "
                        + "build and recognised by an agent as already woven was recognised on a "
                        + "promise the framework does not keep")
                .isEqualTo(digest(underAgent));
        assertThat(atBuildTime).isEqualTo(underAgent);

        assertThat(digest(throughLoader))
                .as("the class-loader driver reaches the engine through a third discovery and a "
                        + "third configuration; agreeing with the other two is what says none of "
                        + "them carries weaving logic of its own")
                .isEqualTo(digest(atBuildTime));
        assertThat(throughLoader).isEqualTo(atBuildTime);
    }

    @Test
    @DisplayName("the fixture really is woven, so the agreement is not an agreement about nothing")
    void theFixtureIsWoven(@TempDir final Path work) throws Exception {
        final Path classes = fixture(work);
        final byte[] original = Files.readAllBytes(classes.resolve(TARGET_INTERNAL + ".class"));

        assertThat(digest(buildTime(work, classes)))
                .as("three drivers that all did nothing would agree perfectly and prove nothing")
                .isNotEqualTo(digest(original));
    }

    // -------------------------------------------------------------------------------------

    private static byte[] buildTime(final Path work, final Path classes) throws Exception {
        final Path output = copyOf(classes, work.resolve("build-time"));

        final WeaveMojo mojo = new WeaveMojo();
        mojo.setLog(new SystemStreamLog());
        set(mojo, "classesDirectory", output.toFile());
        mojo.execute();

        return Files.readAllBytes(output.resolve(TARGET_INTERNAL + ".class"));
    }

    private static byte[] underAgent(final Path work, final Path classes) throws Exception {
        final Path dump = work.resolve("agent-dump");
        final Path agentJar = agentJar(work);

        final List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agentJar + "=dump=" + dump,
                "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"),
                "fixture.Main");

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the child JVM did not finish").isTrue();
        assertThat(process.exitValue()).as("the agent run failed: %s", output).isZero();
        assertThat(output).as("the woven handler must have run: %s", output).contains("woven");

        return Files.readAllBytes(dump.resolve(TARGET_INTERNAL + ".woven.class"));
    }

    private static byte[] throughClassLoader(final Path work, final Path classes) throws Exception {
        final Path dump = work.resolve("loader-dump");
        final WeaverConfig config = ConfigLayer.builder().dumpDirectory(dump).build().resolve();
        final URL[] roots = {classes.toUri().toURL()};

        try (WeavingClassLoader loader = WeavingClassLoader.create(roots,
                CrossDriverEquivalenceTest.class.getClassLoader(), config, diagnostic -> {
                })) {
            final Class<?> target = loader.loadClass("fixture.Target");
            assertThat(target.getClassLoader())
                    .as("the loader must have defined it itself; a class its parent supplied "
                            + "would be the unwoven one and the comparison would be vacuous")
                    .isSameAs(loader);
        }
        return Files.readAllBytes(dump.resolve(TARGET_INTERNAL + ".woven.class"));
    }

    private static Path fixture(final Path work) throws IOException {
        final Path classes = Files.createDirectories(work.resolve("classes"));
        Fixtures.compile(classes, TARGET, TRACE, WEAVE, MAIN);

        final Path manifest = classes.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(
                new WeaveManifest.Weave("fixture.Audit", "INSTANCE", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of("fixture.Target"), List.of(), List.of())))));
        return classes;
    }

    private static Path copyOf(final Path from, final Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (final Path source : paths.toList()) {
                final Path destination = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination);
                }
            }
        }
        return to;
    }

    private static Path agentJar(final Path work) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Premain-Class", "de.splatgames.aether.weaver.agent.WeaverAgent");
        main.putValue("Can-Retransform-Classes", "true");

        final Path jar = work.resolve("agent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
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

    private static String digest(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

            public class Trace {
                public static void say(String what) {
                    System.out.println(what);
                }
            }
            """;

    private static final String WEAVE = """
            package fixture;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.Inject;
            import de.splatgames.aether.weaver.api.Point;
            import de.splatgames.aether.weaver.api.Weave;

            @Weave(Target.class)
            public final class Audit {

                @Inject(method = "greet()", at = @At(Point.HEAD))
                void onGreet() {
                    Trace.say("woven");
                }
            }
            """;

    private static final String MAIN = """
            package fixture;

            public class Main {
                public static void main(String[] args) {
                    new Target().greet();
                }
            }
            """;
}
