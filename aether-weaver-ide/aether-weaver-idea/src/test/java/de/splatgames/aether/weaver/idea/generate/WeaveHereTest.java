package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;

import java.util.List;

public class WeaveHereTest extends BasePlatformTestCase {

    private static final String WEAVE_HERE = "Weave here";

    private static final String CAPTURE_LOCAL = "Weave where this variable is live";

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
                At[] at() default {};
            }
            """;

    private static final String AT = """
            package de.splatgames.aether.weaver.api;

            public @interface At {
                Point value() default Point.HEAD;
                String target() default "";
                int ordinal() default -1;
            }
            """;

    private static final String POINT = """
            package de.splatgames.aether.weaver.api;

            public enum Point {
                HEAD, RETURN, TAIL, INVOKE, INVOKE_AFTER, FIELD, NEW, CONSTANT, THROW
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/At.java", AT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Point.java", POINT);
    }

    // --- where it is offered ---------------------------------------------------------------------

    public void testItIsOfferedInsideAMethodBody() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        System.out.print<caret>ln("x");
                    }
                }
                """);

        assertFalse("standing on the line is how an author says where they want to be",
                myFixture.filterAvailableIntentions(WEAVE_HERE).isEmpty());
    }

    public void testItIsNotOfferedOnTheSignature() {
        configure("""
                package fixture;

                public final class Service {
                    public void cha<caret>rge() {
                        int x = 1;
                    }
                }
                """);

        assertEquals(List.of(), myFixture.filterAvailableIntentions(WEAVE_HERE));
    }

    public void testItIsNotOfferedInsideALambda() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        run(() -> System.out.print<caret>ln("x"));
                    }

                    private void run(final Runnable task) { }
                }
                """);

        assertEquals("its body compiles into a synthetic method, so the enclosing method's "
                        + "operations are not the ones at this caret — and the line looks exactly "
                        + "like one this works for",
                List.of(), myFixture.filterAvailableIntentions(WEAVE_HERE));
    }

    public void testItIsNotOfferedInsideAWeave() {
        configure("""
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Object.class)
                public final class Audit {
                    void onCharge() {
                        int x = <caret>1;
                    }
                }
                """);

        assertEquals(List.of(), myFixture.filterAvailableIntentions(WEAVE_HERE));
    }

    public void testCapturingIsOfferedOnALocal() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        int tot<caret>al = 1;
                        System.out.println(total);
                    }
                }
                """);

        assertFalse("standing on the variable is how an author says which value they want",
                myFixture.filterAvailableIntentions(CAPTURE_LOCAL).isEmpty());
    }

    public void testCapturingIsNotOfferedOnAParameter() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge(final int amo<caret>unt) {
                        System.out.println(amount);
                    }
                }
                """);

        assertEquals("a handler's parameter list is already a prefix of its target's, so the value "
                        + "is there — capturing it would be the same thing twice under two "
                        + "mechanisms",
                List.of(), myFixture.filterAvailableIntentions(CAPTURE_LOCAL));
    }

    public void testCapturingIsNotOfferedOnAField() {
        configure("""
                package fixture;

                public final class Service {
                    private int total;

                    public void charge() {
                        this.to<caret>tal = 1;
                    }
                }
                """);

        assertEquals(List.of(), myFixture.filterAvailableIntentions(CAPTURE_LOCAL));
    }

    // --- what the caret is answered with ---------------------------------------------------------

    public void testWithoutAClassFileTheSourceStillNamesTheCall() {
        myFixture.addFileToProject("fixture/Ledger.java", """
                package fixture;

                public final class Ledger {
                    public void commit() { }
                }
                """);
        configure("""
                package fixture;

                public final class Service {
                    public void charge(final Ledger ledger) {
                        ledger.com<caret>mit();
                    }
                }
                """);

        final List<WeaveSpot> spots = WeaveHereIntention.spotsFor(targetAtCaret(), elementAtCaret(),
                myFixture.getEditor().getDocument(), null);

        WeaveSpot call = null;
        for (final WeaveSpot spot : spots) {
            if (spot.operation() != null) {
                call = spot;
                break;
            }
        }
        assertNotNull("a project that has not been built is every sample and every scratch file, "
                + "and answering all of them with the head of the method is not an answer", call);
        assertEquals("fixture.Ledger.commit()", call.operation().target());
        assertEquals("no instruction was counted, so no ordinal is claimed",
                -1, call.operation().ordinal());
        assertEquals("and the injection insists on the one position the source shows, so the build "
                + "fails rather than the handler binding elsewhere", 1, call.matches());
    }

    public void testAnUnresolvableCallFallsBackToPositions() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge(final ThisTypeDoesNotExist thing) {
                        thing.com<caret>mit();
                    }
                }
                """);

        final List<WeaveSpot> spots = WeaveHereIntention.spotsFor(targetAtCaret(), elementAtCaret(),
                myFixture.getEditor().getDocument(), null);

        assertFalse("an author who asked 'here' is never answered with nothing", spots.isEmpty());
        for (final WeaveSpot spot : spots) {
            assertNull("without an owner there is no selector, and inventing one would name a "
                            + "member that does not exist: " + spot.label(),
                    spot.operation());
        }
    }

    public void testThePreviewShowsTheHandlerRatherThanNothing() {
        myFixture.addFileToProject("fixture/Audit.java", weaveNamed("Audit"));
        configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        int x = <caret>1;
                    }
                }
                """);

        final String preview =
                myFixture.getIntentionPreviewText(myFixture.findSingleIntention(WEAVE_HERE));

        assertNotNull("highlighting the entry has to show something", preview);
        // Qualified, because that is what the generator produces — the names are shortened by the
        // code style manager as the handler is inserted, which a preview has not reached.
        assertTrue("the handler is what would be written: " + preview,
                preview.contains("weaver.api.Inject("));
        assertTrue("naming the method the caret was in: " + preview,
                preview.contains("method = \"charge()\""));
    }

    public void testThePreviewResolvesTheOriginalFile() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge(final StringBuilder text) {
                        text.appen<caret>d("x");
                    }
                }
                """);
        final PsiFile copy = IntentionPreviewUtils.obtainCopyForPreview(myFixture.getFile());
        assertFalse("the platform hands previews a copy, which is the whole problem",
                copy.isPhysical());

        final PsiMethod resolved =
                WeaveHereIntention.targetForPreview(copy, myFixture.getCaretOffset());

        assertNotNull("the caret is inside a weavable method", resolved);
        assertTrue("a copy has no compiler output behind it, so resolving there can only ever "
                        + "answer HEAD",
                resolved.isPhysical());
        assertSame("and it has to be the very method the editor is showing",
                targetAtCaret(), resolved);
    }

    // --- what it writes --------------------------------------------------------------------------

    public void testItCreatesTheWeaveAndWritesIntoIt() {
        configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        int x = <caret>1;
                    }
                }
                """);
        insertInto(null);

        final PsiFile weave = fileNamed("ServiceWeave.java");
        assertNotNull("a target with no weave has one made for it, rather than the action refusing "
                + "and leaving the author to make it by hand", weave);
        final String written = weave.getText();
        assertTrue("the weave names its target: " + written,
                written.contains("Weave(Service.class)"));
        assertTrue("and carries the generated handler: " + written, written.contains("@Inject"));
        assertTrue("and names the method the caret was in: " + written,
                written.contains("charge()"));
    }

    public void testTheTargetFileIsLeftAlone() {
        final PsiFile file = configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        int x = <caret>1;
                    }
                }
                """);
        final String before = file.getText();
        insertInto(null);

        assertEquals("a weave never edits its target's source, and neither does the action that "
                        + "writes one",
                before, file.getText());
    }

    public void testTheChosenWeaveIsTheOneWrittenInto() {
        myFixture.addFileToProject("fixture/Audit.java", weaveNamed("Audit"));
        myFixture.addFileToProject("fixture/Timing.java", weaveNamed("Timing"));
        configure("""
                package fixture;

                public final class Service {
                    public void charge() {
                        int x = <caret>1;
                    }
                }
                """);
        insertInto(classNamed("fixture.Timing"));

        assertTrue("the handler goes where it was told to: " + sourceOf("fixture.Timing"),
                sourceOf("fixture.Timing").contains("@Inject"));
        assertFalse("and nowhere else — a project with two weaves on a class has them for a "
                        + "reason, and putting an audit handler in the timing weave is the one "
                        + "mistake here the preview cannot show: " + sourceOf("fixture.Audit"),
                sourceOf("fixture.Audit").contains("@Inject"));
        assertNull("nor is a third weave made beside them", fileNamed("ServiceWeave.java"));
    }

    // --- the harness -----------------------------------------------------------------------------

    private void insertInto(final PsiClass weave) {
        HandlerInsertion.into(getProject(), myFixture.getEditor(), weave, targetAtCaret(), null,
                List.of(), null, HandlerOptions.defaults());
    }

    private PsiElement elementAtCaret() {
        final PsiElement element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull("the fixture must put the caret on something", element);
        return element;
    }

    private PsiMethod targetAtCaret() {
        final PsiMethod target = WeaveHereIntention.targetAt(elementAtCaret());
        assertNotNull("the fixture must put the caret inside a weavable method", target);
        return target;
    }

    private String sourceOf(final String qualified) {
        return classNamed(qualified).getContainingFile().getText();
    }

    private static String weaveNamed(final String name) {
        return """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Service.class)
                public final class %s {
                }
                """.formatted(name);
    }

    private PsiFile configure(final String source) {
        return myFixture.configureByText("Service.java", source);
    }

    private PsiFile fileNamed(final String name) {
        final PsiDirectory directory = myFixture.getFile().getContainingDirectory();
        for (final PsiFile sibling : directory.getFiles()) {
            if (name.equals(sibling.getName())) {
                return sibling;
            }
        }
        return null;
    }

    private PsiClass classNamed(final String qualified) {
        final PsiClass found = com.intellij.psi.JavaPsiFacade.getInstance(getProject())
                .findClass(qualified,
                        com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare " + qualified, found);
        return found;
    }
}
