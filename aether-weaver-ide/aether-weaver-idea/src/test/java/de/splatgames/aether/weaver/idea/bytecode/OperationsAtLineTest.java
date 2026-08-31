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

public class OperationsAtLineTest extends TestCase {

    static final class Lines {

        private Lines() {
        }

        static String sample(final StringBuilder text) {
            text.append("a");

            text.append("b");
            return text.toString();
        }
    }

    private static final int FIRST_CALL = 0;

    private static MethodView sample;

    private static int firstCallLine;

    @Override
    protected void setUp() throws IOException {
        if (sample != null) {
            return;
        }
        final String resource = '/' + Lines.class.getName().replace('.', '/') + ".class";
        final byte[] bytes;
        try (InputStream in = OperationsAtLineTest.class.getResourceAsStream(resource)) {
            assertNotNull("the fixture's own class file must be on the test classpath: " + resource,
                    in);
            bytes = in.readAllBytes();
        }
        final TargetView view = ModelViews.of(ClassFile.of().parse(bytes));
        for (final MethodView candidate : view.methods()) {
            if ("sample".equals(candidate.name())) {
                sample = candidate;
            }
        }
        assertNotNull("the fixture must declare sample(...)", sample);

        // Anchored on what the class file says rather than on a line number written here, so the
        // fixture can move within this file.
        int earliest = Integer.MAX_VALUE;
        for (final OperationsAtLine.Found found
                : OperationsAtLine.allIn(sample, TargetOperations.Spelling.QUALIFIED)) {
            earliest = Math.min(earliest, found.line());
        }
        assertTrue("the fixture must contain calls with line numbers", earliest < Integer.MAX_VALUE);
        firstCallLine = earliest;
    }

    public void testALineFindsItsOwnOperations() {
        final List<OperationsAtLine.Found> found = at(firstCallLine + FIRST_CALL);

        assertFalse("the fixture calls append on that line", found.isEmpty());
        for (final OperationsAtLine.Found candidate : found) {
            assertEquals("everything offered has to be on the line that was asked about",
                    firstCallLine, candidate.line());
        }
    }

    public void testACallIsOfferedBeforeAndAfter() {
        final List<Point> points = new ArrayList<>();
        for (final OperationsAtLine.Found found : at(firstCallLine)) {
            points.add(found.point());
        }

        assertTrue("before the call: " + points, points.contains(Point.INVOKE));
        assertTrue("after it, where the handler sees the result — asking the author to go and edit "
                + "the annotation for that would give back the decision this removes: " + points,
                points.contains(Point.INVOKE_AFTER));
    }

    public void testAnEmptyLineFallsForward() {
        // The blank line between the two calls in the fixture. Nothing was compiled from it, so a
        // caret standing there has to be answered with the call after it rather than with silence.
        final List<OperationsAtLine.Found> found = at(firstCallLine + 1);

        assertFalse("standing on a blank line inside a method is an ordinary place to be, and "
                + "refusing there would send the author hunting for a line that 'works'",
                found.isEmpty());
        assertTrue("the line reported is the one the operations really came from: " + found,
                found.getFirst().line() > firstCallLine);
    }

    public void testAPositionPastTheEndFallsBack() {
        final List<OperationsAtLine.Found> found = at(firstCallLine + 1000);

        assertFalse("a caret on a closing brace is where a reader ends up constantly", found.isEmpty());
    }

    public void testTheLabelNamesTheLine() {
        final OperationsAtLine.Found found = at(firstCallLine).getFirst();

        assertTrue("a user who asked about one line and is being offered another has to be told "
                        + "before they accept it: " + found.label(),
                found.label().contains("line " + found.line()));
    }

    private static List<OperationsAtLine.Found> at(final int line) {
        return OperationsAtLine.at(sample, line, TargetOperations.Spelling.QUALIFIED);
    }
}
