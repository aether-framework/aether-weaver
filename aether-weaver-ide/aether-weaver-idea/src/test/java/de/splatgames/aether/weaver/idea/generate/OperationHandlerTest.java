package de.splatgames.aether.weaver.idea.generate;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.RedirectShapes;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.util.List;

public class OperationHandlerTest extends BasePlatformTestCase {

    private static final String PACKAGE = "de.splatgames.aether.weaver.idea.generate.fixture";

    private PsiClass weave;

    private PsiMethod target;

    private static MethodView compiled;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", """
                package de.splatgames.aether.weaver.api;

                public @interface Weave {
                    Class<?>[] value() default {};
                    Kind kind() default Kind.INSTANCE;

                    enum Kind { INSTANCE, STATIC }
                }
                """);
        // The same classes the compiled half is read from, added to the fixture as source. The
        // fixture has no mock JDK, so a target whose signature mentions java.lang.String cannot have
        // a selector built for it at all — and then the generator declines for a reason that has
        // nothing to do with what is being tested. Owning every type keeps both halves in one world.
        final String directory = PACKAGE.replace('.', '/');
        myFixture.addFileToProject(directory + "/Item.java", sourceOf("Item"));
        myFixture.addFileToProject(directory + "/Buffer.java", sourceOf("Buffer"));
        myFixture.addFileToProject(directory + "/Host.java", sourceOf("Host"));
        myFixture.addFileToProject(directory + "/HostWeave.java", """
                package %s;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Host.class)
                public final class HostWeave { }
                """.formatted(PACKAGE));

        this.weave = classNamed(PACKAGE + ".HostWeave");
        this.target = methodNamed(classNamed(PACKAGE + ".Host"), "sample");
        if (compiled == null) {
            compiled = parseHost();
        }
    }

    public void testAnInjectAtACallNamesTheCallAndTheOccurrence() {
        final TargetOperations.Operation second = addCalls().get(1);
        final String written = write(second, HandlerOptions.Kind.INJECT);

        assertTrue("the point moves inside @At, which is where a target lives: " + written,
                written.contains("value = de.splatgames.aether.weaver.api.Point.INVOKE"));
        assertTrue("the call is named: " + written,
                written.contains("target = \"" + PACKAGE + ".Buffer.add(" + PACKAGE + ".Item)\""));
        assertEquals("the second call is ordinal 1, and the annotation has to say so or it means "
                        + "the first: " + written,
                1, second.ordinal());
        assertTrue("" + written, written.contains("ordinal = 1"));
        assertTrue("a handler named after the method it sits in would collide with the one for the "
                        + "first call: " + written,
                written.contains("void onAdd"));
    }

    public void testAnInjectAtACallKeepsTheEnclosingMethodsParameters() {
        final String written = write(addCalls().getFirst(), HandlerOptions.Kind.INJECT);

        assertTrue("" + written,
                written.contains(PACKAGE + ".Buffer buffer, " + PACKAGE + ".Item item"));
    }

    public void testARedirectMirrorsTheOperation() {
        final String written = write(addCalls().getFirst(), HandlerOptions.Kind.REDIRECT);

        assertTrue("a redirect is a different annotation, not a different point: " + written,
                written.contains("@de.splatgames.aether.weaver.api.Redirect"));
        assertTrue("it returns what the call returned: " + written,
                written.contains(PACKAGE + ".Item onAdd("));
        assertTrue("an instance call hands over its receiver first: " + written,
                written.contains('(' + PACKAGE + ".Buffer buffer, " + PACKAGE + ".Item item)"));
        assertTrue("an empty body would not compile, so the generator writes the one statement that "
                        + "always does — next to a marker saying it is not the answer: " + written,
                written.contains("return null;") && written.contains("TODO"));
    }

    public void testTheRedirectHandlerIsOneTheEngineAccepts() {
        for (final TargetOperations.Operation operation : addCalls()) {
            final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave, this.target,
                    operation, options(HandlerOptions.Kind.REDIRECT));
            assertNotNull(handler);

            assertTrue("the generator wrote a signature the injector would reject: "
                            + handler.getText(),
                    RedirectShapes.accepts(compiled.code().orElseThrow().elements(),
                            operation.index(), signatureOf(handler)));
        }
    }

    public void testARedirectIgnoresTheCallbackAndTheCaptures() {
        final TargetOperations.Operation call = addCalls().getFirst();
        final HandlerOptions defaults = HandlerOptions.defaults();
        final HandlerOptions everything = new HandlerOptions(HandlerOptions.Kind.REDIRECT,
                HandlerOptions.Point.INVOKE, defaults.match(), defaults.selector(),
                defaults.visibility(), defaults.prefix(), "", true, true, false, false);

        final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave, this.target, call,
                List.of(new TargetLocals.Capture("ignored",
                        java.lang.constant.ClassDesc.ofDescriptor("I"))), everything);
        assertNotNull(handler);
        final String written = handler.getText();

        assertFalse("a redirect stands in for the operation; a callback has no place in its "
                        + "signature: " + written,
                written.contains("Callback"));
        assertFalse("and neither does a capture: " + written, written.contains("@Local"));
        assertTrue("what is left is the operation's own shape: " + written,
                written.contains('(' + PACKAGE + ".Buffer buffer, " + PACKAGE + ".Item item)"));
    }

    public void testAConstantHandlerIsNamedWithAnIdentifier() {
        final MethodView compiled = compiledMethod("constants");
        final List<TargetOperations.Operation> constants = TargetOperations.of(compiled,
                Point.CONSTANT, TargetOperations.Spelling.QUALIFIED);
        assertFalse("the fixture loads constants", constants.isEmpty());

        final PsiMethod host = methodNamed(classNamed(PACKAGE + ".Host"), "constants");
        final PsiNameHelper names = PsiNameHelper.getInstance(getProject());
        for (final TargetOperations.Operation constant : constants) {
            final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave, host, constant,
                    List.of(), constantOptions());
            assertNotNull("a constant this plugin offered must be one it can write a handler for: "
                    + constant.target(), handler);
            assertTrue("the name has to be an identifier, whatever the constant reads like: "
                            + handler.getName() + " for " + constant.target(),
                    names.isIdentifier(handler.getName()));
        }
    }

    public void testEveryOfferedOperationProducesAParseableHandler() {
        final PsiMethod host = methodNamed(classNamed(PACKAGE + ".Host"), "constants");
        final MethodView compiled = compiledMethod("constants");
        int written = 0;
        for (final Point point : new Point[]{Point.INVOKE, Point.INVOKE_AFTER, Point.FIELD,
                Point.NEW, Point.CONSTANT}) {
            for (final TargetOperations.Operation operation
                    : TargetOperations.of(compiled, point, TargetOperations.Spelling.QUALIFIED)) {
                final HandlerOptions options = new HandlerOptions(HandlerOptions.Kind.INJECT,
                        pointFor(point), HandlerOptions.Match.EVERY,
                        HandlerOptions.Selector.DESCRIPTOR, HandlerOptions.Visibility.AUTOMATIC,
                        HandlerOptions.DEFAULT_PREFIX, "", true, true, true, true);
                assertNotNull("offered but not writable: " + point + ' ' + operation.target(),
                        AddHandlerHandler.handlerFor(this.weave, host, operation, List.of(),
                                options));
                written++;
            }
        }
        assertTrue("the fixture must exercise more than one kind", written > 1);
    }

    private static HandlerOptions.Point pointFor(final Point point) {
        for (final HandlerOptions.Point candidate : HandlerOptions.Point.values()) {
            if (candidate.api() == point) {
                return candidate;
            }
        }
        throw new AssertionError("the dialog must offer " + point);
    }

    private static HandlerOptions constantOptions() {
        final HandlerOptions defaults = HandlerOptions.defaults();
        return new HandlerOptions(HandlerOptions.Kind.INJECT, HandlerOptions.Point.CONSTANT,
                defaults.match(), defaults.selector(), defaults.visibility(), defaults.prefix(), "",
                true, false, true, true);
    }

    public void testAParameterIsNeverNamedAfterAKeyword() {
        final PsiMethod host = methodNamed(classNamed(PACKAGE + ".Host"), "primitives");
        final MethodView compiled = compiledMethod("primitives");
        final PsiNameHelper names = PsiNameHelper.getInstance(getProject());

        int checked = 0;
        for (final TargetOperations.Operation call
                : TargetOperations.of(compiled, Point.INVOKE,
                        TargetOperations.Spelling.QUALIFIED)) {
            if (!call.isRedirectable()) {
                continue;
            }
            final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave, host, call,
                    List.of(), options(HandlerOptions.Kind.REDIRECT));
            assertNotNull("a redirectable call must be writable: " + call.target(), handler);
            for (final com.intellij.psi.PsiParameter parameter
                    : handler.getParameterList().getParameters()) {
                assertFalse("a parameter named after a keyword is not a declaration: "
                                + handler.getText(),
                        names.isKeyword(parameter.getName()));
                assertTrue("" + handler.getText(), names.isIdentifier(parameter.getName()));
            }
            checked++;
        }
        assertTrue("the fixture must contain a redirectable call taking primitives", checked > 0);
    }

    public void testAPrimitiveKeepsItsTypeAndLosesOnlyItsName() {
        final PsiMethod host = methodNamed(classNamed(PACKAGE + ".Host"), "primitives");
        final MethodView compiled = compiledMethod("primitives");

        for (final TargetOperations.Operation call
                : TargetOperations.of(compiled, Point.INVOKE,
                        TargetOperations.Spelling.QUALIFIED)) {
            if (!call.target().contains(".count")) {
                continue;
            }
            final String written = AddHandlerHandler.handlerFor(this.weave, host, call, List.of(),
                    options(HandlerOptions.Kind.REDIRECT)).getText();

            assertTrue("the receiver still reads after its type: " + written,
                    written.contains(PACKAGE + ".Buffer buffer"));
            assertTrue("and the primitives keep theirs, with a name Java has: " + written,
                    written.contains("int intValue") && written.contains("boolean booleanValue"));
            return;
        }
        fail("the fixture must call count");
    }

    public void testNoRedirectIsWrittenWithoutAnOperation() {
        assertNull("a redirect stands in for an operation, and at a position there is none — the "
                        + "engine calls that AW1061",
                AddHandlerHandler.handlerFor(this.weave, this.target, null,
                        options(HandlerOptions.Kind.REDIRECT)));
    }

    public void testNoOperationPointIsWrittenWithoutAnOperation() {
        assertNull("INVOKE without a target is an error the user did not write",
                AddHandlerHandler.handlerFor(this.weave, this.target, null,
                        options(HandlerOptions.Kind.INJECT)));
    }

    public void testALocalIsCapturedByItsRecordedName() {
        final PsiMethod host = methodNamed(classNamed(PACKAGE + ".Host"), "withLocals");
        final MethodView compiled = compiledMethod("withLocals");
        assertTrue("the fixture is compiled by this build, which records a local variable table",
                TargetLocals.isAvailable(compiled));

        final List<TargetLocals.Capture> captures = TargetLocals.at(compiled,
                TargetOperations.sitesOf(compiled, Point.TAIL, null));
        final List<String> names = new java.util.ArrayList<>();
        captures.forEach(capture -> names.add(capture.name()));

        assertTrue("both locals live to the end of the method: " + names,
                names.contains("first") && names.contains("count"));
        assertFalse("the handler already receives the target's parameters, so offering them as "
                        + "captures would be offering the same value twice: " + names,
                names.contains("buffer") || names.contains("item"));

        final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave, host, null, captures,
                localsOptions());
        assertNotNull(handler);
        final String written = handler.getText();

        assertTrue("the capture names the local: " + written,
                written.contains("@de.splatgames.aether.weaver.api.Local(name = \"first\")"));
        assertTrue("and carries the local's own type: " + written,
                written.contains(PACKAGE + ".Item first"));
        assertTrue("a primitive local keeps its primitive type: " + written,
                written.contains("int count"));
    }

    public void testALocalMustBeLiveAtEverySite() {
        final MethodView compiled = compiledMethod("withLocals");
        final List<Integer> tail = TargetOperations.sitesOf(compiled, Point.TAIL, null);
        final List<Integer> head = TargetOperations.sitesOf(compiled, Point.HEAD, null);

        assertFalse("the fixture has a tail", tail.isEmpty());
        assertEquals("nothing the method declares is in scope before its first instruction",
                List.of(), TargetLocals.at(compiled, head));
        assertFalse("but at the tail both are", TargetLocals.at(compiled, tail).isEmpty());
    }

    private static HandlerOptions localsOptions() {
        final HandlerOptions defaults = HandlerOptions.defaults();
        return new HandlerOptions(HandlerOptions.Kind.INJECT, HandlerOptions.Point.TAIL,
                defaults.match(), defaults.selector(), defaults.visibility(), defaults.prefix(), "",
                false, true, false, false);
    }

    private static MethodView compiledMethod(final String name) {
        try {
            final String resource = '/' + PACKAGE.replace('.', '/') + "/Host.class";
            final byte[] bytes;
            try (InputStream in = OperationHandlerTest.class.getResourceAsStream(resource)) {
                assertNotNull("the fixture's own class file must be on the test classpath", in);
                bytes = in.readAllBytes();
            }
            for (final MethodView candidate
                    : ModelViews.of(ClassFile.of().parse(bytes)).methods()) {
                if (name.equals(candidate.name())) {
                    return candidate;
                }
            }
        } catch (final IOException unreadable) {
            throw new AssertionError(unreadable);
        }
        throw new AssertionError("the fixture must declare " + name);
    }

    public void testAnOrdinalIsCountedInsideTheSlice() {
        final MethodView compiled = compiledMethod("bounded");
        final List<TargetOperations.Operation> unsliced = callsNamed(compiled, null, ".add");
        assertEquals("the fixture calls add twice", 2, unsliced.size());
        assertEquals("against the whole method the second call is ordinal 1",
                1, unsliced.get(1).ordinal());

        final TargetOperations.Bounds second = new TargetOperations.Bounds(
                callsNamed(compiled, null, ".commit").getFirst(),
                callsNamed(compiled, null, ".end").getFirst());
        final List<TargetOperations.Operation> sliced = callsNamed(compiled, second, ".add");

        assertEquals("only one add lies inside that region", 1, sliced.size());
        assertEquals("and it is the same instruction",
                unsliced.get(1).index(), sliced.getFirst().index());
        assertEquals("counted inside the slice it is ordinal 0, not 1",
                0, sliced.getFirst().ordinal());
    }

    public void testTheSliceIsWritten() {
        final MethodView compiled = compiledMethod("bounded");
        final TargetOperations.Bounds bounds = new TargetOperations.Bounds(
                callsNamed(compiled, null, ".commit").getFirst(),
                callsNamed(compiled, null, ".end").getFirst());
        final TargetOperations.Operation chosen = callsNamed(compiled, bounds, ".add").getFirst();

        final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave,
                methodNamed(classNamed(PACKAGE + ".Host"), "bounded"), chosen, List.of(), bounds,
                options(HandlerOptions.Kind.INJECT));
        assertNotNull(handler);
        final String written = handler.getText();

        assertTrue("the slice bounds the search: " + written,
                written.contains("slice = @de.splatgames.aether.weaver.api.Slice(from ="));
        assertTrue("a bound that resolved to several positions would bound nothing, so both are "
                        + "pinned: " + written,
                written.contains(".commit()\", ordinal = 0")
                        && written.contains(".end()\", ordinal = 0"));
        assertTrue("and the injection's own ordinal is the slice-relative one: " + written,
                written.contains("ordinal = 0)"));
    }

    public void testASliceIsWrittenInTheChosenForm() {
        final MethodView compiled = compiledMethod("bounded");
        final TargetOperations.Bounds bounds = new TargetOperations.Bounds(
                descriptorCallNamed(compiled, "commit"), descriptorCallNamed(compiled, "end"));
        final List<TargetOperations.Operation> inside = TargetOperations.of(compiled, Point.INVOKE,
                TargetOperations.Spelling.DESCRIPTOR, bounds);
        assertFalse("bounds that resolve nowhere leave nothing to enumerate", inside.isEmpty());

        final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave,
                methodNamed(classNamed(PACKAGE + ".Host"), "bounded"), inside.getFirst(), List.of(),
                bounds, descriptorOptions());
        assertNotNull(handler);
        final String written = handler.getText();

        assertEquals("every selector in the annotation is written in the form that was asked for: "
                        + written, 4, occurrencesOf(written, "desc:"));
    }

    private static TargetOperations.Operation descriptorCallNamed(final MethodView method,
                                                                  final String name) {
        for (final TargetOperations.Operation operation : TargetOperations.of(method, Point.INVOKE,
                TargetOperations.Spelling.DESCRIPTOR, null)) {
            if (operation.target().contains('.' + name + '(')) {
                return operation;
            }
        }
        throw new AssertionError("the fixture must call " + name);
    }

    private static int occurrencesOf(final String text, final String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    private static HandlerOptions descriptorOptions() {
        final HandlerOptions injecting = options(HandlerOptions.Kind.INJECT);
        return new HandlerOptions(injecting.kind(), injecting.point(), injecting.match(),
                HandlerOptions.Selector.DESCRIPTOR, injecting.visibility(), injecting.prefix(),
                injecting.group(), injecting.callback(), injecting.locals(), injecting.javadoc(),
                injecting.todo());
    }

    public void testAnInvertedSliceSelectsNothing() {
        final MethodView compiled = compiledMethod("bounded");
        final TargetOperations.Bounds inverted = new TargetOperations.Bounds(
                callsNamed(compiled, null, ".end").getFirst(),
                callsNamed(compiled, null, ".begin").getFirst());

        assertEquals("the engine calls this AW1122, and an operation it would report on is not one "
                        + "to offer", List.of(), callsNamed(compiled, inverted, ".add"));
    }

    private static List<TargetOperations.Operation> callsNamed(final MethodView method,
                                                               final TargetOperations.Bounds bounds,
                                                               final String name) {
        final List<TargetOperations.Operation> found = new java.util.ArrayList<>();
        for (final TargetOperations.Operation operation : TargetOperations.of(method, Point.INVOKE,
                TargetOperations.Spelling.QUALIFIED, bounds)) {
            if (operation.target().contains(name)) {
                found.add(operation);
            }
        }
        return found;
    }

    private static List<TargetOperations.Operation> addCalls() {
        final List<TargetOperations.Operation> calls = new java.util.ArrayList<>();
        for (final TargetOperations.Operation operation
                : TargetOperations.of(compiled, Point.INVOKE, TargetOperations.Spelling.QUALIFIED)) {
            if (operation.target().contains(".add")) {
                calls.add(operation);
            }
        }
        assertEquals("the fixture calls add twice", 2, calls.size());
        return calls;
    }

    private String write(final TargetOperations.Operation operation, final HandlerOptions.Kind kind) {
        final PsiMethod handler = AddHandlerHandler.handlerFor(this.weave, this.target, operation,
                options(kind));
        assertNotNull("the generator declined to write anything for " + operation.label(), handler);
        return handler.getText();
    }

    private static HandlerOptions options(final HandlerOptions.Kind kind) {
        final HandlerOptions defaults = HandlerOptions.defaults();
        return new HandlerOptions(kind, HandlerOptions.Point.INVOKE, defaults.match(),
                defaults.selector(), defaults.visibility(), defaults.prefix(), "",
                defaults.callback(), false, defaults.javadoc(), true);
    }

    private static java.lang.constant.MethodTypeDesc signatureOf(final PsiMethod handler) {
        final String descriptor = com.intellij.psi.util.ClassUtil.getAsmMethodSignature(handler);
        assertNotNull("the platform must be able to encode what was just generated", descriptor);
        return java.lang.constant.MethodTypeDesc.ofDescriptor(descriptor);
    }

    private static MethodView parseHost() throws IOException {
        final String resource = '/' + PACKAGE.replace('.', '/') + "/Host.class";
        final byte[] bytes;
        try (InputStream in = OperationHandlerTest.class.getResourceAsStream(resource)) {
            assertNotNull("the fixture's own class file must be on the test classpath", in);
            bytes = in.readAllBytes();
        }
        final TargetView view = ModelViews.of(ClassFile.of().parse(bytes));
        for (final MethodView candidate : view.methods()) {
            if ("sample".equals(candidate.name())) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must declare sample(...)");
    }

    private static String sourceOf(final String name) {
        return """
                package %s;

                %s
                """.formatted(PACKAGE, bodyOf(name));
    }

    private static String bodyOf(final String name) {
        return switch (name) {
            case "Item" -> "public class Item { }";
            case "Buffer" -> """
                    public class Buffer {
                        public Item add(Item item) { return item; }
                        public int count(int first, int second, boolean flag) {
                            return flag ? first + second : first;
                        }
                        public void begin() { }
                        public void commit() { }
                        public void end() { }
                    }""";
            default -> """
                    public class Host {
                        public static void sample(Buffer buffer, Item item) {
                            buffer.add(item);
                            buffer.add(item);
                        }

                        public static int primitives(Buffer buffer) {
                            return buffer.count(1, 2, true);
                        }

                        public static Object constants(Buffer buffer) {
                            int number = 0;
                            String text = "retry";
                            Class<?> type = Item.class;
                            Item made = new Item();
                            buffer.add(made);
                            return number + text + type + made;
                        }

                        public static void bounded(Buffer buffer, Item item) {
                            buffer.begin();
                            buffer.add(item);
                            buffer.commit();
                            buffer.add(item);
                            buffer.end();
                        }

                        public static void withLocals(Buffer buffer, Item item) {
                            Item first = buffer.add(item);
                            int count = 1;
                            buffer.add(first);
                            if (count > 0) {
                                buffer.add(first);
                            }
                        }
                    }""";
        };
    }

    private PsiMethod methodNamed(final PsiClass owner, final String name) {
        for (final PsiMethod candidate : owner.getMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must declare " + name);
    }

    private PsiClass classNamed(final String qualifiedName) {
        final PsiClass found = JavaPsiFacade.getInstance(getProject())
                .findClass(qualifiedName, GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare " + qualifiedName, found);
        return found;
    }
}
