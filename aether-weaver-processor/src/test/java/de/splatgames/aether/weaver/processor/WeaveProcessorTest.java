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

class WeaveProcessorTest {

    @Nested
    @DisplayName("the position lands on the literal")
    class Positions {

        @Test
        @DisplayName("AW1002 underlines the targets literal, not the annotation")
        void duplicateTargetsPointsAtTheLiteral() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(value = Target.class, targets = "fixture.Target")
                    public final class Both {
                    }
                    """);

            assertThat(compiled.caretText("AW1002"))
                    .as("the class literals are the form to keep, so the caret points at what "
                            + "should go — a diagnostic on the whole annotation would leave the "
                            + "author to work out which half is the problem")
                    .startsWith("\"fixture.Target\"");
        }

        @Test
        @DisplayName("AW1001 underlines the annotation, because there is no literal to blame")
        void missingTargetsPointsAtTheAnnotation() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave
                    public final class None {
                    }
                    """);

            assertThat(compiled.caretText("AW1001"))
                    .as("an element the author did not write has no position; the fallback has to "
                            + "produce the next tightest thing rather than nothing")
                    .isEqualTo("@Weave");
        }

        @Test
        @DisplayName("AW1007 underlines the type parameter")
        void genericPointsAtTheTypeParameter() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public final class Generic<T> {
                    }
                    """);

            assertThat(compiled.caretText("AW1007"))
                    .as("the caret goes on <T> itself, which is the thing that has to be deleted")
                    .isEqualTo("T> {");
        }

        @Test
        @DisplayName("AW1081 underlines the constructor, not the class")
        void constructorPointsAtItself() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public final class Constructed {
                        Constructed(int unused) { }
                    }
                    """);

            assertThat(compiled.caretText("AW1081")).isEqualTo("Constructed(int unused) { }");
        }

        @Test
        @DisplayName("AW1005 underlines the handler method")
        void instanceHandlerPointsAtTheMethod() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.At;
                    import de.splatgames.aether.weaver.api.Inject;
                    import de.splatgames.aether.weaver.api.Point;
                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                    public final class StaticWeave {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        void onRun() {
                        }
                    }
                    """);

            assertThat(compiled.caretText("AW1005"))
                    .as("the fault is the handler's own modifier list, and the weave's kind is "
                            + "the part that is probably right")
                    .isEqualTo("onRun() {");
        }

        @Test
        @DisplayName("AW1008 underlines the class declaration")
        void notFinalPointsAtTheClass() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public class NotFinal {
                    }
                    """);

            assertThat(compiled.caretText("AW1008"))
                    .as("no annotation was named, so the caret falls back to the declaration — "
                            + "which is exactly where 'add final' has to be applied")
                    .isEqualTo("class NotFinal {");
        }
    }

    @Nested
    @DisplayName("what reaches the compiler")
    class Reported {

        @Test
        @DisplayName("an error fails the compilation and a warning does not")
        void severityDecidesTheOutcome() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave
                    public final class None {
                    }
                    """).succeeded())
                    .as("AW1001 is an ERROR, and a framework that reports errors without failing "
                            + "the build has reported nothing")
                    .isFalse();

            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public class NotFinal {
                    }
                    """).succeeded())
                    .as("AW1008 is a WARNING; failing on it would make the advice mandatory")
                    .isTrue();
        }

        @Test
        @DisplayName("the message carries the code, the reason and the remedy")
        void theMessageIsSelfContained() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave
                    public final class None {
                    }
                    """);

            assertThat(compiled.messageOf("AW1001").lines().toList())
                    .as("the first line is what an IDE shows in the gutter, so the code and the "
                            + "reason belong there and the remedy below it")
                    .satisfiesExactly(
                            headline -> assertThat(headline)
                                    .isEqualTo("AW1001 weave fixture.None declares no target"),
                            // Four spaces, not the two the reporter writes: javac's own
                            // formatter indents every continuation line by two more. The rendering
                            // still works — a detail's four become six, so the remedy stays the
                            // least-indented thing under the headline — but the absolute widths
                            // are the compiler's to decide, not this module's.
                            remedy -> assertThat(remedy)
                                    .startsWith("    remedy: ")
                                    .contains("targets = "));
        }

        @Test
        @DisplayName("a correct weave produces nothing at all")
        void silenceIsTheNormalOutcome() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.At;
                    import de.splatgames.aether.weaver.api.Inject;
                    import de.splatgames.aether.weaver.api.Point;
                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public final class Clean {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        void onRun() {
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("a processor that reports something about every weave trains its users to "
                            + "stop reading")
                    .isEmpty();
            assertThat(compiled.succeeded()).isTrue();
        }

        @Test
        @DisplayName("a class that is not a weave is not looked at")
        void plainClassesAreUntouched() {
            assertThat(compile("""
                    package fixture;

                    public class Plain {
                        public Plain(int unused) { }
                    }
                    """).codes()).isEmpty();
        }

        @Test
        @DisplayName("an abstract weave is not told to be final")
        void abstractWeavesAreExempt() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Accessor;
                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public abstract class Abstract {

                        @Accessor abstract String getName();
                    }
                    """).codes())
                    .as("an abstract class cannot be final, so the advice would be impossible to "
                            + "follow — and the abstract spelling of @Accessor is legitimate")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("target resolution")
    class Targets {

        @Test
        @DisplayName("AW1087 — a weave that targets another weave, on the literal that names it")
        void weavingAWeaveIsRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave({
                        Target.class,
                        Other.class
                    })
                    public final class Layered {
                    }

                    @Weave(Target.class)
                    final class Other {
                    }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1087");
            assertThat(compiled.caretLine("AW1087"))
                    .as("the line, not the caret text: javac puts the caret on the '.class' of "
                            + "a class literal, so 'Target.class' and 'Other.class' produce the "
                            + "same three characters — an assertion on those would claim the "
                            + "offending literal was picked out while proving nothing")
                    .isEqualTo("Other.class");
        }

        @Test
        @DisplayName("AW1004 — a named target that does not resolve")
        void unresolvableNameIsRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(targets = "fixture.Absent")
                    public final class Missing {
                    }
                    """);

            assertThat(compiled.caretText("AW1004")).startsWith("\"fixture.Absent\"");
            assertThat(compiled.succeeded()).isFalse();
        }

        @Test
        @DisplayName("require = OPTIONAL makes an unresolvable target legitimate")
        void optionalTargetsMayBeAbsent() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Require;
                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(targets = "fixture.Absent", require = Require.OPTIONAL)
                    public final class Optional {
                    }
                    """).codes())
                    .as("the string form exists precisely for a target that is absent at compile "
                            + "time; reporting it would make the escape hatch unusable")
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1009 — a name that could have been a class literal")
        void resolvableNamesArePointedOut() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(targets = "fixture.Target")
                    public final class ByName {
                    }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1009");
            assertThat(compiled.succeeded())
                    .as("AW1009 is INFO: the weave is correct, the spelling is merely weaker than "
                            + "it needs to be")
                    .isTrue();
            assertThat(compiled.messageOf("AW1009")).contains("@Weave(Target.class)");
        }
    }

    @Nested
    @DisplayName("selectors")
    class Selectors {

        @Test
        @DisplayName("AW1015 — a malformed selector, on the literal")
        void syntaxErrorsPointAtTheSelector() {
            final Compilation compiled = compile(injecting("run("));

            assertThat(compiled.caretText("AW1015")).startsWith("\"run(\"");
        }

        @Test
        @DisplayName("AW1017 — a descriptor written without the desc: prefix, with the fix")
        void aDescriptorWithoutItsPrefixIsRecognised() {
            final Compilation compiled = compile(injecting("(Ljava/lang/String;)V"));

            assertThat(compiled.codes()).contains("AW1017");
            assertThat(compiled.messageOf("AW1017"))
                    .as("the remedy is the corrected selector itself, which is what makes it a "
                            + "quick fix rather than advice")
                    .contains("desc:(Ljava/lang/String;)V");
        }

        @Test
        @DisplayName("AW1019 — a desc: selector missing its return type")
        void aDescriptorMissingItsReturnTypeIsRefused() {
            assertThat(compile(injecting("desc:run(I)")).codes()).contains("AW1019");
        }

        @Test
        @DisplayName("AW1018 — a wildcard inside the descriptor form")
        void wildcardsAreRefusedInTheDescriptorForm() {
            assertThat(compile(injecting("desc:run(*)V")).codes()).contains("AW1018");
        }

        @Test
        @DisplayName("AW1016 — type arguments are accepted and reported as ignored")
        void typeArgumentsAreReported() {
            final Compilation compiled = compile(injecting("accept(java.util.List<String>)"));

            assertThat(compiled.codes()).containsExactly("AW1016");
            assertThat(compiled.succeeded())
                    .as("a user pasting a signature out of their editor should not have to strip "
                            + "them by hand — but should be told the selector does not see them")
                    .isTrue();
        }

        @Test
        @DisplayName("an empty selector is refused rather than parsed")
        void emptySelectorsAreRefused() {
            assertThat(compile(injecting("")).codes()).containsExactly("AW1015");
        }

        @Test
        @DisplayName("both halves of a repeated @Inject are parsed")
        void repeatedInjectionsAreBothSeen() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.At;
                    import de.splatgames.aether.weaver.api.Inject;
                    import de.splatgames.aether.weaver.api.Point;
                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public final class Repeated {

                        @Inject(method = "run(", at = @At(Point.HEAD))
                        @Inject(method = "stop(", at = @At(Point.HEAD))
                        void onBoth() {
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("javac rewrites two @Inject into one @Inject.Container, so code that reads "
                            + "the mirrors directly sees neither — a bug that only appears once "
                            + "someone writes the second annotation")
                    .containsExactly("AW1015", "AW1015");
        }

        private String injecting(final String selector) {
            return """
                    package fixture;

                    import de.splatgames.aether.weaver.api.At;
                    import de.splatgames.aether.weaver.api.Inject;
                    import de.splatgames.aether.weaver.api.Point;
                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Target.class)
                    public final class Selecting {

                        @Inject(method = "%s", at = @At(Point.HEAD))
                        void onRun() {
                        }
                    }
                    """.formatted(selector);
        }
    }

    @Nested
    @DisplayName("what a @Shadow promises")
    class Shadows {

        @Test
        @DisplayName("AW1030 — shadowing a field the target does not declare, with what it does")
        void unknownFieldIsRefused() {
            final Compilation compiled = compile(weave("@Shadow private String ghost;"));

            assertThat(compiled.codes()).containsExactly("AW1030");
            assertThat(compiled.messageOf("AW1030"))
                    .as("the fields the target really has are what turns a refusal into a fix")
                    .contains("declares: name")
                    .contains("declares: level");
        }

        @Test
        @DisplayName("AW1031 — shadowing it at the wrong type")
        void typeMismatchIsRefused() {
            final Compilation compiled = compile(weave("@Shadow private int name;"));

            assertThat(compiled.codes()).containsExactly("AW1031");
            assertThat(compiled.messageOf("AW1031")).contains("int").contains("java.lang.String");
        }

        @Test
        @DisplayName("the renamed form binds to the target's own name")
        void theValueNamesTheTargetsMember() {
            assertThat(compile(weave("@Shadow(\"name\") private String local;")).codes())
                    .as("@Shadow(\"…\") exists so the weave may call the member something else")
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1033 — mutable = true on a final target field, on the mutable literal")
        void removingFinalIsReported() {
            final Compilation compiled = compile(
                    weave("@Shadow(value = \"frozen\", mutable = true) private String frozen;"));

            assertThat(compiled.codes()).containsExactly("AW1033");
            assertThat(compiled.caretText("AW1033"))
                    .as("the caret goes on the element that asked for it, which is the one to "
                            + "delete if it was not meant")
                    .startsWith("true");
        }

        @Test
        @DisplayName("mutable = true on a field that was never final says nothing")
        void nothingToRemoveIsNotWorthSaying() {
            assertThat(compile(weave("@Shadow(mutable = true) private int level;")).codes())
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1032 — a shadow field with an initialiser")
        void initialisersAreReported() {
            assertThat(compile(weave("@Shadow static final String name = \"x\";")).codes())
                    .contains("AW1032");
        }

        @Test
        @DisplayName("AW1020 — shadowing a method the target does not declare")
        void unknownMethodIsRefused() {
            final Compilation compiled = compile(
                    weave("@Shadow private String ghost(long token) { return null; }"));

            assertThat(compiled.codes()).containsExactly("AW1020");
            assertThat(compiled.messageOf("AW1020")).contains("declares: secret(long)");
        }

        @Test
        @DisplayName("a shadowed private method of the target binds")
        void privateTargetMethodsAreReachable() {
            assertThat(compile(weave(
                    "@Shadow private String secret(long token) { return null; }")).codes())
                    .as("after merging the code lives in the target, where its private members "
                            + "are reachable by ordinary rules — that is the point of an instance "
                            + "weave")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("what a weave adds")
    class Merged {

        @Test
        @DisplayName("AW1080 — a merged member colliding with the target's own")
        void collisionIsRefused() {
            assertThat(compile(weave("private String name;")).codes()).containsExactly("AW1080");
        }

        @Test
        @DisplayName("@Unique makes the same collision legitimate")
        void uniqueIsPermissionToRename() {
            assertThat(compile(weave("@Unique private String name;")).codes()).isEmpty();
        }

        @Test
        @DisplayName("AW1093 — a merged field with an initialiser")
        void initialisersAreReported() {
            final Compilation compiled = compile(weave("static final int LIMIT = 5;"));

            assertThat(compiled.codes()).containsExactly("AW1093");
            assertThat(compiled.succeeded())
                    .as("the field works; it merely starts at the JVM's default")
                    .isTrue();
        }

        @Test
        @DisplayName("AW1083 — merging a method the platform calls")
        void objectMethodsAreWarnedAbout() {
            final Compilation compiled = compile(
                    weave("@Override public String toString() { return \"woven\"; }"));

            assertThat(compiled.codes()).containsExactly("AW1083");
        }

        @Test
        @DisplayName("an overload sharing only the name is left alone")
        void overloadsAreNotTheSameMethod() {
            assertThat(compile(weave("public boolean equals(String other) { return false; }"))
                    .codes())
                    .as("equals(String) is not the method the platform calls, and warning about "
                            + "it would train the author to ignore the code")
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1090 / AW1091 — @Shadow and @Unique in a static weave, once each")
        void mergeOnlyAnnotationsAreRefusedInStaticWeaves() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                    public final class StaticMembers {

                        @Shadow private String name;
                        @Unique private long startedAt;
                    }
                    """);

            assertThat(compiled.codes())
                    .as("a weave with several targets must not be told once per target that "
                            + "its annotation is pointless — that is a fact about the weave")
                    .containsExactlyInAnyOrder("AW1090", "AW1091");
        }

        @Test
        @DisplayName("a weave-only fact is reported once even with two targets")
        void declarationFactsDoNotRepeatPerTarget() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave({Target.class, Second.class})
                    public final class TwoTargets {

                        static final int LIMIT = 5;
                    }

                    class Second { }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1093");
        }
    }

    @Nested
    @DisplayName("generated members")
    class Generated {

        @Test
        @DisplayName("an accessor infers the field from its name")
        void namesAreInferred() {
            assertThat(compile(weaveAbstract("@Accessor abstract String getName();")).codes())
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1030 — an accessor naming a field the target does not have")
        void unknownAccessorFieldIsRefused() {
            assertThat(compile(weaveAbstract("@Accessor abstract String getGhost();")).codes())
                    .containsExactly("AW1030");
        }

        @Test
        @DisplayName("AW1097 — a setter for a final field, which verifies and then throws")
        void settersForFinalFieldsAreRefused() {
            final Compilation compiled = compile(
                    weaveAbstract("@Accessor abstract void setFrozen(String value);"));

            assertThat(compiled.codes()).containsExactly("AW1097");
            assertThat(compiled.messageOf("AW1097"))
                    .as("the class VERIFIES and throws IllegalAccessError on the first call, "
                            + "which is why neither ClassFile.verify nor class loading catches it")
                    .contains("IllegalAccessError")
                    .contains("@Shadow(mutable = true)");
        }

        @Test
        @DisplayName("a setter for a mutable field is fine")
        void settersForOrdinaryFieldsAreFine() {
            assertThat(compile(weaveAbstract("@Accessor abstract void setLevel(int value);"))
                    .codes()).isEmpty();
        }

        @Test
        @DisplayName("AW1020 — an invoker naming a method that is not there")
        void unknownInvokerMethodIsRefused() {
            assertThat(compile(weaveAbstract("@Invoker abstract void callGhost();")).codes())
                    .containsExactly("AW1020");
        }

        @Test
        @DisplayName("an invoker reaches the target's private method")
        void invokersReachPrivateMethods() {
            assertThat(compile(weaveAbstract(
                    "@Invoker abstract String callSecret(long token);")).codes())
                    .as("the generated method lives inside the target, where its own private "
                            + "members are reachable by ordinary rules")
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1095 — a generated member the target already declares")
        void collidingGeneratedMembersAreRefused() {
            assertThat(compile(weaveAbstract("@Invoker abstract void run();")).codes())
                    .as("the invoker resolves — it is the generated method's own name that the "
                            + "target already uses. A generated member cannot be @Unique: callers "
                            + "reach it by the name it is declared under")
                    .containsExactly("AW1095");
        }
    }

    @Nested
    @DisplayName("targets whose shape fixes their instance state")
    class ShapedTargets {

        @Test
        @DisplayName("AW1088 — an instance field merged into a record")
        void recordsAreRefused() {
            assertThat(compile(shaped("record Shape(int x) { }",
                    "private long added;")).codes())
                    .containsExactly("AW1088");
        }

        @Test
        @DisplayName("AW1089 — an instance field merged into an enum, with a warning")
        void enumsAreWarnedAbout() {
            final Compilation compiled = compile(shaped("enum Shape { LOW, HIGH }",
                    "private long added;"));

            assertThat(compiled.codes()).containsExactly("AW1089");
            assertThat(compiled.succeeded()).isTrue();
        }

        @Test
        @DisplayName("a static field is neither shape's business")
        void staticFieldsAreOrdinary() {
            assertThat(compile(shaped("record Shape(int x) { }",
                    "private static long added;")).codes())
                    .as("neither a record's component contract nor an enum's fixed set of "
                            + "instances has anything to say about a class-level member")
                    .isEmpty();
        }

        private String shaped(final String target, final String body) {
            return """
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Shape.class)
                    public final class Shaping {
                        %s
                    }

                    %s
                    """.formatted(body, target);
        }
    }

    @Nested
    @DisplayName("a handler against the method it names")
    class Handlers {

        @Test
        @DisplayName("AW1020 — a target method that is not there, with the ones that are")
        void unknownTargetMethodIsRefused() {
            final Compilation compiled = compile(handler("ghost()", "void onRun()"));

            assertThat(compiled.codes()).containsExactly("AW1020");
            assertThat(compiled.messageOf("AW1020")).contains("declares: run()");
            assertThat(compiled.caretText("AW1020")).startsWith("\"ghost()\"");
        }

        @Test
        @DisplayName("AW1021 — a selector that matches two overloads, listing both")
        void ambiguousSelectorsAreRefused() {
            final Compilation compiled = compile(handler("charge", "void onRun()"));

            assertThat(compiled.codes()).containsExactly("AW1021");
            assertThat(compiled.messageOf("AW1021"))
                    .contains("matches: charge(int)")
                    .contains("matches: charge(int,java.lang.String)");
        }

        @Test
        @DisplayName("AW1022 — a wildcard that matched several, which is not the same mistake")
        void broadWildcardsAreTheirOwnRefusal() {
            final Compilation compiled = compile(handler("*()", "void onRun()"));

            assertThat(compiled.codes())
                    .as("a wildcard that matched several did what it was written to do; the fault "
                            + "is that nothing said how many were expected")
                    .containsExactly("AW1022");
            assertThat(compiled.messageOf("AW1022"))
                    .as("the remedy names the count that was actually found, which "
                            + "is what makes it a paste-able fix")
                    .contains("allow = 3");
        }

        @Test
        @DisplayName("allow makes a broad wildcard deliberate")
        void allowDeclaresTheExpectedCount() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public final class Broad {

                        @Inject(method = "*()", at = @At(Point.HEAD), allow = 3)
                        void onRun() {
                        }
                    }
                    """).codes())
                    .as("stating the count is what makes a later change in it catchable")
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1010 — a selector whose explicit owner names nothing")
        void unresolvableOwnersAreRefused() {
            final Compilation compiled = compile(handler("fixture.Absent.run()", "void onRun()"));

            assertThat(compiled.codes())
                    .as("every later check would otherwise ask its questions about a class that "
                            + "is not there and answer them about the target instead")
                    .containsExactly("AW1010");
        }

        @Test
        @DisplayName("an unqualified owner is not reported as missing")
        void simpleOwnerNamesAreLeftAlone() {
            assertThat(compile(handler("Target.run()", "void onRun()")).codes())
                    .as("a simple name cannot be resolved without the file's imports, and "
                            + "reporting one as missing would be wrong far more often than right")
                    .isEmpty();
        }

        @Test
        @DisplayName("the parameter types pick one overload out")
        void parametersDisambiguate() {
            assertThat(compile(handler("charge(int)", "void onRun()")).codes()).isEmpty();
        }

        @Test
        @DisplayName("AW1041 — a handler that returns something")
        void nonVoidHandlersAreRefused() {
            assertThat(compile(handler("run()", "int onRun()", "return 0;")).codes())
                    .containsExactly("AW1041");
        }

        @Test
        @DisplayName("AW1040 — more parameters than the target has")
        void tooManyParametersAreRefused() {
            final Compilation compiled = compile(handler("run()", "void onRun(int extra)"));

            assertThat(compiled.codes()).containsExactly("AW1040");
            assertThat(compiled.messageOf("AW1040")).contains("the target has only 0");
        }

        @Test
        @DisplayName("AW1040 — the right count at the wrong type, on the parameter")
        void mismatchedParameterTypesAreRefused() {
            final Compilation compiled = compile(handler("charge(int)", "void onRun(String wrong)"));

            assertThat(compiled.codes()).containsExactly("AW1040");
            assertThat(compiled.caretText("AW1040"))
                    .as("the caret goes on the parameter that does not fit, not on the handler — "
                            + "javac puts it on a variable's name rather than its type")
                    .startsWith("wrong)");
        }

        @Test
        @DisplayName("a prefix shorter than the target's argument list is accepted")
        void aProperPrefixIsFine() {
            assertThat(compile(handler("charge(int,java.lang.String)", "void onRun(int amount)"))
                    .codes())
                    .as("the injected call pushes the target's arguments in order, so the first n "
                            + "of them are always available")
                    .isEmpty();
        }

        @Test
        @DisplayName("a @Local capture is not counted as a target argument")
        void capturesAreExcludedFromThePrefix() {
            assertThat(compile(handler("run()",
                    "void onRun(@Local(name = \"count\") int count)")).codes())
                    .as("counting captures would make every interesting handler look wrong")
                    .isEmpty();
        }

        @Test
        @DisplayName("a static weave's handler takes the target as its first parameter")
        void theReceiverIsNotATargetArgument() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                    public final class Receiving {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        static void onRun(Target self) {
                        }
                    }
                    """).codes())
                    .as("a static weave is never merged, so its handler has no 'this' — taking "
                            + "the target as the first parameter is what AW1005's own remedy "
                            + "tells the author to do, and counting it as an argument made every "
                            + "correct static handler look like it took one too many")
                    .isEmpty();
        }

        @Test
        @DisplayName("the receiver is recognised by type, not by position")
        void aFirstParameterOfTheWrongTypeIsStillAnArgument() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                    public final class Mistaken {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        static void onRun(String notTheTarget) {
                        }
                    }
                    """).codes())
                    .as("dropping the first parameter unconditionally would stop the prefix rule "
                            + "catching anything at all in a static weave")
                    .containsExactly("AW1040");
        }

        @Test
        @DisplayName("a static target method has no receiver to take")
        void staticTargetsTakeNoReceiver() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = Utility.class, kind = Weave.Kind.STATIC)
                    public final class OnStatic {

                        @Inject(method = "compute()", at = @At(Point.HEAD))
                        static void onCompute(Utility notAReceiver) {
                        }
                    }

                    class Utility {
                        static void compute() { }
                    }
                    """).codes())
                    .as("there is no instance to pass, so the parameter is an argument the "
                            + "target does not have")
                    .contains("AW1040");
        }

        @Test
        @DisplayName("a trailing callback is not counted either")
        void callbacksAreExcludedFromThePrefix() {
            assertThat(compile(handler("run()",
                    "void onRun(de.splatgames.aether.weaver.api.callback.Callback cb)")).codes())
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1071 — a ReturnableCallback of the wrong type, boxed correctly")
        void callbackTypeMustMatchTheReturnType() {
            final Compilation compiled = compile(handler("charge(int)",
                    "void onRun(de.splatgames.aether.weaver.api.callback."
                            + "ReturnableCallback<Integer> cb)"));

            assertThat(compiled.codes()).containsExactly("AW1071");
            assertThat(compiled.messageOf("AW1071"))
                    .contains("returns java.lang.String")
                    .contains("ReturnableCallback<java.lang.String>");
        }

        @Test
        @DisplayName("a primitive return type is matched against its wrapper")
        void primitivesAreBoxed() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.ReturnableCallback;

                    @Weave(Counting.class)
                    public final class Boxed {

                        @Inject(method = "count()", at = @At(Point.RETURN))
                        void onCount(ReturnableCallback<Integer> cb) {
                        }
                    }

                    class Counting {
                        int count() { return 0; }
                    }
                    """).codes())
                    .as("a type argument cannot be primitive, so comparing int to Integer "
                            + "directly would refuse every primitive-returning target; only "
                            + "AW1200 remains, because Counting is compiled in this round")
                    .containsExactly("AW1200");
        }

        @Test
        @DisplayName("AW1023 — an abstract target method")
        void abstractTargetsAreRefused() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Shapeless.class)
                    public final class OnAbstract {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        void onRun() {
                        }
                    }

                    abstract class Shapeless {
                        abstract void run();
                    }
                    """).codes())
                    .as("AW1200 rides along because Shapeless is compiled in this very round, "
                            + "which is exactly the case the notice exists for")
                    .containsExactlyInAnyOrder("AW1023", "AW1200");
        }

        @Test
        @DisplayName("AW1025 — a native target method, which is not the same refusal")
        void nativeTargetsAreTheirOwnRefusal() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Bridged.class)
                    public final class OnNative {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        void onRun() {
                        }
                    }

                    class Bridged {
                        native void run();
                    }
                    """);

            assertThat(compiled.codes()).containsExactlyInAnyOrder("AW1025", "AW1200");
            assertThat(compiled.messageOf("AW1025"))
                    .as("both are bodyless, and AW1023 would send the author looking for an "
                            + "implementation to inject into instead — there is none")
                    .contains("@Redirect");
        }

        @Test
        @DisplayName("AW1061 — a @Redirect pointed at a position rather than an operation")
        void redirectsNeedAnOperation() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public final class Redirecting {

                        @Redirect(method = "run()", at = @At(Point.HEAD))
                        void onRun() {
                        }
                    }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1061");
            assertThat(compiled.messageOf("AW1061")).contains("INVOKE");
        }

        @Test
        @DisplayName("AW1042 — a private handler in a static weave, which nothing could call")
        void privateHandlersInStaticWeavesAreRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = Target.class, kind = Weave.Kind.STATIC)
                    public final class Hidden {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        private static void onRun() {
                        }
                    }
                    """);

            assertThat(compiled.codes()).containsExactly("AW1042");
            assertThat(compiled.messageOf("AW1042")).contains("Kind.INSTANCE");
        }

        @Test
        @DisplayName("AW1042 — a package-private handler in another package is unreachable too")
        void packagePrivateHandlersAcrossPackagesAreRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = other.Collaborator.class, kind = Weave.Kind.STATIC)
                    public final class CrossPackage {

                        @Inject(method = "work()", at = @At(Point.HEAD))
                        static void onWork() {
                        }
                    }
                    """, """
                    package other;

                    public class Collaborator {
                        public void work() { }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("the first version of this check looked at 'private' alone. A "
                            + "package-private handler in a different package is exactly as "
                            + "unreachable, and the JVM says so with IllegalAccessError at the "
                            + "first execution of the injected call — found by a module test "
                            + "whose fixture happened to be written that way")
                    .contains("AW1042");
            assertThat(compiled.messageOf("AW1042"))
                    .as("naming both packages is what makes the refusal actionable")
                    .contains("package other")
                    .contains("fixture");
        }

        @Test
        @DisplayName("a public handler in a public class reaches any package")
        void publicHandlersReachAnywhere() {
            assertThat(compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(value = other.Collaborator.class, kind = Weave.Kind.STATIC)
                    public final class Reachable {

                        @Inject(method = "work()", at = @At(Point.HEAD))
                        public static void onWork() {
                        }
                    }
                    """, """
                    package other;

                    public class Collaborator {
                        public void work() { }
                    }
                    """).codes())
                    .doesNotContain("AW1042");
        }

        @Test
        @DisplayName("a package-private handler in the target's own package is fine")
        void packagePrivateIsFineWithinOnePackage() {
            assertThat(compile(handler("run()", "static void onRun()")).codes())
                    .as("the weave and the target share package 'fixture', where package-private "
                            + "access is exactly what the language grants")
                    .isEmpty();
        }

        @Test
        @DisplayName("a private handler in an instance weave is fine")
        void privateHandlersInInstanceWeavesAreFine() {
            assertThat(compile(handler("run()", "private void onRun()")).codes())
                    .as("an instance weave's handler moves into the target, where private means "
                            + "private to the target — and that is where it is called from")
                    .isEmpty();
        }

        @Test
        @DisplayName("a handler fault is reported once per target, not once")
        void targetFaultsAreReportedPerTarget() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave({Target.class, Sparse.class})
                    public final class TwoTargets {

                        @Inject(method = "stop()", at = @At(Point.HEAD))
                        void onStop() {
                        }
                    }

                    class Sparse { }
                    """);

            assertThat(compiled.codes())
                    .as("whether a target has the method is a fact about that target; saying it "
                            + "once would leave the author guessing which one")
                    .containsExactlyInAnyOrder("AW1020", "AW1200");
        }

        private String handler(final String selector, final String signature) {
            return handler(selector, signature, "");
        }

        private String handler(final String selector, final String signature, final String body) {
            return """
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public final class Handling {

                        @Inject(method = "%s", at = @At(Point.HEAD))
                        %s { %s }
                    }
                    """.formatted(selector, signature, body);
        }
    }

    @Nested
    @DisplayName("a @Wrap handler, whose shape decides whether it can nest")
    class Wraps {

        @Test
        @DisplayName("a well-formed wrap compiles clean")
        void aWellFormedWrapIsAccepted() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.Operation;

                    @Weave(Target.class)
                    public final class Wrapping {

                        @Wrap(method = "run()",
                              at = @At(value = Point.INVOKE, target = "#helper"))
                        static void onHelper(Target receiver, Operation<Void> original) {
                            original.call(receiver);
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("a correct wrap must not be reported as anything")
                    .isEmpty();
        }

        @Test
        @DisplayName("the processor sees @Wrap at all, which an empty report cannot prove")
        void theAnnotationIsRecognised() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.Operation;

                    @Weave(Target.class)
                    public final class Wrapping {

                        @Wrap(method = "absent()",
                              at = @At(value = Point.INVOKE, target = "#helper"))
                        static void onHelper(Target receiver, Operation<Void> original) {
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("this test exists because the clean-compile one above passes just as "
                            + "happily when the processor does not know @Wrap at all: an "
                            + "unrecognised annotation reports nothing, and reporting nothing is "
                            + "what a correct declaration looks like. Only a diagnostic that "
                            + "requires the wrap path to have run can tell the two apart")
                    .contains("AW1020");
        }

        @Test
        @DisplayName("AW1063 — a handler with no Operation is a @Redirect wearing the wrong name")
        void aMissingOperationIsRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public final class Wrapping {

                        @Wrap(method = "run()",
                              at = @At(value = Point.INVOKE, target = "#helper"))
                        static void onHelper(Target receiver) {
                        }
                    }
                    """);

            assertThat(compiled.codes()).contains("AW1063");
        }

        @Test
        @DisplayName("AW1062 — a parameter after the Operation, which only nests by luck")
        void parametersAfterTheOperationAreRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.Operation;

                    @Weave(Target.class)
                    public final class Wrapping {

                        @Wrap(method = "run()",
                              at = @At(value = Point.INVOKE, target = "#helper"))
                        static void onHelper(Target receiver, Operation<Void> original,
                                             int extra) {
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("such a handler is the outermost wrap's shape and not an inner level's, so "
                            + "it would compile, run, and break when a second weave arrived")
                    .contains("AW1062");
        }

        @Test
        @DisplayName("AW1005 — a non-static handler has no receiver to reach as an inner level")
        void anInstanceHandlerIsRefused() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.Operation;

                    @Weave(Target.class)
                    public final class Wrapping {

                        @Wrap(method = "run()",
                              at = @At(value = Point.INVOKE, target = "#helper"))
                        void onHelper(Target receiver, Operation<Void> original) {
                        }
                    }
                    """);

            assertThat(compiled.codes()).contains("AW1005");
        }

        @Test
        @DisplayName("AW1061 — a wrap pointed at a position rather than an operation")
        void wrapsNeedAnOperation() {
            final Compilation compiled = compile("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.Operation;

                    @Weave(Target.class)
                    public final class Wrapping {

                        @Wrap(method = "run()", at = @At(Point.HEAD))
                        static void onRun(Operation<Void> original) {
                        }
                    }
                    """);

            assertThat(compiled.codes()).contains("AW1061");
            assertThat(compiled.messageOf("AW1061"))
                    .as("the message has to name the annotation the author wrote")
                    .contains("@Wrap");
        }
    }

    @Nested
    @DisplayName("a mutable capture, whose type has to match what it claims")
    class MutableCaptures {

        @Test
        @DisplayName("a carrier with mutable = true compiles clean")
        void aCarrierWithMutableIsAccepted() {
            assertThat(compile(capture(
                    "@Local(name = \"total\", mutable = true) LocalIntRef total")).codes())
                    .isEmpty();
        }

        @Test
        @DisplayName("AW1053 — mutable = true on a value parameter, which could only be a no-op")
        void mutableOnAValueParameterIsRefused() {
            assertThat(compile(capture(
                    "@Local(name = \"total\", mutable = true) int total")).codes())
                    .as("Java passes parameters by value: the handler would assign to its own copy "
                            + "and the target would carry on with the old one, with nothing to "
                            + "notice it. That is the failure @Local's flag had for a whole release")
                    .contains("AW1053");
        }

        @Test
        @DisplayName("AW1054 — a carrier on a capture that did not ask to write")
        void aCarrierWithoutMutableIsRefused() {
            assertThat(compile(capture("@Local(name = \"total\") LocalIntRef total")).codes())
                    .contains("AW1054");
        }

        @Test
        @DisplayName("a plain capture is left alone")
        void aPlainCaptureIsAccepted() {
            assertThat(compile(capture("@Local(name = \"total\") int total")).codes()).isEmpty();
        }

        private String capture(final String parameter) {
            return """
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;
                    import de.splatgames.aether.weaver.api.callback.*;

                    @Weave(Target.class)
                    public final class Capturing {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        static void onRun(%s) {
                        }
                    }
                    """.formatted(parameter);
        }
    }

    @Nested
    @DisplayName("injection points, against the target's real bytecode")
    class Points {

        @Test
        @DisplayName("AW1043 — a point that matches nothing, at compile time")
        void aPointThatMatchesNothingIsReported() {
            final Compilation compiled = compile(at(
                    "@At(value = Point.INVOKE, target = \"#absent\")"));

            assertThat(compiled.codes())
                    .as("the whole reason the processor reads bytecode: the selector and the "
                            + "point are both well-formed and name things that exist, and the "
                            + "weave still does nothing")
                    .containsExactly("AW1043");
            assertThat(compiled.succeeded()).isFalse();
        }

        @Test
        @DisplayName("a point that does match is silent")
        void aMatchingPointIsSilent() {
            assertThat(compile(at(
                    "@At(value = Point.INVOKE, target = \"#helper\")")).codes())
                    .as("Target.run() really calls helper(), and the resolver finds it")
                    .isEmpty();
        }

        @Test
        @DisplayName("HEAD always matches, so it is never reported")
        void headAlwaysMatches() {
            assertThat(compile(at("@At(Point.HEAD)")).codes()).isEmpty();
        }

        @Test
        @DisplayName("the caret lands on the selector, which is what the author would change")
        void theDiagnosticIsPositioned() {
            final Compilation compiled = compile(at(
                    "@At(value = Point.INVOKE, target = \"#absent\")"));

            assertThat(compiled.caretText("AW1043")).startsWith("\"run()\"");
        }

        @Test
        @DisplayName("AW1200 — a target compiled in the same round cannot be checked here")
        void aSameRoundTargetIsNotChecked() {
            final Compilation compiled = compileSameRound(at(
                    "@At(value = Point.INVOKE, target = \"#absent\")"));

            assertThat(compiled.codes())
                    .as("annotation processing runs before code generation, so the target has no "
                            + "class file yet — saying so beats both silence and a false refusal")
                    .containsExactly("AW1200");
            assertThat(compiled.succeeded())
                    .as("AW1200 is INFO; the points are validated at weave time, where they "
                            + "always can be")
                    .isTrue();
        }

        @Test
        @DisplayName("AW1200 is said once per target, not once per injection")
        void theNoticeIsNotRepeated() {
            final Compilation compiled = compileSameRound("""
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public final class Twice {

                        @Inject(method = "run()", at = @At(Point.HEAD))
                        void onRun() {
                        }

                        @Inject(method = "stop()", at = @At(Point.HEAD))
                        void onStop() {
                        }
                    }
                    """);

            assertThat(compiled.codes())
                    .as("the notice is about the target, and repeating it per injection would "
                            + "bury whatever else the weave was told")
                    .containsExactly("AW1200");
        }

        @Test
        @DisplayName("a weave with no injections never reaches for the class file")
        void targetsAreOnlyReadWhenThereIsSomethingToResolve() {
            assertThat(compileSameRound(weave("@Unique private long added;")).codes())
                    .as("a purely structural weave has no points to resolve, so AW1200 would be "
                            + "a notice about a check that was never wanted")
                    .isEmpty();
        }

        private String at(final String point) {
            return """
                    package fixture;

                    import de.splatgames.aether.weaver.api.*;

                    @Weave(Target.class)
                    public final class Pointing {

                        @Inject(method = "run()", at = %s)
                        void onRun() {
                        }
                    }
                    """.formatted(point);
        }
    }

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        @DisplayName("the service file names the processor")
        void theServiceFileIsPresent() throws IOException {
            final Path file = Path.of("src/main/resources/META-INF/services",
                    "javax.annotation.processing.Processor");

            assertThat(file)
                    .as("without it the user has to configure an annotationProcessorPath by hand, "
                            + "and the module's whole promise is that having the dependency is "
                            + "enough")
                    .exists();
            assertThat(Files.readString(file).strip())
                    .isEqualTo(WeaveProcessor.class.getName());
        }
    }

    // -------------------------------------------------------------------------------------

    private static Compilation compileSameRound(final String source) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        final JavaFileObject weave = new Source("fixture/" + declaredNameOf(source), source);
        try {
            final Path output = Files.createTempDirectory("aether-weaver-processor");
            output.toFile().deleteOnExit();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(collected, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final JavaCompiler.CompilationTask task = compiler.getTask(null, files, collected,
                        // -proc:only would skip the checks that need a resolved superclass; the
                        // fixtures are small enough that compiling them costs nothing.
                        List.of("-implicit:none"), null,
                        List.of(weave, new Source("fixture/Target", TARGET)));
                task.setProcessors(List.of(new WeaveProcessor()));
                final boolean ok = task.call();
                return new Compilation(ok, collected.getDiagnostics(), source);
            }
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static Compilation compile(final String source) {
        return compile(source, (String[]) null);
    }

    private static Compilation compile(final String source, final String... extras) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try {
            final Path targetClasses = Files.createTempDirectory("aether-weaver-target");
            targetClasses.toFile().deleteOnExit();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(targetClasses));
                assertThat(compiler.getTask(null, files, null, List.of(), null,
                        List.of(new Source("fixture/Target", TARGET))).call())
                        .as("the target fixture must compile on its own")
                        .isTrue();
            }

            final DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
            final Path output = Files.createTempDirectory("aether-weaver-processor");
            output.toFile().deleteOnExit();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(collected, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final JavaCompiler.CompilationTask task = compiler.getTask(null, files, collected,
                        List.of("-classpath",
                                targetClasses + File.pathSeparator
                                        + System.getProperty("java.class.path")),
                        null,
                        units(source, extras));
                task.setProcessors(List.of(new WeaveProcessor()));
                final boolean ok = task.call();
                return new Compilation(ok, collected.getDiagnostics(), source);
            }
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String weave(final String body) {
        return """
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Target.class)
                public final class Weaving {
                    %s
                }
                """.formatted(body);
    }

    private static String weaveAbstract(final String body) {
        return """
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Target.class)
                public abstract class Weaving {
                    %s
                }
                """.formatted(body);
    }

    private static List<JavaFileObject> units(final String source, final String[] extras) {
        final List<JavaFileObject> units = new ArrayList<>();
        units.add(new Source("fixture/" + declaredNameOf(source), source));
        if (extras != null) {
            for (final String extra : extras) {
                units.add(new Source(packageOf(extra) + '/' + declaredNameOf(extra), extra));
            }
        }
        return units;
    }

    private static String packageOf(final String source) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^package (\\S+);").matcher(source);
        assertThat(matcher.find()).as("every fixture declares a package").isTrue();
        return matcher.group(1).replace('.', '/');
    }

    private static String declaredNameOf(final String source) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^public (?:final |abstract )?class (\\w+)")
                .matcher(source);
        assertThat(matcher.find()).as("every fixture declares one public class").isTrue();
        return matcher.group(1);
    }

    private static final String TARGET = """
            package fixture;

            public class Target {

                private String name;
                private final String frozen = "fixed";
                private int level;

                public void run() { helper(); }
                public void helper() { }
                public void stop() { }
                public void accept(java.util.List<String> items) { }
                public String charge(int amount) { return "charged"; }
                public String charge(int amount, String currency) { return "charged"; }
                private String secret(long token) { return this.name; }
            }
            """;

    private record Compilation(boolean succeeded,
                               List<Diagnostic<? extends JavaFileObject>> diagnostics,
                               String source) {

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

        private String messageOf(final String code) {
            return find(code).getMessage(null);
        }

        private String caretText(final String code) {
            final long position = find(code).getPosition();
            assertThat(position)
                    .as("%s has no position at all, which is the failure this test exists to "
                            + "catch", code)
                    .isNotNegative();
            final int start = (int) position;
            final int newline = this.source.indexOf('\n', start);
            return this.source.substring(start, newline < 0 ? this.source.length() : newline);
        }

        private String caretLine(final String code) {
            final int position = (int) find(code).getPosition();
            final int start = this.source.lastIndexOf('\n', position) + 1;
            final int end = this.source.indexOf('\n', position);
            return this.source.substring(start, end < 0 ? this.source.length() : end).strip();
        }

        private Diagnostic<? extends JavaFileObject> find(final String code) {
            final List<Diagnostic<? extends JavaFileObject>> matching = this.diagnostics.stream()
                    .filter(diagnostic -> diagnostic.getMessage(null).startsWith(code + ' '))
                    .toList();
            assertThat(matching)
                    .as("expected exactly one %s; got %s", code, this.diagnostics.stream()
                            .map(diagnostic -> diagnostic.getMessage(null)).toList())
                    .hasSize(1);
            return matching.getFirst();
        }
    }

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
}
