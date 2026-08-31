package de.splatgames.aether.weaver.idea.index;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class WeaveTargetIndexTest extends BasePlatformTestCase {

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
                public void charge() { }
                public static class Inner { public void settle() { } }
            }
            """;

    private static final String OTHER = """
            package fixture;

            public class Other {
                public void refund() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
        myFixture.addFileToProject("fixture/Other.java", OTHER);
    }

    public void testAClassLiteralTargetIsFound() {
        final PsiFile weave = weaveFile("ByLiteral", "@Weave(Target.class)");

        assertTrue("a weave that names this class must be searched",
                scopeFor("fixture.Target").contains(weave.getVirtualFile()));
    }

    public void testAWeaveOnAnotherClassIsNotOffered() {
        final PsiFile weave = weaveFile("ByLiteral", "@Weave(Other.class)");

        assertFalse("searching every weave in the project is what this index exists to stop",
                scopeFor("fixture.Target").contains(weave.getVirtualFile()));
    }

    public void testABinaryNameTargetIsFound() {
        final PsiFile weave = weaveFile("ByName", "@Weave(targets = \"fixture.Target\")");

        assertTrue(scopeFor("fixture.Target").contains(weave.getVirtualFile()));
    }

    public void testANestedBinaryNameTargetIsFound() {
        final PsiFile weave = weaveFile("ByNested", "@Weave(targets = \"fixture.Target$Inner\")");

        assertTrue(scopeFor("fixture.Target.Inner").contains(weave.getVirtualFile()));
    }

    public void testATargetNamedThroughAConstantIsStillSearched() {
        myFixture.addFileToProject("fixture/Names.java", """
                package fixture;

                public final class Names {
                    public static final String TARGET = "fixture.Target";
                }
                """);
        final PsiFile weave = weaveFile("ByConstant", "@Weave(targets = Names.TARGET)");

        assertTrue("what the index cannot read, it must not claim to have ruled out",
                scopeFor("fixture.Target").contains(weave.getVirtualFile()));
    }

    public void testAWeaveWithNoTargetYetIsStillSearched() {
        final PsiFile weave = weaveFile("HalfTyped", "@Weave");

        assertTrue("a weave mid-edit must not make its own injections disappear",
                scopeFor("fixture.Target").contains(weave.getVirtualFile()));
    }

    public void testAClassNothingTargetsYieldsAnEmptyScope() {
        weaveFile("ByLiteral", "@Weave(Other.class)");
        final PsiFile unrelated = myFixture.addFileToProject("fixture/Untouched.java", """
                package fixture;

                public class Untouched {
                    public void run() { }
                }
                """);

        assertFalse(scopeFor("fixture.Untouched").contains(unrelated.getVirtualFile()));
    }

    public void testAnAbsentTargetYieldsAnEmptyScope() {
        assertSame("there is nothing to search for a class that does not exist",
                GlobalSearchScope.EMPTY_SCOPE,
                ReadAction.compute(() -> WeaveTargetIndex.weavesTargeting(null)));
    }

    private PsiFile weaveFile(final String name, final String declaration) {
        return myFixture.addFileToProject("fixture/" + name + ".java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                %s
                public final class %s {
                    @Inject(method = "charge")
                    void onCharge() { }
                }
                """.formatted(declaration, name));
    }

    private GlobalSearchScope scopeFor(final String qualifiedName) {
        return ReadAction.compute(() -> {
            final PsiClass target = JavaPsiFacade.getInstance(getProject())
                    .findClass(qualifiedName, GlobalSearchScope.allScope(getProject()));
            assertNotNull("the fixture must declare " + qualifiedName, target);
            return WeaveTargetIndex.weavesTargeting(target);
        });
    }
}
