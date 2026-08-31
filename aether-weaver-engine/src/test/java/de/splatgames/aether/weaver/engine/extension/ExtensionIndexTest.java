package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionIndexTest {

    private static final String GREETING =
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting";

    private static final String HOLDER =
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions";

    private static final ClassSource CLASSPATH = internalName -> {
        try (InputStream in = ExtensionIndexTest.class.getResourceAsStream(
                '/' + internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (final IOException unreadable) {
            return Optional.empty();
        }
    };

    @Nested
    @DisplayName("a declaration the receiver already answers is thrown away")
    class Shadowing {

        @Test
        @DisplayName("an extension whose signature the receiver really declares is dropped")
        void realMemberWins() {
            // Greeting.greet() genuinely exists. If this were honoured, every existing call to the
            // real greet() anywhere in the program would be redirected into the extension — a call
            // that was already correct, silently made to mean something else. That is the single
            // way this feature could be wrong rather than broken, so it is the first test.
            final List<Diagnostic> reported = new ArrayList<>();
            final ExtensionIndex index = ExtensionIndex.of(
                    List.of(new WeaveManifest.Extension(HOLDER, GREETING, "greet",
                            "()Ljava/lang/String;")),
                    CLASSPATH, reported::add);

            assertThat(index.isEmpty()).isTrue();
            assertThat(reported).singleElement()
                    .extracting(Diagnostic::code)
                    .isEqualTo(DiagnosticCode.EXTENSION_SHADOWED_AT_CALL_SITE);
        }

        @Test
        @DisplayName("an inherited method counts as the receiver's own")
        void inheritedMemberWins() {
            // toString() is Object's, not Greeting's. At the call site that distinction does not
            // exist: javac resolves `greeting.toString()` to a real method either way, so an
            // index that only looked at declared methods would rewrite it.
            final List<Diagnostic> reported = new ArrayList<>();
            final ExtensionIndex index = ExtensionIndex.of(
                    List.of(new WeaveManifest.Extension(HOLDER, GREETING, "toString",
                            "()Ljava/lang/String;")),
                    CLASSPATH, reported::add);

            assertThat(index.isEmpty()).isTrue();
            assertThat(reported).singleElement()
                    .extracting(Diagnostic::code)
                    .isEqualTo(DiagnosticCode.EXTENSION_SHADOWED_AT_CALL_SITE);
        }

        @Test
        @DisplayName("a receiver that cannot be read is not treated as if it declared nothing")
        void unreadableReceiverIsSilent() {
            // A weaver often runs without the JDK's own class files to hand. Reporting AW1309 here
            // would fire on every such build; refusing the extension would break it. The processor
            // checked at compile time, so the right answer is to proceed and say nothing.
            final List<Diagnostic> reported = new ArrayList<>();
            final ExtensionIndex index = ExtensionIndex.of(
                    List.of(new WeaveManifest.Extension(HOLDER, GREETING, "greet",
                            "()Ljava/lang/String;")),
                    ClassSource.NONE, reported::add);

            assertThat(index.size()).isEqualTo(1);
            assertThat(reported).isEmpty();
        }
    }

    @Nested
    @DisplayName("two declarations of the same call")
    class Duplicates {

        @Test
        @DisplayName("a second contributor of the same call is refused, not preferred")
        void duplicateIsRefused() {
            final List<Diagnostic> reported = new ArrayList<>();
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                            new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                                    "(I)Ljava/lang/String;"),
                            new WeaveManifest.Extension("com.acme.OtherExtensions", GREETING,
                                    "shout", "(I)Ljava/lang/String;")),
                    CLASSPATH, reported::add);

            assertThat(index.size())
                    .as("the first declaration stands; the second is refused rather than winning")
                    .isEqualTo(1);
            assertThat(index.declaredOn(
                    "de/splatgames/aether/weaver/engine/extension/fixture/Greeting",
                    "shout", "(I)Ljava/lang/String;"))
                    .extracting(WeaveManifest.Extension::className)
                    .isEqualTo(HOLDER);
            assertThat(reported).singleElement()
                    .extracting(Diagnostic::code)
                    .isEqualTo(DiagnosticCode.DUPLICATE_EXTENSION);
        }

        @Test
        @DisplayName("the same name with a different descriptor is not a duplicate")
        void overloadsCoexist() {
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                            new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                                    "(I)Ljava/lang/String;"),
                            new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                                    "()Ljava/lang/String;")),
                    CLASSPATH, Reporter.NOOP);

            assertThat(index.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("resolving a call whose owner is not the extended type")
    class Resolution {

        private static final String NAMED =
                "de.splatgames.aether.weaver.engine.extension.fixture.Named";

        private static final String GREETING_INTERNAL =
                "de/splatgames/aether/weaver/engine/extension/fixture/Greeting";

        @Test
        @DisplayName("an extension on a supertype answers a call on the subtype")
        void supertypeIsFound() {
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                            new WeaveManifest.Extension(HOLDER, NAMED, "initial",
                                    "()Ljava/lang/String;")),
                    CLASSPATH, Reporter.NOOP);

            assertThat(index.find(GREETING_INTERNAL, "initial", "()Ljava/lang/String;",
                    WeaveManifest.Extension.Kind.INSTANCE))
                    .as("javac writes the receiver's static type into the instruction, so this is "
                            + "the owner a call on a Greeting actually carries")
                    .isNotNull();
        }

        @Test
        @DisplayName("but a real method on the way up stops the walk")
        void realMethodOnTheWayUpWins() {
            // `greet()` is contributed to Named, which does not declare it — so the index keeps the
            // declaration. Greeting, however, declares greet() for real, and javac resolved the
            // call to that. Redirecting it would change what an already-correct call means.
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                            new WeaveManifest.Extension(HOLDER, NAMED, "greet",
                                    "()Ljava/lang/String;")),
                    CLASSPATH, Reporter.NOOP);

            assertThat(index.size())
                    .as("Named itself has no greet(), so nothing is dropped at build time")
                    .isEqualTo(1);
            assertThat(index.find(GREETING_INTERNAL, "greet", "()Ljava/lang/String;",
                    WeaveManifest.Extension.Kind.INSTANCE))
                    .as("the real Greeting.greet() is found before Named is ever reached")
                    .isNull();
        }

        @Test
        @DisplayName("an instance extension does not answer a static call, or the other way round")
        void kindsDoNotSubstitute() {
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                            new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                                    "(I)Ljava/lang/String;")),
                    CLASSPATH, Reporter.NOOP);

            assertThat(index.find(GREETING_INTERNAL, "shout", "(I)Ljava/lang/String;",
                    WeaveManifest.Extension.Kind.STATIC))
                    .as("`Greeting.shout(2)` written as a static call is not this declaration")
                    .isNull();
            assertThat(index.find(GREETING_INTERNAL, "shout", "(I)Ljava/lang/String;",
                    WeaveManifest.Extension.Kind.INSTANCE))
                    .isNotNull();
        }

        @Test
        @DisplayName("a name and descriptor nothing answers to is dismissed before any lookup")
        void unknownSignatureIsDismissed() {
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                            new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                                    "(I)Ljava/lang/String;")),
                    CLASSPATH, Reporter.NOOP);

            assertThat(index.mentions("shout", "(I)Ljava/lang/String;")).isTrue();
            assertThat(index.mentions("shout", "(J)Ljava/lang/String;"))
                    .as("the gate is what keeps a program with no extension calls from walking "
                            + "hierarchies it has no reason to read")
                    .isFalse();
            assertThat(index.mentions("nothing", "()V")).isFalse();
        }

        @Test
        @DisplayName("without a classpath there is no hierarchy to walk, and nothing is invented")
        void noClasspathResolvesNothing() {
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                    new WeaveManifest.Extension(HOLDER, NAMED, "initial",
                            "()Ljava/lang/String;")));

            assertThat(index.find(GREETING_INTERNAL, "initial", "()Ljava/lang/String;",
                    WeaveManifest.Extension.Kind.INSTANCE))
                    .as("a weaver that cannot see the hierarchy must leave the call alone; the "
                            + "NoSuchMethodError that follows is louder than a wrong rewrite")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("what it answers")
    class Lookup {

        @Test
        @DisplayName("a call that is not an extension is not found")
        void ordinaryCallIsNotFound() {
            final ExtensionIndex index = ExtensionIndex.of(List.of(
                    new WeaveManifest.Extension(HOLDER, GREETING, "shout",
                            "(I)Ljava/lang/String;")));

            assertThat(index.declaredOn("java/lang/String", "shout", "(I)Ljava/lang/String;"))
                    .as("the same name and descriptor on a different owner is a different call")
                    .isNull();
            assertThat(index.declaredOn(Greeting.class.getName().replace('.', '/'), "shout",
                    "(J)Ljava/lang/String;"))
                    .as("the same name on the same owner with a different descriptor likewise")
                    .isNull();
        }

        @Test
        @DisplayName("an empty declaration list produces the shared empty index")
        void emptyIsShared() {
            assertThat(ExtensionIndex.of(List.of())).isSameAs(ExtensionIndex.EMPTY);
        }
    }
}
