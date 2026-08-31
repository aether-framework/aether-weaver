package de.splatgames.aether.weaver.api.diagnostic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the properties of {@link DiagnosticCode} that a user relies on without knowing it.
 *
 * <p>A code is the one part of a diagnostic that survives being copied out of a build log into a search
 * box, so its wire form, its uniqueness and its resolvability are contract rather than presentation.
 * Every case here but one iterates {@link DiagnosticCode#values()}, so each of those covers the whole
 * catalogue and a constant added later is held to them without an edit; the exception,
 * {@link #unknownCodeResolvesToEmpty()}, asserts against four literal strings instead.
 *
 * <p>Nothing here reports a diagnostic or reads one from a build. The catalogue is examined as data.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class DiagnosticCodeTest {

    /**
     * Asserts that every {@link DiagnosticCode#code()} matches {@code AW\d{4}} and that no two constants
     * share one.
     *
     * <p>The four-digit shape is what keeps the catalogue disjoint from {@link PluginDiagnosticId}, whose
     * wire form always carries a colon, and it is what makes a code recognisable in output that has been
     * pasted somewhere with no context left.
     *
     * <p>The duplicate half rarely gets to run: {@link DiagnosticCode} builds its lookup map in a static
     * initialiser that throws on a repeated code, so a duplicate surfaces as an
     * {@link ExceptionInInitializerError} while the enum is being loaded, before any case in this class
     * touches it.
     *
     * <p>The closing assertion is the guard against a vacuous pass: an empty catalogue would satisfy a
     * loop that never runs.
     */
    @Test
    @DisplayName("codes are unique and well-formed")
    void codesAreUniqueAndWellFormed() {
        final Set<String> seen = new TreeSet<>();
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            assertThat(code.code())
                    .as("%s must be AW followed by four digits", code.name())
                    .matches("AW\\d{4}");
            assertThat(seen.add(code.code()))
                    .as("duplicate code %s", code.code())
                    .isTrue();
        }
        assertThat(seen).as("the catalogue must not be empty").isNotEmpty();
    }

    /**
     * Asserts that every constant carries a non-blank {@link DiagnosticCode#summary()}.
     *
     * <p>The summary is what a report falls back to when a reporting site sets no message of its own, so a
     * blank one produces a diagnostic that names its condition by number and says nothing else.
     */
    @Test
    @DisplayName("every code carries a non-blank summary")
    void everyCodeHasASummary() {
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            assertThat(code.summary())
                    .as("%s must have a summary — it is shown in reference tables and IDE tooltips",
                            code)
                    .isNotBlank();
        }
    }

    /**
     * Asserts that each constant's declared {@link DiagnosticCode#category()} agrees with the range its
     * number falls in.
     *
     * <p>The category is written out per constant in the enum's constructor call, not computed from the
     * code, so the two can disagree and nothing else would notice. The ranges are published in
     * {@link DiagnosticCode}'s own description as the reason a reader can place a code before looking it
     * up; {@link #expectedCategory(int)} restates them independently, which is what gives the comparison
     * its value.
     *
     * <p>A failure means one of two things, and the fix differs: either a constant was given the wrong
     * category, or it was numbered into a block that does not hold its kind.
     */
    @Test
    @DisplayName("the category always matches the numeric range")
    void categoryMatchesNumericRange() {
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            final int number = Integer.parseInt(code.code().substring(2));
            assertThat(code.category())
                    .as("%s is in the wrong category for its number", code)
                    .isEqualTo(expectedCategory(number));
        }
    }

    /**
     * Asserts that {@link DiagnosticCode#of(String)} resolves every constant from its own wire form.
     *
     * <p>This is the path a tool takes when it turns a code read out of build output back into the
     * catalogue entry that explains it. A constant whose {@code code()} disagreed with the key it is
     * registered under would be reported by the build and then be unfindable.
     */
    @Test
    @DisplayName("lookup by wire form round-trips")
    void lookupRoundTrips() {
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            assertThat(DiagnosticCode.of(code.code()))
                    .as("%s must be resolvable by its wire form", code)
                    .contains(code);
        }
    }

    /**
     * Asserts that four kinds of unrecognised input resolve to an empty {@link java.util.Optional}.
     *
     * <p>A well-formed but unassigned code, the same code in lower case, the empty string and text that is
     * not a code at all all take the same path: lookup is an exact match against the wire form, with no
     * normalisation and no fuzzy fallback, and an unknown key is a miss rather than an exception. That
     * matters for a caller reading codes out of a log written by a different version, where an unknown
     * code is expected rather than exceptional.
     *
     * <p>{@code null} is not among the inputs; {@link DiagnosticCode#of(String)} rejects it separately.
     */
    @Test
    @DisplayName("an unknown code resolves to empty rather than throwing")
    void unknownCodeResolvesToEmpty() {
        assertThat(DiagnosticCode.of("AW9999")).isEmpty();
        assertThat(DiagnosticCode.of("aw1043")).as("lookup is case-sensitive").isEmpty();
        assertThat(DiagnosticCode.of("")).isEmpty();
        assertThat(DiagnosticCode.of("nonsense")).isEmpty();
    }

    /**
     * Asserts that {@link DiagnosticCode#isSuppressible()} is false for exactly the
     * {@link Severity#ERROR} constants.
     *
     * <p>Suppressibility is derived from the severity rather than declared beside it, so no constant can
     * be an error that a build is allowed to ignore. The assertion is an equality against the derivation,
     * which fails in both directions: an error that became suppressible, and a warning that stopped being.
     */
    @Test
    @DisplayName("errors are never suppressible and everything else is")
    void suppressibilityFollowsSeverity() {
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            assertThat(code.isSuppressible())
                    .as("%s (%s)", code, code.defaultSeverity())
                    .isEqualTo(code.defaultSeverity() != Severity.ERROR);
        }
    }

    /**
     * Asserts that {@link DiagnosticCode#toString()} is the wire form rather than the constant name.
     *
     * <p>The two differ, and the difference decides what a reader finds. A code interpolated into a log
     * line as {@code NO_INJECTION_POINT_MATCHED} names an enum constant that appears in one source file
     * and in no documentation; as {@code AW1043} it is the string the catalogue is indexed by.
     */
    @Test
    @DisplayName("toString returns the wire form so logging produces a searchable code")
    void toStringIsTheWireForm() {
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            assertThat(code).hasToString(code.code());
        }
    }

    /**
     * Asserts that every {@link DiagnosticCode.Category} constant is claimed by at least one code.
     *
     * <p>The reverse direction rarely gets to run: {@link DiagnosticCode#category()} is typed
     * {@link DiagnosticCode.Category}, so a code naming a category that has been removed is a compile
     * error, not something this loop can observe. A category with no codes is a gap in the catalogue or a
     * grouping that has stopped earning its place, and either way it appears in reference output as an
     * empty heading.
     */
    @Test
    @DisplayName("every category is represented by at least one code")
    void everyCategoryIsUsed() {
        final Set<DiagnosticCode.Category> used = new TreeSet<>();
        for (final DiagnosticCode code : DiagnosticCode.values()) {
            used.add(code.category());
        }
        assertThat(used)
                .as("an unused category is either a missing code or a category that should go")
                .containsExactlyInAnyOrder(DiagnosticCode.Category.values());
    }

    /**
     * Returns the category the range table assigns to a code's four digits.
     *
     * <p>Written as an independent restatement of the table published in {@link DiagnosticCode}'s
     * description rather than as a call into the enum, so that a category and the number it was given can
     * be compared against each other.
     *
     * <p>{@link DiagnosticCode.Category#POLICY} is returned from two branches, matching the table: the
     * block from {@code 3000} and the block from {@code 3200}. No constant is numbered into the second
     * one, so that branch decides nothing about any code the catalogue declares today.
     *
     * @param number the four digits of a code, read as a number
     * @return the category that number belongs to
     */
    private static DiagnosticCode.Category expectedCategory(final int number) {
        if (number < 1100) {
            return DiagnosticCode.Category.DECLARATION;
        }
        if (number < 1200) {
            return DiagnosticCode.Category.INJECTION_POINT;
        }
        if (number < 1300) {
            return DiagnosticCode.Category.COMPILE_TIME;
        }
        if (number < 1400) {
            return DiagnosticCode.Category.EXTENSION;
        }
        if (number < 2100) {
            return DiagnosticCode.Category.TARGET;
        }
        if (number < 2200) {
            return DiagnosticCode.Category.DRIVER;
        }
        if (number < 2300) {
            return DiagnosticCode.Category.IDEMPOTENCE;
        }
        if (number < 2400) {
            return DiagnosticCode.Category.CONFIGURATION;
        }
        if (number < 2500) {
            return DiagnosticCode.Category.ENVIRONMENT;
        }
        if (number < 3000) {
            return DiagnosticCode.Category.BUILD;
        }
        if (number < 3100) {
            return DiagnosticCode.Category.POLICY;
        }
        if (number < 3200) {
            return DiagnosticCode.Category.PLUGIN;
        }
        if (number < 4000) {
            return DiagnosticCode.Category.POLICY;
        }
        return DiagnosticCode.Category.ENGINE;
    }
}
