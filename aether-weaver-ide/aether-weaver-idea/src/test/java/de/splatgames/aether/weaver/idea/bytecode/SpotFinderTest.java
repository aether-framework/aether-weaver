package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.List;

public class SpotFinderTest extends TestCase {

    static final class Spots {

        private Spots() {
        }

        static String looped(final StringBuilder text, final int times) {
            text.append("begin");
            for (int index = 0; index < times; index++) {
                text.append("x");
                text.append("y");
            }
            text.append("end");
            return text.toString();
        }

        static String twice(final StringBuilder text) {
            text.append("a"); text.append("b");
            return text.toString();
        }
    }

    private static final TargetOperations.Spelling SPELLING =
            TargetOperations.Spelling.QUALIFIED;

    private static TargetView view;

    @Override
    protected void setUp() throws IOException {
        if (view != null) {
            return;
        }
        final String resource = '/' + Spots.class.getName().replace('.', '/') + ".class";
        final byte[] bytes;
        try (InputStream in = SpotFinderTest.class.getResourceAsStream(resource)) {
            assertNotNull("the fixture's own class file must be on the test classpath: " + resource,
                    in);
            bytes = in.readAllBytes();
        }
        view = ModelViews.of(ClassFile.of().parse(bytes));
    }

    public void testTheAnchorPicksItsOwnInstruction() {
        final MethodView looped = methodNamed("looped");
        final TargetOperations.Operation second = appendIn(looped, 1);

        final WeaveSpot spot = firstOperationOf(SpotFinder.at(looped,
                readingOn(anchorFor(looped, second, 0), lineOf(looped, second), 0, 0), SPELLING));

        assertNotNull("standing on a call has to answer with that call", spot);
        assertEquals("before it, which is what INVOKE names", Point.INVOKE, spot.point());
        assertNotNull(spot.operation());
        assertEquals("the second of the four appends, not the first thing the line happens to "
                        + "hold: " + spot.why(),
                1, spot.operation().ordinal());
    }

    public void testACallIsOfferedBeforeAndAfter() {
        final MethodView looped = methodNamed("looped");
        final TargetOperations.Operation second = appendIn(looped, 1);
        final List<Point> points = new ArrayList<>();
        for (final WeaveSpot spot : SpotFinder.at(looped,
                readingOn(anchorFor(looped, second, 0), lineOf(looped, second), 0, 0), SPELLING)) {
            if (spot.operation() != null && spot.operation().index() == second.index()) {
                points.add(spot.point());
            }
        }

        assertTrue("before the call: " + points, points.contains(Point.INVOKE));
        assertTrue("after it, where the handler sees the result — asking the author to go and edit "
                + "the annotation for that would give back the decision this removes: " + points,
                points.contains(Point.INVOKE_AFTER));
    }

    public void testALoopYieldsASliceTheOrdinalIsCountedIn() {
        final MethodView looped = methodNamed("looped");
        final TargetOperations.Operation second = appendIn(looped, 1);
        final TargetOperations.Operation third = appendIn(looped, 2);

        final WeaveSpot spot = firstOperationOf(SpotFinder.at(looped,
                readingOn(anchorFor(looped, second, 0), lineOf(looped, second),
                        lineOf(looped, second), lineOf(looped, third)), SPELLING));

        assertNotNull(spot);
        assertTrue("the caret stands in a block, so the region can be bounded by its own calls: "
                + spot.why(), spot.isNarrowable());
        final WeaveSpot narrowed = spot.narrowed();
        assertNotNull(narrowed);
        assertNotNull(narrowed.slice());
        assertNotNull(narrowed.operation());
        assertEquals("the ordinal is counted inside the region, because that is how the engine "
                        + "counts it — an absolute ordinal written next to a slice names a "
                        + "different instruction, which is the whole class of mistake a slice "
                        + "exists to avoid",
                0, narrowed.operation().ordinal());
        assertEquals("it is still the same instruction", second.index(),
                narrowed.operation().index());
    }

    public void testWithoutABlockThereIsNoSlice() {
        final MethodView looped = methodNamed("looped");
        final TargetOperations.Operation first = appendIn(looped, 0);

        final WeaveSpot spot = firstOperationOf(SpotFinder.at(looped,
                readingOn(anchorFor(looped, first, 0), lineOf(looped, first), 0, 0), SPELLING));

        assertNotNull(spot);
        assertFalse("a slice bounding the whole method bounds nothing, and offering one would be "
                + "ceremony dressed up as safety", spot.isNarrowable());
    }

    public void testTwoCallsOnOneLineAreToldApart() {
        final MethodView twice = methodNamed("twice");
        final TargetOperations.Operation first = appendIn(twice, 0);
        final TargetOperations.Operation second = appendIn(twice, 1);
        assertEquals("the fixture puts both on one line, which is what makes this a question",
                lineOf(twice, first), lineOf(twice, second));

        final WeaveSpot left = firstOperationOf(SpotFinder.at(twice,
                readingOn(anchorFor(twice, first, 0), lineOf(twice, first), 0, 0), SPELLING));
        final WeaveSpot right = firstOperationOf(SpotFinder.at(twice,
                readingOn(anchorFor(twice, second, 1), lineOf(twice, second), 0, 0), SPELLING));

        assertNotNull(left);
        assertNotNull(right);
        assertNotNull(left.operation());
        assertNotNull(right.operation());
        assertEquals("the caret in the first call answers with the first instruction",
                first.index(), left.operation().index());
        assertEquals("and the caret in the second answers with the second — the line alone "
                        + "cannot tell these apart, and a tool that picked one of them would be "
                        + "wrong half the time while looking certain",
                second.index(), right.operation().index());
    }

    public void testThereIsAlwaysSomewhereToAttach() {
        final MethodView looped = methodNamed("looped");

        final List<Point> points = new ArrayList<>();
        for (final WeaveSpot spot : SpotFinder.at(looped,
                new SpotFinder.Reading(List.of(), Integer.MAX_VALUE / 2, 0, 0), SPELLING)) {
            points.add(spot.point());
        }

        assertTrue("an author who asked 'here' is never answered with nothing: " + points,
                points.contains(Point.HEAD));
        assertTrue(points.contains(Point.RETURN));
        assertTrue(points.contains(Point.TAIL));
    }

    public void testAnUnknownMemberIsNotInvented() {
        final MethodView looped = methodNamed("looped");
        final SourceAnchor absent = new SourceAnchor(SourceAnchor.Kind.CALL,
                "com/example/Nothing", "thisIsNotAMethod", null, null, 1, 9999, 0, 0);

        for (final WeaveSpot spot
                : SpotFinder.at(looped, readingOn(absent, 1, 0, 0), SPELLING)) {
            assertNotSame("an anchor that matches nothing must not be reported as an exact answer: "
                    + spot.label(), WeaveSpot.Confidence.EXACT, spot.confidence());
        }
    }

    // --- the harness -----------------------------------------------------------------------------

    private static SpotFinder.Reading readingOn(final SourceAnchor anchor,
                                                final int caretLine,
                                                final int first,
                                                final int last) {
        return new SpotFinder.Reading(List.of(anchor), caretLine, first, last);
    }

    private static SourceAnchor anchorFor(final MethodView method,
                                          final TargetOperations.Operation operation,
                                          final int occurrence) {
        final TargetOperations.Described described =
                TargetOperations.describe(method, operation.index());
        assertNotNull("the fixture's instruction must describe itself", described);
        final int line = lineOf(method, operation);
        return new SourceAnchor(described.kind(), described.owner(), described.name(),
                described.descriptor(), null, line, line, occurrence, 0);
    }

    private static WeaveSpot firstOperationOf(final List<WeaveSpot> spots) {
        for (final WeaveSpot spot : spots) {
            if (spot.operation() != null) {
                return spot;
            }
        }
        return null;
    }

    private static TargetOperations.Operation appendIn(final MethodView method, final int ordinal) {
        for (final TargetOperations.Operation candidate
                : TargetOperations.of(method, Point.INVOKE, SPELLING)) {
            final TargetOperations.Described described =
                    TargetOperations.describe(method, candidate.index());
            if (described != null && "append".equals(described.name())
                    && candidate.ordinal() == ordinal) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must call append at least " + (ordinal + 1)
                + " times with one selector");
    }

    private static int lineOf(final MethodView method,
                              final TargetOperations.Operation operation) {
        final List<Integer> lines = CompiledLines.of(method, List.of(operation.index()));
        assertFalse("the fixture must be compiled with line numbers", lines.isEmpty());
        return lines.getFirst();
    }

    private static MethodView methodNamed(final String name) {
        for (final MethodView candidate : view.methods()) {
            if (name.equals(candidate.name())) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must declare " + name);
    }
}
