package de.splatgames.aether.weaver.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionChecksTest {

    @Nested
    @DisplayName("a declaration that is accepted")
    class Accepted {

        @Test
        @DisplayName("a well-formed extension reports nothing and reaches the manifest")
        void wellFormed() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension
                    public final class Strings {

                        public static String shout(@Receiver String self, int times) {
                            return (self.toUpperCase() + "!").repeat(times);
                        }

                        private static String helper(String text) {
                            return text.strip();
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("a private helper needs no receiver; requiring one would stop an extension "
                            + "class factoring out its own code")
                    .isEmpty();
            assertThat(compiled.manifest())
                    .contains("\"receiver\": \"java.lang.String\"")
                    .contains("\"name\": \"shout\"")
                    .contains("\"descriptor\": \"(I)Ljava/lang/String;\"");
        }

        @Test
        @DisplayName("the manifest stores the call site's descriptor, not the method's")
        void descriptorOmitsTheReceiver() {
            // The whole rewrite is a lookup by owner, name and descriptor against what the class
            // file holds. If the manifest stored (Ljava/lang/String;I)Ljava/lang/String; — the
            // implementation's own descriptor — nothing would ever match and every extension call
            // would survive to throw NoSuchMethodError at runtime.
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension
                    public final class Strings {
                        public static String shout(@Receiver String self, int times) {
                            return self;
                        }
                    }
                    """).manifest())
                    .doesNotContain("(Ljava/lang/String;I)Ljava/lang/String;");
        }
    }

    @Nested
    @DisplayName("a receiver named on the method rather than on a parameter")
    class Static {

        @Test
        @DisplayName("a static extension reaches the manifest as one")
        void wellFormed() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    import java.math.BigDecimal;

                    @Extension
                    public final class Strings {

                        @Receiver(BigDecimal.class)
                        public static BigDecimal parse(String text) {
                            return new BigDecimal(text);
                        }
                    }
                    """);

            assertThat(compiled.codes()).isEmpty();
            assertThat(compiled.manifest())
                    .contains("\"receiver\": \"java.math.BigDecimal\"")
                    .contains("\"kind\": \"static\"")
                    .as("no parameter is the receiver, so none is dropped from the descriptor")
                    .contains("\"descriptor\": \"(Ljava/lang/String;)Ljava/math/BigDecimal;\"");
        }

        @Test
        @DisplayName("AW1313 — a receiver named twice says which form it is nowhere")
        void bothForms() {
            assertThat(refusal("@Receiver(Integer.class) "
                    + "public static String shout(@Receiver String self) { return self; }"))
                    .containsExactly("AW1313");
        }

        @Test
        @DisplayName("AW1304 — @Receiver on a method with no type names void")
        void noType() {
            assertThat(refusal("@Receiver public static String shout(String s) { return s; }"))
                    .containsExactly("AW1304");
        }

        @Test
        @DisplayName("AW1305 — a static extension collides with a real static member too")
        void collidesWithRealStatic() {
            // String.valueOf(int) exists. A stub that declared it twice would not be a class file
            // at all, and javac would fail on the receiver rather than on this declaration.
            assertThat(refusal("@Receiver(String.class) "
                    + "public static String valueOf(int n) { return \"\"; }"))
                    .containsExactly("AW1305");
        }
    }

    @Nested
    @DisplayName("a constant contributed to a type")
    class Constants {

        @Test
        @DisplayName("a well-formed constant reaches the manifest as one")
        void wellFormed() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    import java.math.BigDecimal;

                    @Extension
                    public final class Strings {

                        @Receiver(BigDecimal.class)
                        public static final BigDecimal CENT = new BigDecimal("0.01");
                    }
                    """);

            assertThat(compiled.codes()).isEmpty();
            assertThat(compiled.manifest())
                    .contains("\"kind\": \"constant\"")
                    .as("a constant's descriptor is its type, not a method type")
                    .contains("\"descriptor\": \"Ljava/math/BigDecimal;\"");
        }

        @Test
        @DisplayName("AW1314 — a contributed constant that is not final")
        void notFinal() {
            assertThat(refusal("@Receiver(String.class) public static String NAME = \"x\";"))
                    .containsExactly("AW1314");
        }

        @Test
        @DisplayName("AW1305 — a constant the receiver already declares")
        void collides() {
            // BigDecimal.ONE is real, so the read resolves to it and this would be dead.
            assertThat(refusal("@Receiver(java.math.BigDecimal.class) "
                    + "public static final java.math.BigDecimal ONE = null;"))
                    .containsExactly("AW1305");
        }

        @Test
        @DisplayName("a field without a @Receiver is the holder's own and is not reported")
        void ordinaryFieldIsSilent() {
            assertThat(refusal("private static final int CACHE = 1;")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the two policies the annotations carry")
    class Policies {

        @Test
        @DisplayName("nulls reaches the manifest, which is the only way it reaches the weaver")
        void nullsIsRecorded() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Nulls;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension
                    public final class Strings {
                        public static String shout(@Receiver(nulls = Nulls.CHECKED) String self) {
                            return self;
                        }
                    }
                    """);

            assertThat(compiled.codes()).isEmpty();
            assertThat(compiled.manifest())
                    .as("the guard is woven from the manifest entry; an element that never left "
                            + "the source would be decoration")
                    .contains("\"nulls\": \"CHECKED\"");
        }

        @Test
        @DisplayName("require reaches it too, and is written per contribution")
        void requireIsRecorded() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;
                    import de.splatgames.aether.weaver.api.Require;

                    @Extension(require = Require.OPTIONAL)
                    public final class Strings {
                        public static String shout(@Receiver String self) { return self; }
                    }
                    """).manifest()).contains("\"require\": \"OPTIONAL\"");
        }

        @Test
        @DisplayName("scope reaches the manifest, which is where the stub goal reads it")
        void scopeIsRecorded() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;
                    import de.splatgames.aether.weaver.api.experimental.Scope;

                    @Extension(scope = Scope.MODULE)
                    public final class Strings {
                        public static String shout(@Receiver String self) { return self; }
                    }
                    """).manifest()).contains("\"scope\": \"MODULE\"");
        }

        @Test
        @DisplayName("the defaults are written nowhere, so an ordinary entry is unchanged")
        void defaultsAreNotWritten() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension
                    public final class Strings {
                        public static String shout(@Receiver String self) { return self; }
                    }
                    """).manifest())
                    .doesNotContain("\"nulls\"")
                    .doesNotContain("\"require\"")
                    .doesNotContain("\"scope\"");
        }

        @Test
        @DisplayName("AW1315 — nulls where there is no receiver value to check")
        void nullsOnAStaticContribution() {
            assertThat(refusal("@Receiver(value = Integer.class, nulls = "
                    + "de.splatgames.aether.weaver.api.experimental.Nulls.CHECKED) "
                    + "public static String make(int n) { return \"\"; }"))
                    .containsExactly("AW1315");
        }

        @Test
        @DisplayName("AW1315 — and on a constant, for the same reason")
        void nullsOnAConstant() {
            assertThat(refusal("@Receiver(value = Integer.class, nulls = "
                    + "de.splatgames.aether.weaver.api.experimental.Nulls.NULLABLE) "
                    + "public static final String NAME = \"x\";"))
                    .containsExactly("AW1315");
        }
    }

    @Nested
    @DisplayName("a receiver named once, for the whole class")
    class ClassLevelReceiver {

        @Test
        @DisplayName("every method contributes without repeating @Receiver")
        void parameterZeroIsTheReceiver() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;

                    @Extension(String.class)
                    public final class Strings {

                        public static String shout(String self, int times) { return self; }

                        public static String quiet(String self) { return self; }

                        private static String helper(int n) { return ""; }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("the repetition @Receiver removes carries no information, and a private "
                            + "helper is still a helper")
                    .isEmpty();
            assertThat(compiled.manifest())
                    .contains("\"name\": \"shout\"")
                    .contains("\"name\": \"quiet\"")
                    .as("the receiver is dropped from the call-site descriptor exactly as it is "
                            + "when the parameter is annotated")
                    .contains("\"descriptor\": \"(I)Ljava/lang/String;\"");
        }

        @Test
        @DisplayName("AW1316 — a method that does not take that type first")
        void aMethodThatTakesSomethingElse() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;

                    @Extension(String.class)
                    public final class Strings {
                        public static String shout(int times, String self) { return self; }
                    }
                    """).codes()).containsExactly("AW1316");
        }

        @Test
        @DisplayName("AW1316 — and one that takes nothing at all")
        void aMethodWithNoParameters() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;

                    @Extension(String.class)
                    public final class Strings {
                        public static String nothing() { return ""; }
                    }
                    """).codes()).containsExactly("AW1316");
        }

        @Test
        @DisplayName("a static contribution is unaffected, having no receiver value to default")
        void aStaticContributionStillWorks() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension(String.class)
                    public final class Strings {
                        @Receiver(Integer.class)
                        public static String describe(int n) { return ""; }
                    }
                    """).codes()).isEmpty();
        }

        @Test
        @DisplayName("and @Receiver on parameter zero says the same thing twice, which is allowed")
        void theExplicitSpellingStillWorks() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension(String.class)
                    public final class Strings {
                        public static String shout(@Receiver String self) { return self; }
                    }
                    """).codes())
                    .as("the two cannot contradict each other: the annotation takes its type from "
                            + "the parameter, and the parameter must be the declared type anyway")
                    .isEmpty();
        }

        @Test
        @DisplayName("a field is not contributed by a class-level receiver")
        void fieldsStayExplicit() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;

                    @Extension(String.class)
                    public final class Strings {
                        public static final String EMPTY = "";

                        public static String shout(String self) { return self; }
                    }
                    """);

            assertThat(compiled.codes()).isEmpty();
            assertThat(compiled.manifest())
                    .as("a parameter list has a position that structurally is the receiver and a "
                            + "field has none; making fields implicit would turn a holder's own "
                            + "constants into somebody else's")
                    .doesNotContain("EMPTY");
        }
    }

    @Nested
    @DisplayName("what is refused")
    class Refused {

        @Test
        @DisplayName("AW1312 — a receiver of java.lang.Object, reported but still contributed")
        void receiverIsObject() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension
                    public final class Strings {
                        public static String describe(@Receiver Object self) {
                            return String.valueOf(self);
                        }
                    }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1312");
            assertThat(compiled.manifest())
                    .as("a warning, not a refusal: contributing to Object is occasionally meant, "
                            + "and the framework cannot tell that from the widest type that compiled")
                    .contains("\"name\": \"describe\"");
        }

        @Test
        @DisplayName("AW1305 — an extension that collides with a real member of the receiver")
        void collidesWithRealMember() {
            assertThat(refusal("public static int length(@Receiver String self) { return 0; }"))
                    .containsExactly("AW1305");
        }

        @Test
        @DisplayName("AW1305 — a collision with an inherited member counts too")
        void collidesWithInheritedMember() {
            // hashCode() is Object's. At the call site that makes no difference at all: javac
            // resolves to the real method, and this extension would be dead code.
            assertThat(refusal("public static int hashCode(@Receiver String self) { return 0; }"))
                    .containsExactly("AW1305");
        }

        @Test
        @DisplayName("AW1302 — a public method with no @Receiver")
        void noReceiver() {
            assertThat(refusal("public static String shout(String self) { return self; }"))
                    .containsExactly("AW1302");
        }

        @Test
        @DisplayName("AW1303 — @Receiver on a later parameter")
        void receiverNotFirst() {
            assertThat(refusal(
                    "public static String shout(int n, @Receiver String self) { return self; }"))
                    .containsExactly("AW1303");
        }

        @Test
        @DisplayName("AW1301 — a public method that is not static")
        void notStatic() {
            assertThat(refusal("public String shout(@Receiver String self) { return self; }"))
                    .containsExactly("AW1301");
        }

        @Test
        @DisplayName("AW1304 — a receiver that cannot carry a method")
        void receiverIsPrimitive() {
            assertThat(refusal("public static int twice(@Receiver int self) { return self * 2; }"))
                    .containsExactly("AW1304");
        }

        @Test
        @DisplayName("AW1311 — a parameterised receiver, which erasure cannot distinguish")
        void parameterisedReceiver() {
            assertThat(refusal("public static String first("
                    + "@Receiver java.util.List<String> self) { return self.get(0); }"))
                    .containsExactly("AW1311");
        }

        @Test
        @DisplayName("AW1310 — a method with type parameters of its own")
        void genericMethod() {
            assertThat(refusal(
                    "public static <T> T pick(@Receiver String self, T value) { return value; }"))
                    .containsExactly("AW1310");
        }

        @Test
        @DisplayName("AW1306 — a generic extension class")
        void genericHolder() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;

                    @Extension
                    public final class Strings<T> {
                    }
                    """).codes()).containsExactly("AW1306");
        }

        @Test
        @DisplayName("AW1307 — an extension class with a supertype")
        void holderHasSupertype() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;

                    @Extension
                    public final class Strings implements Runnable {
                        public void run() { }
                    }
                    """).codes()).containsExactly("AW1307");
        }

        @Test
        @DisplayName("AW1300 — a non-final extension class is reported but still contributes")
        void notFinal() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.experimental.Extension;
                    import de.splatgames.aether.weaver.api.experimental.Receiver;

                    @Extension
                    public class Strings {
                        public static String shout(@Receiver String self) { return self; }
                    }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1300");
            assertThat(compiled.manifest())
                    .as("a warning must not silently drop the declaration it warns about")
                    .contains("\"name\": \"shout\"");
        }
    }

    // --- the compiler harness ------------------------------------------------------------------

    private static List<String> refusal(final String member) {
        return compile("""
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                    %s
                }
                """.formatted(member)).codes();
    }

    private static Compilation compile(final String source) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("these tests need a JDK, not a JRE").isNotNull();

        final DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try {
            final Path output = Files.createTempDirectory("aether-extension-checks");
            output.toFile().deleteOnExit();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(collector, null, null)) {
                files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
                files.setLocation(StandardLocation.CLASS_PATH,
                        classpath());

                final boolean succeeded = compiler.getTask(null, files, collector,
                                List.of("-proc:only"), null,
                                List.of(new InMemorySource(source)))
                        .call();
                return new Compilation(succeeded, collector.getDiagnostics(), output);
            }
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static List<File> classpath() {
        final List<File> entries = new ArrayList<>();
        for (final String entry : System.getProperty("java.class.path")
                .split(File.pathSeparator)) {
            entries.add(new File(entry));
        }
        return entries;
    }

    private record Compilation(boolean succeeded,
                               List<Diagnostic<? extends JavaFileObject>> diagnostics,
                               Path output) {

        private List<String> codes() {
            final List<String> codes = new ArrayList<>();
            for (final Diagnostic<? extends JavaFileObject> diagnostic : this.diagnostics) {
                final String message = diagnostic.getMessage(null);
                if (message.startsWith("AW")) {
                    codes.add(message.substring(0, message.indexOf(' ')));
                }
            }
            return codes;
        }

        private String manifest() {
            final Path file = this.output.resolve("META-INF/aether/weaves.json");
            if (!Files.isRegularFile(file)) {
                return "";
            }
            try {
                return Files.readString(file);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        private InMemorySource(final String source) {
            super(URI.create("string:///fixture/Strings.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(final boolean encodingErrors) {
            return this.source;
        }
    }
}
