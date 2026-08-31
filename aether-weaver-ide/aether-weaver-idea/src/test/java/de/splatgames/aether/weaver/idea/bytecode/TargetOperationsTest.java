package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.RedirectShapes;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TargetOperationsTest extends TestCase {

    static final class Sample {

        private Sample() {
        }

        static Object[] constants() {
            return new Object[]{42, "retry", Void.class};
        }

        static String sample(final StringBuilder text, final Object value) {
            text.append("a");
            text.append("b");
            final StringBuilder extra = new StringBuilder();
            extra.append(value.toString());
            return extra.toString();
        }
    }

    private static MethodView sample;

    private static MethodView constants;

    @Override
    protected void setUp() throws IOException {
        if (sample != null) {
            return;
        }
        final String resource = '/' + Sample.class.getName().replace('.', '/') + ".class";
        final byte[] bytes;
        try (InputStream in = TargetOperationsTest.class.getResourceAsStream(resource)) {
            assertNotNull("the fixture's own class file must be on the test classpath: " + resource,
                    in);
            bytes = in.readAllBytes();
        }
        final TargetView view = ModelViews.of(ClassFile.of().parse(bytes));
        for (final MethodView candidate : view.methods()) {
            if ("sample".equals(candidate.name())) {
                sample = candidate;
            } else if ("constants".equals(candidate.name())) {
                constants = candidate;
            }
        }
        assertNotNull("the fixture must declare sample(...)", sample);
        assertNotNull("the fixture must declare constants()", constants);
    }

    public void testRepeatedCallsGetDistinctOrdinals() {
        final List<Integer> ordinals = new ArrayList<>();
        for (final TargetOperations.Operation operation : invocations()) {
            if (operation.target().contains("append")) {
                ordinals.add(operation.ordinal());
            }
        }

        assertEquals("the fixture calls append three times: " + labels(), 3, ordinals.size());
        assertEquals("without distinct ordinals every generated annotation would name the first "
                        + "call, whichever row the user picked: " + ordinals,
                List.of(0, 1, 2), ordinals);
    }

    public void testNoTwoOperationsShareAnAnnotation() {
        final Set<String> written = new HashSet<>();
        for (final TargetOperations.Operation operation : invocations()) {
            assertTrue("two operations would generate the same @At: " + operation.target()
                            + " ordinal " + operation.ordinal(),
                    written.add(operation.target() + '#' + operation.ordinal()));
        }
        assertFalse("the fixture has invocations to find", written.isEmpty());
    }

    public void testTheRedirectShapeMirrorsTheCall() {
        for (final TargetOperations.Operation operation : invocations()) {
            if (!operation.target().contains("append")) {
                continue;
            }
            final RedirectShapes.Shape shape =
                    RedirectShapes.at(sample.code().orElseThrow().elements(), operation.index());
            assertNotNull("an instance call is redirectable", shape);
            assertEquals("(Ljava/lang/StringBuilder;Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    shape.handler().descriptorString());
            assertTrue("what the generator writes has to be accepted by the predicate the injector "
                            + "will apply to it",
                    RedirectShapes.accepts(sample.code().orElseThrow().elements(),
                            operation.index(), shape.handler()));
            return;
        }
        fail("the fixture must contain an append call");
    }

    public void testAnInstantiationIsFoundAndIsRedirectable() {
        final List<TargetOperations.Operation> instantiations = TargetOperations.of(
                sample, Point.NEW, TargetOperations.Spelling.QUALIFIED);

        assertEquals("the fixture instantiates exactly one StringBuilder", 1, instantiations.size());
        assertEquals("java.lang.StringBuilder", instantiations.getFirst().target());
        assertTrue("an instantiation is one of the three things a redirect can replace",
                instantiations.getFirst().isRedirectable());
    }

    public void testASimplerSpellingNamesTheSameInstructions() {
        final List<Integer> qualified = indicesOf(TargetOperations.Spelling.QUALIFIED);
        final List<Integer> simple = indicesOf(TargetOperations.Spelling.SIMPLE);
        final List<Integer> descriptor = indicesOf(TargetOperations.Spelling.DESCRIPTOR);

        assertEquals("a spelling changes how an operation is named, never which ones exist",
                qualified, simple);
        assertEquals(qualified, descriptor);
        assertFalse("the fixture has invocations to find", qualified.isEmpty());
    }

    public void testTheDescriptorSpellingWritesDescriptors() {
        final List<TargetOperations.Operation> operations =
                TargetOperations.of(sample, Point.INVOKE, TargetOperations.Spelling.DESCRIPTOR);

        assertFalse("the fixture has invocations to find", operations.isEmpty());
        for (final TargetOperations.Operation operation : operations) {
            assertTrue("a row offered as a descriptor and written as a source form is the fallback "
                            + "covering for a form that does not work: " + operation.target(),
                    operation.target().startsWith(MemberSelector.DESCRIPTOR_PREFIX));
        }
    }

    public void testASliceInDescriptorFormNarrowsTheSearch() {
        final List<TargetOperations.Operation> calls =
                TargetOperations.of(sample, Point.INVOKE, TargetOperations.Spelling.DESCRIPTOR);
        assertTrue("the fixture needs several calls to bound between: " + calls.size(),
                calls.size() > 2);
        // Asserted before the bounds are used, because it is what makes the rest of this test
        // mean anything: an unverifiable proposal falls back to the qualified source form, so
        // without this the slice below would be bounded by source-form selectors and would narrow
        // perfectly well while the descriptor form remained broken. Measured — this assertion is
        // the only one here that fails when it is.
        assertTrue("a bound offered as a descriptor and written as a source form is the fallback "
                        + "covering for a form that does not work: " + calls.getFirst().target(),
                calls.getFirst().target().startsWith(MemberSelector.DESCRIPTOR_PREFIX)
                        && calls.getLast().target().startsWith(MemberSelector.DESCRIPTOR_PREFIX));

        final List<TargetOperations.Operation> sliced = TargetOperations.of(
                sample, Point.INVOKE, TargetOperations.Spelling.DESCRIPTOR,
                new TargetOperations.Bounds(calls.getFirst(), calls.getLast()));

        assertFalse("a slice whose bounds resolve nowhere leaves nothing to enumerate", sliced.isEmpty());
        assertTrue("the closing bound is outside the region, so the slice is strictly smaller: "
                        + sliced.size() + " of " + calls.size(),
                sliced.size() < calls.size());
    }

    public void testAPositionalPointYieldsNoOperations() {
        assertEquals("HEAD names a position rather than an operation, and there is nothing in a "
                        + "body to enumerate for it",
                List.of(), TargetOperations.of(sample, Point.HEAD,
                        TargetOperations.Spelling.QUALIFIED));
    }

    public void testConstantsOfEveryNameableKindAreFound() {
        final List<String> targets = new ArrayList<>();
        for (final TargetOperations.Operation operation
                : TargetOperations.of(constants, Point.CONSTANT,
                        TargetOperations.Spelling.QUALIFIED)) {
            targets.add(operation.target());
        }

        assertTrue("a string constant is the one people reach for first, and it could not be "
                        + "matched at all: " + targets,
                targets.contains("string:\"retry\""));
        assertTrue("a class constant was compared against ClassOrInterfaceDesc[Void]: " + targets,
                targets.contains("class:java.lang.Void"));
        assertTrue("the numeric kinds were the only ones that ever worked: " + targets,
                targets.contains("int:42"));
    }

    public void testAConstantIsWrittenAsTheApiRendersIt() {
        for (final TargetOperations.Operation operation
                : TargetOperations.of(constants, Point.CONSTANT,
                        TargetOperations.Spelling.QUALIFIED)) {
            assertEquals("a second opinion about quoting or escaping would produce a selector that "
                            + "parses and names something else",
                    operation.target(),
                    MemberSelector.parse(operation.target()).canonical().orElse(null));
        }
    }

    public void testAConstantWithNoSpellingIsNotOffered() {
        assertNull("a method handle is loadable and the constant grammar cannot name it",
                de.splatgames.aether.weaver.api.select.ConstantSelector.of(
                        java.lang.constant.ConstantDescs.BSM_INVOKE));
    }

    private static List<TargetOperations.Operation> invocations() {
        return TargetOperations.of(sample, Point.INVOKE, TargetOperations.Spelling.QUALIFIED);
    }

    private static List<Integer> indicesOf(final TargetOperations.Spelling spelling) {
        final List<Integer> indices = new ArrayList<>();
        for (final TargetOperations.Operation operation
                : TargetOperations.of(sample, Point.INVOKE, spelling)) {
            indices.add(operation.index());
        }
        return indices;
    }

    private static List<String> labels() {
        final List<String> labels = new ArrayList<>();
        for (final TargetOperations.Operation operation : invocations()) {
            labels.add(operation.label());
        }
        return labels;
    }
}
