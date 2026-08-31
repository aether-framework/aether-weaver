package de.splatgames.aether.weaver.idea.selector;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class SelectorRenameTest extends BasePlatformTestCase {

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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
    }

    public void testRenameRewritesTheNameAndLeavesTheSignature() {
        final PsiFile weave = myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "charge(java.math.BigDecimal)")
                    void onCharge() { }
                }
                """);

        renameTargetMemberTo("settle");

        assertEquals("the name is replaced inside the string and the signature around it is left "
                        + "alone; losing it would leave a weave that still resolves and targets "
                        + "something else",
                "\"settle(java.math.BigDecimal)\"", selectorIn(weave));
    }

    public void testRenameRewritesABareSelector() {
        final PsiFile weave = myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "charge")
                    void onCharge() { }
                }
                """);

        renameTargetMemberTo("settle");

        assertEquals("\"settle\"", selectorIn(weave));
    }

    public void testAForeignSelectorIsLeftAlone() {
        myFixture.addFileToProject("other/Unrelated.java", """
                package other;

                public @interface Unrelated {
                    String method();
                }
                """);
        final PsiFile foreign = myFixture.addFileToProject("fixture/NotAWeave.java", """
                package fixture;

                import other.Unrelated;

                @Unrelated(method = "charge")
                public final class NotAWeave { }
                """);

        renameTargetMemberTo("settle");

        assertEquals("rewriting somebody else's annotation would be a silent edit to code this "
                        + "plugin has no business touching",
                "\"charge\"", selectorIn(foreign));
    }

    private void renameTargetMemberTo(final String newName) {
        myFixture.configureByText("Target.java", """
                package fixture;

                public class Target {
                    public String cha<caret>rge(java.math.BigDecimal amount) { return "x"; }
                    public void untouched() { }
                }
                """);
        myFixture.renameElementAtCaret(newName);
    }

    private static String selectorIn(final PsiFile file) {
        final String text = file.getText();
        final int start = text.indexOf("method = ");
        if (start < 0) {
            return text;
        }
        final int open = text.indexOf('"', start);
        final int close = text.indexOf('"', open + 1);
        return open < 0 || close < 0 ? text : text.substring(open, close + 1);
    }
}
