package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionRoundTripTest {

    private static final WeaveManifest.Extension SHOUT = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "shout",
            "(I)Ljava/lang/String;");

    private static final WeaveManifest.Extension WORDS = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "words",
            "()Ljava/util/List;");

    private static final WeaveManifest.Extension INITIAL = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Named",
            "initial",
            "()Ljava/lang/String;");

    private static final WeaveManifest.Extension OF = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "of",
            "(Ljava/lang/String;)Lde/splatgames/aether/weaver/engine/extension/fixture/Greeting;",
            WeaveManifest.Extension.Kind.STATIC);

    private static final WeaveManifest.Extension READ = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "read",
            "()Ljava/lang/String;");

    private static final WeaveManifest.Extension JOIN = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "join",
            "([Ljava/lang/String;)Ljava/lang/String;");

    private static final WeaveManifest.Extension DEFAULT = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "DEFAULT",
            "Lde/splatgames/aether/weaver/engine/extension/fixture/Greeting;",
            WeaveManifest.Extension.Kind.CONSTANT);

    private static final WeaveManifest.Extension PREFIX = new WeaveManifest.Extension(
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions",
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "PREFIX",
            "Ljava/lang/String;",
            WeaveManifest.Extension.Kind.CONSTANT);

    private static final List<WeaveManifest.Extension> ALL =
            List.of(SHOUT, WORDS, INITIAL, OF, READ, JOIN, DEFAULT, PREFIX);

    private static final ClassSource CLASSPATH = internalName -> {
        try (InputStream in = ExtensionRoundTripTest.class.getResourceAsStream(
                '/' + internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (final IOException unreadable) {
            return Optional.empty();
        }
    };

    @Nested
    @DisplayName("the whole loop, through a real compiler")
    class RoundTrip {

        @Test
        @DisplayName("code compiled against a stub runs correctly once its call sites are rewritten")
        void compilesAndRuns(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return new Greeting("world").shout(2);
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            final byte[] rewritten = ExtensionCalls.rewrite(Files.readAllBytes(compiled), index());

            assertThat(rewritten)
                    .as("the caller does make an extension call, so it must have been rewritten")
                    .isNotNull();

            Files.write(compiled, rewritten);
            assertThat(run(classes))
                    .isEqualTo("HELLO WORLD! HELLO WORLD!");
        }

        @Test
        @DisplayName("the same class, unrewritten, fails loudly rather than quietly")
        void unrewrittenFailsLoudly(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return new Greeting("world").shout(2);
                        }
                    }
                    """);

            // Nothing is rewritten here. The class file genuinely says
            // `invokevirtual Greeting.shout`, and Greeting genuinely has no such method — so a
            // build that skipped weaving is reported by the first call rather than by a subtly
            // different result. This is the behaviour @Extension documents, and it only holds if
            // the stub is never on the runtime classpath, which is what this asserts.
            assertThatThrownBy(() -> run(classes))
                    .isInstanceOf(NoSuchMethodError.class)
                    .hasMessageContaining("shout");
        }

        @Test
        @DisplayName("a generic return type survives the stub")
        void genericsSurvive(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);

            // This only compiles if the stub carried `()Ljava/util/List<Ljava/lang/String;>;`
            // across. Without the Signature attribute the call yields a raw List, and assigning it
            // to List<String> is an unchecked warning rather than an error — so the test asks for
            // something erasure cannot fake: a String out of the list, with no cast.
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            String first = new Greeting("world").words().get(0);
                            return first.toUpperCase();
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));

            assertThat(run(classes)).isEqualTo("HELLO");
        }
    }

    @Nested
    @DisplayName("a receiver that is not the type the call site names")
    class Inheritance {

        @Test
        @DisplayName("an extension on a supertype is reached from a subtype's call site")
        void supertypeIsResolved(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);

            // `initial` is contributed to Named. The variable is a Greeting, so javac emits
            // `invokevirtual …/Greeting.initial` — an owner no extension was ever declared on.
            // Comparing owners alone finds nothing here, and the class would compile and then throw
            // NoSuchMethodError, which is what this whole test class exists to catch.
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            Greeting greeting = new Greeting("world");
                            return greeting.initial();
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            final byte[] rewritten = ExtensionCalls.rewrite(Files.readAllBytes(compiled), index());
            assertThat(rewritten)
                    .as("the call names Greeting, and Greeting is a Named: it must be rewritten")
                    .isNotNull();

            Files.write(compiled, rewritten);
            assertThat(run(classes)).isEqualTo("w");
        }

        @Test
        @DisplayName("a static extension is reached where the call site names the type")
        void staticIsResolved(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return Greeting.of("world").greet();
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            final byte[] rewritten = ExtensionCalls.rewrite(Files.readAllBytes(compiled), index());
            assertThat(rewritten)
                    .as("Greeting.of is an invokestatic on a type that never declared it")
                    .isNotNull();

            Files.write(compiled, rewritten);
            assertThat(run(classes)).isEqualTo("hello world");
        }
    }

    @Nested
    @DisplayName("a constant, which is read rather than called")
    class Constants {

        @Test
        @DisplayName("a contributed constant is read off the holder")
        void constantIsRepointed(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return Greeting.DEFAULT.greet();
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            final byte[] rewritten = ExtensionCalls.rewrite(Files.readAllBytes(compiled), index());
            assertThat(rewritten)
                    .as("`Greeting.DEFAULT` is a getstatic naming a field Greeting does not have")
                    .isNotNull();

            Files.write(compiled, rewritten);
            assertThat(run(classes)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("a compile-time constant is inlined, and its value comes from the stub")
        void compileTimeConstantIsInlined(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return Greeting.PREFIX + "there";
                        }
                    }
                    """);

            // Nothing is woven at all here, and that is the assertion. javac inlined the value out
            // of the stub, so a stub carrying only the type would have compiled the wrong string
            // into the caller for ever — and no later weave could have corrected it.
            final Path compiled = classes.resolve("probe/Caller.class");
            assertThat(ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()))
                    .as("a constant the compiler inlined leaves nothing behind to rewrite")
                    .isNull();
            assertThat(run(classes)).isEqualTo("hello there");
        }
    }

    @Nested
    @DisplayName("a method reference, which is not an instruction at all")
    class MethodReferences {

        @Test
        @DisplayName("an unbound reference to an extension runs")
        void unboundReferenceRuns(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);

            // `Greeting::initial` compiles to an invokedynamic whose bootstrap arguments carry a
            // MethodHandle for Greeting.initial. Nothing in the instruction stream calls it — the
            // call is generated by LambdaMetafactory at run time, from that handle — so a rewrite
            // that only looked at instructions left this compiling and then failing on first use.
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
                    import java.util.function.Function;

                    public final class Caller {
                        public static String run() {
                            Function<Greeting, String> initial = Greeting::initial;
                            return initial.apply(new Greeting("world"));
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            final byte[] rewritten = ExtensionCalls.rewrite(Files.readAllBytes(compiled), index());
            assertThat(rewritten)
                    .as("the handle names a method that does not exist; leaving it is a class that "
                            + "compiles and throws")
                    .isNotNull();

            Files.write(compiled, rewritten);
            assertThat(run(classes)).isEqualTo("w");
        }

        @Test
        @DisplayName("a bound reference runs too, with the receiver captured")
        void boundReferenceRuns(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
                    import java.util.function.IntFunction;

                    public final class Caller {
                        public static String run() {
                            Greeting greeting = new Greeting("world");
                            IntFunction<String> shout = greeting::shout;
                            return shout.apply(2);
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            // A bound reference captures the receiver as an argument to the call site, which is
            // exactly where an invokestatic taking it as parameter zero expects it. The bootstrap
            // arguments the compiler wrote stay correct, and only the handle moves.
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));
            assertThat(run(classes)).isEqualTo("HELLO WORLD! HELLO WORLD!");
        }

        @Test
        @DisplayName("a reference to a static extension runs")
        void staticReferenceRuns(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
                    import java.util.function.Function;

                    public final class Caller {
                        public static String run() {
                            Function<String, Greeting> of = Greeting::of;
                            return of.apply("world").greet();
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));
            assertThat(run(classes)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("an ordinary method reference beside it is left alone")
        void ordinaryReferenceIsUntouched(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
                    import java.util.function.Function;

                    public final class Caller {
                        public static String run() {
                            Function<Greeting, String> greet = Greeting::greet;
                            Function<Greeting, String> initial = Greeting::initial;
                            return greet.apply(new Greeting("world")) + initial.apply(
                                    new Greeting("world"));
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));
            assertThat(run(classes))
                    .as("greet() is a real method of Greeting; repointing it would break a call "
                            + "site that was already correct")
                    .isEqualTo("hello worldw");
        }

        @Test
        @DisplayName("a lambda over an extension call was always fine, and stays fine")
        void lambdasStillWork(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            // Worth stating as a test rather than as a comment: a lambda body is an ordinary
            // synthetic method holding an ordinary invokevirtual, so it needed nothing special —
            // and a rewrite of the bootstrap arguments must not disturb it.
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
                    import java.util.function.Function;

                    public final class Caller {
                        public static String run() {
                            Function<Greeting, String> initial = greeting -> greeting.initial();
                            return initial.apply(new Greeting("world"));
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));
            assertThat(run(classes)).isEqualTo("w");
        }
    }

    @Nested
    @DisplayName("what the stub tells javac beyond the descriptor")
    class Declaration {

        @Test
        @DisplayName("a checked exception must be handled at the call site")
        void checkedExceptionIsDeclared(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final StringWriter errors = new StringWriter();

            // Not a style point. `read` declares IOException; if the stub does not, this compiles,
            // and the exception then travels out of a call nobody was told could throw it.
            final Path refused = tryCompile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return new Greeting("world").read();
                        }
                    }
                    """, errors);

            assertThat(refused)
                    .as("javac accepted an unhandled checked exception, so the stub dropped the "
                            + "throws clause: %s", errors)
                    .isNull();
            assertThat(errors.toString()).contains("IOException");
        }

        @Test
        @DisplayName("and handling it compiles, so the refusal above was about the exception")
        void checkedExceptionCanBeHandled(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
                    import java.io.IOException;

                    public final class Caller {
                        public static String run() {
                            try {
                                return new Greeting("world").read();
                            } catch (IOException impossible) {
                                return "caught";
                            }
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));
            assertThat(run(classes)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("varargs are written as varargs, not as an array")
        void varargsAreDeclared(@TempDir final Path directory) throws Exception {
            final Path stubs = writeStub(directory);
            final Path classes = compile(directory, stubs, """
                    package probe;

                    import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;

                    public final class Caller {
                        public static String run() {
                            return new Greeting("world").join("and", "goodbye");
                        }
                    }
                    """);

            final Path compiled = classes.resolve("probe/Caller.class");
            Files.write(compiled, ExtensionCalls.rewrite(Files.readAllBytes(compiled), index()));
            assertThat(run(classes)).isEqualTo("hello world and goodbye");
        }
    }

    // --- the machinery the tests above drive -------------------------------------------------

    private static ExtensionIndex index() {
        return ExtensionIndex.of(ALL, CLASSPATH, diagnostic -> {
            throw new AssertionError("unexpected " + diagnostic.code());
        });
    }

    private static Path writeStub(final Path directory) throws IOException {
        final ExtensionIndex index = ExtensionIndex.of(ALL, CLASSPATH, diagnostic -> {
            throw new AssertionError("unexpected " + diagnostic.code());
        });
        final Path stubs = directory.resolve("stubs");

        for (final String internal : index.receivers()) {
            final byte[] original = CLASSPATH.find(internal).orElseThrow();
            final byte[] patched = ExtensionStubs.patch(original,
                    index.contributedTo(internal), CLASSPATH);
            assertThat(patched)
                    .as("%s declares none of what is contributed to it, so a stub must be produced",
                            internal)
                    .isNotNull();

            final Path file = stubs.resolve(internal + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, patched);
        }
        return stubs;
    }

    private static Path compile(final Path directory, final Path stubs, final String source)
            throws IOException {
        final StringWriter errors = new StringWriter();
        final Path classes = tryCompile(directory, stubs, source, errors);

        assertThat(classes)
                .as("javac refused the extension call, which means the stub did not carry it: %s",
                        errors)
                .isNotNull();
        return classes;
    }

    @Nullable
    private static Path tryCompile(final Path directory, final Path stubs, final String source,
                                   final StringWriter errors) throws IOException {
        final Path sources = directory.resolve("src");
        final Path file = sources.resolve("probe/Caller.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);

        final Path classes = directory.resolve("classes");
        Files.createDirectories(classes);

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("these tests need a JDK, not a JRE; the build already requires one")
                .isNotNull();

        final boolean compiled = compiler.getTask(errors, null, null,
                List.of("-classpath", stubs + java.io.File.pathSeparator
                                + System.getProperty("java.class.path"),
                        "-d", classes.toString()),
                null,
                compiler.getStandardFileManager(null, null, null)
                        .getJavaFileObjects(file.toFile())).call();
        return compiled ? classes : null;
    }

    private static String run(final Path classes) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()},
                ExtensionRoundTripTest.class.getClassLoader())) {
            final Class<?> caller = Class.forName("probe.Caller", true, loader);
            final Method run = caller.getMethod("run");
            try {
                return (String) run.invoke(null);
            } catch (final java.lang.reflect.InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof final Error error) {
                    throw error;
                }
                throw wrapped;
            }
        }
    }
}
