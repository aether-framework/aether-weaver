package de.splatgames.aether.weaver.idea.selector;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class SelectorReferenceTest extends BasePlatformTestCase {

    private static final String API = """
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
                public void unrelated() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", API);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
    }

    public void testSelectorResolvesToTheTargetMethod() {
        final PsiReference reference = referenceIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "cha<caret>rge(java.math.BigDecimal)")
                    void onCharge() { }
                }
                """);

        assertNotNull("a selector inside a weave must contribute a reference", reference);
        final PsiElement resolved = reference.resolve();
        assertTrue("it must resolve to a method of the target, but was " + resolved,
                resolved instanceof PsiMethod);
        assertEquals("charge", ((PsiMethod) resolved).getName());
        assertEquals("the one-parameter overload is what the signature named",
                1, ((PsiMethod) resolved).getParameterList().getParametersCount());
    }

    public void testSelectorWithoutSignatureNamesEveryOverload() {
        final PsiReference reference = referenceIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "cha<caret>rge")
                    void onCharge() { }
                }
                """);

        assertNotNull(reference);
        assertEquals("both overloads are named, and the platform shows the user both",
                2, ((com.intellij.psi.PsiPolyVariantReference) reference)
                        .multiResolve(false).length);
    }

    public void testAnAttributeNamedMethodElsewhereIsNotOurs() {
        myFixture.addFileToProject("other/Unrelated.java", """
                package other;

                public @interface Unrelated {
                    String method();
                }
                """);

        assertNull("without an enclosing @Weave this is somebody else's annotation, and a plugin "
                        + "that claimed it would break their navigation",
                referenceIn("""
                        package fixture;

                        import other.Unrelated;

                        @Unrelated(method = "cha<caret>rge")
                        public final class NotAWeave { }
                        """));
    }

    public void testAMalformedSelectorIsSilent() {
        assertNull("a half-typed selector must not put a platform error over the editor",
                referenceIn("""
                        package fixture;

                        import de.splatgames.aether.weaver.api.Inject;
                        import de.splatgames.aether.weaver.api.Weave;

                        @Weave(Target.class)
                        public final class Audit {
                            @Inject(method = "cha<caret>rge((((")
                            void onCharge() { }
                        }
                        """));
    }

    private PsiReference referenceIn(final String source) {
        myFixture.configureByText("Probe.java", source);
        final PsiElement at = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        final com.intellij.psi.PsiLiteralExpression literal =
                com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                        at, com.intellij.psi.PsiLiteralExpression.class);
        assertNotNull("every fixture puts the caret inside a string literal; if this is null the "
                + "test is broken, not the plugin", literal);
        for (final PsiReference reference : literal.getReferences()) {
            if (reference instanceof SelectorReference) {
                return reference;
            }
        }
        return null;
    }
}
