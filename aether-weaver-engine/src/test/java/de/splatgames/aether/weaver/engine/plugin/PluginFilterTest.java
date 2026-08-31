package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.spi.PluginId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class PluginFilterTest {

    @Test
    @DisplayName("nothing configured permits everything")
    void unconfiguredPermitsEverything() {
        assertThat(PluginFilter.from(null, null, null).test(id("acme"))).isTrue();
        assertThat(PluginFilter.from("", "", "").test(id("acme"))).isTrue();
    }

    @Test
    @DisplayName("enabled=false refuses every plugin")
    void disabledRefusesEverything() {
        final Predicate<PluginId> filter = PluginFilter.from("false", "acme", null);

        assertThat(filter.test(id("acme")))
                .as("switching discovery off wins over an allowlist; it is the blunter and more "
                        + "deliberate instrument")
                .isFalse();
    }

    @Test
    @DisplayName("only the exact text 'false' disables, so a typo does not silently switch it off")
    void typosDoNotDisable() {
        assertThat(PluginFilter.from("fasle", null, null).test(id("acme")))
                .as("Boolean.parseBoolean maps every misspelling to false and would turn a typo "
                        + "into a mystery")
                .isTrue();
        assertThat(PluginFilter.from("FALSE", null, null).test(id("acme"))).isFalse();
        assertThat(PluginFilter.from(" false ", null, null).test(id("acme"))).isFalse();
    }

    @Test
    @DisplayName("an allowlist is exhaustive, not a set of exceptions")
    void allowlistIsExhaustive() {
        final Predicate<PluginId> filter = PluginFilter.from(null, "acme, corp", null);

        assertThat(filter.test(id("acme"))).isTrue();
        assertThat(filter.test(id("corp"))).isTrue();
        assertThat(filter.test(id("other"))).isFalse();
    }

    @Test
    @DisplayName("a denylist refuses only what it names")
    void denylistRefusesWhatItNames() {
        final Predicate<PluginId> filter = PluginFilter.from(null, null, "acme");

        assertThat(filter.test(id("acme"))).isFalse();
        assertThat(filter.test(id("other"))).isTrue();
    }

    @Test
    @DisplayName("deny wins over allow")
    void denyWins() {
        assertThat(PluginFilter.from(null, "acme", "acme").test(id("acme")))
                .as("reading the configuration must answer 'what can load here' without having to "
                        + "reason about interaction")
                .isFalse();
    }

    @Test
    @DisplayName("an empty allowlist means unconfigured, not 'permit nothing'")
    void emptyAllowlistIsUnconfigured() {
        assertThat(PluginFilter.allowOnly().test(id("acme"))).isTrue();
        assertThat(PluginFilter.allowOnly("acme").test(id("other"))).isFalse();
        assertThat(PluginFilter.none().test(id("acme"))).isFalse();
    }

    private static PluginId id(final String namespace) {
        return new PluginId(namespace, namespace, "1.0");
    }
}
