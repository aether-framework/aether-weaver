package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Inlay;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeaveInlayTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                int priority() default 0;
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
                At[] at() default {};
            }
            """;

    private static final String REDIRECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Redirect {
                String method();
                At at();
            }
            """;

    private static final String AT = """
            package de.splatgames.aether.weaver.api;

            public @interface At {
                Point value() default Point.HEAD;
                String target() default "";
                int ordinal() default -1;
                Shift shift() default Shift.NONE;
                Access access() default Access.ANY;
            }
            """;

    private static final String ACCESS = """
            package de.splatgames.aether.weaver.api;

            public enum Access { ANY, GET, PUT, STATIC_GET, STATIC_PUT }
            """;

    private static final String SHIFT = """
            package de.splatgames.aether.weaver.api;

            public enum Shift { NONE, BEFORE, AFTER }
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
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shift.java", SHIFT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Redirect.java", REDIRECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Access.java", ACCESS);
    }

    public void testTheInjectedCodeIsShownAtTheHead() {
        weave("HEAD", """
                        System.out.println("charging");
                        audit.record("charge");
                """);

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """);

        assertEquals("one handler at one point is one block: " + headers(shown), 1, shown.size());
        final WeaveBlock block = shown.getFirst();
        assertEquals("one point is one section", 1, block.sections().size());
        final String header = block.sections().getFirst().header();
        assertTrue("the header must name the handler and the point: " + header,
                header.contains("Audit.onCharge()") && header.contains("@HEAD"));
        assertTrue("the handler's own code is the point of the whole feature: " + text(block),
                text(block).contains("System.out.println(\"charging\")"));
        assertTrue("every line of it, not just the first: " + text(block),
                text(block).contains("audit.record(\"charge\")"));
    }

    public void testTheBlockSitsAtTheFirstStatement() {
        weave("HEAD", "        System.out.println(\"charging\");\n");

        final String source = """
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals(1, shown.size());
        assertEquals("anchored at the first statement, so the reader sees it run before that line "
                        + "rather than at some other place in the method",
                lineStartOf(source, "return \"x\""), shown.getFirst().offset());
    }

    public void testAReturnHandlerIsShownAtTheReturn() {
        weave("RETURN", "        System.out.println(\"charged\");\n");

        final String source = """
                package fixture;

                public class Target {
                    public String charge() {
                        int computed = 1;
                        return "x" + computed;
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("" + headers(shown), 1, shown.size());
        assertTrue(headers(shown).toString(),
                shown.getFirst().sections().getFirst().header().contains("@RETURN"));
        assertEquals("at the return, not at the entry — the difference is the whole information",
                lineStartOf(source, "return \"x\""), shown.getFirst().offset());
    }

    public void testInvokeIsShownAtTheMatchingCall() {
        invokeWeave("helper", "", "");

        final String source = """
                package fixture;

                public class Target {
                    public String charge() {
                        other();
                        return helper();
                    }

                    String helper() { return "x"; }

                    void other() { }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("only the call the selector names, not every call in the method: "
                + headers(shown), 1, shown.size());
        assertTrue(headers(shown).toString(),
                shown.getFirst().sections().getFirst().header().contains("@INVOKE"));
        assertEquals("anchored on the line the call is written on",
                lineStartOf(source, "return helper()"), shown.getFirst().offset());
    }

    public void testEveryMatchingCallIsShown() {
        invokeWeave("helper", "", "");

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        helper();
                        helper();
                        return "x";
                    }

                    String helper() { return "x"; }
                }
                """);

        assertEquals("ordinal defaults to -1, which is every match: " + headers(shown),
                2, shown.size());
    }

    public void testAnOrdinalSilencesInvoke() {
        invokeWeave("helper", ", ordinal = 0", "");

        assertEquals("an ordinal counts instructions, and the source's first call is not "
                        + "necessarily the bytecode's first: string concatenation, boxing and an "
                        + "enhanced for loop all put calls in the bytecode that are nowhere in the "
                        + "text. The engine answers this against a class file; here there is none, "
                        + "so the answer is still nothing",
                List.of(), show("""
                        package fixture;

                        public class Target {
                            public String charge() {
                                return helper();
                            }

                            String helper() { return "x"; }
                        }
                        """));
    }

    public void testANestedOwnerIsShown() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {

                    @Inject(method = "charge",
                            at = @At(value = Point.FIELD,
                                     target = "fixture.Target$Mode.STRICT"))
                    void onStrict() {
                        System.out.println("strict");
                    }
                }
                """);

        final String source = """
                package fixture;

                public class Target {

                    public enum Mode { STRICT, LOOSE }

                    public String charge() {
                        Mode chosen = Mode.STRICT;
                        return "x";
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("the only spelling that binds at weave time must be the one the editor "
                        + "recognises, or a working weave is invisible: " + headers(shown),
                1, shown.size());
        assertEquals("anchored on the access it names",
                lineStartOf(source, "Mode chosen = Mode.STRICT"), shown.getFirst().offset());
    }

    public void testAShiftSilencesInvoke() {
        // At.Shift, not Shift: the enum is nested, and there is no top-level Shift to import.
        // The fixture used to import one that does not exist. Nothing failed, because the reader
        // takes the reference's last name without resolving it — so the fixture was wrong in a way
        // this assertion could never have shown.
        invokeWeave("helper", ", shift = At.Shift.AFTER", "");

        assertEquals(List.of(), show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return helper();
                    }

                    String helper() { return "x"; }
                }
                """));
    }

    public void testAConstantIsShownAtItsLiteral() {
        pointWeave("CONSTANT", ", target = \"int:7\"");

        final String source = """
                package fixture;

                public class Target {
                    public String charge(final int amount) {
                        int limit = 7;
                        return "x" + limit + amount;
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("a literal initialising a local is loaded as itself: " + headers(shown),
                1, shown.size());
        assertEquals("anchored on the literal the load comes from",
                lineStartOf(source, "int limit = 7"), shown.getFirst().offset());
    }

    public void testAFoldedConstantIsSilent() {
        pointWeave("CONSTANT", ", target = \"int:2\"");

        assertEquals("the compiler computes 2 * 3 and loads 6 — a block on the 2 would tell the "
                        + "reader their handler runs at an instruction that does not exist",
                List.of(), show("""
                package fixture;

                public class Target {
                    public String charge() {
                        int limit = 2 * 3;
                        return "x" + limit;
                    }
                }
                """));
    }

    public void testACaseLabelIsSilent() {
        pointWeave("CONSTANT", ", target = \"int:7\"");

        assertEquals("a switch's labels are its jump table, and nothing loads them",
                List.of(), show("""
                package fixture;

                public class Target {
                    public String charge(final int amount) {
                        switch (amount) {
                            case 7:
                                return "seven";
                            default:
                                return "other";
                        }
                    }
                }
                """));
    }

    public void testASliceIsSilentWithoutACompiledTarget() {
        invokeWeave("helper", "", """
                , slice = @Slice(
                        from = @At(value = Point.INVOKE, target = "begin", ordinal = 0),
                        to = @At(value = Point.INVOKE, target = "end", ordinal = 0))""");

        assertEquals(List.of(), show("""
                package fixture;

                public class Target {
                    public String charge() {
                        begin();
                        helper();
                        end();
                        return "x";
                    }

                    void begin() {}
                    String helper() { return "x"; }
                    void end() {}
                }
                """));
    }

    public void testANonMatchingCallIsNotShown() {
        invokeWeave("nosuchmethod", "", "");

        assertEquals(List.of(), show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return helper();
                    }

                    String helper() { return "x"; }
                }
                """));
    }

    public void testAMismatchedSignatureIsNotShown() {
        invokeWeave("helper(int)", "", "");

        assertEquals("matching on the name alone would mark the wrong overload, which is a false "
                        + "statement about where code runs",
                List.of(), show("""
                        package fixture;

                        public class Target {
                            public String charge() {
                                return helper("a");
                            }

                            String helper(String what) { return what; }
                        }
                        """));
    }

    public void testWithoutAWeaveNothingIsShown() {
        assertEquals("if this were non-empty the assertions above would prove nothing",
                List.of(), show("""
                        package fixture;

                        public class Target {
                            public String charge() {
                                return "x";
                            }
                        }
                        """));
    }

    public void testHeadIsShownBeforeReturnOnTheSameStatement() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {

                    @Inject(method = "charge", at = @At(Point.HEAD))
                    void first() {
                        System.out.println("entering");
                    }

                    @Inject(method = "charge", at = @At(Point.RETURN))
                    void last() {
                        System.out.println("leaving");
                    }
                }
                """);

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """);

        assertEquals("both points share one statement, so they share one block — their order is "
                        + "then this plugin's to state, not the platform's: " + headers(shown),
                1, shown.size());
        final List<WeaveBlock.Section> sections = shown.getFirst().sections();
        assertEquals("two points, two sections", 2, sections.size());
        assertTrue("HEAD runs first, so it must be shown first — the order is not decoration, it "
                        + "is a statement about when the code runs: " + headers(shown),
                sections.get(0).header().contains("@HEAD"));
        assertTrue(headers(shown).toString(), sections.get(1).header().contains("@RETURN"));
    }

    public void testTheBlockStaysInsideAnEmptyBody() {
        weave("HEAD", "        System.out.println(\"settling\");\n");

        final String source = """
                package fixture;

                public class Target {
                    public void charge() {
                        // nothing yet
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("" + headers(shown), 1, shown.size());
        assertEquals("anchored on what is inside the braces, not on the body itself — the body's "
                        + "own offset is its opening brace, which sits on the signature line, and "
                        + "a block drawn there reads as running before the method is entered",
                lineStartOf(source, "// nothing yet"), shown.getFirst().offset());
    }

    public void testHandlersAreListedInExecutionOrder() {
        myFixture.addFileToProject("fixture/Zebra.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Zebra {
                    @Inject(method = "charge", at = @At(Point.HEAD))
                    void onCharge() { System.out.println("zebra"); }
                }
                """);
        myFixture.addFileToProject("fixture/Alpha.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Alpha {
                    @Inject(method = "charge", at = @At(Point.HEAD))
                    void onCharge() { System.out.println("alpha"); }
                }
                """);

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """);

        assertEquals("" + headers(shown), 1, shown.size());
        final String header = shown.getFirst().sections().getFirst().header();
        assertTrue("equal priority breaks on the weave's class name, ascending, exactly as the "
                        + "engine's OrderKey does — and it must not depend on which file the index "
                        + "happened to hand over first: " + header,
                header.indexOf("Alpha.onCharge()") < header.indexOf("Zebra.onCharge()"));
    }

    public void testHigherPriorityIsListedFirst() {
        myFixture.addFileToProject("fixture/Alpha.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(value = Target.class, priority = 0)
                public final class Alpha {
                    @Inject(method = "charge", at = @At(Point.HEAD))
                    void onCharge() { System.out.println("alpha"); }
                }
                """);
        myFixture.addFileToProject("fixture/Zebra.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(value = Target.class, priority = 100)
                public final class Zebra {
                    @Inject(method = "charge", at = @At(Point.HEAD))
                    void onCharge() { System.out.println("zebra"); }
                }
                """);

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """);

        final String header = shown.getFirst().sections().getFirst().header();
        assertTrue("priority beats the alphabet, and it beats it descending — a higher number runs "
                        + "first: " + header,
                header.indexOf("Zebra.onCharge()") < header.indexOf("Alpha.onCharge()"));
    }

    public void testASecondPassKeepsTheSameInlays() {
        weave("HEAD", "        System.out.println(\"charging\");\n");

        myFixture.configureByText("Target.java", """
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }

                    public String other() {
                        return "y";
                    }
                }
                """);

        myFixture.doHighlighting();
        final List<Inlay<?>> first = ourInlays();
        assertFalse("nothing below means anything if the first pass produced no inlays",
                first.isEmpty());

        myFixture.doHighlighting();
        final List<Inlay<?>> second = ourInlays();

        assertEquals("the same number", first.size(), second.size());
        for (int index = 0; index < first.size(); index++) {
            assertSame("the very same inlay, not an equal one: keeping it is the whole fix",
                    first.get(index), second.get(index));
        }
    }

    public void testABlockThatNoLongerAppliesIsDisposed() {
        weave("HEAD", "        System.out.println(\"charging\");\n");

        myFixture.configureByText("Target.java", """
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """);
        myFixture.doHighlighting();
        assertEquals(1, ourInlays().size());

        final Document document = myFixture.getEditor().getDocument();
        final int at = document.getText().indexOf("charge()");
        WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.replaceString(at, at + "charge".length(), "settled"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        myFixture.doHighlighting();
        assertEquals("the selector names nothing here any more, so nothing may be drawn here",
                List.of(), WeaveInlayPass.shownIn(myFixture.getEditor()));
    }

    public void testOverloadsDoNotMultiplyOnRefresh() {
        weave("RETURN", "        System.out.println(\"charged\");\n");

        myFixture.configureByText("Target.java", """
                package fixture;

                public class Target {
                    public String charge() {
                        return "a";
                    }

                    public String charge(String one) {
                        return "b";
                    }

                    public String charge(String one, String two) {
                        return "c";
                    }
                }
                """);

        myFixture.doHighlighting();
        final int first = ourInlays().size();
        assertEquals("one block per overload", 3, first);

        for (int pass = 0; pass < 4; pass++) {
            myFixture.doHighlighting();
            assertEquals("pass " + (pass + 2) + " must add nothing: refreshing is not a change, "
                            + "and a block that grows on every refresh buries the file",
                    first, ourInlays().size());
        }
    }

    public void testTwoReturnsDoNotMultiplyOnRefresh() {
        weave("RETURN", "        System.out.println(\"charged\");\n");

        myFixture.configureByText("Target.java", """
                package fixture;

                public class Target {
                    public String charge() {
                        if (System.nanoTime() > 0) {
                            return "a";
                        }
                        return "b";
                    }
                }
                """);

        myFixture.doHighlighting();
        assertEquals("one block per return", 2, ourInlays().size());

        for (int pass = 0; pass < 4; pass++) {
            myFixture.doHighlighting();
            assertEquals("pass " + (pass + 2) + " must add nothing", 2, ourInlays().size());
        }
    }

    private static int lineStartOf(final String source, final String needle) {
        final int found = source.indexOf(needle);
        assertTrue("the fixture must contain " + needle, found >= 0);
        return source.lastIndexOf('\n', found) + 1;
    }

    private List<Inlay<?>> ourInlays() {
        final List<Inlay<?>> ours = new ArrayList<>();
        for (final Inlay<?> inlay : myFixture.getEditor().getInlayModel()
                .getBlockElementsInRange(0, myFixture.getEditor().getDocument().getTextLength())) {
            if (inlay.getRenderer() instanceof WeaveBlockRenderer) {
                ours.add(inlay);
            }
        }
        return ours;
    }

    public void testARedirectIsListedOnce() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Redirect;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {

                    @Inject(method = "charge", at = @At(Point.RETURN))
                    void onAnyCharge() { System.out.println("any"); }

                    @Redirect(method = "charge(String,String)", at = @At(Point.HEAD))
                    void onRedirect() { System.out.println("redirected"); }
                }
                """);

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return "a";
                    }

                    public String charge(String one, String two) {
                        return "b";
                    }
                }
                """);

        final StringBuilder headers = new StringBuilder();
        for (final WeaveBlock block : shown) {
            for (final WeaveBlock.Section section : block.sections()) {
                headers.append(section.header()).append('\n');
            }
        }
        final String all = headers.toString();
        final int occurrences = all.split("onRedirect\\(\\)", -1).length - 1;
        assertEquals("the redirect must be listed once, not once per time the search happened to "
                + "hand it over: " + all, 1, occurrences);
    }

    public void testRepeatedCallsOnOneLineAreOneBlock() {
        invokeWeave("helper", "", "");

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return helper() + helper();
                    }

                    String helper() { return "x"; }
                }
                """);

        assertEquals("a block inlay is drawn above a line, so two on one line stack into what looks "
                        + "like a rendering fault: " + headers(shown), 1, shown.size());
        final String header = shown.getFirst().sections().getFirst().header();
        assertTrue("how often it applies is said in words, not by drawing the code twice: " + header,
                header.contains("\u00d72"));
    }

    public void testARedirectSaysItReplaces() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Redirect;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Redirect(method = "charge", at = @At(value = Point.INVOKE, target = "helper"))
                    void onRedirect() { System.out.println("redirected"); }
                }
                """);

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return helper();
                    }

                    String helper() { return "x"; }
                }
                """);

        assertEquals("" + headers(shown), 1, shown.size());
        final WeaveBlock.Section section = shown.getFirst().sections().getFirst();
        assertEquals("a redirect takes the operation out and puts a call in its place; drawn as an "
                        + "injection it claims the original still runs",
                WeaveBlock.Kind.REDIRECT, section.kind());
        assertTrue("the header carries the tag and nothing more: " + section.header(),
                section.header().contains("@INVOKE"));
        assertFalse("no prose in the header — that is what the hover and the colour are for: "
                + section.header(), section.header().contains("replaces"));
        assertTrue("and the sentence lives where it costs nobody anything: "
                        + section.explanation(),
                section.explanation().contains("replaces the call to helper"));
    }

    public void testTailIsShownForASingleExit() {
        pointWeave("TAIL", "");

        final String source = """
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("" + headers(shown), 1, shown.size());
        assertEquals(lineStartOf(source, "return \"x\""), shown.getFirst().offset());
    }

    public void testTailIsSilentWithSeveralReturns() {
        pointWeave("TAIL", "");

        assertEquals("TAIL is the last return in bytecode order, and bytecode order is not source "
                        + "order — a block on the wrong return says code runs on a path it never "
                        + "runs on",
                List.of(), show("""
                        package fixture;

                        public class Target {
                            public String charge() {
                                if (System.nanoTime() > 0) {
                                    return "a";
                                }
                                return "b";
                            }
                        }
                        """));
    }

    public void testInvokeAfterIsShownAtTheCall() {
        pointWeave("INVOKE_AFTER", ", target = \"helper\"");

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        return helper();
                    }

                    String helper() { return "x"; }
                }
                """);

        assertEquals("" + headers(shown), 1, shown.size());
        final WeaveBlock.Section section = shown.getFirst().sections().getFirst();
        assertTrue(section.header(), section.header().contains("@INVOKE_AFTER"));
        assertTrue(section.explanation(),
                section.explanation().contains("after the call to helper"));
    }

    public void testFieldIsShownAtTheAccess() {
        pointWeave("FIELD", ", target = \"state\"");

        final String source = """
                package fixture;

                public class Target {
                    int state;

                    public String charge() {
                        int read = state;
                        return "x";
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("" + headers(shown), 1, shown.size());
        assertEquals(lineStartOf(source, "int read = state"), shown.getFirst().offset());
    }

    public void testFieldRespectsTheAccessKind() {
        pointWeave("FIELD", ", target = \"state\", access = Access.PUT");

        final String source = """
                package fixture;

                public class Target {
                    int state;

                    public String charge() {
                        int read = state;
                        state = 1;
                        return "x";
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("only the write, not the read: " + headers(shown), 1, shown.size());
        assertEquals(lineStartOf(source, "state = 1"), shown.getFirst().offset());
    }

    public void testNewIsShownAtTheInstantiation() {
        myFixture.addFileToProject("fixture/Payment.java", """
                package fixture;

                public class Payment { }
                """);
        pointWeave("NEW", ", target = \"fixture.Payment\"");

        final String source = """
                package fixture;

                public class Target {
                    public String charge() {
                        Payment made = new Payment();
                        return "x";
                    }
                }
                """;
        final List<WeaveBlock> shown = show(source);

        assertEquals("" + headers(shown), 1, shown.size());
        assertEquals(lineStartOf(source, "new Payment"), shown.getFirst().offset());
    }

    public void testThrowIsShownAtEveryThrow() {
        pointWeave("THROW", "");

        final List<WeaveBlock> shown = show("""
                package fixture;

                public class Target {
                    public String charge() {
                        if (System.nanoTime() > 0) {
                            throw new IllegalStateException("a");
                        }
                        throw new IllegalArgumentException("b");
                    }
                }
                """);

        assertEquals("the point documents that an omitted target matches every throw: "
                + headers(shown), 2, shown.size());
    }

    public void testAnInlinedConstantIsNotFoundInSource() {
        pointWeave("CONSTANT", ", target = \"int:42\"");

        assertEquals("the load is real and the literal is somewhere else entirely; marking the "
                        + "field's declaration would put the block outside the target method",
                List.of(), show("""
                        package fixture;

                        public class Target {
                            private static final int LIMIT = 42;

                            public int charge(final int amount) {
                                return amount + LIMIT;
                            }
                        }
                        """));
    }

    private void pointWeave(final String point, final String extra) {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Access;
                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                    @Inject(method = "charge", at = @At(value = Point.%s%s))
                    void onPoint() {
                        System.out.println("here");
                    }
                }
                """.formatted(point, extra));
    }

    private void invokeWeave(final String target, final String extra, final String slice) {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Slice;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {

                    @Inject(method = "charge", at = @At(value = Point.INVOKE, target = "%s"%s)%s)
                    void onCall() {
                        System.out.println("intercepted");
                    }
                }
                """.formatted(target, extra, slice));
    }

    private PsiFile weave(final String point, final String body) {
        return myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.At;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Point;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {

                    Audit audit;

                    @Inject(method = "charge", at = @At(Point.%s))
                    void onCharge() {
                %s    }

                    void record(String what) { }
                }
                """.formatted(point, body));
    }

    private List<WeaveBlock> show(final String source) {
        myFixture.configureByText("Target.java", source);
        myFixture.doHighlighting();
        return WeaveInlayPass.shownIn(myFixture.getEditor());
    }

    private static String text(final WeaveBlock block) {
        final StringBuilder joined = new StringBuilder();
        for (final WeaveBlock.Section section : block.sections()) {
            for (final List<WeaveBlock.Fragment> line : section.lines()) {
                for (final WeaveBlock.Fragment fragment : line) {
                    joined.append(fragment.text());
                }
                joined.append('\n');
            }
        }
        return joined.toString();
    }

    private static List<String> headers(final List<WeaveBlock> blocks) {
        final List<String> headers = new ArrayList<>();
        for (final WeaveBlock block : blocks) {
            for (final WeaveBlock.Section section : block.sections()) {
                headers.add(section.header());
            }
        }
        return headers;
    }
}
