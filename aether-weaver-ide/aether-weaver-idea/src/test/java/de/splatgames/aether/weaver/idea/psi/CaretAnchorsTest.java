package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.idea.bytecode.SourceAnchor;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;

import java.util.ArrayList;
import java.util.List;

public class CaretAnchorsTest extends BasePlatformTestCase {

    public void testTheChainRunsInnermostFirst() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    void charge() {
                        send(bui<caret>ld("x"));
                    }

                    void send(final String payload) { }

                    String build(final String seed) { return seed; }
                }
                """);

        assertEquals("standing in the argument still has to reach the call around it: "
                + names(reading), List.of("build", "send"), names(reading));
        assertEquals("the innermost is depth zero, which is what ranks it first",
                0, reading.anchors().getFirst().depth());
    }

    public void testAFieldInAQualifierIsItsOwnAnchor() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    private final Ledger ledger = new Ledger();

                    void charge() {
                        this.led<caret>ger.commit();
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """);

        assertEquals("a caret on the field is on a field access, and the call around it is the next "
                        + "answer: " + kinds(reading),
                List.of(SourceAnchor.Kind.FIELD_ACCESS, SourceAnchor.Kind.CALL), kinds(reading));
    }

    public void testTheSecondCallOnALineKnowsItIsTheSecond() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    void charge(final StringBuilder text) {
                        text.append("a"); text.app<caret>end("b");
                    }
                }
                """);

        assertEquals("append", reading.anchors().getFirst().name());
        assertEquals("without this the two are indistinguishable and the tool would attach to "
                        + "whichever the compiler emitted first, half the time wrongly",
                1, reading.anchors().getFirst().occurrence());
    }

    public void testANestedCallCompletesFirst() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    void charge() {
                        wrap(wr<caret>ap("x"));
                    }

                    String wrap(final String seed) { return seed; }
                }
                """);

        final List<SourceAnchor> anchors = reading.anchors();
        assertEquals("both are calls to wrap", List.of("wrap", "wrap"), names(reading));
        assertEquals("the inner one runs first, so it is the first instruction",
                0, anchors.get(0).occurrence());
        assertEquals("and the outer one is the second — read in source order it would be the "
                        + "first, which is the wrong instruction",
                1, anchors.get(1).occurrence());
    }

    public void testALoopIsReportedAsTheRegion() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    void charge(final StringBuilder text) {
                        text.append("begin");
                        for (int index = 0; index < 3; index++) {
                            text.app<caret>end("x");
                        }
                    }
                }
                """);

        assertTrue("a caret inside a loop stands in a region the author can see, and an ordinal "
                + "counted inside it survives an edit outside it", reading.hasRegion());
        assertTrue("the region has to contain the caret: " + reading,
                reading.regionFirstLine() <= reading.caretLine()
                        && reading.regionLastLine() >= reading.caretLine());
        assertTrue("and it must not be the whole method, which would bound nothing",
                reading.regionFirstLine() > 1);
    }

    public void testStraightLineCodeHasNoRegion() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    void charge(final StringBuilder text) {
                        text.app<caret>end("x");
                    }
                }
                """);

        assertFalse("a slice spanning the whole method bounds nothing", reading.hasRegion());
    }

    public void testALiteralIsDescribedAsTheSelectorWouldWriteIt() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    void charge() {
                        String tag = "ret<caret>ry";
                    }
                }
                """);

        assertEquals(List.of(SourceAnchor.Kind.CONSTANT), kinds(reading));
        assertEquals("rendered by the API's own ConstantSelector, because a second spelling "
                        + "would compare unequal to the one written into the annotation",
                "string:\"retry\"", reading.anchors().getFirst().constant());
    }

    public void testAReturnIsItsOwnAnchor() {
        final SpotFinder.Reading reading = read("""
                package fixture;

                public final class Service {
                    int charge() {
                        ret<caret>urn 1;
                    }
                }
                """);

        assertTrue("standing on a return is how an author says they mean the way out: "
                + kinds(reading), kinds(reading).contains(SourceAnchor.Kind.RETURN));
    }

    // --- the harness -----------------------------------------------------------------------------

    private SpotFinder.Reading read(final String source) {
        myFixture.configureByText("Service.java", source);
        final PsiElement element =
                myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull("the fixture must put the caret on something", element);
        final PsiMethod target = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        assertNotNull("the caret must be inside a method", target);
        return CaretAnchors.at(element, target, myFixture.getEditor().getDocument());
    }

    private static List<String> names(final SpotFinder.Reading reading) {
        final List<String> names = new ArrayList<>();
        for (final SourceAnchor anchor : reading.anchors()) {
            names.add(anchor.name());
        }
        return names;
    }

    private static List<SourceAnchor.Kind> kinds(final SpotFinder.Reading reading) {
        final List<SourceAnchor.Kind> kinds = new ArrayList<>();
        for (final SourceAnchor anchor : reading.anchors()) {
            kinds.add(anchor.kind());
        }
        return kinds;
    }
}
