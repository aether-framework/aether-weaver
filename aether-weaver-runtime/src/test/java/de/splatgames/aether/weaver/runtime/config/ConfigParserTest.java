package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigParserTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    @Nested
    @DisplayName("reading settings")
    class Settings {

        @Test
        @DisplayName("the scalar settings are read")
        void scalarsAreRead() {
            final WeaverConfig config = properties("""
                    aether.weaver.enabled = false
                    aether.weaver.verification = report
                    aether.weaver.onError = report
                    aether.weaver.phase = early
                    aether.weaver.dump = /tmp/woven
                    """).resolve();

            assertThat(config.enabled()).isFalse();
            assertThat(config.verification()).isEqualTo(VerificationPolicy.REPORT);
            assertThat(config.onError()).isEqualTo(ErrorPolicy.REPORT);
            assertThat(config.phase()).isEqualTo(Phase.EARLY);
            assertThat(config.dumpDirectoryIfSet()).contains(Path.of("/tmp/woven"));
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("enum values are read case-insensitively")
        void caseDoesNotMatter() {
            assertThat(properties("aether.weaver.verification = STRICT").resolve().verification())
                    .as("a properties file is written by a person, and shouting is not an error")
                    .isEqualTo(VerificationPolicy.STRICT);
        }

        @Test
        @DisplayName("dump = off means no directory, which a higher layer can say")
        void dumpingCanBeSwitchedOff() {
            assertThat(properties("aether.weaver.dump = off").resolve().dumpDirectoryIfSet())
                    .isEmpty();
        }

        @Test
        @DisplayName("both tag lists survive, because they describe one filter")
        void bothTagListsAreKept() {
            final TagFilter tags = properties("""
                    aether.weaver.tags.include = audit, metrics
                    aether.weaver.tags.exclude = experimental
                    """).resolve().tags();

            assertThat(tags.included())
                    .as("two keys describe one filter, and a file that sets both must end with "
                            + "both rather than with whichever line came last")
                    .containsExactlyInAnyOrder("audit", "metrics");
            assertThat(tags.excluded()).containsExactly("experimental");
        }

        @Test
        @DisplayName("the indexed keys address one weave and one injection")
        void indexedKeysAreRead() {
            final WeaverConfig config = properties("""
                    aether.weaver.weave[com.acme.Audit].enabled = false
                    aether.weaver.weave[com.acme.Audit].priority = 500
                    aether.weaver.injector[com.acme.Audit#onCharge].enabled = false
                    """).resolve();

            assertThat(config.isEnabled("com.acme.Audit", Set.of())).isFalse();
            assertThat(config.priorityOf("com.acme.Audit")).contains(500);
            assertThat(config.isInjectionEnabled("com.acme.Audit#onCharge")).isFalse();
        }

        @Test
        @DisplayName("policy relaxations are read and accumulate")
        void policiesAreRead() {
            final PolicyConfig policy = properties("""
                    aether.weaver.policy.allowSigned = true
                    aether.weaver.policy.allowPackage = com.a, com.b
                    """).resolve().policy();

            assertThat(policy.allowSigned()).isTrue();
            assertThat(policy.allowPackages()).containsExactlyInAnyOrder("com.a", "com.b");
        }
    }

    @Nested
    @DisplayName("a key that does nothing says so")
    class UnknownKeys {

        @Test
        @DisplayName("AW2310 — an unknown key is reported")
        void unknownKeysAreReported() {
            properties("aether.weaver.frobnicate = true");

            assertThat(codes()).containsExactly("AW2310");
            assertThat(reported.getFirst().details())
                    .anyMatch(detail -> detail.contains("did not change"));
        }

        @Test
        @DisplayName("a near miss comes with the key that was meant")
        void typosGetASuggestion() {
            properties("aether.weaver.verifcation = report");

            assertThat(reported.getFirst().remedy().orElseThrow())
                    .as("'unknown key' alone leaves the reader comparing two long strings by eye, "
                            + "which is how the typo got there in the first place")
                    .isEqualTo("did you mean 'aether.weaver.verification'?");
        }

        @Test
        @DisplayName("a key that resembles nothing gets no suggestion")
        void nonsenseGetsNoGuess() {
            properties("aether.weaver.frobnicate = true");

            assertThat(reported.getFirst().remedy())
                    .as("answering 'frobnicate' with 'did you mean enabled?' is worse than "
                            + "silence: it reads as though the framework understood the intent")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown setting under a known indexed family is reported too")
        void unknownIndexedSettingsAreReported() {
            properties("aether.weaver.weave[com.acme.Audit].colour = blue");

            assertThat(codes()).containsExactly("AW2310");
        }
    }

    @Nested
    @DisplayName("a value that cannot be read")
    class BadValues {

        @Test
        @DisplayName("a misspelt boolean is reported, and the setting stays unset")
        void badBooleansDoNotBecomeFalse() {
            final ConfigLayer layer = properties("aether.weaver.enabled = ture");

            assertThat(codes()).containsExactly("AW2310");
            assertThat(layer.enabled())
                    .as("Boolean.parseBoolean answers false for everything it does not "
                            + "recognise, so 'ture' would read as a deliberate 'off' and every "
                            + "weave would silently vanish")
                    .isNull();
        }

        @Test
        @DisplayName("a bad value leaves a lower layer still deciding")
        void badValuesDoNotOverrideALowerLayer() {
            final ConfigLayer lower = ConfigLayer.builder()
                    .verification(VerificationPolicy.OFF).build();

            assertThat(lower.merge(properties("aether.weaver.verification = strikt"))
                    .resolve().verification())
                    .as("falling back to the default would silently downgrade a deliberate "
                            + "setting from the layer below — the same class of error, one layer "
                            + "along")
                    .isEqualTo(VerificationPolicy.OFF);
        }

        @Test
        @DisplayName("a bad enum lists what it does take")
        void badEnumsListTheAlternatives() {
            properties("aether.weaver.verification = strikt");

            assertThat(reported.getFirst().details())
                    .anyMatch(detail -> detail.contains("strict"))
                    .anyMatch(detail -> detail.contains("report"))
                    .anyMatch(detail -> detail.contains("off"));
        }

        @Test
        @DisplayName("a non-numeric priority is reported")
        void badNumbersAreReported() {
            properties("aether.weaver.weave[com.acme.Audit].priority = high");

            assertThat(codes()).containsExactly("AW2310");
        }
    }

    @Nested
    @DisplayName("system properties")
    class SystemProperties {

        @Test
        @DisplayName("this framework's keys are read")
        void prefixedKeysAreRead() {
            final Properties system = new Properties();
            system.setProperty("aether.weaver.verification", "off");

            assertThat(ConfigParser.ofSystemProperties(system, reporter).resolve().verification())
                    .isEqualTo(VerificationPolicy.OFF);
        }

        @Test
        @DisplayName("everything else is ignored without a word")
        void foreignKeysAreNotReported() {
            final Properties system = new Properties();
            system.setProperty("java.home", "/opt/jdk");
            system.setProperty("user.name", "someone");

            ConfigParser.ofSystemProperties(system, reporter);

            assertThat(reported)
                    .as("the system properties hold everything the JVM and the application ever "
                            + "set; complaining about them would bury this framework's own "
                            + "diagnostics under hundreds of lines about java.home")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("agent arguments")
    class AgentArguments {

        @Test
        @DisplayName("the prefix is implied, and pairs are comma-separated")
        void argumentsUseTheSameKeys() {
            final WeaverConfig config = ConfigParser.ofAgentArguments(
                    "verification=report,dump=/tmp/woven,tags.exclude=experimental", reporter)
                    .resolve();

            assertThat(config.verification()).isEqualTo(VerificationPolicy.REPORT);
            assertThat(config.dumpDirectoryIfSet()).contains(Path.of("/tmp/woven"));
            assertThat(config.tags().excluded()).containsExactly("experimental");
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("the same key works in all three places")
        void theGrammarIsOne() {
            final Properties system = new Properties();
            system.setProperty("aether.weaver.verification", "report");

            assertThat(ConfigParser.ofAgentArguments("verification=report", reporter).resolve())
                    .as("every key working verbatim as -Daether.weaver.… is the reason the "
                            + "format is flat properties rather than something nested")
                    .isEqualTo(ConfigParser.ofSystemProperties(system, reporter).resolve());
        }

        @Test
        @DisplayName("no arguments at all is not a problem")
        void absenceIsFine() {
            assertThat(ConfigParser.ofAgentArguments(null, reporter).saysNothing()).isTrue();
            assertThat(ConfigParser.ofAgentArguments("", reporter).saysNothing()).isTrue();
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("an argument with no value is reported rather than guessed at")
        void valuelessArgumentsAreReported() {
            ConfigParser.ofAgentArguments("verification", reporter);

            assertThat(codes()).containsExactly("AW2310");
            assertThat(reported.getFirst().remedy().orElseThrow()).contains("key=value");
        }
    }

    // -------------------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private ConfigLayer properties(final String text) {
        final Properties properties = new Properties();
        try {
            properties.load(new java.io.StringReader(text));
        } catch (final java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        return ConfigParser.ofProperties(properties, this.reporter);
    }
}
