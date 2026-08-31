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

public class CompiledLinesTest extends TestCase {

    private static final String PACKAGE = "de.splatgames.aether.weaver.idea.generate.fixture";

    private static TargetView host;

    @Override
    protected void setUp() throws IOException {
        if (host != null) {
            return;
        }
        final String resource = '/' + PACKAGE.replace('.', '/') + "/Host.class";
        try (InputStream in = CompiledLinesTest.class.getResourceAsStream(resource)) {
            assertNotNull("the fixture's class file must be on the test classpath", in);
            host = ModelViews.of(ClassFile.of().parse(in.readAllBytes()));
        }
    }

    public void testEachCallMapsToItsOwnLine() {
        final MethodView method = methodNamed("bounded");
        final List<Integer> calls = new ArrayList<>();
        for (final TargetOperations.Operation call : TargetOperations.of(method, Point.INVOKE,
                TargetOperations.Spelling.QUALIFIED)) {
            calls.add(call.index());
        }
        assertEquals("the fixture makes five calls", 5, calls.size());

        final List<Integer> lines = CompiledLines.of(method, calls);

        assertEquals("five calls, five lines", 5, lines.size());
        for (int call = 1; call < lines.size(); call++) {
            assertEquals("the calls are on consecutive lines, so the mapping must be too: " + lines,
                    lines.get(call - 1) + 1, (int) lines.get(call));
        }
    }

    public void testTheOrderIsPreserved() {
        final MethodView method = methodNamed("bounded");
        // A real call, not element 0. Element 0 of a method body is a local-variable pseudo
        // element that precedes every line marker, so it correctly maps to no line at all — which
        // the first version of this test mistook for a defect in the mapping.
        final int call = TargetOperations.of(method, Point.INVOKE,
                TargetOperations.Spelling.QUALIFIED).getFirst().index();
        final List<Integer> lines = CompiledLines.of(method, List.of(call, call));

        assertEquals("two sites, two answers", 2, lines.size());
        assertEquals("and the same one twice", lines.get(0), lines.get(1));
    }

    public void testAPositionWithNoLineIsLeftOut() {
        assertEquals("a negative index precedes every line marker there is",
                List.of(), CompiledLines.of(methodNamed("bounded"), List.of(-1)));
    }

    private static MethodView methodNamed(final String name) {
        for (final MethodView candidate : host.methods()) {
            if (name.equals(candidate.name())) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must declare " + name);
    }
}
