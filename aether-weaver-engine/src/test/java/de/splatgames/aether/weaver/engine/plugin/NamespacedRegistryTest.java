package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.spi.Alias;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NamespacedRegistryTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("a built-in and a namespaced identifier coexist")
        void bothKindsRegister() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("", "HEAD", "head")
                    .register("acme", "acme:AFTER_LOGGING", "acme")
                    .build(listener);

            assertThat(registry.lookup("HEAD", listener)).contains("head");
            assertThat(registry.lookup("acme:AFTER_LOGGING", listener)).contains("acme");
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("an identifier outside its own namespace is AW3110")
        void contributionOutsideNamespace() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "other:THING", "x")
                    .build(listener);

            assertThat(registry.size()).isZero();
            assertThat(codes()).containsExactly("AW3110");
            assertThat(reported.getFirst().remedy())
                    .as("the message must say what to write instead, not only what is wrong")
                    .hasValueSatisfying(remedy -> assertThat(remedy).contains("acme:THING"));
        }

        @Test
        @DisplayName("a built-in identifier may not be namespaced")
        void builtInMustNotCarryANamespace() {
            NamespacedRegistry.<String>builder("point")
                    .register("", "acme:HEAD", "x")
                    .build(listener);

            assertThat(codes()).containsExactly("AW3110");
        }

        @Test
        @DisplayName("claiming the reserved namespace is AW3101")
        void reservedNamespace() {
            NamespacedRegistry.<String>builder("point")
                    .register("aether", "aether:HEAD", "x")
                    .build(listener);

            assertThat(codes()).containsExactly("AW3101");
        }

        @Test
        @DisplayName("a malformed namespace is AW3100")
        void malformedNamespace() {
            NamespacedRegistry.<String>builder("point")
                    .register("Acme", "Acme:X", "x")
                    .build(listener);

            assertThat(codes()).containsExactly("AW3100");
        }

        @Test
        @DisplayName("two contributors claiming one identifier is AW3111, naming both")
        void collisionNamesBothOwners() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:X", "first")
                    .register("acme", "acme:X", "second")
                    .build(listener);

            assertThat(codes()).containsExactly("AW3111");
            assertThat(registry.lookup("acme:X", DiagnosticListener.NOOP))
                    .as("the first registration stands; the second is refused")
                    .contains("first");
            assertThat(reported.getFirst().details())
                    .as("a collision is undiagnosable unless the message names both owners")
                    .anySatisfy(detail -> assertThat(detail).contains("plugin 'acme'"));
        }

        @Test
        @DisplayName("registration problems do not stop the registry from being built")
        void oneBrokenRegistrationDoesNotLoseTheOthers() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "wrong:A", "a")
                    .register("acme", "acme:B", "b")
                    .register("acme", "wrong:C", "c")
                    .build(listener);

            assertThat(codes())
                    .as("three problems should be reported once each, not one per rebuild")
                    .containsExactly("AW3110", "AW3110");
            assertThat(registry.ids()).containsExactly("acme:B");
        }
    }

    @Nested
    @DisplayName("aliases")
    class Aliases {

        @Test
        @DisplayName("a deprecated spelling resolves and reports AW3120 naming the replacement")
        void aliasResolvesAndWarns() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:AFTER_LOGGING", "point")
                    .alias("acme", new Alias("acme:AFTER_LOG", "acme:AFTER_LOGGING", "0.2.0"))
                    .build(listener);

            assertThat(reported).as("building must not warn; only using the alias does").isEmpty();

            assertThat(registry.lookup("acme:AFTER_LOG", listener)).contains("point");

            assertThat(codes()).containsExactly("AW3120");
            final Diagnostic warning = reported.getFirst();
            assertThat(warning.severity())
                    .as("an alias that errored would defeat its own purpose")
                    .isEqualTo(Severity.WARNING);
            assertThat(warning.message())
                    .contains("acme:AFTER_LOG")
                    .contains("acme:AFTER_LOGGING")
                    .contains("0.2.0");
        }

        @Test
        @DisplayName("retiring a spelling cannot reach into the reserved namespace")
        void anAliasCannotClaimTheReservedNamespace() {
            // Only the alias call, so the code can come from nowhere else. Registering under
            // this namespace was already AW3101; retiring a spelling was the way round it,
            // because the alias path applied no namespace rule at all.
            NamespacedRegistry.<String>builder("point")
                    .alias("aether", new Alias("aether:OLD", "aether:NEW", "0.2.0"))
                    .build(listener);

            assertThat(codes())
                    .as("the reservation has to cover every way of claiming a name, not only "
                            + "the one that registers")
                    .containsExactly("AW3101");
        }

        @Test
        @DisplayName("retiring a spelling cannot reach into another contributor's namespace")
        void anAliasStaysInItsOwnNamespace() {
            NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:NEW", "point")
                    .alias("acme", new Alias("other:OLD", "acme:NEW", "0.2.0"))
                    .build(listener);

            assertThat(codes()).containsExactly("AW3110");
        }

        @Test
        @DisplayName("both spellings resolve to the very same registration")
        void bothSpellingsAreIndistinguishable() {
            final Object value = new Object();
            final NamespacedRegistry<Object> registry = NamespacedRegistry.builder("point")
                    .register("acme", "acme:NEW", value)
                    .alias("acme", new Alias("acme:OLD", "acme:NEW", "0.2.0"))
                    .build(listener);

            final Object viaNew = registry.lookup("acme:NEW", DiagnosticListener.NOOP).orElseThrow();
            final Object viaOld = registry.lookup("acme:OLD", DiagnosticListener.NOOP).orElseThrow();

            assertThat(viaOld)
                    .as("an alias is a rename, not a variant — if the two could differ, the plan "
                            + "and therefore the fingerprint would depend on which spelling the "
                            + "user happened to write")
                    .isSameAs(viaNew)
                    .isSameAs(value);
        }

        @Test
        @DisplayName("an alias pointing at nothing is AW3121, with the available identifiers")
        void danglingAliasIsRejected() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:REAL", "x")
                    .alias("acme", new Alias("acme:OLD", "acme:GONE", "0.2.0"))
                    .build(listener);

            assertThat(codes()).containsExactly("AW3121");
            assertThat(reported.getFirst().details())
                    .anySatisfy(detail -> assertThat(detail).contains("acme:REAL"));
            assertThat(registry.lookup("acme:OLD", DiagnosticListener.NOOP))
                    .as("a dangling alias must not resolve to anything")
                    .isEmpty();
        }

        @Test
        @DisplayName("an identifier cannot be current and retired at once")
        void aliasCannotShadowALiveIdentifier() {
            NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:X", "x")
                    .register("acme", "acme:Y", "y")
                    .alias("acme", new Alias("acme:X", "acme:Y", "0.2.0"))
                    .build(listener);

            assertThat(codes()).containsExactly("AW3111");
        }

        @Test
        @DisplayName("an alias pointing at itself is refused at construction")
        void selfAliasIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Alias("acme:X", "acme:X", "0.2.0"))
                    .withMessageContaining("point at itself");
        }

        @Test
        @DisplayName("probing with contains does not warn about a deprecation nobody wrote")
        void containsDoesNotWarn() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:NEW", "x")
                    .alias("acme", new Alias("acme:OLD", "acme:NEW", "0.2.0"))
                    .build(listener);

            assertThat(registry.contains("acme:OLD")).isTrue();
            assertThat(reported)
                    .as("suggesting alternatives must not warn about identifiers the user never "
                            + "typed")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("ids and aliases are sorted, never in registration order")
        void orderIsSorted() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:Z", "z")
                    .register("acme", "acme:A", "a")
                    .register("acme", "acme:M", "m")
                    .alias("acme", new Alias("acme:OLD_Z", "acme:Z", "0.2.0"))
                    .alias("acme", new Alias("acme:OLD_A", "acme:A", "0.2.0"))
                    .build(listener);

            assertThat(registry.ids())
                    .as("the fingerprint covers the contributed identifiers; a registration-order "
                            + "listing would make two identical builds disagree")
                    .containsExactly("acme:A", "acme:M", "acme:Z");
            assertThat(registry.aliases())
                    .extracting(Alias::deprecated)
                    .containsExactly("acme:OLD_A", "acme:OLD_Z");
        }

        @Test
        @DisplayName("aliases are excluded from the identifier listing")
        void aliasesAreNotIdentifiers() {
            final NamespacedRegistry<String> registry = NamespacedRegistry.<String>builder("point")
                    .register("acme", "acme:NEW", "x")
                    .alias("acme", new Alias("acme:OLD", "acme:NEW", "0.2.0"))
                    .build(listener);

            assertThat(registry.ids())
                    .as("a 'did you mean' listing must offer the current spelling, never the "
                            + "one that is being retired")
                    .containsExactly("acme:NEW");
        }
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    @Test
    @DisplayName("the codes this test names still exist in the catalogue")
    void codesExist() {
        assertThat(DiagnosticCode.of("AW3110")).contains(
                DiagnosticCode.PLUGIN_CONTRIBUTION_OUTSIDE_NAMESPACE);
        assertThat(DiagnosticCode.of("AW3120")).contains(DiagnosticCode.DEPRECATED_ALIAS_USED);
        assertThat(DiagnosticCode.of("AW3121")).contains(DiagnosticCode.ALIAS_TARGET_UNKNOWN);
    }
}
