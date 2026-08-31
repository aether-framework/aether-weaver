package de.splatgames.aether.weaver.api.select;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins {@link MemberSelector#render(MemberSelector.Form)} as an operation that always produces text the
 * grammar reads.
 *
 * <p>A rendered selector is not decoration. It goes into diagnostics, into a plan, and back into a
 * declaration when a user copies it out of build output, so a rendering that cannot be parsed again
 * breaks the loop at a point where nothing downstream can tell that it did.
 *
 * <p>The asymmetry between the two forms is the subject. {@link MemberSelector.Form#SOURCE} answers for
 * every selector. {@link MemberSelector.Form#DESCRIPTOR} answers with a descriptor only when the
 * selector is exact enough to have one and otherwise falls back to the source rendering, which is what
 * makes a fallback something a caller has to be able to recognise rather than trust.
 *
 * <p>Each case parses its input, renders it, and reads the result back. Nothing is resolved against a
 * class and no target is present, so what a case proves is a property of the text.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class SelectorRenderingTest {

    /**
     * Fixes that a rendering is readable, stable, and -- where the form is exact -- the same member.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("every rendering can be read back")
    class RoundTrip {

        /**
         * Asserts that both renderings of each of seventeen selectors parse.
         *
         * <p>The inputs span the source form with and without an owner, a return type and a wildcard, the
         * descriptor form for methods and for a field, and a field written in source form with a primitive
         * and with a class type. Every one is rendered in both forms, so the case covers thirty-four
         * renderings.
         *
         * <p>What is asserted is that the result parses, not that it names the same member. The equality is
         * asserted separately, for the inputs where the form guarantees it.
         *
         * @param selector the selector to parse and render
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "charge",
                "charge(BigDecimal)",
                "charge(java.math.BigDecimal)",
                "charge(java.math.BigDecimal):void",
                "com.acme.Gateway.charge(java.math.BigDecimal)",
                "com.acme.Gateway.charge(java.math.BigDecimal):java.lang.String",
                "charge(int)",
                "charge(int[]):long",
                "charge(*)",
                "count(java.lang.String[], int):long",
                "desc:settle()V",
                "desc:charge(Ljava/math/BigDecimal;)V",
                "desc:count([Ljava/lang/String;I)J",
                "desc:com/acme/Gateway.charge(Ljava/math/BigDecimal;)Ljava/lang/String;",
                "amount:int",
                "amount:java.math.BigDecimal",
                "desc:com/acme/Ledger.amount:Ljava/math/BigDecimal;",
        })
        void everyRenderingParsesAgain(final String selector) {
            final MemberSelector parsed = MemberSelector.parse(selector);

            for (final MemberSelector.Form form : MemberSelector.Form.values()) {
                final String rendered = parsed.render(form);
                assertThatCode(() -> MemberSelector.parse(rendered))
                        .withFailMessage(
                                "render(%s) of \"%s\" produced \"%s\", which this library rejects. "
                                        + "A rendering that cannot be read back is worse than one in "
                                        + "the other form, because nothing downstream can tell",
                                form, selector, rendered)
                        .doesNotThrowAnyException();
            }
        }

        /**
         * Asserts that rendering, re-parsing and rendering again reproduces the first text, for seven
         * selectors in both forms.
         *
         * <p>Idempotence is what lets rendered text be compared, cached or used as a map key. Without it a
         * selector could drift a little on each pass -- a name qualified once and abbreviated the next time
         * -- and two records of the same member would stop matching each other for no reason a reader could
         * see.
         *
         * @param selector the selector to parse and render
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "charge(java.math.BigDecimal):void",
                "com.acme.Gateway.charge(java.math.BigDecimal):java.lang.String",
                "charge(int[]):long",
                "charge(*)",
                "desc:count([Ljava/lang/String;I)J",
                "desc:com/acme/Gateway.charge(Ljava/math/BigDecimal;)Ljava/lang/String;",
                "desc:com/acme/Ledger.amount:Ljava/math/BigDecimal;",
        })
        void everyRenderingIsAFixedPoint(final String selector) {
            final MemberSelector parsed = MemberSelector.parse(selector);

            for (final MemberSelector.Form form : MemberSelector.Form.values()) {
                final String once = parsed.render(form);
                assertThat(MemberSelector.parse(once).render(form))
                        .withFailMessage("render(%s) of \"%s\" is not stable: \"%s\" renders again "
                                + "as \"%s\"", form, selector, once,
                                MemberSelector.parse(once).render(form))
                        .isEqualTo(once);
            }
        }

        /**
         * Asserts that the descriptor rendering of four descriptor-form selectors re-parses to an equal
         * selector.
         *
         * <p>These are the inputs whose types are all resolved, so the descriptor form is genuinely available
         * and the round trip is an equality rather than only a successful parse. Two of the four carry an
         * owner and two do not, which pins that an ownerless descriptor still renders as one.
         *
         * @param selector the selector to parse and render
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "desc:settle()V",
                "desc:count([Ljava/lang/String;I)J",
                "desc:com/acme/Gateway.charge(Ljava/math/BigDecimal;)Ljava/lang/String;",
                "desc:com/acme/Ledger.amount:Ljava/math/BigDecimal;",
        })
        void aDescriptorRenderingNamesTheSameMember(final String selector) {
            final MemberSelector parsed = MemberSelector.parse(selector);

            assertThat(MemberSelector.parse(parsed.render(MemberSelector.Form.DESCRIPTOR)))
                    .isEqualTo(parsed);
        }
    }

    /**
     * Fixes when the descriptor form is produced and when a fallback is returned instead.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("the descriptor form is offered only when there is one")
    class DescriptorAvailability {

        /**
         * Asserts that a selector parsed from the descriptor form renders back to exactly the text it was
         * written as.
         *
         * <p>An equality on the text, not on the selector, so it pins the spelling: the internal name stays
         * an internal name, the descriptor stays a descriptor, and the {@code desc:} prefix is kept. Three
         * of the five carry no owner, which shows that the descriptor rendering depends on the types being
         * resolved rather than on the selector naming exactly one member.
         *
         * @param selector the selector to parse and render
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "desc:settle()V",
                "desc:charge(Ljava/math/BigDecimal;)V",
                "desc:count([Ljava/lang/String;I)J",
                "desc:com/acme/Gateway.charge(Ljava/math/BigDecimal;)Ljava/lang/String;",
                "desc:com/acme/Ledger.amount:Ljava/math/BigDecimal;",
        })
        void aResolvedSelectorKeepsItsDescriptor(final String selector) {
            assertThat(MemberSelector.parse(selector).render(MemberSelector.Form.DESCRIPTOR))
                    .isEqualTo(selector);
        }

        /**
         * Asserts that five selectors with something left unresolved render, when the descriptor form is
         * asked for, as the source text they were written as.
         *
         * <p>The rows carry, in order, a fully written but unresolved parameter type, an unqualified one,
         * no parameter list at all, a wildcard parameter, and an unresolved field type; the first two lack a
         * return type as well, which a method needs for the descriptor form. The expected value is the input
         * in every row, which is the stronger statement -- the fallback is the source rendering, and the
         * source rendering of these is what the user wrote.
         *
         * <p>Refusing to render at all would leave a caller with no text for a selector that is perfectly
         * valid, since asking for the descriptor form is what a caller does when it wants the most exact
         * spelling available.
         *
         * @param selector the selector to parse and render
         * @param expected the text the descriptor rendering should produce
         */
        @ParameterizedTest
        @CsvSource({
                "charge(java.math.BigDecimal),        charge(java.math.BigDecimal)",
                "charge(BigDecimal),                  charge(BigDecimal)",
                "charge,                              charge",
                "charge(*),                           charge(*)",
                "amount:java.math.BigDecimal,         amount:java.math.BigDecimal",
        })
        void anUnresolvedSelectorFallsBackToSource(final String selector, final String expected) {
            assertThat(MemberSelector.parse(selector).render(MemberSelector.Form.DESCRIPTOR))
                    .isEqualTo(expected);
        }

        /**
         * Asserts that the fallback for six unresolved selectors does not begin with {@code desc:}.
         *
         * <p>The prefix is what a reader uses to tell an exact spelling from an approximate one, so a
         * fallback carrying it would claim an exactness the selector does not have.
         *
         * <p>The property holds for these six and is not universal. A field selector with no owner, named
         * {@code desc} and carrying a type, renders, in either form, as text beginning with the prefix,
         * because nothing inspects the rendered name for one. No such selector is among the inputs; a
         * method named {@code desc}, or a field selector named {@code desc} with no type, does not trigger
         * the caveat.
         *
         * @param selector the selector to parse and render
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "charge(java.math.BigDecimal)",
                "charge(BigDecimal)",
                "charge",
                "charge(*)",
                "com.acme.Gateway.charge(java.math.BigDecimal)",
                "amount:java.math.BigDecimal",
        })
        void aFallbackNeverClaimsTheDescriptorForm(final String selector) {
            assertThat(MemberSelector.parse(selector).render(MemberSelector.Form.DESCRIPTOR))
                    .doesNotStartWith("desc:");
        }
    }

    /**
     * Fixes that the source form is the rendering of last resort and always answers.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("the source form is always available")
    class SourceAlwaysWorks {

        /**
         * Asserts that five selectors, two of them written in the descriptor form, have a non-blank source
         * rendering that does not begin with {@code desc:}.
         *
         * <p>The direction that is easy to lose is the descriptor one: turning an internal name and a
         * descriptor back into source spelling is work, and a rendering that gave up would hand a user a
         * blank string or the descriptor unchanged. The same limit as above applies -- an owner-less field
         * selector named {@code desc} that carries a type is not among the inputs.
         *
         * @param selector the selector to parse and render
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "charge",
                "charge(*)",
                "charge(java.math.BigDecimal)",
                "desc:count([Ljava/lang/String;I)J",
                "desc:com/acme/Ledger.amount:Ljava/math/BigDecimal;",
        })
        void everySelectorHasASourceRendering(final String selector) {
            assertThat(MemberSelector.parse(selector).render(MemberSelector.Form.SOURCE))
                    .isNotBlank()
                    .doesNotStartWith("desc:");
        }
    }

    /**
     * Fixes that {@link MemberSelector#canonical()} stays absent wherever a selector is not exact.
     *
     * <p>Neither case here carries a {@code @DisplayName}, so both appear in a report under their method
     * names.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("canonical stays the strict answer")
    class Canonical {

        /**
         * Asserts that a source-form selector has no canonical form even when every type in it is written
         * out in full.
         *
         * <p>A source-form owner is a name to be resolved rather than a resolved type, and a fully spelled
         * name is still a name. The distinction is the reason a canonical form can be used as an identity:
         * it is present only where nothing is left to interpretation, so a fingerprint built from it cannot
         * quietly depend on a classpath.
         *
         * <p>The selector still has a descriptor rendering, which is the fallback text; that rendering is not
         * the canonical form.
         */
        @Test
        void canonicalIsEmptyForAnUnresolvedSelector() {
            assertThat(MemberSelector.parse("charge(java.math.BigDecimal)").canonical()).isEmpty();
        }

        /**
         * Asserts that a fully qualified descriptor selector's canonical form is its own text.
         *
         * <p>The counterpart: with an owner and resolved types there is exactly one spelling, and the
         * canonical form is it. Asserting the literal text rather than only its presence pins that the
         * canonical form is the descriptor form and not some third spelling.
         */
        @Test
        void canonicalIsTheDescriptorForAResolvedSelector() {
            final String selector =
                    "desc:com/acme/Gateway.charge(Ljava/math/BigDecimal;)Ljava/lang/String;";

            assertThat(MemberSelector.parse(selector).canonical()).contains(selector);
        }
    }
}
