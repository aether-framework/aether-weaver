package de.splatgames.aether.weaver.idea.selector;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class SelectorCompletionTest extends BasePlatformTestCase {

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

            public class Target {
                public String charge(java.math.BigDecimal amount) { return "x"; }
                public String charge() { return "y"; }
                public void settle() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
    }

    public void testEmptySelectorOffersEveryMember() {
        final List<String> offered = completeIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "<caret>")
                    void onCharge() { }
                }
                """);

        assertTrue("the bare name must be offered, because it is a legal selector: " + offered,
                offered.contains("charge"));
        assertTrue("so must the unique member: " + offered, offered.contains("settle"));
    }

    public void testOverloadsAreOfferedWithTheirSignatures() {
        final List<String> offered = completeIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "cha<caret>")
                    void onCharge() { }
                }
                """);

        assertTrue("the one-parameter overload: " + offered,
                offered.contains("charge(BigDecimal)"));
        assertTrue("and the one that takes nothing, which is a different selector: " + offered,
                offered.contains("charge()"));
        assertFalse("a member the prefix does not name must be filtered out: " + offered,
                offered.contains("settle"));
    }

    public void testQualifiedSelectorStillCompletesTheMemberName() {
        final List<String> offered = completeIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "fixture.Target.cha<caret>")
                    void onCharge() { }
                }
                """);

        assertTrue("the member name is what is being typed, whatever precedes it: " + offered,
                offered.contains("charge"));
    }

    public void testCompletionOutsideAWeaveOffersNothingOfOurs() {
        myFixture.addFileToProject("other/Unrelated.java", """
                package other;

                public @interface Unrelated {
                    String method();
                }
                """);

        final List<String> offered = completeIn("""
                package fixture;

                import other.Unrelated;

                @Unrelated(method = "cha<caret>")
                public final class NotAWeave { }
                """);

        assertFalse("claiming a foreign annotation would put our members in their completion: "
                + offered, offered.contains("charge"));
        assertFalse(offered.contains("charge(BigDecimal)"));
    }

    public void testNoMemberNamesInsideTheSignature() {
        final List<String> offered = completeIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "charge(<caret>")
                    void onCharge() { }
                }
                """);

        assertFalse("a parameter type is being typed, not a member name: " + offered,
                offered.contains("charge"));
        assertFalse(offered.contains("settle"));
    }

    private List<String> completeIn(final String source) {
        myFixture.configureByText("Probe.java", source);
        myFixture.completeBasic();
        final List<String> offered = myFixture.getLookupElementStrings();
        return offered == null ? List.of() : offered;
    }
}
