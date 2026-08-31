package de.splatgames.aether.weaver.engine.parse;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeaveClassParserTest {

    private static Path fixtures;

    @BeforeAll
    static void compileFixtures() throws IOException {
        fixtures = Files.createTempDirectory("aether-weaver-fixtures");
        fixtures.toFile().deleteOnExit();
        compile(
                source("weavefixtures.Minimal", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class Minimal {

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            void onRun() {
                            }
                        }
                        """),
                source("weavefixtures.ReadsValueAtHead", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;
                        import de.splatgames.aether.weaver.api.callback.ReturnableCallback;

                        @Weave(Target.class)
                        public final class ReadsValueAtHead {

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            void onRun(ReturnableCallback<String> cb) {
                                String seen = cb.value();
                            }
                        }
                        """),
                source("weavefixtures.ReadsValueAtReturn", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;
                        import de.splatgames.aether.weaver.api.callback.ReturnableCallback;

                        @Weave(Target.class)
                        public final class ReadsValueAtReturn {

                            @Inject(method = "run()", at = @At(Point.RETURN))
                            void onRun(ReturnableCallback<String> cb) {
                                String seen = cb.value();
                            }
                        }
                        """),
                source("weavefixtures.Configured", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(value = Target.class,
                               kind = Weave.Kind.STATIC,
                               priority = 100,
                               require = Require.OPTIONAL,
                               phase = Phase.EARLY,
                               tags = {"tracing", "diagnostics"})
                        public final class Configured {

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            static void onRun(Target self) {
                            }
                        }
                        """),
                source("weavefixtures.Accounting", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        @Group(name = "compat", min = 1, max = 1)
                        public final class Accounting {

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            void defaulted() {
                            }

                            @Inject(method = "run()", at = @At(Point.HEAD),
                                    require = 0, group = "compat")
                            void explicitlyOptional() {
                            }

                            @Inject(method = "run()", at = @At(Point.HEAD), require = 2, allow = 4)
                            void bounded() {
                            }
                        }
                        """),
                source("weavefixtures.StaticInitialiser", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class StaticInitialiser {

                            static final String NAME;

                            static {
                                NAME = "computed";
                            }

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            static void onRun() {
                            }
                        }
                        """),
                source("weavefixtures.Implementing", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class Implementing implements Runnable {

                            @Override
                            public void run() {
                            }
                        }
                        """),
                source("weavefixtures.Members", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class Members {

                            @Shadow private java.util.List<String> log;
                            @Shadow("a") private int counter;
                            @Unique private long startedAt;
                            @Unique(silent = true) private int hits;
                            private String helper;

                            @Accessor String getName() { throw new AssertionError("accessor"); }
                            @Invoker void callFlush(boolean force) {
                                throw new AssertionError("invoker");
                            }
                            @Shadow private void close() { }

                            long elapsed() {
                                return this.startedAt;
                            }
                        }
                        """),
                source("weavefixtures.Points", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class Points {

                            @Inject(method = "run()",
                                    at = @At(value = Point.INVOKE, target = "#flush", ordinal = 0),
                                    slice = @Slice(from = @At(value = Point.INVOKE,
                                                              target = "#begin")))
                            void sliced() {
                            }

                            @Inject(method = "run()",
                                    at = @At(value = Point.NEW, target = "weavefixtures.Target"))
                            void atNew() {
                            }

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            @Inject(method = "stop()", at = @At(Point.HEAD))
                            void repeated() {
                            }

                            @Inject(method = "run()", at = @At(Point.TAIL))
                            void capturing(@Local(name = "count") int count,
                                           @Local(ordinal = 1) String label) {
                            }
                        }
                        """),
                source("weavefixtures.NoTargets", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave
                        public final class NoTargets {
                        }
                        """),
                source("weavefixtures.BothForms", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(value = Target.class, targets = "weavefixtures.Target")
                        public final class BothForms {
                        }
                        """),
                source("weavefixtures.InstanceHandlerInStaticWeave", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                        public final class InstanceHandlerInStaticWeave {

                            @Inject(method = "run()", at = @At(Point.HEAD))
                            void notStatic() {
                            }
                        }
                        """),
                source("weavefixtures.BrokenSelector", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class BrokenSelector {

                            @Inject(method = "run(", at = @At(Point.HEAD))
                            void broken() {
                            }
                        }
                        """),
                source("weavefixtures.StaticWeaveMembers", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                        public final class StaticWeaveMembers {

                            @Shadow private int counter;
                            @Unique private long startedAt;
                            @Shadow private void close() { }
                            @Unique private void helper() { }
                        }
                        """),
                source("weavefixtures.Initialisers", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class Initialisers {

                            @Shadow static final int LIMIT = 5;
                            static final String TAG = "audit";
                        }
                        """),
                source("weavefixtures.Overriding", """
                        package weavefixtures;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Target.class)
                        public final class Overriding {

                            @Override public String toString() { return "woven"; }
                            public boolean equals(String other) { return false; }
                        }
                        """),
                source("weavefixtures.NotAWeave", """
                        package weavefixtures;

                        public final class NotAWeave {
                        }
                        """),
                source("weavefixtures.Target", """
                        package weavefixtures;

                        public class Target {
                            public void run() { }
                            public void stop() { }
                        }
                        """));
    }

    @Nested
    @DisplayName("the weave class is never loaded")
    class NeverLoaded {

        @Test
        @DisplayName("the fixtures are not even on the classpath")
        void fixturesAreNotOnTheClasspath() {
            assertThatThrownBy(() -> Class.forName("weavefixtures.Minimal", false,
                    WeaveClassParserTest.class.getClassLoader()))
                    .as("if this ever resolves, the proof below is worthless: the parser could "
                            + "fall back to reflection and the test would not notice")
                    .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("a weave is fully modelled anyway")
        void aWeaveIsModelledAnyway() {
            final WeaveClass weave = parse("weavefixtures.Minimal");

            assertThat(weave.binaryName()).isEqualTo("weavefixtures.Minimal");
            assertThat(weave.injectors()).singleElement()
                    .extracting(InjectorSpec::rawMethod).isEqualTo("run()");

            assertThatThrownBy(() -> Class.forName("weavefixtures.Minimal", false,
                    WeaveClassParserTest.class.getClassLoader()))
                    .as("and it is still not loaded afterwards")
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("every unwritten element is filled in by the parser")
        void unwrittenElementsAreFilledIn() {
            final WeaveClass weave = parse("weavefixtures.Minimal");

            assertThat(weave.kind()).isEqualTo(Weave.Kind.INSTANCE);
            assertThat(weave.priority()).isZero();
            assertThat(weave.require()).isEqualTo(Require.REQUIRED);
            assertThat(weave.phase()).isEqualTo(Phase.DEFAULT);
            assertThat(weave.tags()).isEmpty();
            assertThat(weave.groups()).isEmpty();
        }

        @Test
        @DisplayName("the class file really does omit them")
        void theClassFileOmitsThem() {
            final ClassModel model = model("weavefixtures.Minimal");
            final java.lang.classfile.Annotation weave =
                    Annotations.find(Annotations.on(model), Weave.class);

            assertThat(weave).isNotNull();
            assertThat(weave.elements())
                    .as("javac records only what was written, which is why the parser has to "
                            + "supply the defaults itself")
                    .singleElement()
                    .extracting(element -> element.name().stringValue())
                    .isEqualTo("value");
        }

        @Test
        @DisplayName("written values win over them")
        void writtenValuesWin() {
            final WeaveClass weave = parse("weavefixtures.Configured");

            assertThat(weave.kind()).isEqualTo(Weave.Kind.STATIC);
            assertThat(weave.priority()).isEqualTo(100);
            assertThat(weave.require()).isEqualTo(Require.OPTIONAL);
            assertThat(weave.phase()).isEqualTo(Phase.EARLY);
            assertThat(weave.tags()).containsExactlyInAnyOrder("tracing", "diagnostics");
        }

        @Test
        @DisplayName("a written zero is not the same as an omitted one")
        void writtenZeroDiffersFromAnOmittedOne() {
            final WeaveClass weave = parse("weavefixtures.Accounting");

            assertThat(injector(weave, "defaulted").require())
                    .as("omitted: the injector's own default of one applies, so a weave that "
                            + "matches nothing fails")
                    .isEqualTo(1);
            assertThat(injector(weave, "explicitlyOptional").require())
                    .as("written as 0: deliberately optional, because the group carries the "
                            + "requirement instead")
                    .isZero();
            assertThat(injector(weave, "bounded").require()).isEqualTo(2);
            assertThat(injector(weave, "bounded").allow()).isEqualTo(4);
        }

        @Test
        @DisplayName("an id is derived when none was written")
        void anIdIsDerivedWhenNoneWasWritten() {
            final InjectorSpec spec = injector(parse("weavefixtures.Minimal"), "onRun");

            assertThat(spec.id())
                    .contains("weavefixtures.Minimal")
                    .contains("onRun")
                    .endsWith("#inject");
        }
    }

    @Nested
    @DisplayName("members")
    class Members {

        @Test
        @DisplayName("each member gets the disposition its annotation implies")
        void membersAreClassifiedByDisposition() {
            final WeaveClass weave = parse("weavefixtures.Members");

            assertThat(member(weave, "log", WeaveMember.Shadowed.class).targetName())
                    .as("no explicit name: the declaration's own name is the target's")
                    .isEqualTo("log");
            assertThat(member(weave, "counter", WeaveMember.Shadowed.class).targetName())
                    .isEqualTo("a");
            assertThat(member(weave, "close", WeaveMember.Shadowed.class).isField())
                    .as("a shadow may be a method too")
                    .isFalse();

            assertThat(member(weave, "startedAt", WeaveMember.Merged.class).unique()).isTrue();
            assertThat(member(weave, "startedAt", WeaveMember.Merged.class).silent()).isFalse();
            assertThat(member(weave, "hits", WeaveMember.Merged.class).silent()).isTrue();

            assertThat(member(weave, "helper", WeaveMember.Merged.class).unique())
                    .as("no annotation at all: merged, and a collision is an error rather than a "
                            + "silent rename")
                    .isFalse();
            assertThat(member(weave, "elapsed", WeaveMember.Merged.class).isField()).isFalse();
        }

        @Test
        @DisplayName("accessor and invoker names are inferred from the declaration")
        void namesAreInferred() {
            final WeaveClass weave = parse("weavefixtures.Members");

            assertThat(member(weave, "getName", WeaveMember.Accessor.class).targetField())
                    .isEqualTo("name");
            assertThat(member(weave, "getName", WeaveMember.Accessor.class).isGetter()).isTrue();
            assertThat(member(weave, "callFlush", WeaveMember.Invoker.class).targetMethod())
                    .isEqualTo("flush");
        }

        @Test
        @DisplayName("a handler is described by its injector, not duplicated as a member")
        void handlersAreNotAlsoMembers() {
            final WeaveClass weave = parse("weavefixtures.Minimal");

            assertThat(weave.members())
                    .as("describing a handler in two places is how the two descriptions start "
                            + "to disagree")
                    .noneMatch(member -> member.name().equals("onRun"));
            assertThat(weave.injectors()).singleElement()
                    .extracting(spec -> spec.handler().name()).isEqualTo("onRun");
        }

        @Test
        @DisplayName("AW1082 — a static initialiser is refused")
        void aStaticInitialiserIsRefused() {
            assertThat(diagnosticsOf("weavefixtures.StaticInitialiser"))
                    .as("""
                            Reported from exactly one place and asserted from none, found by \
                            listing every diagnostic code against every test. A weave's <clinit> \
                            would have to run somewhere, and the two candidates are both wrong: \
                            in the weave, which is never loaded, or in the target, whose own \
                            static state it would then be part of.""")
                    .anyMatch(diagnostic -> diagnostic.code().code().equals("AW1082"));
        }

        @Test
        @DisplayName("AW1084 — a weave implementing an interface is refused in 0.1.0")
        void anImplementingWeaveIsRefused() {
            assertThat(diagnosticsOf("weavefixtures.Implementing"))
                    .as("the interface would have to be added to the target, which is @Implements "
                            + "and is not in this release — refusing it is what keeps a later "
                            + "release free to define what it means")
                    .anyMatch(diagnostic -> diagnostic.code().code().equals("AW1084"));
        }

        @Test
        @DisplayName("the implicit constructor is not mistaken for a declared one")
        void theImplicitConstructorIsAccepted() {
            final List<Diagnostic> reported = new ArrayList<>();

            assertThat(new WeaveClassParser(reported::add)
                    .parse(model("weavefixtures.Minimal"), Origin.of("test", null)))
                    .isPresent();
            assertThat(reported)
                    .as("every class file has a constructor; refusing all of them would refuse "
                            + "every weave")
                    .noneMatch(diagnostic -> diagnostic.code().code().equals("AW1081"));
        }
    }

    @Nested
    @DisplayName("injection points")
    class Points {

        @Test
        @DisplayName("a slice is declared on the handler and referenced by id")
        void slicesAreDeclaredOnTheHandler() {
            final InjectorSpec spec = injector(parse("weavefixtures.Points"), "sliced");

            assertThat(spec.slices()).singleElement()
                    .satisfies(slice -> {
                        assertThat(slice.isUnnamed()).isTrue();
                        assertThat(slice.from().point()).isEqualTo("INVOKE");
                        assertThat(slice.from().ordinal())
                                .as("a range boundary must be exactly one position, so both "
                                        + "bounds default to the first match")
                                .isZero();
                        assertThat(slice.to().point())
                                .as("an omitted upper bound means the end of the method")
                                .isEqualTo("TAIL");
                    });
            assertThat(spec.sliceFor(spec.points().getFirst()))
                    .as("the query leaves its reference empty, which selects the unnamed slice")
                    .isNotNull();
        }

        @Test
        @DisplayName("a point whose target is a type keeps the text unparsed")
        void nonMemberTargetsKeepTheirText() {
            final PointSpec spec = injector(parse("weavefixtures.Points"), "atNew")
                    .points().getFirst();

            assertThat(spec.point()).isEqualTo(Point.NEW.name());
            assertThat(spec.hasTarget()).isTrue();
            assertThat(spec.rawTarget()).isEqualTo("weavefixtures.Target");
            assertThat(spec.hasSelector())
                    .as("Point.NEW names a class, and forcing a class name through the member "
                            + "grammar would either fail or succeed with the wrong meaning")
                    .isFalse();
        }

        @Test
        @DisplayName("a repeated annotation is flattened back into its occurrences")
        void repeatedAnnotationsAreFlattened() {
            final WeaveClass weave = parse("weavefixtures.Points");

            assertThat(weave.injectors())
                    .filteredOn(spec -> spec.handler().name().equals("repeated"))
                    .as("javac rewrites two @Inject into one container, and a handler with one "
                            + "@Inject must not look structurally different")
                    .hasSize(2)
                    .extracting(InjectorSpec::rawMethod)
                    .containsExactly("run()", "stop()");
        }

        @Test
        @DisplayName("captures are read from the parameter annotations")
        void capturesAreRead() {
            final InjectorSpec spec = injector(parse("weavefixtures.Points"), "capturing");

            assertThat(spec.locals()).hasSize(2);
            assertThat(spec.locals().getFirst())
                    .satisfies(local -> {
                        assertThat(local.parameter()).isZero();
                        assertThat(local.name()).isEqualTo("count");
                        assertThat(local.strategy()).isEqualTo(LocalSpec.Strategy.BY_NAME);
                    });
            assertThat(spec.locals().getLast())
                    .satisfies(local -> {
                        assertThat(local.parameter()).isEqualTo(1);
                        assertThat(local.ordinal()).isEqualTo(1);
                        assertThat(local.strategy()).isEqualTo(LocalSpec.Strategy.BY_ORDINAL);
                    });
        }

        @Test
        @DisplayName("the injector kind follows the annotation")
        void theKindFollowsTheAnnotation() {
            assertThat(injector(parse("weavefixtures.Minimal"), "onRun").kind())
                    .isEqualTo(InjectorKind.INJECT);
        }
    }

    @Nested
    @DisplayName("declaration errors")
    class Errors {

        @Test
        @DisplayName("AW1072 — a handler reading the callback's value where there is none")
        void readingTheValueTooEarlyIsRefused() {
            assertThat(diagnosticsOf("weavefixtures.ReadsValueAtHead"))
                    .extracting(diagnostic -> diagnostic.code().code())
                    .as("the processor cannot see this: javax.lang.model models declarations "
                            + "and whether a handler CALLS value() is a statement. Here the handler "
                            + "is a compiled method and the call is an instruction. Without the "
                            + "check it receives null and cannot tell that from a target which "
                            + "genuinely returned null")
                    .contains("AW1072");
        }

        @Test
        @DisplayName("the same handler at RETURN is left alone")
        void readingTheValueAtReturnIsFine() {
            // Not diagnosticsOf: that helper insists on a non-empty report, and a clean fixture
            // is precisely what this asserts.
            final List<Diagnostic> reported = new ArrayList<>();
            new WeaveClassParser(reported::add)
                    .parse(model("weavefixtures.ReadsValueAtReturn"),
                            Origin.of("unit-test", "memory"));

            assertThat(reported.stream().map(diagnostic -> diagnostic.code().code()).toList())
                    .as("without this the test above would pass against a check that refused "
                            + "every call to value(), which is a different defect wearing the same "
                            + "code")
                    .doesNotContain("AW1072");
        }

        @Test
        @DisplayName("a weave with no target is refused")
        void noTargetIsRefused() {
            assertThat(diagnosticsOf("weavefixtures.NoTargets"))
                    .extracting(diagnostic -> diagnostic.code().code())
                    .contains("AW1001");
        }

        @Test
        @DisplayName("declaring targets in both forms is refused")
        void bothFormsIsRefused() {
            assertThat(diagnosticsOf("weavefixtures.BothForms"))
                    .extracting(diagnostic -> diagnostic.code().code())
                    .as("which of the two is authoritative would be a guess")
                    .contains("AW1002");
        }

        @Test
        @DisplayName("a static weave with an instance handler is refused")
        void instanceHandlerInStaticWeaveIsRefused() {
            assertThat(diagnosticsOf("weavefixtures.InstanceHandlerInStaticWeave"))
                    .extracting(diagnostic -> diagnostic.code().code())
                    .contains("AW1005");
        }

        @Test
        @DisplayName("a selector that does not parse is a diagnostic, not an exception")
        void aBrokenSelectorIsADiagnostic() {
            final List<Diagnostic> reported = diagnosticsOf("weavefixtures.BrokenSelector");

            assertThat(reported)
                    .extracting(diagnostic -> diagnostic.code().code())
                    .contains("AW1015");
            assertThat(reported.getFirst().message())
                    .as("the message must name the handler, or the user has to find it themselves")
                    .contains("broken");
        }

        @Test
        @DisplayName("every diagnostic names where the weave came from")
        void everyDiagnosticCarriesTheOrigin() {
            assertThat(diagnosticsOf("weavefixtures.NoTargets"))
                    .allSatisfy(diagnostic -> assertThat(diagnostic.details())
                            .anyMatch(detail -> detail.contains("unit-test")));
        }

        @Test
        @DisplayName("a class that is not a weave is not an error either")
        void aPlainClassIsSimplySkipped() {
            final List<Diagnostic> reported = new ArrayList<>();
            final Optional<WeaveClass> parsed = new WeaveClassParser(reported::add)
                    .parse(model("weavefixtures.NotAWeave"), Origin.of("unit-test", null));

            assertThat(parsed).isEmpty();
            assertThat(reported)
                    .as("most classes are not weaves; saying so about each of them would drown "
                            + "the ones that are broken")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("members that only mean something in a merged weave")
    class StaticWeaveMembers {

        @Test
        @DisplayName("@Shadow and @Unique are refused in a static weave, field and method alike")
        void mergeOnlyAnnotationsAreRefused() {
            assertThat(diagnosticsOf("weavefixtures.StaticWeaveMembers"))
                    .extracting(diagnostic -> diagnostic.code().code())
                    .as("a static weave is never dissolved, so neither annotation has anything to "
                            + "bind to — and both spellings, field and method, reach the target "
                            + "through the same dead end")
                    .containsExactlyInAnyOrder("AW1090", "AW1091", "AW1090", "AW1091");
        }

        @Test
        @DisplayName("the refused member is named, and the remedy names the kind that would work")
        void theDiagnosticSaysWhichMemberAndWhatToDo() {
            assertThat(diagnosticsOf("weavefixtures.StaticWeaveMembers"))
                    .filteredOn(diagnostic -> diagnostic.code().code().equals("AW1090"))
                    .first()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.message()).contains("counter", "static weave");
                        assertThat(diagnostic.remedy().orElseThrow()).contains("Kind.INSTANCE");
                    });
        }

        @Test
        @DisplayName("a refused member is not modelled")
        void theMemberDoesNotSurviveIntoTheModel() {
            final List<Diagnostic> reported = new ArrayList<>();
            final Optional<WeaveClass> parsed = new WeaveClassParser(reported::add)
                    .parse(model("weavefixtures.StaticWeaveMembers"),
                            Origin.of("unit-test", "memory"));

            assertThat(parsed)
                    .as("an error stops the parse, so nothing downstream can act on a member the "
                            + "parser has already said is meaningless")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("field initialisers")
    class Initialisers {

        @Test
        @DisplayName("a @Shadow field's initialiser is reported as ignored")
        void aShadowInitialiserIsReported() {
            assertThat(diagnosticsOf("weavefixtures.Initialisers"))
                    .filteredOn(diagnostic -> diagnostic.code().code().equals("AW1032"))
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.message()).contains("LIMIT");
                        assertThat(diagnostic.severity().name()).isEqualTo("WARNING");
                    });
        }

        @Test
        @DisplayName("a merged field's initialiser is reported, and points at the constructor HEAD")
        void aMergedInitialiserIsReported() {
            assertThat(diagnosticsOf("weavefixtures.Initialisers"))
                    .filteredOn(diagnostic -> diagnostic.code().code().equals("AW1093"))
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.message()).contains("TAG", "default value");
                        assertThat(diagnostic.severity().name())
                                .as("the field still works; it merely starts at the JVM's default")
                                .isEqualTo("INFO");
                        assertThat(diagnostic.remedy().orElseThrow()).contains("HEAD");
                    });
        }

        @Test
        @DisplayName("neither report stops the weave from being modelled")
        void theWeaveIsStillUsable() {
            final List<Diagnostic> reported = new ArrayList<>();

            assertThat(new WeaveClassParser(reported::add)
                    .parse(model("weavefixtures.Initialisers"), Origin.of("unit-test", "memory")))
                    .as("an ignored initialiser is a surprise worth naming, not a reason to "
                            + "discard the whole weave")
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("merging a method the platform calls")
    class ObjectMethods {

        @Test
        @DisplayName("merging toString is warned about")
        void toStringIsWarnedAbout() {
            assertThat(diagnosticsOf("weavefixtures.Overriding"))
                    .filteredOn(diagnostic -> diagnostic.code().code().equals("AW1083"))
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.message()).contains("toString");
                        assertThat(diagnostic.severity().name()).isEqualTo("WARNING");
                    });
        }

        @Test
        @DisplayName("an overload that shares only the name is left alone")
        void anOverloadIsNotTheSameMethod() {
            assertThat(diagnosticsOf("weavefixtures.Overriding"))
                    .filteredOn(diagnostic -> diagnostic.code().code().equals("AW1083"))
                    .as("equals(String) is not the method the platform calls, and warning about "
                            + "it would train the author to ignore the code")
                    .noneMatch(diagnostic -> diagnostic.message().contains("equals"));
        }
    }

    // -------------------------------------------------------------------------------------

    private static WeaveClass parse(final String className) {
        return new WeaveClassParser(diagnostic -> {
            throw new AssertionError("unexpected diagnostic: " + diagnostic.format());
        }).parse(model(className), Origin.of("unit-test", "memory")).orElseThrow();
    }

    private static List<Diagnostic> diagnosticsOf(final String className) {
        final List<Diagnostic> reported = new ArrayList<>();
        new WeaveClassParser(reported::add)
                .parse(model(className), Origin.of("unit-test", "memory"));
        assertThat(reported).as("%s was expected to produce diagnostics", className).isNotEmpty();
        return reported;
    }

    private static ClassModel model(final String className) {
        final Path path = fixtures.resolve(className.replace('.', '/') + ".class");
        try {
            return ClassFile.of().parse(Files.readAllBytes(path));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InjectorSpec injector(final WeaveClass weave, final String handler) {
        return weave.injectors().stream()
                .filter(spec -> spec.handler().name().equals(handler))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no injector on " + handler + " in " + weave.binaryName()));
    }

    private static <T extends WeaveMember> T member(final WeaveClass weave,
                                                    final String name,
                                                    final Class<T> disposition) {
        return weave.members().stream()
                .filter(disposition::isInstance)
                .map(disposition::cast)
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + disposition.getSimpleName() + " named " + name + " in "
                                + weave.binaryName() + "; found " + weave.members()));
    }

    private static void compile(final JavaFileObject... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests need a JDK, not a JRE").isNotNull();

        final StringWriterCollector diagnostics = new StringWriterCollector();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            final boolean ok = compiler.getTask(
                    diagnostics.writer(),
                    files,
                    null,
                    List.of("-d", fixtures.toString(),
                            "-classpath", System.getProperty("java.class.path"),
                            "-g",
                            "-proc:none"),
                    null,
                    List.of(sources)).call();
            assertThat(ok).as("fixture compilation failed:%n%s", diagnostics).isTrue();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JavaFileObject source(final String className, final String body) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                return body;
            }
        };
    }

    private static final class StringWriterCollector {

        private final java.io.StringWriter writer = new java.io.StringWriter();

        private java.io.Writer writer() {
            return this.writer;
        }

        @Override
        public String toString() {
            return this.writer.toString();
        }
    }
}
