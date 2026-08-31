package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.plan.OrderKey;
import de.splatgames.aether.weaver.engine.plan.PlanEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationChainTest {

    private static final ClassDesc TARGET = ClassDesc.of("chainfixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    private static final byte[] FIXTURE = compileFixture();

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void onNew() {
            // Nothing.
        }
    }

    @Test
    @DisplayName("AW1027 — two constructors that call one another are reported")
    void aDirectChainIsReported() {
        weave("<init>()", "<init>(int)");

        assertThat(codes())
                .as("Target() calls this(1), so `new Target()` runs both and the handler fires "
                        + "twice for one object. Nothing about the two injections shows it")
                .contains("AW1027");
        assertThat(this.reported.stream()
                .filter(diagnostic -> "AW1027".equals(diagnostic.code().code()))
                .findFirst().orElseThrow().details())
                .anySatisfy(detail -> assertThat(detail).contains("directly"));
    }

    @Test
    @DisplayName("the chain is followed past a constructor the weave did not attach to")
    void anIndirectChainIsReported() {
        weave("<init>()", "<init>(int,java.lang.String)");

        assertThat(codes())
                .as("Target() reaches Target(int, String) through Target(int), and that the middle "
                        + "one is untouched changes nothing about how often the handler runs")
                .contains("AW1027");
        assertThat(this.reported.stream()
                .filter(diagnostic -> "AW1027".equals(diagnostic.code().code()))
                .findFirst().orElseThrow().details())
                .anySatisfy(detail -> assertThat(detail).contains("through the chain"));
    }

    @Test
    @DisplayName("two constructors that do not call one another are silent")
    void unrelatedConstructorsAreSilent() {
        weave("<init>(int,java.lang.String)", "<init>(long)");

        assertThat(codes())
                .as("both end in super(), so one `new` runs exactly one of them. Without this "
                        + "the tests above would pass against a check that fired whenever a weave "
                        + "touched two constructors at all")
                .doesNotContain("AW1027")
                .as("and AW1021 would mean the two signatures never resolved at all, which would "
                        + "make the absence of AW1027 mean nothing")
                .doesNotContain("AW1021");
    }

    @Test
    @DisplayName("an overload is selected by its parameter list, which it used not to be")
    void anOverloadIsSelectable() {
        weave("<init>(long)");

        assertThat(codes())
                .as("""
                        This is the defect AW1027 uncovered rather than AW1027 itself.

                        methodFor chopped the raw selector at its first bracket and matched on the \
                        name alone, so every one of these four constructors matched every \
                        selector: an overloaded target resolved to AW1021 whatever the author \
                        wrote, and the remedy said "add the parameter types" -- the very thing \
                        being discarded one line earlier.

                        The parsed selector is now used, through the same predicate injection \
                        points already matched calls with.""")
                .isEmpty();
    }

    @Test
    @DisplayName("a name-only selector on an overloaded constructor is still ambiguous")
    void aNameOnlySelectorIsStillAmbiguous() {
        weave("<init>");

        assertThat(codes())
                .as("naming no signature over four constructors is genuinely ambiguous, and the "
                        + "remedy that says to add the parameter types now works")
                .contains("AW1021");
    }

    @Test
    @DisplayName("one constructor alone is silent")
    void oneConstructorIsSilent() {
        weave("<init>()");

        assertThat(codes()).doesNotContain("AW1027");
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private void weave(final String... selectors) {
        final HandlerRef handler = new HandlerRef(HANDLER_OWNER, "onNew",
                MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC));
        final List<PlanEntryView> entries = new ArrayList<>();
        for (int index = 0; index < selectors.length; index++) {
            final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                    selectors[index], MemberSelector.parse(selectors[index]),
                    List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                    "chain" + index, 0, 0, "", List.<LocalSpec>of());
            // One weave class name for all of them: the diagnostic is about what ONE weave did,
            // and two weaves each attaching to one constructor is not this defect.
            entries.add(new PlanEntry(TARGET, spec, "chainfixture.Weave",
                    Origin.of("test", null),
                    new OrderKey(index, "chainfixture.Weave", handler.name(),
                            handler.type().descriptorString())));
        }

        new WeavingPipeline(BuiltInPoints.all()::get,
                kind -> InjectorKind.INJECT.id().equals(kind) ? new InjectInjector() : null)
                .weave(ClassFile.of().parse(FIXTURE), entries, List.of(), this.reporter);
    }

    private static byte[] compileFixture() {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-chain");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null,
                        List.of("-g"), null, List.of(new Source())).call();
                if (!ok) {
                    throw new AssertionError("the chain fixture must compile");
                }
            }
            return Files.readAllBytes(output.resolve("chainfixture/Target.class"));
        } catch (final Exception failed) {
            throw new AssertionError("could not build the chain fixture", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private static final String CODE = """
                package chainfixture;

                public class Target {

                    private final int count;
                    private final String label;

                    // A three-step chain: () -> (int) -> (int, String) -> super().
                    public Target() {
                        this(1);
                    }

                    public Target(int count) {
                        this(count, "none");
                    }

                    public Target(int count, String label) {
                        this.count = count;
                        this.label = label;
                    }

                    // Off the chain entirely: it ends in super(), like the one above.
                    public Target(long wide) {
                        this.count = (int) wide;
                        this.label = "wide";
                    }
                }
                """;

        Source() {
            super(URI.create("string:///chainfixture/Target.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
