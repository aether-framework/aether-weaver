package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class ConvertSelectorFormTest extends BasePlatformTestCase {

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
                public void charge(java.math.BigDecimal amount) { }
                public long count(java.lang.String[] names, int limit) { return 0; }
                public void settle() { }
            }
            """;

    private static final String INTENTION = "Convert selector to source form";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
    }

    public void testADescriptorSelectorIsConverted() {
        weaveWith("desc:charge(Ljava/math/BigDecimal;)V");

        final IntentionAction convert = offered();
        assertNotNull("the descriptor form is not second-class, and neither is reading it", convert);
        myFixture.launchAction(convert);

        assertTrue(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"charge(java.math.BigDecimal):void\""));
    }

    public void testArraysAndPrimitivesAreDecoded() {
        weaveWith("desc:count([Ljava/lang/String;I)J");

        myFixture.launchAction(offered());

        assertTrue(myFixture.getFile().getText(),
                myFixture.getFile().getText()
                        .contains("\"count(java.lang.String[], int):long\""));
    }

    public void testAMethodWithoutParametersIsConverted() {
        weaveWith("desc:settle()V");

        myFixture.launchAction(offered());

        assertTrue(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"settle():void\""));
    }

    public void testASourceSelectorIsNotOffered() {
        weaveWith("charge(java.math.BigDecimal)");

        assertNull("offering a no-op conversion trains the user to ignore the intention list",
                offered());
    }

    public void testAMalformedSelectorIsNotOffered() {
        weaveWith("desc:charge((((");

        assertNull(offered());
    }

    public void testAStringOutsideAWeaveIsNotOffered() {
        myFixture.configureByText("NotAWeave.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;

                public final class NotAWeave {
                    @Inject(method = "desc:charge(Ljava/math/BigDecimal;)V")
                    void onCharge() { }
                }
                """);

        assertNull("this plugin does not claim annotations it did not define", offered());
    }

    public void testAnOrdinaryStringInAWeaveIsNotOffered() {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "settle")
                    void onSettle() {
                        System.out.println("desc:charge(Ljava/math/BigDecimal;)V<caret>");
                    }
                }
                """);

        assertNull("a selector-shaped string is not a selector; only the method attribute is",
                offered());
    }

    private void weaveWith(final String selector) {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "%s<caret>")
                    void onCharge() { }
                }
                """.formatted(selector));
    }

    private IntentionAction offered() {
        return myFixture.getAvailableIntention(INTENTION);
    }
}
