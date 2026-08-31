package de.splatgames.aether.weaver.idea.marker;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class ExtensionLineMarkerTest extends BasePlatformTestCase {

    private static final String EXTENSION = """
            package de.splatgames.aether.weaver.api.experimental;

            public @interface Extension {
                Class<?> value() default void.class;
            }
            """;

    private static final String RECEIVER = """
            package de.splatgames.aether.weaver.api.experimental;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;

            @Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
            public @interface Receiver {
                Class<?> value() default void.class;
            }
            """;

    private static final String TARGET = """
            package fixture;

            public final class Greeting {
                public String greet() { return "hello"; }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
    }

    public void testAContributedMethodIsMarked() {
        final List<String> tooltips = ourGuttersIn("""
                public static String shout(@Receiver Greeting self) { return self.greet(); }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue("the receiver is the most important fact about the declaration and the least "
                        + "visible one, so the gutter has to name it: " + tooltips,
                tooltips.getFirst().contains("fixture.Greeting"));
    }

    public void testAStaticContributionSaysSo() {
        final List<String> tooltips = ourGuttersIn("""
                @Receiver(Greeting.class)
                public static Greeting of(String name) { return null; }
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue(tooltips.getFirst(), tooltips.getFirst().contains("static method"));
    }

    public void testAContributedConstantIsMarked() {
        final List<String> tooltips = ourGuttersIn("""
                @Receiver(Greeting.class)
                public static final String CENT = "0.01";
                """);

        assertEquals("" + tooltips, 1, tooltips.size());
        assertTrue("the declaration looks like an ordinary field until the gutter says otherwise: "
                + tooltips, tooltips.getFirst().contains("as a constant"));
    }

    public void testANonFinalFieldIsNotMarked() {
        assertEquals("AW1314 refuses it, and an icon beside it would say everything is fine",
                List.of(), ourGuttersIn("""
                        @Receiver(Greeting.class)
                        public static String NAME = "x";
                        """));
    }

    public void testAnOrdinaryFieldIsNotMarked() {
        assertEquals(List.of(), ourGuttersIn("""
                private static final int CACHE = 1;
                """));
    }

    public void testAHelperIsNotMarked() {
        assertEquals("if this were non-empty every assertion above would prove nothing, because a "
                        + "marker on every method would satisfy them too",
                List.of(), ourGuttersIn("""
                        private static String helper(String text) { return text; }
                        """));
    }

    public void testADeclarationWithoutAReceiverIsNotMarked() {
        assertEquals("an icon beside it would say everything is fine, on the same line as an error",
                List.of(), ourGuttersIn("""
                        public static String shout(Greeting self) { return self.greet(); }
                        """));
    }

    public void testAMethodOutsideAnExtensionIsNotMarked() {
        myFixture.configureByText("NotAnExtension.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Receiver;

                public final class NotAnExtension {
                    public static String shout(@Receiver Greeting self) { return self.greet(); }
                }
                """);

        assertEquals(List.of(), ourGutters());
    }

    private List<String> ourGuttersIn(final String body) {
        myFixture.configureByText("Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                %s
                }
                """.formatted(body.indent(4)));
        return ourGutters();
    }

    private List<String> ourGutters() {
        final List<String> ours = new ArrayList<>();
        for (final GutterMark gutter : myFixture.findAllGutters()) {
            final String tooltip = gutter.getTooltipText();
            if (tooltip != null && tooltip.contains("Contributed to")) {
                ours.add(tooltip);
            }
        }
        return ours;
    }
}
