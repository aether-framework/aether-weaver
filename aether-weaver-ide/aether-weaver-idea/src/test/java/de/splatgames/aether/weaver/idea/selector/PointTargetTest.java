package de.splatgames.aether.weaver.idea.selector;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.idea.inspection.PointTargetInspection;

import java.util.ArrayList;
import java.util.List;

public class PointTargetTest extends BasePlatformTestCase {

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
                String custom() default "";
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

    private static final String LEDGER = """
            package fixture;

            public final class Ledger {
                public int entries;

                public void flush() { }

                public void flush(int count) { }

                public void commit() { }
            }
            """;

    private static final String SERVICE = """
            package fixture;

            public final class Service {
                public void charge(Ledger ledger) {
                    ledger.flush();
                    ledger.commit();
                }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/At.java", AT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Point.java", POINT);
        myFixture.addFileToProject("fixture/Ledger.java", LEDGER);
        myFixture.addFileToProject("fixture/Service.java", SERVICE);
    }

    // --- navigation ------------------------------------------------------------------------------

    public void testATargetLeadsToTheCalledMethod() {
        final PsiElement resolved = resolve("Ledger.flush()");

        assertTrue("expected a method, got " + resolved, resolved instanceof PsiMethod);
        assertEquals("flush", ((PsiMethod) resolved).getName());
        assertEquals("Ledger", ((PsiMethod) resolved).getContainingClass().getName());
    }

    public void testAQualifiedOwnerResolves() {
        assertTrue(resolve("fixture.Ledger.commit()") instanceof PsiMethod);
    }

    public void testTheDescriptorFormResolves() {
        final PsiElement resolved = resolve("desc:fixture/Ledger.commit()V");

        assertTrue("the form the API documents as naming exactly one member has to be the one that "
                + "navigates: " + resolved, resolved instanceof PsiMethod);
        assertEquals("commit", ((PsiMethod) resolved).getName());
    }

    public void testAFieldTargetLeadsToTheField() {
        final PsiElement resolved = resolve("Point.FIELD", "Ledger.entries");

        assertTrue("expected a field, got " + resolved, resolved instanceof PsiField);
        assertEquals("entries", ((PsiField) resolved).getName());
    }

    public void testANameOnlyTargetResolvesToNothing() {
        assertNull("'#flush' says 'whichever class declares it', and the class it binds to is "
                        + "whatever the target method happens to call. Resolving it against the "
                        + "weave's own target would be right occasionally and confidently wrong the "
                        + "rest of the time",
                resolve("#flush"));
    }

    public void testTheReferenceCoversOnlyTheName() {
        final PsiReference reference = referenceTo("Ledger.flush(int)");
        assertNotNull(reference);

        assertEquals("a range over the whole literal would turn renaming flush into replacing "
                        + "\"Ledger.flush(int)\" wholesale — owner and signature gone, in a "
                        + "refactoring the user believed was safe",
                "flush", reference.getRangeInElement().substring(reference.getElement().getText()));
    }

    public void testAParameterNamedLikeTheMemberIsNotTheName() {
        final PsiReference reference = referenceTo("Ledger.flush(fixture.flush)");
        assertNotNull(reference);

        assertEquals("'flush' occurs twice in that target and only the first is the member; "
                        + "rewriting the other one is a rename that changes a parameter type",
                "Ledger.".length() + 1, reference.getRangeInElement().getStartOffset());
    }

    // --- the inspection --------------------------------------------------------------------------

    public void testACorrectTargetIsSilent() {
        assertEquals("an inspection that fired on every target would satisfy every other assertion "
                        + "in this section; this is the one that would not",
                List.of(), codesIn("Point.INVOKE", "Ledger.flush()"));
    }

    public void testAMissingMemberIsReported() {
        assertEquals(List.of("AW1043"), codesIn("Point.INVOKE", "Ledger.drain()"));
    }

    public void testAnUnknownSignatureIsNotReported() {
        assertEquals("comparing a written parameter list with a declared one means comparing "
                        + "erasures, and that is where a plugin and a compiler part company",
                List.of(), codesIn("Point.INVOKE", "Ledger.flush(long)"));
    }

    public void testAnUnresolvableOwnerIsSilent() {
        assertEquals("the target method may well call a class the weave's own module never sees",
                List.of(), codesIn("Point.INVOKE", "com.elsewhere.Gateway.send()"));
    }

    public void testANameOnlyTargetIsSilent() {
        assertEquals(List.of(), codesIn("Point.INVOKE", "#flush"));
    }

    public void testATargetOnAPositionalPointIsReported() {
        assertEquals(List.of("AW1043"), codesIn("Point.HEAD", "Ledger.flush()"));
    }

    public void testAMissingTargetIsReported() {
        myFixture.enableInspections(new PointTargetInspection());
        myFixture.configureByText("Audit.java", weaveWith("@At(Point.INVOKE)"));

        assertEquals(List.of("AW1043"), reported());
    }

    public void testAPositionalPointWithoutATargetIsSilent() {
        myFixture.enableInspections(new PointTargetInspection());
        myFixture.configureByText("Audit.java", weaveWith("@At(Point.HEAD)"));

        assertEquals(List.of(), reported());
    }

    public void testAMalformedTargetIsReportedWithItsCode() {
        final List<String> codes = codesIn("Point.INVOKE", "Ledger.flush(");

        assertEquals("" + codes, 1, codes.size());
        assertTrue("the parser's own AW10xx, so a user who searches for it finds one explanation "
                + "rather than two: " + codes, codes.getFirst().startsWith("AW10"));
    }

    // --- the intentions --------------------------------------------------------------------------

    public void testTheTargetConvertsToDescriptorForm() {
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = Point.INVOKE, target = \"Ledger.com<caret>mit()\")"));
        final IntentionAction convert =
                myFixture.findSingleIntention("Convert the target to descriptor form");
        myFixture.launchAction(convert);

        assertTrue("the owner and the descriptor come from the resolved method, not from the text: "
                        + myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"desc:fixture/Ledger.commit()V\""));
    }

    public void testTheTargetConvertsToSourceForm() {
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = Point.INVOKE, target = \"desc:fixture/Ledger.com<caret>mit()V\")"));
        final IntentionAction convert =
                myFixture.findSingleIntention("Convert the target to source form");
        myFixture.launchAction(convert);

        assertTrue(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"fixture.Ledger.commit():void\""));
    }

    public void testAnAmbiguousTargetIsNotConverted() {
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = Point.INVOKE, target = \"Ledger.flu<caret>sh\")"));

        assertTrue("Ledger declares two flush methods, and picking one of them is a narrowing "
                        + "decision the author makes — not a change of spelling an intention "
                        + "performs for them",
                myFixture.filterAvailableIntentions("Convert the target to descriptor form")
                        .isEmpty());
    }

    // --- completion ------------------------------------------------------------------------------

    public void testTheOwnersMembersAreOffered() {
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = Point.INVOKE, target = \"Ledger.<caret>\")"));
        final List<String> offered = completions();

        assertTrue("the members of the class the target already names are the only things that can "
                        + "follow it, whatever the build state is: " + offered,
                offered.contains("Ledger.flush") && offered.contains("Ledger.commit"));
    }

    public void testCompletingReplacesTheWrittenOwner() {
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = Point.INVOKE, target = \"Ledger.com<caret>\")"));
        myFixture.completeBasic();

        final String written = myFixture.getFile().getText();
        assertTrue("an entry is a whole target, so inserting it over a prefix of the member name "
                        + "alone would produce \"Ledger.Ledger.commit\": " + written,
                written.contains("\"Ledger.commit\""));
    }

    // --- the harness -----------------------------------------------------------------------------

    private PsiElement resolve(final String target) {
        return resolve("Point.INVOKE", target);
    }

    private PsiElement resolve(final String point, final String target) {
        final PsiReference reference = referenceTo(point, target);
        return reference == null ? null : reference.resolve();
    }

    private PsiReference referenceTo(final String target) {
        return referenceTo("Point.INVOKE", target);
    }

    private PsiReference referenceTo(final String point, final String target) {
        // Placed one character into the member name, found the way the plugin finds it: after the
        // owner's last separator and before whatever begins the signature. Using the last dot in
        // the whole string would put the caret inside a qualified parameter type instead.
        final int end = target.indexOf('(') < 0 ? target.length() : target.indexOf('(');
        final int name = Math.max(target.lastIndexOf('.', end - 1),
                target.lastIndexOf('/', end - 1)) + 1;
        final String caret = target.substring(0, name + 1) + "<caret>" + target.substring(name + 1);
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = " + point + ", target = \"" + caret + "\")"));
        return myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
    }

    private List<String> codesIn(final String point, final String target) {
        myFixture.enableInspections(new PointTargetInspection());
        myFixture.configureByText("Audit.java",
                weaveWith("@At(value = " + point + ", target = \"" + target + "\")"));
        return reported();
    }

    private List<String> reported() {
        final List<String> codes = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            final String description = info.getDescription();
            if (description != null && description.startsWith("AW")) {
                codes.add(description.substring(0, description.indexOf(':')));
            }
        }
        return codes;
    }

    private List<String> completions() {
        myFixture.completeBasic();
        final List<String> offered = myFixture.getLookupElementStrings();
        return offered == null ? List.of() : offered;
    }

    private static String weaveWith(final String at) {
        return """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Service.class)
                public final class Audit {

                    @Inject(method = "charge(Ledger)", at = %s)
                    void onFlush() { }
                }
                """.formatted(at);
    }
}
