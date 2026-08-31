package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;

import java.util.ArrayList;
import java.util.List;

public class SourceSpotsTest extends BasePlatformTestCase {

    public void testACallIsOfferedWithoutAClassFile() {
        final List<WeaveSpot> spots = spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.com<caret>mit();
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """);

        final WeaveSpot spot = firstOperationOf(spots);
        assertNotNull("without this the only answer was HEAD, in every unbuilt project", spot);
        assertEquals(Point.INVOKE, spot.point());
        assertNotNull(spot.operation());
        assertEquals("the editor resolved the call, so the selector is exact",
                "fixture.Ledger.commit()", spot.operation().target());
    }

    public void testNoOrdinalIsInvented() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.com<caret>mit();
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """));

        assertNotNull(spot);
        assertNotNull(spot.operation());
        assertEquals("an ordinal counted in source order is a claim about bytecode nobody has "
                        + "read — the compiler emits calls no source shows and numbers them its own "
                        + "way, so a number here binds the handler to whatever happens to land on it",
                -1, spot.operation().ordinal());
        assertEquals("and there is no instruction stream for an index to point into",
                -1, spot.operation().index());
    }

    public void testASingleCallInsistsOnExactlyOnePosition() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.begin();
                        ledger.com<caret>mit();
                    }
                }

                final class Ledger {
                    void begin() { }

                    void commit() { }
                }
                """));

        assertNotNull(spot);
        assertEquals("this is what makes an ordinal unnecessary: one match, or the build fails",
                1, spot.matches());
    }

    public void testSeveralCallsAreCounted() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.commit();
                        ledger.com<caret>mit();
                        ledger.commit();
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """));

        assertNotNull(spot);
        assertEquals("claiming exactly one here would be a build failure on correct code, and "
                        + "picking one of the three would be a guess about which instruction the "
                        + "caret meant — the honest answer is all of them, said out loud",
                3, spot.matches());
        assertTrue("and the row has to say so: " + spot.why(), spot.why().contains("every one"));
    }

    public void testACaretInsideNoExpressionStillFindsItsLine() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger, final int mode) {
                        i<caret>f (mode == 1) {
                            ledger.commit();
                        }
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """));

        assertNotNull("the bytecode side has always answered this with the caret's line; without "
                + "the same fallback the source side answered it with nothing, and nothing is a "
                + "dialog that looks like it only knows HEAD", spot);
        assertTrue("and the row says it came from near the caret rather than from it: "
                + spot.why(), spot.why().contains("near the caret"));
    }

    public void testACaretOnAClosingBraceFallsBack() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.commit();
                    <caret>}
                }

                final class Ledger {
                    void commit() { }
                }
                """));

        assertNotNull("a caret on a closing brace is where a reader ends up constantly", spot);
    }

    public void testNoSliceIsDerivedFromSourceAlone() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.begin();
                        for (int index = 0; index < 3; index++) {
                            ledger.com<caret>mit();
                        }
                        ledger.end();
                    }
                }

                final class Ledger {
                    void begin() { }

                    void commit() { }

                    void end() { }
                }
                """));

        assertNotNull(spot);
        assertFalse("a slice's bounds are two more @At queries needing ordinals of their own, and "
                + "two guesses do not make a certainty", spot.isNarrowable());
    }

    public void testARedirectIsNotOffered() {
        final WeaveSpot spot = firstOperationOf(spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.com<caret>mit();
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """));

        assertNotNull(spot);
        assertNotNull(spot.operation());
        assertFalse("the signature a redirect must have mirrors the operation it replaces, and that "
                + "shape comes from the engine reading an instruction", spot.operation().isRedirectable());
    }

    public void testThePositionsAreStillOffered() {
        final List<Point> points = new ArrayList<>();
        for (final WeaveSpot spot : spotsIn("""
                package fixture;

                public final class Service {
                    void charge(final Ledger ledger) {
                        ledger.com<caret>mit();
                    }
                }

                final class Ledger {
                    void commit() { }
                }
                """)) {
            points.add(spot.point());
        }

        assertTrue(points.contains(Point.HEAD));
        assertTrue(points.contains(Point.RETURN));
        assertTrue(points.contains(Point.TAIL));
    }

    // --- the harness -----------------------------------------------------------------------------

    private List<WeaveSpot> spotsIn(final String source) {
        myFixture.configureByText("Service.java", source);
        final PsiElement element =
                myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull("the fixture must put the caret on something", element);
        final PsiMethod target = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        assertNotNull("the caret must be inside a method", target);
        return SourceSpots.at(target,
                CaretAnchors.at(element, target, myFixture.getEditor().getDocument()),
                myFixture.getEditor().getDocument(), TargetOperations.Spelling.QUALIFIED);
    }

    private static WeaveSpot firstOperationOf(final List<WeaveSpot> spots) {
        for (final WeaveSpot spot : spots) {
            if (spot.operation() != null) {
                return spot;
            }
        }
        return null;
    }

    public void testTheFixtureReallyHasNoClassFile() {
        assertNull("a headless fixture has no compiler output, which is the state this whole file "
                        + "is about — if that ever changed, every assertion here would be about the "
                        + "wrong code path",
                SpotFinder.positions().getFirst().operation());
    }
}
