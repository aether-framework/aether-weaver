package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.PsiMethodMember;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;

import java.util.ArrayList;
import java.util.List;

public class AddHandlerDialogTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
            }
            """;

    private static final String TARGET = """
            package fixture;

            public final class Service {
                public void charge() { }

                public void settle() { }
            }
            """;

    private static final String AUDIT = """
            package fixture;

            import de.splatgames.aether.weaver.api.Weave;

            @Weave(Service.class)
            public final class Audit { }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("fixture/Service.java", TARGET);
        myFixture.addFileToProject("fixture/Audit.java", AUDIT);
    }

    public void testAPositionalPointChoosesOneMethod() {
        final AddHandlerDialog dialog = dialogFor(HandlerOptions.Point.HEAD);
        try {
            final List<ClassMember> chosen = dialog.chosen();

            assertEquals("the list offers both methods and selects the first; a table of checkboxes "
                            + "let both be chosen, and then the preview, the slice bounds and the "
                            + "captured locals all described only one of them: " + chosen,
                    1, chosen.size());
            assertEquals("charge", methodNameOf(chosen.getFirst()));
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testAnOperationPointWithoutOperationsChoosesNothing() {
        final AddHandlerDialog dialog = dialogFor(HandlerOptions.Point.INVOKE);
        try {
            assertEquals("a headless fixture has no compiler output, so there are no operations to "
                            + "offer — and the answer to that is nothing, not the target method the "
                            + "other list would have offered",
                    List.of(), dialog.chosen());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testABoundReadsAsItsCall() {
        final TargetOperations.Operation bound = new TargetOperations.Operation(
                de.splatgames.aether.weaver.api.Point.INVOKE, "Ledger.commit()", 0, 12,
                "Ledger.commit()", null);

        assertEquals("a record renders itself as its entire state, and a combo sizes itself to its "
                        + "widest item — so without this the Slice rows showed "
                        + "\"Operation[point=INVOKE, target=..., ordinal=0, index=12, ...]\" and "
                        + "changed width with whatever the longest call in the chosen method was",
                "Ledger.commit()", AddHandlerDialog.labelOfBound(bound));
    }

    public void testTheAbsenceOfABoundReadsAsItself() {
        assertEquals("(whole method)", AddHandlerDialog.labelOfBound("(whole method)"));
    }

    private AddHandlerDialog dialogFor(final HandlerOptions.Point point) {
        final PsiClass weave = classNamed("fixture.Audit");
        final PsiClass target = classNamed("fixture.Service");
        final List<PsiMethod> targets = new ArrayList<>();
        for (final PsiMethod candidate : target.getMethods()) {
            if (!candidate.isConstructor()) {
                targets.add(candidate);
            }
        }
        assertEquals("the fixture declares two methods to choose between", 2, targets.size());

        final HandlerOptions defaults = HandlerOptions.defaults();
        return new AddHandlerDialog(getProject(), weave, targets,
                new HandlerOptions(defaults.kind(), point, defaults.match(), defaults.selector(),
                        defaults.visibility(), defaults.prefix(), "", defaults.callback(),
                        defaults.locals(), defaults.javadoc(), defaults.todo()));
    }

    private static String methodNameOf(final ClassMember member) {
        assertTrue("expected a target method, got " + member, member instanceof PsiMethodMember);
        return ((PsiMethodMember) member).getElement().getName();
    }

    private PsiClass classNamed(final String qualified) {
        final PsiClass found = com.intellij.psi.JavaPsiFacade.getInstance(getProject())
                .findClass(qualified, com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare " + qualified, found);
        return found;
    }
}
