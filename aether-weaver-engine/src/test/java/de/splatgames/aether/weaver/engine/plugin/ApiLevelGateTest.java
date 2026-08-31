package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.spi.ConfigView;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiLevelGateTest {

    private static final int FROM_THE_FUTURE = 99;

    private static final int FROM_THE_PAST = PluginLoader.MINIMUM_SUPPORTED_API_LEVEL - 1;

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Test
    @DisplayName("a plugin reports the level it was COMPILED against, not the one it runs against")
    void theLevelIsBakedInAtCompileTime() throws Exception {
        assertThat(WeaverApi.LEVEL)
                .as("this test is only meaningful while the real level differs from the fake one")
                .isNotEqualTo(FROM_THE_FUTURE);

        final WeaverPlugin plugin = compilePluginAgainstLevel(FROM_THE_FUTURE);

        assertThat(plugin.apiLevel())
                .as("the plugin ran against a WeaverApi whose LEVEL is %d, yet reports %d — which "
                        + "is only possible because javac folded the constant into the plugin's "
                        + "own class file. That is the entire mechanism, and it is why "
                        + "apiLevel() is abstract rather than a default method",
                        WeaverApi.LEVEL, FROM_THE_FUTURE)
                .isEqualTo(FROM_THE_FUTURE);
    }

    @Test
    @DisplayName("apiLevel() reads no field — the value is in the instruction stream")
    void theValueIsInTheCodeNotInAFieldRead() throws Exception {
        final Path compiled = compileToDirectory(FROM_THE_FUTURE);
        final byte[] bytes = Files.readAllBytes(compiled.resolve("probe/ProbePlugin.class"));

        final ClassModel model = ClassFile.of().parse(bytes);
        final MethodModel apiLevel = model.methods().stream()
                .filter(m -> "apiLevel".equals(m.methodName().stringValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture must declare apiLevel()"));

        final List<CodeElement> body = apiLevel.code().orElseThrow().elementList();

        assertThat(body)
                .as("a field read would mean the level is resolved at runtime, against whichever "
                        + "WeaverApi happens to be present — and the gate would be measuring the "
                        + "engine rather than the plugin")
                .noneMatch(FieldInstruction.class::isInstance);

        assertThat(body)
                .filteredOn(ConstantInstruction.class::isInstance)
                .map(ConstantInstruction.class::cast)
                .extracting(ConstantInstruction::constantValue)
                .as("the number itself is what javac wrote into the plugin's own class file")
                .containsExactly(FROM_THE_FUTURE);
    }

    @Test
    @DisplayName("the folded owner class nevertheless lingers in the constant pool")
    void theOwnerClassRemainsInterned() throws Exception {
        final Path compiled = compileToDirectory(FROM_THE_FUTURE);
        final byte[] bytes = Files.readAllBytes(compiled.resolve("probe/ProbePlugin.class"));

        final boolean interned = new String(bytes, StandardCharsets.ISO_8859_1)
                .contains("de/splatgames/aether/weaver/api/spi/WeaverApi");

        assertThat(interned)
                .as("recorded because it surprised us: folding removes the field *read*, but "
                        + "javac still interns the owner as a class entry, unreachable from any "
                        + "instruction. Same shape as the constant-pool residue that made "
                        + "ConstantPoolSharingOption.NEW_POOL mandatory in the remapper — harmless "
                        + "to execution, visible to javap and to dependency analysers, and a trap "
                        + "for anyone who tries to prove the folding by grepping the bytes")
                .isTrue();
    }

    @Test
    @DisplayName("a plugin from the future is refused with AW3112 before it can contribute")
    void tooNewIsRefusedAtPrepare() throws Exception {
        final WeaverPlugin plugin = compilePluginAgainstLevel(FROM_THE_FUTURE);

        final PluginRegistry registry = PluginLoader.load(List.of(plugin),
                PluginLoader.acceptAll(), id -> ConfigView.empty(), this.listener);

        assertThat(registry.isEmpty()).isTrue();
        assertThat(this.reported).singleElement().satisfies(d -> {
            assertThat(d.code().code()).isEqualTo("AW3112");
            assertThat(d.details())
                    .anySatisfy(detail -> assertThat(detail).contains("99"));
        });
    }

    @Test
    @DisplayName("a plugin from before the supported range is refused with AW3113")
    void tooOldIsRefusedAtPrepare() throws Exception {
        final WeaverPlugin plugin = compilePluginAgainstLevel(FROM_THE_PAST);

        final PluginRegistry registry = PluginLoader.load(List.of(plugin),
                PluginLoader.acceptAll(), id -> ConfigView.empty(), this.listener);

        assertThat(registry.isEmpty()).isTrue();
        assertThat(this.reported).singleElement().satisfies(d ->
                assertThat(d.code().code()).isEqualTo("AW3113"));
    }

    @Test
    @DisplayName("a plugin compiled against the current level loads")
    void currentLevelLoads() throws Exception {
        final WeaverPlugin plugin = compilePluginAgainstLevel(WeaverApi.LEVEL);

        final PluginRegistry registry = PluginLoader.load(List.of(plugin),
                PluginLoader.acceptAll(), id -> ConfigView.empty(), this.listener);

        assertThat(registry.plugins()).hasSize(1);
        assertThat(this.reported).isEmpty();
    }

    private static WeaverPlugin compilePluginAgainstLevel(final int level) throws Exception {
        final Path compiled = compileToDirectory(level);

        // Parent is this test's loader, which carries the REAL api. The plugin class itself comes
        // from the temporary directory, and the fake WeaverApi is deliberately NOT on this path:
        // if the level were read at runtime it would resolve to the real one and the test would
        // measure nothing.
        final URLClassLoader loader = new URLClassLoader(
                new URL[]{compiled.toUri().toURL()}, ApiLevelGateTest.class.getClassLoader());
        final Class<?> type = loader.loadClass("probe.ProbePlugin");
        return (WeaverPlugin) type.getDeclaredConstructor().newInstance();
    }

    private static Path compileToDirectory(final int level) throws IOException {
        final Path apiClasses = codeSourceOf(WeaverApi.class);
        final Path fakeApi = Files.createTempDirectory("aether-weaver-fake-api");
        final Path pluginOut = Files.createTempDirectory("aether-weaver-probe-plugin");

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK is required to run this test").isNotNull();

        // 1. A WeaverApi that disagrees with the real one about LEVEL.
        compile(compiler, List.of(apiClasses), fakeApi, new InMemorySource(
                "de.splatgames.aether.weaver.api.spi.WeaverApi",
                """
                package de.splatgames.aether.weaver.api.spi;

                public final class WeaverApi {
                    public static final int LEVEL = %d;
                    private WeaverApi() { }
                }
                """.formatted(level)));

        // 2. A plugin compiled against it. The fake directory comes first, so javac resolves
        //    WeaverApi there and folds ITS value into the plugin's class file.
        compile(compiler, List.of(fakeApi, apiClasses), pluginOut, new InMemorySource(
                "probe.ProbePlugin",
                """
                package probe;

                import de.splatgames.aether.weaver.api.spi.PluginId;
                import de.splatgames.aether.weaver.api.spi.WeaverApi;
                import de.splatgames.aether.weaver.api.spi.WeaverPlugin;

                public final class ProbePlugin implements WeaverPlugin {

                    private static final PluginId ID = new PluginId("probe", "Probe", "1.0");

                    @Override public PluginId id() { return ID; }

                    // Exactly what every plugin is told to write.
                    @Override public int apiLevel() { return WeaverApi.LEVEL; }
                }
                """));

        return pluginOut;
    }

    private static void compile(final JavaCompiler compiler, final List<Path> classpath,
                                final Path output, final JavaFileObject source) throws IOException {
        final DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collected, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));

            final boolean ok = compiler
                    .getTask(null, files, collected, List.of(), null, List.of(source)).call();

            assertThat(ok)
                    .as("fixture compilation failed: %s", collected.getDiagnostics())
                    .isTrue();
        }
    }

    private static Path codeSourceOf(final Class<?> type) {
        final CodeSource source = type.getProtectionDomain().getCodeSource();
        assertThat(source).as("%s must have a locatable code source", type).isNotNull();
        return Path.of(URI.create(source.getLocation().toString()));
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String code;

        InMemorySource(final String name, final String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.code;
        }
    }
}
