package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLayeringTest {

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        @DisplayName("a higher layer wins where it speaks")
        void higherWins() {
            final ConfigLayer lower = ConfigLayer.builder()
                    .verification(VerificationPolicy.STRICT).build();
            final ConfigLayer higher = ConfigLayer.builder()
                    .verification(VerificationPolicy.REPORT).build();

            assertThat(lower.merge(higher).resolve().verification())
                    .isEqualTo(VerificationPolicy.REPORT);
        }

        @Test
        @DisplayName("silence in a higher layer inherits rather than resetting")
        void silenceInherits() {
            final ConfigLayer lower = ConfigLayer.builder()
                    .verification(VerificationPolicy.OFF)
                    .onError(ErrorPolicy.REPORT)
                    .build();
            final ConfigLayer higher = ConfigLayer.builder().phase(Phase.EARLY).build();

            final WeaverConfig resolved = lower.merge(higher).resolve();

            assertThat(resolved.verification())
                    .as("a layer that said nothing about verification has not said 'default' — "
                            + "and treating it as though it had would silently undo the layer "
                            + "below, which is the whole failure mode layering exists to avoid")
                    .isEqualTo(VerificationPolicy.OFF);
            assertThat(resolved.onError()).isEqualTo(ErrorPolicy.REPORT);
            assertThat(resolved.phase()).isEqualTo(Phase.EARLY);
        }

        @Test
        @DisplayName("the fold is associative, so the order of composition does not matter")
        void foldingIsAssociative() {
            final ConfigLayer a = ConfigLayer.builder().enabled(false).build();
            final ConfigLayer b = ConfigLayer.builder().phase(Phase.EARLY).build();
            final ConfigLayer c = ConfigLayer.builder().onError(ErrorPolicy.REPORT).build();

            assertThat(a.merge(b).merge(c).resolve())
                    .as("a fold whose result depended on how the caller grouped it would make "
                            + "the precedence rules unstatable")
                    .isEqualTo(a.merge(b.merge(c)).resolve());
        }

        @Test
        @DisplayName("EMPTY changes nothing, from either side")
        void emptyIsTheIdentity() {
            final ConfigLayer layer = ConfigLayer.builder()
                    .enabled(false)
                    .tags(TagFilter.include("audit"))
                    .weave("com.acme.Audit", new WeaveOverride(null, 7))
                    .build();

            assertThat(ConfigLayer.EMPTY.merge(layer)).isEqualTo(layer);
            assertThat(layer.merge(ConfigLayer.EMPTY)).isEqualTo(layer);
        }
    }

    @Nested
    @DisplayName("what accumulates instead of replacing")
    class Accumulation {

        @Test
        @DisplayName("a higher layer naming one weave leaves the others alone")
        void overridesAccumulate() {
            final ConfigLayer lower = ConfigLayer.builder()
                    .weave("com.acme.First", new WeaveOverride(false, null))
                    .weave("com.acme.Second", new WeaveOverride(null, 10))
                    .build();
            final ConfigLayer higher = ConfigLayer.builder()
                    .weave("com.acme.Second", new WeaveOverride(true, null))
                    .build();

            final WeaverConfig resolved = lower.merge(higher).resolve();

            assertThat(resolved.isEnabled("com.acme.First", Set.of()))
                    .as("a layer that named one weave has said nothing about the others, and "
                            + "wiping them would break a deployment for a reason nobody wrote down")
                    .isFalse();
            assertThat(resolved.isEnabled("com.acme.Second", Set.of())).isTrue();
            assertThat(resolved.priorityOf("com.acme.Second"))
                    .as("the higher layer set enabled, not priority — the lower one's priority "
                            + "must survive within the same weave's override")
                    .contains(10);
        }

        @Test
        @DisplayName("policy relaxations accumulate")
        void policiesAccumulate() {
            final ConfigLayer lower = ConfigLayer.builder()
                    .policy(new PolicyConfig(false, Set.of("com.a"))).build();
            final ConfigLayer higher = ConfigLayer.builder()
                    .policy(new PolicyConfig(true, Set.of("com.b"))).build();

            assertThat(lower.merge(higher).resolve().policy())
                    .isEqualTo(new PolicyConfig(true, Set.of("com.a", "com.b")));
        }

        @Test
        @DisplayName("a higher layer cannot revoke a relaxation, only add to it")
        void relaxationsCannotBeWithdrawnByMerging() {
            final ConfigLayer relaxed = ConfigLayer.builder()
                    .policy(new PolicyConfig(true, Set.of())).build();
            final ConfigLayer strict = ConfigLayer.builder()
                    .policy(PolicyConfig.STRICT).build();

            assertThat(relaxed.merge(strict).resolve().policy().allowSigned())
                    .as("PolicyConfig.STRICT is what a layer holds when it says nothing about "
                            + "policy at all, so treating it as 'revoke everything' would let any "
                            + "silent layer undo a deliberate exception")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("resolving")
    class Resolving {

        @Test
        @DisplayName("nothing configured produces the safe defaults")
        void defaultsAreSafe() {
            final WeaverConfig config = WeaverConfig.defaults();

            assertThat(config.enabled()).isTrue();
            assertThat(config.verification()).isEqualTo(VerificationPolicy.STRICT);
            assertThat(config.onError())
                    .as("a weaver that reports a problem and carries on ships an application "
                            + "that differs from the reviewed one, invisibly")
                    .isEqualTo(ErrorPolicy.FAIL);
            assertThat(config.phase()).isEqualTo(Phase.DEFAULT);
            assertThat(config.tags().isUnrestricted()).isTrue();
            assertThat(config.policy().isStrict()).isTrue();
            assertThat(config.dumpDirectoryIfSet()).isEmpty();
        }

        @Test
        @DisplayName("a dump directory survives the fold")
        void settingsSurvive() {
            assertThat(ConfigLayer.builder().dumpDirectory(Path.of("/tmp/woven")).build()
                    .resolve().dumpDirectoryIfSet())
                    .contains(Path.of("/tmp/woven"));
        }
    }

    @Nested
    @DisplayName("deciding whether a weave runs")
    class Selection {

        @Test
        @DisplayName("an explicit override beats the tag filter, in both directions")
        void overridesBeatTags() {
            final WeaverConfig config = ConfigLayer.builder()
                    .tags(TagFilter.include("audit"))
                    .weave("com.acme.Untagged", new WeaveOverride(true, null))
                    .weave("com.acme.Audit", new WeaveOverride(false, null))
                    .build().resolve();

            assertThat(config.isEnabled("com.acme.Untagged", Set.of()))
                    .as("an operator who named a weave has said something about THAT weave; a "
                            + "tag filter is a rule about many, and a rule must not overrule the "
                            + "exception written for it")
                    .isTrue();
            assertThat(config.isEnabled("com.acme.Audit", Set.of("audit"))).isFalse();
        }

        @Test
        @DisplayName("without an override the tag filter decides")
        void tagsDecideOtherwise() {
            final WeaverConfig config = ConfigLayer.builder()
                    .tags(TagFilter.include("audit")).build().resolve();

            assertThat(config.isEnabled("com.acme.A", Set.of("audit"))).isTrue();
            assertThat(config.isEnabled("com.acme.B", Set.of("metrics"))).isFalse();
            assertThat(config.isEnabled("com.acme.C", Set.of()))
                    .as("once an operator says 'include audit' they have said what they want, "
                            + "and an untagged weave is not it")
                    .isFalse();
        }

        @Test
        @DisplayName("an injection can be switched off without its weave")
        void injectionsAreAddressableOnTheirOwn() {
            final WeaverConfig config = ConfigLayer.builder()
                    .injector("com.acme.Audit#onCharge", new InjectorOverride(false))
                    .build().resolve();

            assertThat(config.isInjectionEnabled("com.acme.Audit#onCharge")).isFalse();
            assertThat(config.isInjectionEnabled("com.acme.Audit#onRefund"))
                    .as("turning off the weave would take the other injections with it, which is "
                            + "why this granularity exists at all")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("tag filtering")
    class Tags {

        @Test
        @DisplayName("exclusion beats inclusion")
        void exclusionWins() {
            assertThat(TagFilter.include("audit").excluding("experimental")
                    .accepts(Set.of("audit", "experimental")))
                    .as("an exclusion is written to stop something happening, usually in a hurry "
                            + "and usually because it is already going wrong")
                    .isFalse();
        }

        @Test
        @DisplayName("an untagged weave runs when nothing is included by name")
        void untaggedRunsByDefault() {
            assertThat(TagFilter.exclude("experimental").accepts(Set.of())).isTrue();
            assertThat(TagFilter.ALL.accepts(Set.of("anything"))).isTrue();
        }
    }

    @Nested
    @DisplayName("the startup summary")
    class Summary {

        @Test
        @DisplayName("it names the settings an operator would want to see")
        void theSummaryIsUseful() {
            assertThat(WeaverConfig.defaults().summary())
                    .contains("enabled")
                    .contains("verification=strict")
                    .contains("onError=fail");
        }

        @Test
        @DisplayName("a relaxed policy is never left out of it")
        void relaxationsAreAlwaysVisible() {
            final WeaverConfig config = ConfigLayer.builder()
                    .policy(new PolicyConfig(true, Set.of())).build().resolve();

            assertThat(config.summary())
                    .as("a relaxed safety rule that leaves no trace in the log is one nobody "
                            + "reviewing the deployment will know was used")
                    .contains("POLICY RELAXED");
        }

        @Test
        @DisplayName("disabling the weaver is shouted, not whispered")
        void beingOffIsObvious() {
            assertThat(ConfigLayer.builder().enabled(false).build().resolve().summary())
                    .contains("DISABLED");
        }
    }
}
