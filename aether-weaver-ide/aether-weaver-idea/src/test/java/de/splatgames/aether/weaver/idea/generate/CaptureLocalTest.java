package de.splatgames.aether.weaver.idea.generate;

import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.List;

public class CaptureLocalTest extends TestCase {

    static final class Locals {

        private Locals() {
        }

        static String captured(final StringBuilder text) {
            final String tag = text.toString();
            text.append(tag);
            return tag;
        }
    }

    private static MethodView captured;

    @Override
    protected void setUp() throws IOException {
        if (captured != null) {
            return;
        }
        final String resource = '/' + Locals.class.getName().replace('.', '/') + ".class";
        final byte[] bytes;
        try (InputStream in = CaptureLocalTest.class.getResourceAsStream(resource)) {
            assertNotNull("the fixture's own class file must be on the test classpath: " + resource,
                    in);
            bytes = in.readAllBytes();
        }
        final TargetView view = ModelViews.of(ClassFile.of().parse(bytes));
        for (final MethodView candidate : view.methods()) {
            if ("captured".equals(candidate.name())) {
                captured = candidate;
            }
        }
        assertNotNull("the fixture must declare captured(...)", captured);
    }

    public void testTheFixtureHasALocalVariableTable() {
        assertTrue("without -g there are no names for @Local to bind to, and every assertion below "
                        + "would be vacuously true",
                TargetLocals.isAvailable(captured));
    }

    public void testAVariableIsOfferedWhereItIsLive() {
        final List<String> offered = targetsOfferedFor("tag");

        assertFalse("the fixture uses tag at a call, so there is somewhere to attach: " + offered,
                offered.isEmpty());
        assertTrue("append is the call it is passed to: " + offered,
                offered.stream().anyMatch(target -> target.contains("append")));
        assertFalse("toString is the call whose result becomes tag, so the store has not happened "
                        + "yet and tag is not live there — a @Local generated at it is AW1050 on a "
                        + "handler that reads perfectly: " + offered,
                offered.stream().anyMatch(target -> target.contains("toString")));
    }

    public void testAnUnknownNameIsOfferedNowhere() {
        assertEquals("offering a position for a name the table does not have would generate a "
                        + "capture the engine cannot resolve",
                List.of(), targetsOfferedFor("thisIsNotAVariable"));
    }

    public void testTheNearestPositionComesFirst() {
        final List<WeaveSpot> far = offersFor("tag", Integer.MAX_VALUE / 2);
        final List<WeaveSpot> near = offersFor("tag", 1);

        assertFalse(far.isEmpty());
        assertFalse(near.isEmpty());
        assertTrue("ordered by distance from the caret: " + far.getFirst().label(),
                Math.abs(far.getFirst().line() - Integer.MAX_VALUE / 2)
                        <= Math.abs(near.getFirst().line() - Integer.MAX_VALUE / 2));
    }

    public void testTheSearchIsNotConfinedToTheCaretsLine() {
        final List<WeaveSpot> offered = offersFor("tag", Integer.MAX_VALUE / 2);

        assertFalse("no line in the fixture is anywhere near that caret, and the answer is still "
                + "every position the variable is live at", offered.isEmpty());
    }

    private static List<String> targetsOfferedFor(final String name) {
        final List<String> targets = new ArrayList<>();
        for (final WeaveSpot spot : offersFor(name, 1)) {
            if (spot.operation() != null) {
                targets.add(spot.operation().target());
            }
        }
        return targets;
    }

    private static List<WeaveSpot> offersFor(final String name, final int line) {
        return CaptureLocalIntention.offersFor(captured,
                new SpotFinder.Reading(List.of(), line, 0, 0), name,
                TargetOperations.Spelling.QUALIFIED);
    }
}
