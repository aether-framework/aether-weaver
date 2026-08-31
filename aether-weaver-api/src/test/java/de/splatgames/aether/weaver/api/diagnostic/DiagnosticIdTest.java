package de.splatgames.aether.weaver.api.diagnostic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Pins the two implementations of {@link DiagnosticId} against each other.
 *
 * <p>{@link DiagnosticCode} is a closed catalogue the framework declares; {@link PluginDiagnosticId} is
 * a record a plugin builds at run time. Because {@link Diagnostic} accepts the interface, a report can
 * carry either, and the cases here fix the four properties that keeps workable: the two wire forms
 * cannot collide, a plugin identity is validated where it is created rather than where it is printed,
 * suppressibility is derived rather than declarable on either implementation, and {@link Diagnostic}
 * treats the two alike.
 *
 * <p>Nothing is woven and no build is run. Identities are constructed directly and diagnostics are
 * built from them, so what a case proves is a property of the identity types, not of any reporting
 * site.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class DiagnosticIdTest {

    /** A valid plugin identity, standing for one a plugin would declare: error severity, and a summary. */
    private static final PluginDiagnosticId ACME = new PluginDiagnosticId(
            "acme", "AX0001", Severity.ERROR, DiagnosticCode.Category.DECLARATION,
            "a wrapped operation was invoked twice in one handler");

    /**
     * Fixes the boundary between the framework's codes and a plugin's.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("the two code spaces cannot collide")
    class Disjoint {

        /**
         * Asserts that no built-in code contains a colon and that a plugin code renders as
         * {@code namespace:IDENTIFIER}.
         *
         * <p>The colon is the whole of the separation. A built-in code that acquired one would be
         * indistinguishable from a plugin's, and a reader who wants to know which half of the system
         * reported a condition would have to look it up to find out.
         */
        @Test
        @DisplayName("a built-in wire form never contains a colon, a plugin one always does")
        void wireFormsAreDisjoint() {
            for (final DiagnosticCode code : DiagnosticCode.values()) {
                assertThat(code.code())
                        .as("%s would become ambiguous if it carried a colon", code.name())
                        .doesNotContain(":");
            }
            assertThat(ACME.code()).isEqualTo("acme:AX0001");
        }

        /**
         * Asserts that a plugin's wire form resolves to empty in {@link DiagnosticCode#of(String)}.
         *
         * <p>The consequence of the colon rule, checked from the lookup side: a plugin code must not be
         * answered with an unrelated built-in condition, and it must not be answered at all.
         */
        @Test
        @DisplayName("looking a plugin code up in the built-in catalogue yields empty")
        void pluginCodeIsNotInTheCatalogue() {
            assertThat(DiagnosticCode.of(ACME.code()))
                    .as("a plugin code must not resolve to an unrelated built-in condition")
                    .isEmpty();
        }

        /**
         * Asserts that {@code aether} is refused as a plugin namespace.
         *
         * <p>The namespace is reserved so that a plugin cannot publish codes that read as the framework's own
         * to anyone triaging a build. The refusal is an {@link IllegalArgumentException} from the record's
         * compact constructor whose message names the reservation, which is what tells the plugin author that
         * the name is taken rather than malformed.
         */
        @Test
        @DisplayName("a plugin cannot claim the framework's namespace")
        void aetherNamespaceIsReserved() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PluginDiagnosticId("aether", "AX0001", Severity.ERROR,
                            DiagnosticCode.Category.PLUGIN, "…"))
                    .withMessageContaining("reserved");
        }

        /**
         * Asserts that every built-in code reports an empty namespace and is built in, and that a plugin
         * identity reports neither.
         *
         * <p>{@link DiagnosticCode} inherits both from the interface's defaults rather than overriding them,
         * so the case pins that it keeps inheriting: {@code isBuiltIn()} is derived from an empty namespace,
         * and a catalogue constant that started reporting one would classify itself as a plugin's.
         */
        @Test
        @DisplayName("built-in codes report an empty namespace and are built in")
        void builtInCodesAreBuiltIn() {
            for (final DiagnosticCode code : DiagnosticCode.values()) {
                assertThat(code.namespace()).isEmpty();
                assertThat(code.isBuiltIn()).isTrue();
            }
            assertThat(ACME.isBuiltIn()).isFalse();
            assertThat(ACME.namespace()).isEqualTo("acme");
        }
    }

    /**
     * Fixes what {@link PluginDiagnosticId}'s compact constructor refuses.
     *
     * <p>Validation at construction is what makes an identity safe to print later. A plugin builds these
     * once, typically in a static field, so a malformed one fails while the plugin is being loaded rather
     * than in the middle of reporting the condition it was meant to describe.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("malformed identities are rejected at construction")
    class Validation {

        /**
         * Asserts the namespace pattern by four rejections and one acceptance.
         *
         * <p>An upper-case letter, a leading digit and the empty string are refused, and so is a dot, which
         * rules out spelling a namespace as a package name. A hyphen is accepted, and the accepted case
         * asserts the resulting wire form rather than only that construction succeeded.
         */
        @Test
        @DisplayName("a namespace must be lowercase and start with a letter")
        void namespaceShape() {
            assertThatIllegalArgumentException().isThrownBy(() -> id("Acme", "AX0001"));
            assertThatIllegalArgumentException().isThrownBy(() -> id("9acme", "AX0001"));
            assertThatIllegalArgumentException().isThrownBy(() -> id("", "AX0001"));
            assertThatIllegalArgumentException().isThrownBy(() -> id("acme.corp", "AX0001"));
            assertThat(id("acme-corp", "AX0001").code()).isEqualTo("acme-corp:AX0001");
        }

        /**
         * Asserts the identifier pattern by three rejections and one acceptance.
         *
         * <p>Lower case, a leading digit and the empty string are refused. The accepted case is an identifier
         * with an underscore and no digits at all, which pins that the identifier half is not required to
         * imitate the framework's {@code AW} plus four digits: a plugin may name a condition in words.
         */
        @Test
        @DisplayName("an identifier must be uppercase and start with a letter")
        void idShape() {
            assertThatIllegalArgumentException().isThrownBy(() -> id("acme", "ax0001"));
            assertThatIllegalArgumentException().isThrownBy(() -> id("acme", "0001"));
            assertThatIllegalArgumentException().isThrownBy(() -> id("acme", ""));
            assertThat(id("acme", "WRAP_NOT_REENTRANT").code())
                    .isEqualTo("acme:WRAP_NOT_REENTRANT");
        }

        /**
         * Asserts that a summary of whitespace is refused.
         *
         * <p>The check is on blankness rather than emptiness, so a summary that looks present in source and
         * prints as nothing is caught with the empty one. The summary is what a report falls back to when no
         * message is set, so an identity without one can produce a diagnostic that says nothing.
         */
        @Test
        @DisplayName("a blank summary is rejected — it is shown in reference tables and tooltips")
        void summaryMustNotBeBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PluginDiagnosticId("acme", "AX0001", Severity.ERROR,
                            DiagnosticCode.Category.DECLARATION, "  "));
        }

        /**
         * Asserts that a {@code null} severity is refused with a {@link NullPointerException}.
         *
         * <p>The severity is the one component checked here. The compact constructor rejects a {@code null} in
         * any of the five, and the distinction the case draws is between a null component and a malformed
         * one: the first is a {@link NullPointerException} and the second an
         * {@link IllegalArgumentException}.
         */
        @Test
        @DisplayName("null components are rejected")
        void nullsAreRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new PluginDiagnosticId("acme", "AX0001", null,
                            DiagnosticCode.Category.DECLARATION, "…"));
        }

        /**
         * Builds an identity that varies only in its namespace and identifier.
         *
         * <p>The remaining components are fixed at values the constructor accepts, so a failure is
         * attributable to the two under test.
         *
         * @param namespace  the namespace to try
         * @param identifier the identifier to try
         * @return the identity, when both are accepted
         */
        private static PluginDiagnosticId id(final String namespace, final String identifier) {
            return new PluginDiagnosticId(namespace, identifier, Severity.ERROR,
                    DiagnosticCode.Category.DECLARATION, "…");
        }
    }

    /**
     * Fixes suppressibility as something a plugin inherits rather than declares.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("suppressibility is derived, so a plugin cannot opt out of it")
    class Suppressibility {

        /**
         * Asserts that a plugin identity of {@link Severity#ERROR} severity is not suppressible.
         *
         * <p>{@link DiagnosticId#isSuppressible()} is a default method deriving the answer from the severity,
         * and {@link PluginDiagnosticId} does not override it. A plugin therefore cannot declare a condition
         * that fails a build and can be switched off, which is the same rule the built-in catalogue follows.
         */
        @Test
        @DisplayName("an error-severity plugin code is not suppressible")
        void errorsAreNeverSuppressible() {
            assertThat(ACME.isSuppressible()).isFalse();
        }

        /**
         * Asserts that a plugin identity of {@link Severity#WARNING} severity is suppressible.
         *
         * <p>The other side of the derivation: everything that is not an error can be silenced, so a plugin
         * gets suppressible warnings without asking for them.
         */
        @Test
        @DisplayName("a warning-severity plugin code is suppressible")
        void warningsAre() {
            final PluginDiagnosticId warning = new PluginDiagnosticId(
                    "acme", "AX0002", Severity.WARNING,
                    DiagnosticCode.Category.DECLARATION, "…");
            assertThat(warning.isSuppressible()).isTrue();
        }
    }

    /**
     * Fixes that {@link Diagnostic} makes no distinction between the two kinds of identity.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("Diagnostic treats both implementations identically")
    class Reporting {

        /**
         * Asserts that a diagnostic built on a plugin identity keeps the identity, its severity and its
         * suppressibility, and renders with the plugin wire form first.
         *
         * <p>{@link Diagnostic#format()} is asserted with {@code startsWith} rather than an equality, so it
         * pins the leading token and the space after it; the message and the remedy that follow are not part
         * of the assertion. The severity is not rendered, which is why nothing in the formatted text names it.
         */
        @Test
        @DisplayName("a plugin code can be built, rendered and read back")
        void pluginCodeRoundTrips() {
            final Diagnostic d = Diagnostic.builder(ACME)
                    .message("acme.WrapHandler#around invokes operation.call() twice")
                    .remedy("call the operation exactly once")
                    .build();

            assertThat(d.code()).isSameAs(ACME);
            assertThat(d.severity()).isEqualTo(Severity.ERROR);
            assertThat(d.format()).startsWith("acme:AX0001 ");
            assertThat(d.isSuppressible()).isFalse();
        }

        /**
         * Asserts that a diagnostic built with no message reports the identity's summary.
         *
         * <p>The fallback is what makes a summary mandatory on both kinds of identity, and it is the reason a
         * reporting site may build a diagnostic from a code alone when the condition has nothing site-specific
         * to add.
         */
        @Test
        @DisplayName("an unset message falls back to the plugin code's summary")
        void messageFallsBackToSummary() {
            assertThat(Diagnostic.builder(ACME).build().message()).isEqualTo(ACME.summary());
        }

        /**
         * Asserts that two equal-valued plugin identities produce equal diagnostics with equal hash codes.
         *
         * <p>{@link Diagnostic} compares its identity with {@link Object#equals(Object)}. Reference equality
         * would be enough for the enum and wrong for the record, since a plugin loaded twice, or one that
         * builds its identity per call, yields distinct instances of equal value. Deduplicating or grouping
         * reports would then keep every copy.
         */
        @Test
        @DisplayName("equality compares codes by value, not by identity")
        void equalityIsByValue() {
            final PluginDiagnosticId same = new PluginDiagnosticId(
                    "acme", "AX0001", Severity.ERROR, DiagnosticCode.Category.DECLARATION,
                    "a wrapped operation was invoked twice in one handler");

            assertThat(Diagnostic.of(same, "boom"))
                    .as("widening the code type from an enum to an interface means == no longer "
                            + "works; two equal plugin ids must still produce equal diagnostics")
                    .isEqualTo(Diagnostic.of(ACME, "boom"))
                    .hasSameHashCodeAs(Diagnostic.of(ACME, "boom"));
        }

        /**
         * Asserts that a diagnostic built on a catalogue constant keeps the constant by identity, renders
         * {@code AW1043} first, and equals another diagnostic built the same way.
         *
         * <p>{@link Diagnostic} compares its code with {@link Object#equals(Object)}. {@code isSameAs} is
         * the stronger assertion available for an enum constant and pins that nothing copies or re-derives
         * the identity on the way through the builder.
         */
        @Test
        @DisplayName("built-in codes still behave exactly as before the widening")
        void builtInCodesUnchanged() {
            final Diagnostic d = Diagnostic.of(DiagnosticCode.NO_INJECTION_POINT_MATCHED, "boom");

            assertThat(d.code()).isSameAs(DiagnosticCode.NO_INJECTION_POINT_MATCHED);
            assertThat(d.format()).startsWith("AW1043 ");
            assertThat(d).isEqualTo(Diagnostic.of(DiagnosticCode.NO_INJECTION_POINT_MATCHED, "boom"));
        }
    }
}
