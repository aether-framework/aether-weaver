package de.splatgames.aether.weaver.idea.marker;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeaveLineMarkerTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                int priority() default 0;
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
    }

    public void testAWovenMemberIsMarkedAndAnUntouchedOneIsNot() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "charge")
                    void onCharge() { }
                }
                """);

        final List<String> tooltips = ourGuttersIn("""
                package fixture;

                public class Target {
                    public String charge() { return "x"; }
                    public void untouched() { }
                }
                """);

        assertEquals("exactly the woven member is marked, and the neighbour beside it is not — a "
                        + "marker on everything would say nothing: " + tooltips,
                1, tooltips.size());
        assertTrue(tooltips.getFirst(), tooltips.getFirst().contains("one handler"));
    }

    public void testADescriptorSelectorMarksItsTarget() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "desc:charge(I)Ljava/lang/String;")
                    void onCharge() { }
                }
                """);

        final List<String> tooltips = ourGuttersIn("""
                package fixture;

                public class Target {
                    public String charge(int amount) { return "x"; }
                    public void untouched() { }
                }
                """);

        assertEquals("the descriptor form names exactly one method, so it must mark exactly one: "
                        + tooltips, 1, tooltips.size());
    }

    public void testAWeaveInAnotherPackageMarksAPackagePrivateTarget() {
        myFixture.addFileToProject("other/Audit.java", """
                package other;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(fixture.Target.class)
                public final class Audit {
                    @Inject(method = "hidden")
                    void onHidden() { }
                }
                """);

        final List<String> tooltips = ourGuttersIn("""
                package fixture;

                public class Target {
                    static String hidden() { return "x"; }
                    public void untouched() { }
                }
                """);

        assertEquals("a weave edits bytecode; it does not call the member, so Java's accessibility "
                        + "rules do not decide whether it may name it: " + tooltips,
                1, tooltips.size());
    }

    public void testSeveralHandlersAreCounted() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "charge")
                    void onCharge() { }

                    @Inject(method = "charge")
                    void alsoOnCharge() { }
                }
                """);

        final List<String> tooltips = ourGuttersIn("""
                package fixture;

                public class Target {
                    public String charge() { return "x"; }
                }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue(tooltips.getFirst(), tooltips.getFirst().contains("2 handlers"));
    }

    public void testHandlersAreListedInExecutionOrderWithTheirPriority() {
        myFixture.addFileToProject("fixture/Alpha.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Alpha {
                    @Inject(method = "charge")
                    void runsSecond() { }
                }
                """);
        myFixture.addFileToProject("fixture/Zebra.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(value = Target.class, priority = 100)
                public final class Zebra {
                    @Inject(method = "charge")
                    void runsFirst() { }
                }
                """);

        final List<String> tooltips = ourGuttersIn("""
                package fixture;

                public class Target {
                    public String charge() { return "x"; }
                }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        final String tooltip = tooltips.getFirst();
        assertTrue("the higher priority must be named: " + tooltip,
                tooltip.contains("priority 100"));
        assertTrue("and the default one too, so the rule is visible: " + tooltip,
                tooltip.contains("priority 0"));
        assertTrue("higher priority runs earlier. The weave names are Zebra and Alpha on purpose: "
                        + "sorted by class name — the engine's tie-break, and the order an unsorted "
                        + "list would drift into — Alpha would come first. Only priority puts Zebra "
                        + "there, so this assertion fails if the comparator degrades to the "
                        + "tie-break: " + tooltip,
                tooltip.indexOf("runsFirst") < tooltip.indexOf("runsSecond"));
    }

    public void testWithoutAWeaveNothingIsMarked() {
        assertEquals("if this were non-empty the assertions above would prove nothing, because a "
                        + "marker on every method would satisfy them too",
                List.of(), ourGuttersIn("""
                        package fixture;

                        public class Target {
                            public String charge() { return "x"; }
                        }
                        """));
    }

    private List<String> ourGuttersIn(final String source) {
        myFixture.configureByText("Target.java", source);
        final List<String> ours = new ArrayList<>();
        for (final GutterMark gutter : myFixture.findAllGutters()) {
            final String tooltip = gutter.getTooltipText();
            if (tooltip != null && tooltip.contains("Woven by")) {
                ours.add(tooltip);
            }
        }
        return ours;
    }
}
