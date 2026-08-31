package de.splatgames.aether.weaver.runtime;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.runtime.config.ConfigLayer;
import de.splatgames.aether.weaver.runtime.config.ErrorPolicy;
import de.splatgames.aether.weaver.runtime.config.PolicyConfig;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeavingClassLoaderTest {

    private static final String MARKER = "aether.test.woven";

    private final List<Diagnostic> reported = new ArrayList<>();

    @BeforeEach
    void clearMarker() {
        System.clearProperty(MARKER);
    }

    @AfterEach
    void removeMarker() {
        System.clearProperty(MARKER);
    }

    @Nested
    @DisplayName("classes this loader defines are woven")
    class Weaving {

        @Test
        @DisplayName("the injected handler runs")
        void theTargetIsWoven(@TempDir final Path work) throws Exception {
            final Path root = fixture(work);

            try (WeavingClassLoader loader = create(root)) {
                run(loader.loadClass("fx.Service"));
            }

            assertThat(System.getProperty(MARKER))
                    .as("the handler is injected at HEAD of run(); if nothing were woven the "
                            + "method would return without touching this property")
                    .isEqualTo("yes");
        }

        @Test
        @DisplayName("counter-probe: the same fixture loaded plainly is not woven")
        void theFixtureDoesNotSetItItself(@TempDir final Path work) throws Exception {
            final Path root = fixture(work);

            try (URLClassLoader plain = new URLClassLoader(new URL[]{url(root)}, parent())) {
                run(plain.loadClass("fx.Service"));
            }

            assertThat(System.getProperty(MARKER))
                    .as("without this the whole suite would pass on a fixture that set the "
                            + "property in its own body")
                    .isNull();
        }

        @Test
        @DisplayName("the woven class keeps the code source it was read from")
        void theProtectionDomainIsReconstructed(@TempDir final Path work) throws Exception {
            final Path root = fixture(work);

            try (WeavingClassLoader loader = create(root)) {
                final Class<?> service = loader.loadClass("fx.Service");

                assertThat(service.getProtectionDomain().getCodeSource().getLocation())
                        .as("a woven class that quietly gained a different code source would have "
                                + "different permissions from the class it replaced, and nothing "
                                + "would report the difference")
                        .isEqualTo(url(root));
            }
        }

        @Test
        @DisplayName("a class the plan says nothing about is defined unchanged")
        void untargetedClassesAreLeftAlone(@TempDir final Path work) throws Exception {
            final Path root = fixture(work);
            final byte[] onDisk = Files.readAllBytes(root.resolve("fx/Bystander.class"));

            try (WeavingClassLoader loader = create(root)) {
                final Class<?> bystander = loader.loadClass("fx.Bystander");

                assertThat(bystander.getDeclaredMethods()).hasSize(1);
            }
            assertThat(Files.readAllBytes(root.resolve("fx/Bystander.class")))
                    .as("nothing is written back; the loader must not have touched the artefact")
                    .isEqualTo(onDisk);
        }
    }

    @Nested
    @DisplayName("only what this loader defines is woven")
    class Delegation {

        @Test
        @DisplayName("a target the parent can also see is loaded by the parent, unwoven")
        void theParentWins(@TempDir final Path work) throws Exception {
            final Path root = fixture(work);

            // The arrangement the class documentation warns about, made concrete: the parent has
            // the same artefact, so parent-first delegation defines the target there.
            try (URLClassLoader parent = new URLClassLoader(new URL[]{url(root)}, parent());
                 WeavingClassLoader loader = create(root, parent, defaults())) {
                final Class<?> service = loader.loadClass("fx.Service");
                run(service);

                assertThat(service.getClassLoader())
                        .as("delegation is ordinary parent-first, because the alternative breaks "
                                + "every assumption Java code makes about type identity")
                        .isSameAs(parent);
            }

            assertThat(System.getProperty(MARKER))
                    .as("this is the failure mode the documentation exists for, and it is "
                            + "asserted rather than described: there is no diagnostic for it, "
                            + "because a target loaded by a parent is one this loader is never "
                            + "asked about")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("signed artefacts")
    class Signed {

        @Test
        @DisplayName("a class from a signed jar is defined unwoven, and AW3002 says so")
        void signedClassesAreRefused(@TempDir final Path work) throws Exception {
            final Path jar = signedJar(work);

            try (WeavingClassLoader loader = create(jar)) {
                final Class<?> service = loader.loadClass("fx.Service");
                run(service);

                assertThat(service.getProtectionDomain().getCodeSource().getCertificates())
                        .as("if the fixture were not really signed this test would prove nothing")
                        .isNotEmpty();
            }

            assertThat(System.getProperty(MARKER))
                    .as("woven bytes are not covered by the signature that was applied to the "
                            + "artefact, so a consumer verifying it would find a class the signer "
                            + "never saw")
                    .isNull();
            assertThat(codes()).contains("AW3002");
        }

        @Test
        @DisplayName("the override weaves it and the run says the override was used")
        void theOverrideIsHonoured(@TempDir final Path work) throws Exception {
            final Path jar = signedJar(work);
            final WeaverConfig relaxed = ConfigLayer.builder()
                    .policy(new PolicyConfig(true, java.util.Set.of()))
                    .build()
                    .resolve();

            try (WeavingClassLoader loader = create(jar, parent(), relaxed)) {
                run(loader.loadClass("fx.Service"));
            }

            assertThat(System.getProperty(MARKER)).isEqualTo("yes");
            assertThat(codes()).doesNotContain("AW3002");
        }
    }

    @Nested
    @DisplayName("when weaving fails")
    class Failures {

        @Test
        @DisplayName("onError=fail throws rather than halting the JVM")
        void strictThrows(@TempDir final Path work) throws Exception {
            final Path root = corruptedFixture(work);
            final WeaverConfig strict = ConfigLayer.builder()
                    .onError(ErrorPolicy.FAIL)
                    .build()
                    .resolve();

            try (WeavingClassLoader loader = create(root, parent(), strict)) {
                assertThatThrownBy(() -> loader.loadClass("fx.Service"))
                        .as("a transformer has nothing it can throw — the JVM discards it and "
                                + "carries on with the original bytes. From findClass an exception "
                                + "is real, so this driver can be strict without halting")
                        .isInstanceOf(ClassNotFoundException.class)
                        .hasMessageContaining("onError=fail");
            }
            assertThat(codes()).contains("AW4090");
        }

        @Test
        @DisplayName("onError=report defines the original bytes and says what happened")
        void reportContinues(@TempDir final Path work) throws Exception {
            final Path root = corruptedFixture(work);
            final WeaverConfig lenient = ConfigLayer.builder()
                    .onError(ErrorPolicy.REPORT)
                    .build()
                    .resolve();

            try (WeavingClassLoader loader = create(root, parent(), lenient)) {
                // The original bytes are deliberately unloadable, so the JVM rejects them here.
                // What is being asserted is which side rejected them: the class reached the JVM,
                // rather than being refused by the loader.
                assertThatThrownBy(() -> loader.loadClass("fx.Service"))
                        .isInstanceOf(ClassFormatError.class);
            }
            assertThat(codes())
                    .as("a weave that did not apply must never be silent")
                    .contains("AW4090");
        }
    }

    @Nested
    @DisplayName("modules")
    class Modules {

        @Test
        @DisplayName("what this loader defines is in an unnamed module, which reads everything")
        void noReadEdgeIsEverNeeded(@TempDir final Path work) throws Exception {
            final Path root = fixture(work);

            try (WeavingClassLoader loader = create(root)) {
                final Module module = loader.loadClass("fx.Service").getModule();

                assertThat(module.isNamed())
                        .as("a loader with no module layer to define into cannot produce a class "
                                + "in a named module, which is the only place a missing read edge "
                                + "exists")
                        .isFalse();
                assertThat(module.canRead(WeavingClassLoader.class.getModule()))
                        .as("spike 8b warned that the JVM's automatic read edge does not cover a "
                                + "weaver in this loader. It does not need to: an unnamed module "
                                + "reads every module unconditionally")
                        .isTrue();
                assertThat(module.canRead(String.class.getModule())).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("the AOT cache warning")
    class AotWarning {

        @Test
        @DisplayName("this JVM has no cache, so construction is quiet")
        void quietWithoutACache(@TempDir final Path work) throws Exception {
            try (WeavingClassLoader ignored = create(fixture(work))) {
                assertThat(codes())
                        .as("AW2401 must not fire on an ordinary JVM, or it would be noise "
                                + "everywhere and read as noise where it matters")
                        .doesNotContain("AW2401");
            }
        }
    }

    @Nested
    @DisplayName("the handles it opens")
    class Handles {

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("no handle on the artefact survives close()")
        void noHandleSurvivesClose(@TempDir final Path work) throws Exception {
            final Path jar = signedJar(work);

            try (WeavingClassLoader loader = create(jar)) {
                loader.loadClass("fx.Service");

                assertThat(handlesTo(jar))
                        .as("if this is empty the probe below proves nothing, because it would "
                                + "report success on a loader that never opened the jar at all")
                        .isNotEmpty();
            }

            assertThat(handlesTo(jar))
                    .as("Windows cannot delete a file that is still open, which is how this was "
                            + "found: there every jar-backed test failed on @TempDir cleanup and "
                            + "every directory-backed one passed. Reading a jar through "
                            + "URLConnection puts a JarFile in the JVM-wide cache that neither the "
                            + "stream nor super.close() releases")
                    .isEmpty();
        }
    }

    // -------------------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private WeavingClassLoader create(final Path root) throws IOException {
        return create(root, parent(), defaults());
    }

    private WeavingClassLoader create(final Path root, final ClassLoader parent,
                                      final WeaverConfig config) throws IOException {
        return WeavingClassLoader.create(new URL[]{url(root)}, parent, config, this.reported::add);
    }

    private static WeaverConfig defaults() {
        return WeaverConfig.defaults();
    }

    private static ClassLoader parent() {
        return WeavingClassLoaderTest.class.getClassLoader();
    }

    private static void run(final Class<?> type) throws Exception {
        type.getMethod("run").invoke(type.getDeclaredConstructor().newInstance());
    }

    private static URL url(final Path root) throws IOException {
        return root.toUri().toURL();
    }

    private static Path fixture(final Path work) throws IOException {
        final Path root = Files.createDirectories(work.resolve("classes"));
        compile(root, SERVICE, BYSTANDER, TRACE);
        manifest(root);
        return root;
    }

    private static Path corruptedFixture(final Path work) throws IOException {
        final Path root = fixture(work);
        final Path target = root.resolve("fx/Service.class");
        final byte[] bytes = Files.readAllBytes(target);
        Files.write(target, Arrays.copyOf(bytes, 12));
        return root;
    }

    private static Path signedJar(final Path work) throws Exception {
        final Path root = fixture(work.resolve("signed"));
        final Path jar = work.resolve("fixture.jar");
        final Path keystore = work.resolve("keystore.p12");

        tool("jar", "--create", "--file", jar.toString(), "-C", root.toString(), ".");
        tool("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=aether-weaver-test", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit");
        tool("jarsigner", "-keystore", keystore.toString(), "-storepass", "changeit",
                jar.toString(), "fixture");
        return jar;
    }

    private static void tool(final String name, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", name).toString());
        command.addAll(List.of(arguments));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("%s hung", name).isTrue();
        assertThat(process.exitValue()).as("%s failed: %s", name, output).isZero();
    }

    private static void manifest(final Path root) throws IOException {
        final Path file = root.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ManifestWriter.write(WeaveManifest.of("test",
                List.of(new WeaveManifest.Weave("fx.Trace", "STATIC", 0, "REQUIRED", "DEFAULT",
                        List.of(), List.of("fx.Service"), List.of(), List.of())))));
    }

    private static void compile(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new ArrayList<>();
            for (final String source : sources) {
                units.add(new Source(pathOf(source), source));
            }
            assertThat(compiler.getTask(null, files, null,
                    List.of("-cp", System.getProperty("java.class.path"), "-proc:none"),
                    null, units).call())
                    .as("the fixtures must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String pathOf(final String source) {
        final Matcher pkg = Pattern.compile("(?m)^package (\\S+);").matcher(source);
        final Matcher type = Pattern.compile("(?m)^public (?:final )?class (\\w+)").matcher(source);
        assertThat(pkg.find() && type.find()).as("every fixture has a package and a class").isTrue();
        return pkg.group(1).replace('.', '/') + '/' + type.group(1);
    }

    private static final String SERVICE = """
            package fx;

            public class Service {
                public void run() {
                }
            }
            """;

    private static final String BYSTANDER = """
            package fx;

            public class Bystander {
                public void idle() {
                }
            }
            """;

    private static final String TRACE = """
            package fx;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.Inject;
            import de.splatgames.aether.weaver.api.Point;
            import de.splatgames.aether.weaver.api.Weave;

            @Weave(targets = "fx.Service", kind = Weave.Kind.STATIC)
            public final class Trace {

                @Inject(method = "run()", at = @At(Point.HEAD))
                public static void onRun() {
                    System.setProperty("aether.test.woven", "yes");
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

    private static List<String> handlesTo(final Path file) throws IOException {
        final String wanted = file.toAbsolutePath().normalize().toString();
        final List<String> found = new ArrayList<>();
        try (Stream<Path> descriptors = Files.list(Path.of("/proc/self/fd"))) {
            for (final Path descriptor : descriptors.toList()) {
                try {
                    if (Files.readSymbolicLink(descriptor).toString().equals(wanted)) {
                        found.add(descriptor.getFileName().toString());
                    }
                } catch (final IOException closed) {
                    // The descriptor went away while the directory was being walked, which simply
                    // means it is not held any more.
                }
            }
        }
        return found;
    }
}
