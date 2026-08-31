package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeaveMemberInspectionTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                Kind kind() default Kind.INSTANCE;

                enum Kind { INSTANCE, STATIC }
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
            }
            """;

    private static final String SHADOW = """
            package de.splatgames.aether.weaver.api;

            public @interface Shadow {
                String value() default "";
                boolean mutable() default false;
            }
            """;

    private static final String UNIQUE = """
            package de.splatgames.aether.weaver.api;

            public @interface Unique { }
            """;

    private static final String ACCESSOR = """
            package de.splatgames.aether.weaver.api;

            public @interface Accessor {
                String value() default "";
            }
            """;

    private static final String MONEY = """
            package fixture;

            public class Money { }
            """;

    private static final String RECEIPT = """
            package fixture;

            public class Receipt { }
            """;

    private static final String TARGET = """
            package fixture;

            public class Gateway {
                Money balance;
                public void charge(Money amount) { }
                public Money settle() { return null; }
                public Money getBalance() { return balance; }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shadow.java", SHADOW);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Unique.java", UNIQUE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Accessor.java", ACCESSOR);
        myFixture.addFileToProject("fixture/Money.java", MONEY);
        myFixture.addFileToProject("fixture/Receipt.java", RECEIPT);
        myFixture.addFileToProject("fixture/Gateway.java", TARGET);
    }

    public void testAShadowWithTheWrongTypeIsReported() {
        weave("", """
                @Shadow
                private Receipt balance;
                """);

        assertTrue("" + problems(new WeaveMemberInspection()),
                describes(new WeaveMemberInspection(), "AW1031")
                        && describes(new WeaveMemberInspection(), "fixture.Money"));
    }

    public void testAMatchingShadowIsAccepted() {
        weave("", """
                @Shadow
                private Money balance;
                """);

        assertEquals("this is what every weave reaching into its target's state looks like",
                List.of(), problems(new WeaveMemberInspection()));
    }

    public void testAMergedCollisionIsReported() {
        weave("", """
                private Money balance;
                """);

        assertTrue("overwriting the target's own member would replace working code with an "
                        + "uninitialised copy: " + problems(new WeaveMemberInspection()),
                describes(new WeaveMemberInspection(), "AW1080"));
    }

    public void testAUniqueCollisionIsNotReported() {
        weave("", """
                @Unique
                private Money balance;
                """);

        assertEquals("the engine renames it to balance$aw$<digest> and reports AW1094, which is "
                        + "information — reporting an error here would fire on the framework's own "
                        + "recommended way of avoiding the problem",
                List.of(), problems(new WeaveMemberInspection()));
    }

    public void testAGeneratedCollisionIsReported() {
        weave("", """
                @Accessor
                Money getBalance() { throw new AssertionError("accessor"); }
                """);

        assertTrue("a generated member cannot be @Unique, because callers reach it by the name it "
                        + "is declared under: " + problems(new WeaveMemberInspection()),
                describes(new WeaveMemberInspection(), "AW1095"));
    }

    public void testAnUnreachableHandlerIsReportedAndFixed() {
        myFixture.addFileToProject("other/Audit.java", """
                package other;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(value = fixture.Gateway.class, kind = Weave.Kind.STATIC)
                final class Audit {
                    @Inject(method = "charge(fixture.Money)")
                    private static void onCharge() { }
                }
                """);
        myFixture.configureByFile("other/Audit.java");

        assertTrue("" + problems(new WeaveMemberInspection()),
                describes(new WeaveMemberInspection(), "AW1042"));

        applyFix("Make the handler and its weave public");
        final String text = myFixture.getFile().getText();
        assertTrue("a public method on a package-private class is just as unreachable: " + text,
                text.contains("public final class Audit") && text.contains("public static void"));
    }

    public void testASamePackageHandlerIsAccepted() {
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Inject(method = "charge(fixture.Money)")
                static void onCharge() { }
                """);

        assertEquals("package-private is reachable from the same package, and demanding public "
                        + "there would push every weave to widen its API for nothing",
                List.of(), problems(new WeaveMemberInspection()));
    }

    public void testAnInstanceHandlerInAStaticWeaveIsReported() {
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Inject(method = "charge(fixture.Money)")
                void onCharge() { }
                """);

        assertTrue("" + problems(new WeaveDeclarationInspection()),
                describes(new WeaveDeclarationInspection(), "AW1005"));

        myFixture.enableInspections(new WeaveDeclarationInspection());
        applyFix("Declare the handler static");
        assertTrue(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("static void onCharge"));
    }

    public void testAnInstanceHandlerInAnInstanceWeaveIsAccepted() {
        weave("", """
                @Inject(method = "charge(fixture.Money)")
                void onCharge() { }
                """);

        assertEquals(List.of(), problems(new WeaveDeclarationInspection()));
    }

    private void weave(final String declaration, final String body) {
        final String targets = declaration.isEmpty() ? "Gateway.class" : declaration;
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Accessor;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Shadow;
                import de.splatgames.aether.weaver.api.Unique;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(%s)
                public final class Audit {
                %s
                }
                """.formatted(targets, body.indent(4)));
    }

    private void applyFix(final String family) {
        for (final IntentionAction fix : myFixture.getAllQuickFixes()) {
            if (family.equals(fix.getFamilyName())) {
                myFixture.launchAction(fix);
                return;
            }
        }
        fail("no fix named '" + family + "' was offered");
    }

    private List<String> problems(final LocalInspectionTool inspection) {
        myFixture.enableInspections(inspection);
        final List<String> found = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getDescription() != null && info.getDescription().startsWith("AW")) {
                found.add(info.getDescription());
            }
        }
        return found;
    }

    private boolean describes(final LocalInspectionTool inspection, final String fragment) {
        for (final String problem : problems(inspection)) {
            if (problem.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
