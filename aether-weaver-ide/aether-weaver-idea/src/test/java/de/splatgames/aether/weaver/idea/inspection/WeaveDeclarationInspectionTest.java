package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeaveDeclarationInspectionTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                Kind kind() default Kind.INSTANCE;

                enum Kind { INSTANCE, STATIC }
            }
            """;

    private static final String SHADOW = """
            package de.splatgames.aether.weaver.api;

            public @interface Shadow {
                String value() default "";
            }
            """;

    private static final String UNIQUE = """
            package de.splatgames.aether.weaver.api;

            public @interface Unique {
            }
            """;

    private static final String TARGET = """
            package fixture;

            public class Target {
                public void charge() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shadow.java", SHADOW);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Unique.java", UNIQUE);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
        myFixture.enableInspections(new WeaveDeclarationInspection());
    }

    public void testAWeaveWithoutATargetIsReported() {
        final List<String> problems = problemsIn("@Weave", "");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst(), problems.getFirst().contains("AW1001"));
    }

    public void testAnEmptyTargetArrayIsReported() {
        final List<String> problems = problemsIn("@Weave({})", "");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst(), problems.getFirst().contains("AW1001"));
    }

    public void testTargetsNamedTwiceAreReported() {
        final List<String> problems =
                problemsIn("@Weave(value = Target.class, targets = \"fixture.Target\")", "");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst(), problems.getFirst().contains("AW1002"));
    }

    public void testShadowInAStaticWeaveIsReported() {
        final List<String> problems = problemsIn(
                "@Weave(value = Target.class, kind = Weave.Kind.STATIC)",
                "@Shadow private int amount;");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst(), problems.getFirst().contains("AW1090"));
        assertTrue("the message must point somewhere, or the reader goes looking for a flag that "
                        + "does not exist: " + problems,
                problems.getFirst().contains("@Accessor"));
    }

    public void testAShadowMethodInAStaticWeaveIsReported() {
        final List<String> problems = problemsIn(
                "@Weave(value = Target.class, kind = Weave.Kind.STATIC)",
                "@Shadow private void flush() { }");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst(), problems.getFirst().contains("AW1090"));
    }

    public void testUniqueInAStaticWeaveIsReported() {
        final List<String> problems = problemsIn(
                "@Weave(value = Target.class, kind = Weave.Kind.STATIC)",
                "@Unique private long startedAt;");

        assertEquals("" + problems, 1, problems.size());
        assertTrue(problems.getFirst(), problems.getFirst().contains("AW1091"));
    }

    public void testShadowAndUniqueInAnInstanceWeaveAreFine() {
        assertEquals("an instance weave is dissolved into its target, which is exactly what makes "
                        + "@Shadow and @Unique work",
                List.of(), problemsIn("@Weave(Target.class)", """
                        @Shadow private int amount;
                        @Unique private long startedAt;
                        """));
    }

    public void testAnExplicitInstanceKindIsFine() {
        assertEquals(List.of(), problemsIn(
                "@Weave(value = Target.class, kind = Weave.Kind.INSTANCE)",
                "@Shadow private int amount;"));
    }

    public void testACorrectWeaveIsNotReported() {
        assertEquals("if this were non-empty every assertion above would pass for the wrong reason",
                List.of(), problemsIn("@Weave(Target.class)", ""));
    }

    public void testAPlainClassIsNotInspected() {
        myFixture.configureByText("Plain.java", """
                package fixture;

                public final class Plain {
                    private int amount;
                }
                """);

        assertEquals(List.of(), ourProblems());
    }

    private List<String> problemsIn(final String declaration, final String body) {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Shadow;
                import de.splatgames.aether.weaver.api.Unique;
                import de.splatgames.aether.weaver.api.Weave;

                %s
                public final class Audit {
                %s
                }
                """.formatted(declaration, body.indent(4)));
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
