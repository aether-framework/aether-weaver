package de.splatgames.aether.weaver.idea.generate;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;

import java.util.ArrayList;
import java.util.List;

public class WeaveHereDialogTest extends BasePlatformTestCase {

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
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("fixture/Service.java", TARGET);
    }

    public void testItOpensOnTheFirstSpot() {
        final WeaveHereDialog dialog = dialogFor(List.of());
        try {
            final WeaveSpot spot = dialog.spot();

            assertNotNull("something has to be chosen, or the author is met with a disabled button "
                    + "and a list that looks ready", spot);
            assertEquals("the first offer is the best one the search found",
                    SpotFinder.positions().getFirst().point(), spot.point());
            assertEquals("and the options agree with it", HandlerOptions.Point.HEAD,
                    dialog.options().point());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testOneWeaveIsUsed() {
        addWeave("Audit");
        final WeaveHereDialog dialog = dialogFor(List.of(classNamed("fixture.Audit")));
        try {
            final PsiClass weave = dialog.weave();

            assertNotNull("there is nothing to ask about when there is one", weave);
            assertEquals("fixture.Audit", weave.getQualifiedName());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testSeveralWeavesArePreselectedToNone() {
        addWeave("Audit");
        addWeave("Timing");
        final WeaveHereDialog dialog =
                dialogFor(List.of(classNamed("fixture.Audit"), classNamed("fixture.Timing")));
        try {
            assertNull("a project with two weaves on a class has them for a reason, and picking "
                            + "the first would put an audit handler in the timing weave — the one "
                            + "decision here the preview looks the same for either way",
                    dialog.weave());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testNoWeaveOffersToMakeOne() {
        final WeaveHereDialog dialog = dialogFor(List.of());
        try {
            assertNull("nothing to select means the creation entry, which is what null stands for",
                    dialog.weave());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testWithoutAClassFileNothingIsCaptured() {
        final WeaveHereDialog dialog = dialogFor(List.of());
        try {
            assertEquals("a capture names a variable by what the compiler recorded, and without a "
                            + "class file there is no record — generating one anyway would be "
                            + "AW1052 on a handler that reads perfectly",
                    List.of(), dialog.captures());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testItSaysWhyOnlyPositionsAreOffered() {
        final WeaveHereDialog dialog = dialogFor(List.of());
        try {
            final String said = dialog.unavailableMessage();

            assertFalse("silence here is indistinguishable from a feature that only knows HEAD",
                    said.isEmpty());
            assertTrue("and it has to name something the reader can act on: " + said,
                    said.contains("build") || said.contains("save"));
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testASourceSpotInsistsOnItsMatchRule() {
        final WeaveSpot spot = new WeaveSpot(de.splatgames.aether.weaver.api.Point.INVOKE,
                new de.splatgames.aether.weaver.idea.bytecode.TargetOperations.Operation(
                        de.splatgames.aether.weaver.api.Point.INVOKE, "fixture.Ledger.commit()",
                        -1, -1, "fixture.Ledger.commit()", null),
                null, 3, 1, WeaveSpot.Confidence.FROM_SOURCE, "before commit()", "read from source",
                null);
        final WeaveHereDialog dialog = dialogWith(List.of(spot));
        try {
            assertEquals("one match in the source means the build fails rather than binding "
                            + "elsewhere",
                    HandlerOptions.Match.EXACTLY_ONE, dialog.options().match());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testSeveralSourceMatchesRequireAtLeastOne() {
        final WeaveSpot spot = new WeaveSpot(de.splatgames.aether.weaver.api.Point.INVOKE,
                new de.splatgames.aether.weaver.idea.bytecode.TargetOperations.Operation(
                        de.splatgames.aether.weaver.api.Point.INVOKE, "fixture.Ledger.commit()",
                        -1, -1, "fixture.Ledger.commit()", null),
                null, 3, 3, WeaveSpot.Confidence.FROM_SOURCE, "before commit()", "read from source",
                null);
        final WeaveHereDialog dialog = dialogWith(List.of(spot));
        try {
            assertEquals("insisting on exactly one would fail the build on correct code",
                    HandlerOptions.Match.EVERY_REQUIRED, dialog.options().match());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    // --- the harness -----------------------------------------------------------------------------

    private WeaveHereDialog dialogFor(final List<PsiClass> weaves) {
        return dialogWith(SpotFinder.positions(), weaves);
    }

    private WeaveHereDialog dialogWith(final List<WeaveSpot> spots) {
        return dialogWith(spots, List.of());
    }

    private WeaveHereDialog dialogWith(final List<WeaveSpot> spots, final List<PsiClass> weaves) {
        // No compiled method, which is the state a headless fixture is always in — and the state
        // an unbuilt project is in. The dialog has to open in it rather than refuse, and it has to
        // say so.
        return new WeaveHereDialog(getProject(), targetMethod(),
                CompiledClasses.methodOf(targetMethod()), spots, weaves, spelling -> spots,
                HandlerOptions.defaults());
    }

    private PsiMethod targetMethod() {
        final List<PsiMethod> methods = new ArrayList<>();
        for (final PsiMethod candidate : classNamed("fixture.Service").getMethods()) {
            if (!candidate.isConstructor()) {
                methods.add(candidate);
            }
        }
        assertFalse("the fixture must declare a method", methods.isEmpty());
        return methods.getFirst();
    }

    private void addWeave(final String name) {
        myFixture.addFileToProject("fixture/" + name + ".java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Service.class)
                public final class %s { }
                """.formatted(name));
    }

    private PsiClass classNamed(final String qualified) {
        final PsiClass found = com.intellij.psi.JavaPsiFacade.getInstance(getProject())
                .findClass(qualified,
                        com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare " + qualified, found);
        return found;
    }
}
