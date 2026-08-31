package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.PointSpec;
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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the api module to the promise that an extension needs nothing else to build against.
 *
 * <p>An extension contributes injection points and injectors from outside the project. If writing one
 * required the engine on the compile classpath, the extension would be coupled to engine internals
 * that change between releases, and every such extension would break on an upgrade. The promise is
 * therefore about the api module's compiled output rather than about anyone's intent: a public
 * signature that mentions a type living elsewhere makes that type a dependency whether or not it was
 * meant to be one.
 *
 * <h2>What is actually compiled, and against what</h2>
 *
 * <p>{@link #codeSourceOf(Class)} takes the location an api class was loaded from, which under this
 * module's own test run is the module's compiled output directory, and that single directory is the
 * whole of {@link javax.tools.StandardLocation#CLASS_PATH} for the compilation. Nothing else is added
 * -- no annotation artefact, no engine, no test dependency -- so the fixture sees the api classes and
 * the platform classes the compiler supplies by default, and nothing more.
 *
 * <p>{@link #EXTENSION_SOURCE} imports {@code java.lang.classfile} types as well, which are part of
 * the JDK. An extension therefore needs a JDK new enough to carry that package, and no artefact of
 * this project beyond the api module.
 *
 * <h2>How this differs from the architecture test</h2>
 *
 * <p>{@code ProjectStructureTest} reads import statements to decide what a module may depend on.
 * That is a check on source. The cases here compile and run real code against the artefact a user
 * would get, so they catch what an import list cannot: a type reachable through a signature rather
 * than named in an import, and a class file that ended up in the api module's output without a
 * source file in it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class ExtensionCompilesAgainstApiAloneTest {

    /**
     * A complete extension, written as a plugin author would write one.
     *
     * <p>Chosen to touch the whole seam rather than one type of it: a contributed
     * {@link InjectionPoint} that searches a method body and reports a diagnostic of its own, the
     * {@link InjectionPointFactory} that publishes it under a namespace and an alias, an
     * {@link Injector} that validates an entry and returns an emitter, and the
     * {@link InjectorFactory} that publishes that. Each of them is a place where a leaked engine type
     * would show up as a compile error.
     *
     * <p>The source is never executed as a weave. It is compiled, and part of it is instantiated
     * reflectively; what the bodies do matters only in that they have to type-check.
     */
    private static final String EXTENSION_SOURCE = """
            package acme;

            import de.splatgames.aether.weaver.api.At;
            import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
            import de.splatgames.aether.weaver.api.diagnostic.PluginDiagnosticId;
            import de.splatgames.aether.weaver.api.diagnostic.Severity;
            import de.splatgames.aether.weaver.api.model.InjectorKind;
            import de.splatgames.aether.weaver.api.model.InjectorSpec;
            import de.splatgames.aether.weaver.api.model.PointSpec;
            import de.splatgames.aether.weaver.api.spi.Alias;
            import de.splatgames.aether.weaver.api.spi.CodeView;
            import de.splatgames.aether.weaver.api.spi.HandlerBinding;
            import de.splatgames.aether.weaver.api.spi.InjectionContext;
            import de.splatgames.aether.weaver.api.spi.InjectionPoint;
            import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
            import de.splatgames.aether.weaver.api.spi.Injector;
            import de.splatgames.aether.weaver.api.spi.InjectorFactory;
            import de.splatgames.aether.weaver.api.spi.MethodView;
            import de.splatgames.aether.weaver.api.spi.PlanEntryView;
            import de.splatgames.aether.weaver.api.spi.Reporter;
            import de.splatgames.aether.weaver.api.spi.Site;
            import de.splatgames.aether.weaver.api.spi.TargetView;

            import java.lang.classfile.CodeElement;
            import java.lang.classfile.CodeTransform;
            import java.lang.classfile.instruction.InvokeInstruction;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Set;

            public final class AcmeExtension {

                public static final InjectorKind WRAP = InjectorKind.of("acme:wrap");

                public static final PluginDiagnosticId NO_LOGGING = new PluginDiagnosticId(
                        "acme", "AX0001", Severity.WARNING, DiagnosticCode.Category.INJECTION_POINT,
                        "no logging call was found in the target method");

                public static final class AfterLoggingPoint implements InjectionPoint {

                    @Override
                    public String id() {
                        return "acme:AFTER_LOGGING";
                    }

                    @Override
                    public TargetRequirement targetRequirement() {
                        return TargetRequirement.FORBIDDEN;
                    }

                    @Override
                    public boolean supportsShift(At.Shift shift) {
                        return shift != At.Shift.BEFORE;
                    }

                    @Override
                    public List<Site> find(MethodView method, CodeView code,
                                           PointSpec spec, Reporter reporter) {
                        // Configuration this point defines for itself, which no built-in has and
                        // no annotation element anticipates.
                        String level = spec.arguments().getOrDefault("level", "INFO");
                        List<Site> sites = new ArrayList<>();
                        List<CodeElement> elements = code.elements();
                        for (int i = 0; i < elements.size(); i++) {
                            if (elements.get(i) instanceof InvokeInstruction invoke
                                    && invoke.owner().asInternalName().startsWith("org/slf4j/")) {
                                sites.add(new Site(i, Site.Kind.AFTER_ELEMENT, invoke));
                            }
                        }
                        if (sites.isEmpty()) {
                            reporter.report(NO_LOGGING, "no SLF4J call in " + method.describe());
                        }
                        return List.copyOf(sites);
                    }
                }

                public static final class AcmePoints implements InjectionPointFactory {

                    @Override
                    public String namespace() {
                        return "acme";
                    }

                    @Override
                    public Set<String> ids() {
                        return Set.of("acme:AFTER_LOGGING");
                    }

                    @Override
                    public Set<Alias> aliases() {
                        return Set.of(new Alias("acme:AFTER_LOG", "acme:AFTER_LOGGING", "0.2.0"));
                    }

                    @Override
                    public InjectionPoint create(String id) {
                        return new AfterLoggingPoint();
                    }
                }

                public static final class WrapInjector implements Injector {

                    @Override
                    public InjectorKind kind() {
                        return WRAP;
                    }

                    @Override
                    public void validate(PlanEntryView entry, TargetView target,
                                         Reporter reporter) {
                        if (target.methods().isEmpty()) {
                            reporter.report(NO_LOGGING, target.binaryName() + " has no methods");
                        }
                    }

                    @Override
                    public Emitter emitter(InjectionContext context) {
                        // Everything an injector needs comes off the context: which method, which
                        // positions in it, and how to reach the target's own arguments.
                        Set<Integer> where = Set.copyOf(context.sites());
                        InjectorSpec spec = context.entry().spec();
                        HandlerBinding arguments = context.argumentsAt(context.sites().get(0));
                        // The engine writes the element; an emitter only adds to it.
                        return (builder, element, index) -> Disposition.KEEP;
                    }
                }

                public static final class AcmeInjectors implements InjectorFactory {

                    @Override
                    public String namespace() {
                        return "acme";
                    }

                    @Override
                    public Set<InjectorKind> kinds() {
                        return Set.of(WRAP);
                    }

                    @Override
                    public Injector create(InjectorKind kind) {
                        return new WrapInjector();
                    }
                }
            }
            """;

    /**
     * Asserts that the extension compiles against the api module's output alone, with no diagnostic of
     * any kind, and that a class file is produced.
     *
     * <p>{@link javax.tools.DiagnosticCollector} collects notes as well as warnings and errors, so the
     * emptiness assertion is stricter than the success one: a deprecation, an unchecked call or a note
     * about an api type would fail the case even though the compilation succeeded. An extension author
     * sees those, and one about this project's own api is a defect in the api.
     *
     * <p>The collected diagnostics are interpolated into the description of the success assertion, so a
     * compile failure names the reason rather than only reporting a false.
     *
     * <p>The final assertion is on the output file, which pins that the compiler was actually given
     * somewhere to write and did.
     *
     * @throws IOException if the output directory or the file manager cannot be set up
     */
    @Test
    @DisplayName("a full extension compiles with only the api classes on the classpath")
    void extensionCompilesAgainstApiAlone() throws IOException {
        final Path apiClasses = codeSourceOf(Injector.class);
        final Path output = Files.createTempDirectory("aether-weaver-extension");

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK is required to run this test").isNotNull();

        final DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collected, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(apiClasses));
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));

            final boolean ok = compiler.getTask(null, files, collected, List.of(), null,
                    List.of(new InMemorySource("acme.AcmeExtension", EXTENSION_SOURCE))).call();

            assertThat(ok)
                    .as("an extension must compile against api alone; diagnostics: %s",
                            collected.getDiagnostics())
                    .isTrue();
            assertThat(collected.getDiagnostics())
                    .as("not even a warning: an extension author sees these, and a warning about "
                            + "our own API is a defect in our API")
                    .isEmpty();
        }

        assertThat(output.resolve("acme/AcmeExtension.class")).exists();
    }

    /**
     * Asserts that the directory used as that compile classpath holds no class file outside
     * {@code de/splatgames/aether/weaver/api/}.
     *
     * <p>Without it the compilation case would weaken quietly: an api module whose output had acquired
     * classes from somewhere else would still compile the fixture, and the promise would then be that an
     * extension needs the api artefact -- whatever happens to be inside it -- rather than the api.
     *
     * <p>Only class files are examined, so a resource in the output is not a violation, and every
     * offender is named in the failure rather than counted. The case says nothing about the classpath the
     * suite itself runs on, which carries the test dependencies but not the engine -- the engine depends
     * on the api, not the other way around.
     *
     * @throws IOException if the directory cannot be walked
     */
    @Test
    @DisplayName("the classpath that compiled it contains no engine class")
    void thatClasspathHasNoEngine() throws IOException {
        final Path apiClasses = codeSourceOf(Injector.class);

        try (var walk = Files.walk(apiClasses)) {
            final List<String> foreign = walk
                    .filter(Files::isRegularFile)
                    .map(p -> apiClasses.relativize(p).toString().replace('\\', '/'))
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.startsWith("de/splatgames/aether/weaver/api/"))
                    .toList();

            assertThat(foreign)
                    .as("the compile classpath for an extension must be the api module and nothing "
                            + "else — anything here would become a de facto plugin dependency")
                    .isEmpty();
        }
    }

    /**
     * Asserts that the compiled extension loads and answers through a loader that can reach the api
     * classes and nothing else of this project.
     *
     * <p>Compiling proves the signatures are self-contained; loading proves the class files are. A
     * constant folded at compile time hides a missing type from the compiler and not from the verifier,
     * and a supertype that lives outside the api would fail here rather than at compile time.
     *
     * <p>The loader's parent is the platform loader, so nothing of Aether Weaver is inherited from the
     * loader that runs the suite: the two URLs handed to it are the only route to an api class.
     *
     * <p>The factory is instantiated and its three methods invoked reflectively, which resolves the
     * return types as well as the class. Loading the injector afterwards resolves {@code Injector}, its
     * direct superinterface; the kind it names is not resolved, since {@code kind()} is never invoked and
     * the injector is not instantiated.
     *
     * <p>The compiler's verdict is not read here. A compilation that failed would surface as a
     * {@link ClassNotFoundException} from the first load rather than as a compile failure.
     *
     * @throws Exception if compilation, loading or reflection fails
     */
    @Test
    @DisplayName("the compiled extension loads and answers without the engine present")
    void compiledExtensionRunsWithoutTheEngine() throws Exception {
        final Path apiClasses = codeSourceOf(Injector.class);
        final Path output = Files.createTempDirectory("aether-weaver-extension-run");

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collected, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(apiClasses));
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            compiler.getTask(null, files, collected, List.of(), null,
                    List.of(new InMemorySource("acme.AcmeExtension", EXTENSION_SOURCE))).call();
        }

        // Parent is the platform loader, so nothing of Aether Weaver is inherited: the only way
        // these classes can resolve is through the api directory handed to this loader.
        final URL[] classpath = {apiClasses.toUri().toURL(), output.toUri().toURL()};
        try (URLClassLoader isolated = new URLClassLoader(
                classpath, ClassLoader.getPlatformClassLoader())) {

            final Class<?> factory = isolated.loadClass("acme.AcmeExtension$AcmePoints");
            final Object points = factory.getDeclaredConstructor().newInstance();

            final Object namespace = factory.getMethod("namespace").invoke(points);
            final Object ids = factory.getMethod("ids").invoke(points);
            final Object aliases = factory.getMethod("aliases").invoke(points);

            assertThat(namespace).isEqualTo("acme");
            assertThat(((Set<?>) ids).stream().map(String::valueOf).toList())
                    .containsExactly("acme:AFTER_LOGGING");
            assertThat((Set<?>) aliases).hasSize(1);

            assertThat(isolated.loadClass("acme.AcmeExtension$WrapInjector"))
                    .as("the injector loads too, from the same api-only classpath")
                    .isNotNull();
        }
    }

    /**
     * Asserts that {@link InjectorKind} admits a namespaced kind the framework never declared, and keeps
     * it distinguishable from a built-in one.
     *
     * <p>The kind is a value type rather than an enum, which is what lets a plugin name a kind at all;
     * an enum would close the set at whatever this release ships. Both halves are needed: an open set
     * without a way to tell the two apart would let a plugin's kind read as the framework's in
     * diagnostics and in a manifest.
     *
     * <p>The distinction is drawn from the spelling. A colon makes a kind a plugin's, so
     * {@link InjectorKind#INJECT} is built in and {@code acme:wrap} is not.
     */
    @Test
    @DisplayName("InjectorKind is a value type, so a plugin can add a kind we never declared")
    void injectorKindIsOpen() {
        final InjectorKind contributed = InjectorKind.of("acme:wrap");

        assertThat(contributed.isBuiltIn())
                .as("a contributed kind must be distinguishable from ours")
                .isFalse();
        assertThat(contributed.namespace()).isEqualTo("acme");
        assertThat(InjectorKind.INJECT.isBuiltIn()).isTrue();
    }

    /**
     * Asserts that {@link PointSpec} carries free-form arguments for a contributed point, and that a
     * built-in point carries none.
     *
     * <p>A contributed injection point needs configuration that no element of {@code @At} anticipates,
     * and there is nowhere else to put it: {@link PointSpec} is a record, so a component added after
     * release changes its canonical constructor. The map has to be there before anyone needs it.
     *
     * <p>The second assertion is the boundary. A built-in point takes its configuration from
     * {@code @At}'s own elements, so the argument map is empty rather than a second place the same
     * setting could be written.
     */
    @Test
    @DisplayName("PointSpec carries arguments a contributed point defines for itself")
    void pointSpecCarriesCustomArguments() {
        final PointSpec spec = PointSpec.named("acme:AFTER_LOGGING")
                .arguments(Map.of("level", "WARN"))
                .build();

        assertThat(spec.arguments())
                .as("without this seam, every contributed point that needs configuration would "
                        + "require a new @At attribute — and adding a component to this record "
                        + "after release is a breaking change, so it had to exist before anyone "
                        + "needed it")
                .containsEntry("level", "WARN");
        assertThat(PointSpec.builtIn(Point.HEAD).build().arguments())
                .as("a built-in point takes its configuration from @At's own attributes")
                .isEmpty();
    }

    /**
     * Returns the location a class was loaded from.
     *
     * <p>Used with an api type, so the answer is the api module's compiled output. The result is treated
     * as a directory by the cases that walk it, which is what it is when the suite runs against the
     * module's own output rather than against a packaged artefact.
     *
     * @param type the class whose origin is wanted
     * @return the code source location as a path
     */
    private static Path codeSourceOf(final Class<?> type) {
        final CodeSource source = type.getProtectionDomain().getCodeSource();
        assertThat(source).as("%s must have a locatable code source", type).isNotNull();
        return Path.of(URI.create(source.getLocation().toString()));
    }

    /**
     * A compilation unit held as a string rather than as a file.
     *
     * <p>Keeps the fixture beside the assertions that depend on it, and keeps the compilation input out
     * of the temporary directory the output goes to, so nothing the compiler writes can be mistaken for
     * something it read.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class InMemorySource extends SimpleJavaFileObject {

        /** The source text of the compilation unit. */
        private final String code;

        /**
         * Creates a source file object for a binary name.
         *
         * <p>The URI is derived from the name so that the compiler's own reports name the unit the way a
         * file would, which is what makes a collected diagnostic readable.
         *
         * @param name the binary name of the type being compiled
         * @param code the source text
         */
        InMemorySource(final String name, final String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        /**
         * Returns the source text.
         *
         * @param ignoreEncodingErrors ignored; the text is already characters
         * @return the source text
         */
        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.code;
        }
    }
}
