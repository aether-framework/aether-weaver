package de.splatgames.aether.weaver.api.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginIdTest {

    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("a namespaced plugin renders all three components")
        void namespacedRendersEverything() {
            assertThat(new PluginId("acme", "Acme Tracing", "1.2.0").describe())
                    .isEqualTo("Acme Tracing (acme 1.2.0)");
        }

        @Test
        @DisplayName("the built-in namespace is left out rather than printed as a space")
        void builtInLeavesTheNamespaceOut() {
            // The built-in plugin holds the empty namespace, so interpolating it put a stray
            // space in front of the version in the agent's explain footer: "Aether Weaver
            // ( 0.1.0)". The reader sees this line on every run with explain on.
            final PluginId builtIn =
                    new PluginId(PluginId.BUILT_IN_NAMESPACE, "Aether Weaver", "0.1.0");

            assertThat(builtIn.describe())
                    .isEqualTo("Aether Weaver (0.1.0)")
                    .doesNotContain("( ");
        }
    }
}
