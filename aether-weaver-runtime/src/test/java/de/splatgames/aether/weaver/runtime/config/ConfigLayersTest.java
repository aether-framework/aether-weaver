package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.engine.explain.ExplainReport;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLayersTest {

    @Nested
    @DisplayName("who decided a setting")
    class Provenance {

        @Test
        @DisplayName("a setting nobody configured is attributed to the default")
        void nobodyConfiguredIt() {
            assertThat(sourceOf(ConfigLayers.of(), "verification")).isEqualTo("default");
        }

        @Test
        @DisplayName("a setting one layer configured is attributed to that layer")
        void oneLayerConfiguredIt() {
            final ConfigLayers layers = ConfigLayers.of()
                    .add("weaver.properties", ConfigLayer.builder()
                            .verification(VerificationPolicy.REPORT).build());

            assertThat(sourceOf(layers, "verification")).isEqualTo("weaver.properties");
            assertThat(sourceOf(layers, "onError"))
                    .as("a layer that said nothing about a setting must not be credited with it")
                    .isEqualTo("default");
        }

        @Test
        @DisplayName("the last layer that spoke is the one credited, matching the merge")
        void theWinnerIsCredited() {
            final ConfigLayers layers = ConfigLayers.of()
                    .add("weaver.properties", ConfigLayer.builder()
                            .verification(VerificationPolicy.STRICT).build())
                    .add("agent arguments", ConfigLayer.builder()
                            .verification(VerificationPolicy.REPORT).build());

            assertThat(layers.resolve().verification()).isEqualTo(VerificationPolicy.REPORT);
            assertThat(sourceOf(layers, "verification"))
                    .as("provenance reads the same rule the merge applies, from the other end; two "
                            + "mechanisms would eventually disagree and the report would be worse "
                            + "than none")
                    .isEqualTo("agent arguments");
        }

        @Test
        @DisplayName("a lower layer still wins where the higher one said nothing")
        void silenceDoesNotOverride() {
            final ConfigLayers layers = ConfigLayers.of()
                    .add("weaver.properties", ConfigLayer.builder()
                            .verification(VerificationPolicy.REPORT).build())
                    .add("agent arguments", ConfigLayer.builder().explain(true).build());

            assertThat(sourceOf(layers, "verification")).isEqualTo("weaver.properties");
            assertThat(sourceOf(layers, "explain")).isEqualTo("agent arguments");
        }
    }

    @Nested
    @DisplayName("the values reported alongside")
    class Values {

        @Test
        @DisplayName("every reported setting carries the value the run actually uses")
        void valuesAreResolved() {
            final ConfigLayers layers = ConfigLayers.of()
                    .add("agent arguments", ConfigLayer.builder()
                            .onError(ErrorPolicy.REPORT).explain(true).build());

            assertThat(valueOf(layers, "onError")).isEqualTo("report");
            assertThat(valueOf(layers, "explain")).isEqualTo("true");
            assertThat(valueOf(layers, "verification")).isEqualTo("strict");
        }

        @Test
        @DisplayName("the list is stable, so two runs' reports can be compared line by line")
        void orderIsStable() {
            assertThat(ConfigLayers.of().settings())
                    .extracting(ExplainReport.Setting::name)
                    .containsExactly("enabled", "verification", "onError", "phase", "tags",
                            "dump", "explain", "policy.allowSigned");
        }
    }

    @Nested
    @DisplayName("resolution matches a plain merge chain")
    class Resolution {

        @Test
        @DisplayName("the same layers in the same order produce the same configuration")
        void sameAsMerging() {
            final ConfigLayer lower = ConfigLayer.builder().onError(ErrorPolicy.REPORT).build();
            final ConfigLayer higher = ConfigLayer.builder().explain(true).build();

            assertThat(ConfigLayers.of().add("a", lower).add("b", higher).resolve())
                    .as("keeping the layers must not change what they resolve to, or the report "
                            + "would describe a configuration the run is not using")
                    .isEqualTo(ConfigLayer.EMPTY.merge(lower).merge(higher).resolve());
        }
    }

    @Nested
    @DisplayName("the explain key itself")
    class ExplainKey {

        @Test
        @DisplayName("aether.weaver.explain=true is read like every other scalar")
        void parsedFromProperties() {
            final java.util.Properties properties = new java.util.Properties();
            properties.setProperty("aether.weaver.explain", "true");

            assertThat(ConfigParser.ofProperties(properties, diagnostic -> {
            }).resolve().explain()).isTrue();
        }

        @Test
        @DisplayName("and it shows up in the summary, because a run must log what it is doing")
        void appearsInTheSummary() {
            assertThat(ConfigLayer.builder().explain(true).build().resolve().summary())
                    .contains("explain");
            assertThat(ConfigLayer.EMPTY.resolve().summary()).doesNotContain("explain");
        }
    }

    // -------------------------------------------------------------------------------------

    private static String sourceOf(final ConfigLayers layers, final String name) {
        return settingOf(layers, name).source();
    }

    private static String valueOf(final ConfigLayers layers, final String name) {
        return settingOf(layers, name).value();
    }

    private static ExplainReport.Setting settingOf(final ConfigLayers layers, final String name) {
        final Optional<ExplainReport.Setting> found = layers.settings().stream()
                .filter(setting -> setting.name().equals(name))
                .findFirst();
        assertThat(found).as("the report must know about '%s'", name).isPresent();
        return found.orElseThrow();
    }
}
