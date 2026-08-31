package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class SelectorInspectionTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
            }
            """;

    private static final String TARGET = """
            package fixture;

            public class Target extends Base {
                public String charge(java.math.BigDecimal amount) { return "x"; }
                public String charge() { return "y"; }
                public void settle() { }
            }
            """;

    private static final String BASE = """
            package fixture;

            public class Base {
                public void settle(String note) { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("fixture/Base.java", BASE);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
        myFixture.enableInspections(new SelectorInspection());
    }

    public void testAnUnknownMemberIsReported() {
        final List<String> problems = problemsIn("\"nosuchmember\"");

        assertEquals("exactly one problem, on the one thing that is wrong: " + problems,
                1, problems.size());
        assertTrue("the code must be the build's, so one search finds one explanation: " + problems,
                problems.getFirst().contains("AW1020"));
        assertTrue(problems.getFirst().contains("nosuchmember"));
        assertTrue("naming the target is what makes the message actionable: " + problems,
                problems.getFirst().contains("Target"));
    }

    public void testAnImpossibleSignatureIsReported() {
        final List<String> problems = problemsIn("\"charge(int,int,int)\"");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst().contains("AW1020"));
        assertTrue("saying how many parameters were asked for is the whole diagnosis: " + problems,
                problems.getFirst().contains("3 parameters"));
    }

    public void testAMalformedSelectorCarriesItsSyntaxCode() {
        final List<String> problems = problemsIn("\"charge((((\"");

        assertEquals("" + problems, 1, problems.size());
        assertTrue("a syntax failure must not be dressed up as a missing member: " + problems,
                problems.getFirst().contains("AW10"));
    }

    public void testABareNameMatchingSeveralOverloadsIsAmbiguous() {
        assertTrue("" + problemsIn("\"charge\""),
                problemsIn("\"charge\"").toString().contains("AW1021"));
    }

    public void testAMatchingSignatureIsNotAProblem() {
        assertEquals(List.of(), problemsIn("\"charge(java.math.BigDecimal)\""));
    }

    public void testAnUnresolvableTargetSilencesTheInspection() {
        myFixture.configureByText("Probe.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(targets = "com.acme.NotOnTheClasspath")
                public final class Audit {
                    @Inject(method = "nosuchmember")
                    void onCharge() { }
                }
                """);

        assertEquals("with no target resolved there is nothing to compare against, and guessing "
                        + "would underline code that is very probably correct",
                List.of(), ourProblems());
    }

    public void testAForeignAnnotationIsNotInspected() {
        myFixture.addFileToProject("other/Unrelated.java", """
                package other;

                public @interface Unrelated {
                    String method();
                }
                """);
        myFixture.configureByText("Probe.java", """
                package fixture;

                import other.Unrelated;

                @Unrelated(method = "nosuchmember")
                public final class NotAWeave { }
                """);

        assertEquals(List.of(), ourProblems());
    }

    public void testAMisspelledNameIsOfferedTheNearestMemberWithoutLosingTheSignature() {
        problemsIn("\"charg(java.math.BigDecimal)\"");

        final IntentionAction fix = fixNamed("Change selector to 'charge'");
        assertNotNull("a one-character typo in a six-character name is what a quick fix is for",
                fix);
        myFixture.launchAction(fix);

        assertTrue("the signature must survive. Replacing the literal's whole text would leave "
                        + "\"charge\", which parses, resolves, and now names both overloads instead "
                        + "of the one that was narrowed to: " + myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"charge(java.math.BigDecimal)\""));
    }

    public void testANameResemblingNothingIsOfferedNoFix() {
        problemsIn("\"zzzzzzzzzzzz\"");

        for (final IntentionAction offered : myFixture.getAllQuickFixes()) {
            assertFalse("guessing here would bind the injection to a method nobody asked for: "
                            + offered.getText(),
                    offered.getText().startsWith("Change selector to"));
        }
    }

    private IntentionAction fixNamed(final String text) {
        for (final IntentionAction offered : myFixture.getAllQuickFixes()) {
            if (text.equals(offered.getText())) {
                return offered;
            }
        }
        return null;
    }

    public void testABareNameMatchingOneMethodIsAccepted() {
        assertEquals("naming a method that is not overloaded is the ordinary, correct spelling",
                List.of(), problemsIn("\"settle\""));
    }

    private List<String> problemsIn(final String selector) {
        myFixture.configureByText("Probe.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = %s)
                    void onCharge() { }
                }
                """.formatted(selector));
        return ourProblems();
    }

    private List<String> ourProblems() {
        final List<String> ours = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            final String description = info.getDescription();
            if (description != null && description.startsWith("AW")) {
                ours.add(description);
            }
        }
        return ours;
    }
}
