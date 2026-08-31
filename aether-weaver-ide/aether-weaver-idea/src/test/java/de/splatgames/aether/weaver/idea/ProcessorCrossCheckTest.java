package de.splatgames.aether.weaver.idea;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.idea.inspection.ExtensionDeclarationInspection;
import de.splatgames.aether.weaver.idea.inspection.HandlerSignatureInspection;
import de.splatgames.aether.weaver.idea.inspection.SelectorInspection;
import de.splatgames.aether.weaver.idea.inspection.WeaveDeclarationInspection;
import de.splatgames.aether.weaver.idea.inspection.WeaveMemberInspection;
import de.splatgames.aether.weaver.processor.WeaveProcessor;
import org.jetbrains.annotations.NotNull;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProcessorCrossCheckTest extends BasePlatformTestCase {

    private static final Pattern CODE = Pattern.compile("AW\\d{4}");

    private static final Set<String> PLUGIN_ONLY = Set.of("AW1070");

    private static final Set<String> KNOWN_GAPS = Set.of(
            "AW1006",   // a weave with a superclass; PSI could decide it, nobody has written it
            "AW1008",   // a weave that is not final; likewise
            "AW1009",   // targets named as strings where a class literal would do
            "AW1081",   // a weave declaring a constructor
            "AW1200");  // "injection points not validated", which the processor says about itself

    private static final String TARGET = """
            package corpus;

            public class Gateway {
                int entries;
                public Note charge(Money amount) { return null; }
                public Note charge(Money amount, Note currency) { return null; }
                public void settle() { }
            }
            """;

    private static final String MONEY = """
            package corpus;

            public class Money { }
            """;

    private static final String NOTE = """
            package corpus;

            public class Note { }
            """;

    private record Case(@NotNull String name,
                        @NotNull String source,
                        @NotNull Set<String> expected) {
    }

    private static List<Case> corpus() {
        final List<Case> cases = new ArrayList<>();
        cases.add(new Case("NoTarget", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave
                public final class NoTarget {
                    @Inject(method = "settle()", at = @At(Point.HEAD))
                    void onSettle() { }
                }
                """, Set.of("AW1001")));
        cases.add(new Case("TargetsTwice", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(value = Gateway.class, targets = "corpus.Gateway")
                public final class TargetsTwice {
                    @Inject(method = "settle()", at = @At(Point.HEAD))
                    void onSettle() { }
                }
                """, Set.of("AW1002")));
        cases.add(new Case("AmbiguousSelector", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class AmbiguousSelector {
                    @Inject(method = "charge", at = @At(Point.HEAD))
                    void onCharge() { }
                }
                """, Set.of("AW1021")));
        cases.add(new Case("WrongPrefix", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class WrongPrefix {
                    @Inject(method = "charge(corpus.Money)", at = @At(Point.HEAD))
                    void onCharge(Note wrong) { }
                }
                """, Set.of("AW1040")));
        cases.add(new Case("NotVoid", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class NotVoid {
                    @Inject(method = "settle()", at = @At(Point.HEAD))
                    int onSettle() { return 0; }
                }
                """, Set.of("AW1041")));
        cases.add(new Case("StaticReachingTooFar", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Shadow;
                import de.splatgames.aether.weaver.api.Unique;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(value = Gateway.class, kind = Weave.Kind.STATIC)
                public final class StaticReachingTooFar {
                    @Shadow private int entries;
                    @Unique private int extra;

                    @Inject(method = "settle()", at = @At(Point.HEAD))
                    static void onSettle() { }
                }
                """, Set.of("AW1090", "AW1091")));
        cases.add(new Case("MergedCollision", """
                package corpus;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class MergedCollision {
                    private int entries;

                    @Inject(method = "settle()", at = @At(Point.HEAD))
                    void onSettle() { this.entries = 1; }
                }
                """, Set.of("AW1080")));

        // --- extensions ----------------------------------------------------------------------
        //
        // An extension is validated once and never again: by weave time javac has already
        // compiled callers against a stub built from whatever was accepted. So the editor and the
        // build agreeing about a declaration is not a convenience here, it is the only check there
        // will ever be.
        cases.add(new Case("ExtensionNotFinal", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public class ExtensionNotFinal {
                    public static void shout(@Receiver Gateway self) { }
                }
                """, Set.of("AW1300")));
        cases.add(new Case("ExtensionNotStatic", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionNotStatic {
                    public void shout(@Receiver Gateway self) { }
                }
                """, Set.of("AW1301")));
        cases.add(new Case("ExtensionNoReceiver", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionNoReceiver {
                    public static void shout(Gateway self) { }
                }
                """, Set.of("AW1302")));
        cases.add(new Case("ExtensionReceiverLate", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionReceiverLate {
                    public static void shout(int times, @Receiver Gateway self) { }
                }
                """, Set.of("AW1303")));
        cases.add(new Case("ExtensionPrimitiveReceiver", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionPrimitiveReceiver {
                    public static int twice(@Receiver int self) { return self; }
                }
                """, Set.of("AW1304")));
        cases.add(new Case("ExtensionCollides", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionCollides {
                    public static void settle(@Receiver Gateway self) { }
                }
                """, Set.of("AW1305")));
        cases.add(new Case("ExtensionGenericHolder", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionGenericHolder<T> {
                }
                """, Set.of("AW1306")));
        cases.add(new Case("ExtensionSupertype", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionSupertype extends Money {
                }
                """, Set.of("AW1307")));
        cases.add(new Case("ExtensionGenericMethod", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionGenericMethod {
                    public static <T> T pick(@Receiver Gateway self, T value) { return value; }
                }
                """, Set.of("AW1310")));
        cases.add(new Case("ExtensionReceiverTwice", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionReceiverTwice {
                    @Receiver(Money.class)
                    public static Money both(@Receiver Gateway self) { return null; }
                }
                """, Set.of("AW1313")));
        cases.add(new Case("ExtensionReceiverNamesNothing", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionReceiverNamesNothing {
                    @Receiver
                    public static Money make(Note note) { return null; }
                }
                """, Set.of("AW1304")));
        cases.add(new Case("ExtensionForTheWholeClass", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;

                @Extension(Gateway.class)
                public final class ExtensionForTheWholeClass {
                    public static Money doubled(Gateway self) { return null; }
                }
                """, Set.of()));
        cases.add(new Case("ExtensionWrongFirstParameter", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;

                @Extension(Gateway.class)
                public final class ExtensionWrongFirstParameter {
                    public static Money doubled(Money self) { return null; }
                }
                """, Set.of("AW1316")));
        cases.add(new Case("ExtensionStatic", """
                package corpus;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class ExtensionStatic {
                    @Receiver(Gateway.class)
                    public static Money parse(Note note) { return null; }
                }
                """, Set.of()));
        return cases;
    }

    @Override
    @NotNull
    protected LightProjectDescriptor getProjectDescriptor() {
        return new LightProjectDescriptor() {
            @Override
            public void configureModule(@NotNull final Module module,
                                        @NotNull final ModifiableRootModel model,
                                        @NotNull final ContentEntry entry) {
                final Path jar = jarOf(Weave.class);
                PsiTestUtil.addLibrary(model, "aether-weaver-api",
                        jar.getParent().toString(), jar.getFileName().toString());
            }
        };
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("corpus/Money.java", MONEY);
        myFixture.addFileToProject("corpus/Note.java", NOTE);
        myFixture.addFileToProject("corpus/Gateway.java", TARGET);
    }

    public void testTheCorpusAgrees() {
        final List<String> failures = new ArrayList<>();
        for (final Case example : corpus()) {
            final Set<String> fromProcessor = processorCodes(example);
            final Set<String> fromPlugin = pluginCodes(example);

            if (!fromPlugin.equals(example.expected())) {
                failures.add(example.name() + ": the plugin reported " + fromPlugin
                        + " where this corpus declares " + example.expected());
            }
            final Set<String> different = new TreeSet<>(fromPlugin);
            different.removeAll(fromProcessor);
            different.removeAll(PLUGIN_ONLY);
            if (!different.isEmpty()) {
                failures.add(example.name() + ": the plugin reported " + different
                        + " which the build does not. The plugin may say less than the build; it "
                        + "may never say something else. Processor said " + fromProcessor);
            }
            if (!fromProcessor.containsAll(example.expected())
                    && !PLUGIN_ONLY.containsAll(example.expected())) {
                failures.add(example.name() + ": declared " + example.expected()
                        + " but the build reported " + fromProcessor);
            }
        }

        assertEquals("the editor and the build disagree about the same source:\n"
                + String.join("\n", failures), List.of(), failures);
    }

    public void testTheProcessorActuallyRuns() {
        final Case example = corpus().getFirst();

        assertFalse("if this is empty the processor never ran — a misplaced classpath, a missing "
                        + "service registration — and every comparison above passes vacuously",
                processorCodes(example).isEmpty());
    }

    @NotNull
    private static Set<String> processorCodes(@NotNull final Case example) {
        final JavaCompiler compiler = compiler();

        final DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                     compiler.getStandardFileManager(collected, Locale.ROOT, null)) {
            final List<JavaFileObject> sources = List.of(
                    new Source("corpus.Money", MONEY),
                    new Source("corpus.Note", NOTE),
                    new Source("corpus.Gateway", TARGET),
                    new Source("corpus." + example.name(), example.source()));
            // -s as well as -proc:only. The processor emits META-INF/aether/weaves.json for
            // every weave it sees, and without somewhere to put it that manifest lands in the
            // module's own directory — which is how a 256-line weaves.json for a corpus of
            // deliberately broken weaves once reached a commit.
            final Path scratch = java.nio.file.Files.createTempDirectory("aether-corpus");
            scratch.toFile().deleteOnExit();
            final JavaCompiler.CompilationTask task = compiler.getTask(null, files, collected,
                    // -proc:only: the corpus is here to be examined, not to produce class files.
                    List.of("-proc:only", "-s", scratch.toString(), "-d", scratch.toString(),
                            "-classpath", classpath()), null, sources);
            task.setProcessors(List.of(new WeaveProcessor()));
            task.call();
        } catch (final Exception unusable) {
            fail("the processor could not be run: " + unusable);
        }

        final Set<String> codes = new LinkedHashSet<>();
        for (final Diagnostic<? extends JavaFileObject> diagnostic : collected.getDiagnostics()) {
            final Matcher matcher = CODE.matcher(diagnostic.getMessage(Locale.ROOT));
            while (matcher.find()) {
                codes.add(matcher.group());
            }
        }
        return codes;
    }

    @NotNull
    private static JavaCompiler compiler() {
        final JavaCompiler provided = ToolProvider.getSystemJavaCompiler();
        if (provided != null) {
            return provided;
        }
        try {
            return (JavaCompiler) Class.forName("com.sun.tools.javac.api.JavacTool")
                    .getMethod("create").invoke(null);
        } catch (final ReflectiveOperationException absent) {
            throw new AssertionError("no Java compiler in this JVM; the corpus cannot be run "
                    + "against the processor at all", absent);
        }
    }

    @NotNull
    private Set<String> pluginCodes(@NotNull final Case example) {
        myFixture.configureByText(example.name() + ".java", example.source());
        myFixture.enableInspections(new SelectorInspection(), new WeaveDeclarationInspection(),
                new HandlerSignatureInspection(), new WeaveMemberInspection(),
                new ExtensionDeclarationInspection());

        final Set<String> codes = new LinkedHashSet<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getDescription() == null) {
                continue;
            }
            final Matcher matcher = CODE.matcher(info.getDescription());
            while (matcher.find()) {
                codes.add(matcher.group());
            }
        }
        return codes;
    }

    @NotNull
    private static String classpath() {
        final Set<String> entries = new LinkedHashSet<>();
        entries.add(jarOf(Weave.class).toString());
        entries.add(jarOf(WeaveProcessor.class).toString());
        final String declared = System.getProperty("java.class.path");
        if (declared != null && !declared.isBlank()) {
            entries.add(declared);
        }
        return String.join(File.pathSeparator, entries);
    }

    @NotNull
    private static Path jarOf(@NotNull final Class<?> loaded) {
        final String resource = '/' + loaded.getName().replace('.', '/') + ".class";
        final URL url = loaded.getResource(resource);
        assertNotNull("cannot locate " + loaded.getName(), url);

        final String text = url.toString();
        final String location = text.startsWith("jar:")
                ? text.substring("jar:".length(), text.indexOf("!/"))
                : text.substring(0, text.length() - resource.length());
        return Paths.get(URI.create(location));
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String text;

        Source(@NotNull final String name, @NotNull final String text) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
            this.text = text;
        }

        @Override
        @NotNull
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.text;
        }
    }
}
