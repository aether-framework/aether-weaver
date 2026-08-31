package de.splatgames.aether.weaver.idea.marker;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class HandlerLineMarkerTest extends BasePlatformTestCase {

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

    private static final String REDIRECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Redirect {
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
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Redirect.java", REDIRECT);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
    }

    public void testAHandlerIsMarked() {
        final List<String> tooltips = ourGuttersIn("""
                @Inject(method = "settle")
                void onSettle() { }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue(tooltips.getFirst(), tooltips.getFirst().contains("one member"));
    }

    public void testARedirectIsMarked() {
        final List<String> tooltips = ourGuttersIn("""
                @Redirect(method = "settle")
                void onSettle() { }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
    }

    public void testABareNameLeadsToEveryOverload() {
        final List<String> tooltips = ourGuttersIn("""
                @Inject(method = "charge")
                void onCharge() { }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue("a bare name naming every overload is the language's design, and the marker must "
                        + "not quietly choose one of them: " + tooltips,
                tooltips.getFirst().contains("2 members"));
    }

    public void testASignatureNarrowsTheMarker() {
        final List<String> tooltips = ourGuttersIn("""
                @Inject(method = "charge(java.math.BigDecimal)")
                void onCharge() { }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue(tooltips.getFirst(), tooltips.getFirst().contains("one member"));
    }

    public void testAnUnresolvableSelectorIsNotMarked() {
        assertEquals(List.of(), ourGuttersIn("""
                @Inject(method = "nosuchmember")
                void onNothing() { }
                """));
    }

    public void testAPlainMethodInAWeaveIsNotMarked() {
        assertEquals("if this were non-empty the assertions above would prove nothing, because a "
                        + "marker on every method would satisfy them too",
                List.of(), ourGuttersIn("""
                        void notAHandler() { }
                        """));
    }

    public void testAHandlerOutsideAWeaveIsNotMarked() {
        myFixture.configureByText("NotAWeave.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;

                public final class NotAWeave {
                    @Inject(method = "settle")
                    void onSettle() { }
                }
                """);

        assertEquals(List.of(), ourGutters());
    }

    private List<String> ourGuttersIn(final String body) {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Redirect;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                %s
                }
                """.formatted(body.indent(4)));
        return ourGutters();
    }

    private List<String> ourGutters() {
        final List<String> ours = new ArrayList<>();
        for (final GutterMark gutter : myFixture.findAllGutters()) {
            final String tooltip = gutter.getTooltipText();
            if (tooltip != null && tooltip.contains("Weaves into")) {
                ours.add(tooltip);
            }
        }
        return ours;
    }
}
