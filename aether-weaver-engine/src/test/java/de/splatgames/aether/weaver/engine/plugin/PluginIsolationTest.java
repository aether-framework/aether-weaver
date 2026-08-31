package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PluginIsolationTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("each phase reports its own code")
    class Phases {

        @Test
        @DisplayName("instantiation, contribute, planning and apply are errors")
        void fatalPhasesReportErrors() {
            for (final PluginIsolation.Phase phase : PluginIsolation.Phase.values()) {
                if (!phase.isFatal()) {
                    continue;
                }
                reported.clear();
                final boolean ok = PluginIsolation.run("acme 1.0", phase, listener, () -> {
                    throw new IllegalStateException("boom");
                });

                assertThat(ok).as("%s must not report success", phase).isFalse();
                assertThat(reported).singleElement().satisfies(d -> {
                    assertThat(d.code()).isEqualTo(phase.code());
                    assertThat(d.severity()).isEqualTo(Severity.ERROR);
                });
            }
        }

        @Test
        @DisplayName("an observer failure is a WARNING and nothing more")
        void observerFailureIsAWarning() {
            final boolean ok = PluginIsolation.run("acme 1.0", PluginIsolation.Phase.OBSERVE,
                    listener, () -> {
                        throw new IllegalStateException("boom");
                    });

            assertThat(ok).isFalse();
            assertThat(reported).singleElement().satisfies(d -> {
                assertThat(d.code()).isEqualTo(DiagnosticCode.PLUGIN_OBSERVER_FAILED);
                assertThat(d.severity())
                        .as("an observer cannot change the woven bytes, so an observer that failed "
                                + "has not changed the program; failing a build for it would be a "
                                + "defect")
                        .isEqualTo(Severity.WARNING);
            });
            assertThat(PluginIsolation.Phase.OBSERVE.isFatal()).isFalse();
        }

        @Test
        @DisplayName("every phase maps to a distinct code in the PLUGIN category")
        void phasesAreDistinctAndCategorised() {
            final List<DiagnosticCode> codes = List.of(PluginIsolation.Phase.values()).stream()
                    .map(PluginIsolation.Phase::code)
                    .toList();

            assertThat(codes).doesNotHaveDuplicates();
            assertThat(codes).allSatisfy(code ->
                    assertThat(code.category()).isEqualTo(DiagnosticCode.Category.PLUGIN));
        }
    }

    @Nested
    @DisplayName("the message names the plugin and what it was doing")
    class Messages {

        @Test
        @DisplayName("the plugin is named, not the framework")
        void theMessageNamesThePlugin() {
            PluginIsolation.run("Acme Weaving Extensions (acme 1.4.0)",
                    PluginIsolation.Phase.CONTRIBUTE, listener, () -> {
                        throw new IllegalStateException("nope");
                    });

            final Diagnostic d = reported.getFirst();
            assertThat(d.message())
                    .contains("Acme Weaving Extensions (acme 1.4.0)")
                    .contains("registering its contributions");
            assertThat(d.details())
                    .as("the exception type and message are what the plugin's author needs")
                    .anySatisfy(detail -> assertThat(detail)
                            .contains("IllegalStateException")
                            .contains("nope"));
        }

        @Test
        @DisplayName("the first stack frame is included, so the author has a place to look")
        void theFirstFrameIsIncluded() {
            PluginIsolation.run("acme 1.0", PluginIsolation.Phase.PLANNING, listener, () -> {
                throw new IllegalStateException("boom");
            });

            assertThat(reported.getFirst().details())
                    .anySatisfy(detail -> assertThat(detail).startsWith("at "));
        }

        @Test
        @DisplayName("a LinkageError is explained as a version mismatch")
        void linkageErrorGetsTheVersionRemedy() {
            PluginIsolation.run("acme 1.0", PluginIsolation.Phase.APPLY, listener, () -> {
                throw new NoSuchMethodError("de.splatgames.SomeType.gone()V");
            });

            assertThat(reported.getFirst().remedy())
                    .as("a bare NoSuchMethodError from a plugin is almost always an SPI generation "
                            + "mismatch that slipped past the gate; saying so beats leaving it to "
                            + "be interpreted")
                    .hasValueSatisfying(remedy -> assertThat(remedy)
                            .contains("apiLevel()")
                            .contains("aether-weaver-api"));
        }

        @Test
        @DisplayName("a message-less throwable still produces a usable detail")
        void nullMessageIsHandled() {
            PluginIsolation.run("acme 1.0", PluginIsolation.Phase.CONTRIBUTE, listener,
                    () -> {
                        throw new NullPointerException();
                    });

            assertThat(reported.getFirst().details())
                    .anySatisfy(detail -> assertThat(detail)
                            .isEqualTo("java.lang.NullPointerException"));
        }
    }

    @Nested
    @DisplayName("what is deliberately not contained")
    class NotContained {

        @Test
        @DisplayName("a VirtualMachineError is re-thrown, never reported")
        void virtualMachineErrorEscapes() {
            assertThatExceptionOfType(OutOfMemoryError.class)
                    .as("the JVM itself is compromised; reporting 'a plugin threw' would be a "
                            + "false account, and continuing would bury the cause under its "
                            + "consequences")
                    .isThrownBy(() -> PluginIsolation.run("acme 1.0",
                            PluginIsolation.Phase.APPLY, listener, () -> {
                                throw new OutOfMemoryError("heap");
                            }));

            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("a checked exception is contained like any other")
        void checkedExceptionsAreContained() {
            final boolean ok = PluginIsolation.run("acme 1.0", PluginIsolation.Phase.CONTRIBUTE,
                    listener, () -> {
                        throw new java.io.IOException("disk");
                    });

            assertThat(ok).isFalse();
            assertThat(reported).hasSize(1);
        }
    }

    @Nested
    @DisplayName("call returns a value when the plugin behaves")
    class Call {

        @Test
        @DisplayName("a successful call yields its result")
        void successYieldsTheResult() {
            final Optional<String> result = PluginIsolation.call("acme 1.0",
                    PluginIsolation.Phase.PLANNING, listener, () -> "value");

            assertThat(result).contains("value");
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("a null result is empty and is not an error")
        void nullResultIsEmpty() {
            final Optional<String> result = PluginIsolation.call("acme 1.0",
                    PluginIsolation.Phase.PLANNING, listener, () -> null);

            assertThat(result).isEmpty();
            assertThat(reported)
                    .as("returning null is the plugin declining, not the plugin failing")
                    .isEmpty();
        }

        @Test
        @DisplayName("a throwing call is empty and reported")
        void throwingCallIsEmpty() {
            final Optional<String> result = PluginIsolation.call("acme 1.0",
                    PluginIsolation.Phase.PLANNING, listener, () -> {
                        throw new IllegalArgumentException("bad");
                    });

            assertThat(result).isEmpty();
            assertThat(reported).hasSize(1);
        }
    }
}
