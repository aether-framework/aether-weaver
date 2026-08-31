package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.callback.LocalIntRef;
import de.splatgames.aether.weaver.api.callback.LocalLongRef;
import de.splatgames.aether.weaver.api.callback.LocalRef;
import de.splatgames.aether.weaver.api.callback.ReturnableCallback;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.internal.transform.LocalTable;
import de.splatgames.aether.weaver.engine.plan.OrderKey;
import de.splatgames.aether.weaver.engine.plan.PlanEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCaptureTest {

    static final List<String> SEEN = new ArrayList<>();

    private static final ClassDesc TARGET = ClassDesc.of("localfixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    private static final byte[] WITH_DEBUG = compile(true);

    private static final byte[] WITHOUT_DEBUG = compile(false);

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void onString(final String captured) {
            SEEN.add(String.valueOf(captured));
        }

        public static void onLong(final long captured) {
            SEEN.add(Long.toString(captured));
        }

        public static void onBoth(final boolean argument, final String captured) {
            SEEN.add(argument + "/" + captured);
        }

        public static void onOrdered(final int times,
                                     final ReturnableCallback<String> callback,
                                     final String captured) {
            SEEN.add(times + "/" + callback.id() + '/' + captured);
        }

        public static void onOrderedAndCancel(final int times,
                                              final ReturnableCallback<String> callback,
                                              final String captured) {
            callback.cancel("cancelled:" + captured);
        }

        public static void bumpTotal(final LocalIntRef total) {
            SEEN.add("saw " + total.get());
            total.set(total.get() + 100);
        }

        public static void replaceText(final LocalRef<String> text) {
            SEEN.add("saw " + text.get());
            text.set("after");
        }

        public static void bumpWide(final LocalLongRef big) {
            big.set(big.get() + 41L);
        }

        public static void readsThroughARef(final LocalIntRef total) {
            SEEN.add("saw " + total.get());
        }

        public static void wantsMutableByValue(final int total) {
            SEEN.add("saw " + total);
        }
    }

    private static final ClassDesc CD_INT_REF =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.LocalIntRef");

    private static final ClassDesc CD_LONG_REF =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.LocalLongRef");

    private static final ClassDesc CD_REF =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.LocalRef");

    private static PointSpec atMark() {
        return PointSpec.builtIn(Point.INVOKE).target("#mark").build();
    }

    @Nested
    @DisplayName("a mutable capture writes back into the target's own slot")
    class Mutable {

        @Test
        @DisplayName("an int the handler writes is the int the target goes on to use")
        void anIntIsWrittenBack() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "mutates", atMark(),
                    List.of(new LocalSpec(0, "total", -1, -1, true)),
                    handler("bumpTotal", CD_INT_REF));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();

            final Object result = invoke(woven, "mutates", 7);
            assertThat(SEEN)
                    .as("the handler has to have seen the variable's real value, not a default")
                    .containsExactly("saw 7");
            assertThat(result)
                    .as("the whole point: without the write-back this is total=7, and the "
                            + "handler's set() would have changed its own copy while the target "
                            + "carried on with the old value — silently, which is why @Local's "
                            + "mutable flag could not stay a flag")
                    .isEqualTo("total=107");
        }

        @Test
        @DisplayName("a reference the handler replaces is the reference the target returns")
        void aReferenceIsWrittenBack() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "mutatesText", atMark(),
                    List.of(new LocalSpec(0, "text", -1, -1, true)),
                    handler("replaceText", CD_REF));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("the generic carrier erases, so get() returns Object and the slot's frame "
                            + "says otherwise — without the checkcast this does not verify")
                    .isEmpty();
            assertThat(invoke(woven, "mutatesText", 0)).isEqualTo("after");
        }

        @Test
        @DisplayName("a long is written back across both of its slots")
        void aWideValueIsWrittenBack() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "mutatesWide", atMark(),
                    List.of(new LocalSpec(0, "big", -1, -1, true)),
                    handler("bumpWide", CD_LONG_REF));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "mutatesWide", 0)).isEqualTo("42");
        }

        @Test
        @DisplayName("AW1053 — mutable = true on a plain parameter, which could only be a no-op")
        void mutableNeedsACarrier() {
            SEEN.clear();
            weave(WITH_DEBUG, "mutates", atMark(),
                    List.of(new LocalSpec(0, "total", -1, -1, true)),
                    handler("wantsMutableByValue", ConstantDescs.CD_int));

            assertThat(codes())
                    .as("Java passes parameters by value, so accepting this would emit code that "
                            + "does exactly nothing and says nothing")
                    .contains("AW1053");
        }

        @Test
        @DisplayName("AW1054 — a carrier without mutable = true, which reads as the wrong intent")
        void aCarrierNeedsMutable() {
            SEEN.clear();
            weave(WITH_DEBUG, "mutates", atMark(),
                    List.of(new LocalSpec(0, "total", -1, -1, false)),
                    handler("readsThroughARef", CD_INT_REF));

            assertThat(codes()).contains("AW1054");
        }
    }

    @Nested
    @DisplayName("the same declaration resolves per site")
    class PerSite {

        @Test
        @DisplayName("two sites of one injection read different slots, and both are right")
        void twoSitesOfOneInjectionReadDifferentSlots() throws Exception {
            final LocalTable table = tableOf("pick");
            final List<LocalTable.LocalSlot> both = table.slots().stream()
                    .filter(slot -> slot.name().equals("s"))
                    .toList();

            assertThat(both)
                    .as("the fixture must offer two variables named 's'")
                    .hasSize(2);
            assertThat(both.get(0).slot())
                    .as("this test is only meaningful while javac puts the two 's' variables in "
                            + "DIFFERENT slots. If this ever fails, the fixture stopped exercising "
                            + "per-site resolution and must be made to diverge again — it did not "
                            + "become correct, it became blind")
                    .isNotEqualTo(both.get(1).slot());

            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "pick", invokeUse(),
                    List.of(new LocalSpec(0, "s", -1, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();

            assertThat(invoke(woven, "pick", true)).isEqualTo("alpha");
            assertThat(invoke(woven, "pick", false)).isEqualTo("beta-1-2");

            assertThat(SEEN)
                    .as("each site read the variable that is live at IT, not the one that happened "
                            + "to be resolved first")
                    .containsExactly("alpha", "beta-1-2");
        }
    }

    @Nested
    @DisplayName("resolution strategies")
    class Strategies {

        @Test
        @DisplayName("by name, and the value actually arrives")
        void byName() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "compute", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "label", -1, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(reported).isEmpty();
            assertThat(invoke(woven, "compute", 3)).isEqualTo("total=9");
            assertThat(SEEN).containsExactly("total");
        }

        @Test
        @DisplayName("by type, when exactly one of that type is live")
        void byType() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "single", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "", -1, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(reported).isEmpty();
            assertThat(invoke(woven, "single", 1)).isEqualTo("only");
            assertThat(SEEN).containsExactly("only");
        }

        @Test
        @DisplayName("by ordinal, which picks the n-th of that type in slot order")
        void byOrdinal() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "two", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "", -1, 1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(reported).isEmpty();
            invoke(woven, "two", 1);
            assertThat(SEEN)
                    .as("ordinal 1 is the second in SLOT order, not in declaration order")
                    .containsExactly("second");
        }

        @Test
        @DisplayName("a long is read from its own slot, not from its index")
        void aLongIsReadFromItsSlot() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "widths", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "big", -1, -1, false)),
                    handler("onLong", ConstantDescs.CD_long));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("reading the high half of a category-2 value is a verify error, which is "
                            + "the GOOD outcome of getting this wrong")
                    .isEmpty();
            invoke(woven, "widths", 0);
            assertThat(SEEN).containsExactly("9000000000");
        }
    }

    @Nested
    @DisplayName("the engine refuses rather than guesses")
    class Refusals {

        @Test
        @DisplayName("AW1052 — resolving by name against a target with no debug information")
        void noDebugInformation() {
            weave(WITHOUT_DEBUG, "compute", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "label", -1, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(codes()).contains("AW1052");
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(remedy -> assertThat(remedy).contains("-g"));
        }

        @Test
        @DisplayName("AW1050 — a name that is not live here, and the message says what is")
        void nameNotLiveHere() {
            weave(WITH_DEBUG, "compute", PointSpec.builtIn(Point.HEAD).build(),
                    List.of(new LocalSpec(0, "label", -1, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(codes()).contains("AW1050");
            assertThat(reported.getFirst().details())
                    .as("'no local named label' is a dead end; 'live here: …' is a fix")
                    .anySatisfy(detail -> assertThat(detail).contains("live here"));
        }

        @Test
        @DisplayName("AW1051 — two locals of the type and nothing to choose between them")
        void ambiguousByType() {
            weave(WITH_DEBUG, "two", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "", -1, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(codes()).contains("AW1051");
            assertThat(reported.getFirst().details())
                    .as("both candidates are named, so the author can pick one")
                    .hasSize(2);
        }

        @Test
        @DisplayName("AW1050 — an explicit slot that holds something else there")
        void explicitSlotHoldsSomethingElse() {
            final LocalTable table = tableOf("widths");
            final int longSlot = table.slots().stream()
                    .filter(slot -> slot.name().equals("big"))
                    .findFirst().orElseThrow().slot();

            weave(WITH_DEBUG, "widths", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "", longSlot, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(codes())
                    .as("the escape hatch is unchecked about which variable it means, not about "
                            + "whether the load is possible")
                    .contains("AW1050");
        }

        @Test
        @DisplayName("AW1040 — a capture that is not one of the handler's last parameters")
        void captureNotAtTheTail() {
            weave(WITH_DEBUG, "compute", PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "label", -1, -1, false)),
                    new HandlerRef(HANDLER_OWNER, "onBoth",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean,
                                    ConstantDescs.CD_String),
                            Set.of(AccessFlag.STATIC)));

            assertThat(codes())
                    .as("the order is prefix, callback, captures — interleaving them leaves the "
                            + "parameter after a capture with no meaning at all")
                    .contains("AW1040");
            assertThat(reported.getFirst().message())
                    .contains("@Local on parameter 0");
        }
    }

    @Nested
    @DisplayName("the emission order is prefix, callback, captures")
    class Ordering {

        @Test
        @DisplayName("a handler taking all three receives all three, in the right slots")
        void allThreeArrive() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "compute",
                    PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(2, "label", -1, -1, false)),
                    new HandlerRef(HANDLER_OWNER, "onOrdered",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                                    ClassDesc.of(ReturnableCallback.class.getName()),
                                    ConstantDescs.CD_String),
                            Set.of(AccessFlag.STATIC)));

            assertThat(reported).isEmpty();
            assertThat(ClassFile.of().verify(woven))
                    .as("pushing the capture before the callback would put a String where a "
                            + "ReturnableCallback is expected, which is a verify error here — but "
                            + "only because those two types differ")
                    .isEmpty();

            assertThat(invoke(woven, "compute", 3)).isEqualTo("total=9");
            assertThat(SEEN).containsExactly("3/capture/total");
        }

        @Test
        @DisplayName("cancellation still works when the handler also captures a local")
        void cancellationSurvivesACapture() throws Exception {
            SEEN.clear();
            final byte[] woven = weave(WITH_DEBUG, "compute",
                    PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(2, "label", -1, -1, false)),
                    new HandlerRef(HANDLER_OWNER, "onOrderedAndCancel",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                                    ClassDesc.of(ReturnableCallback.class.getName()),
                                    ConstantDescs.CD_String),
                            Set.of(AccessFlag.STATIC)));

            assertThat(reported).isEmpty();
            assertThat(invoke(woven, "compute", 3))
                    .as("the capture is loaded into the same call that carries the callback, so a "
                            + "stack imbalance between them would break cancellation too")
                    .isEqualTo("cancelled:total");
        }
    }

    @Nested
    @DisplayName("the escape hatch works where nothing else does")
    class EscapeHatch {

        @Test
        @DisplayName("an explicit slot resolves against a target with no debug information")
        void explicitSlotNeedsNoTable() throws Exception {
            final int slot = tableOf("compute").slots().stream()
                    .filter(entry -> entry.name().equals("label"))
                    .findFirst().orElseThrow().slot();

            SEEN.clear();
            final byte[] woven = weave(WITHOUT_DEBUG, "compute",
                    PointSpec.builtIn(Point.RETURN).build(),
                    List.of(new LocalSpec(0, "", slot, -1, false)),
                    handler("onString", ConstantDescs.CD_String));

            assertThat(reported)
                    .as("index is the one strategy that does not consult the table, which is the "
                            + "whole point of it")
                    .isEmpty();
            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "compute", 3)).isEqualTo("total=9");
            assertThat(SEEN).containsExactly("total");
        }
    }

    @Nested
    @DisplayName("scope is part of the answer")
    class Scope {

        @Test
        @DisplayName("one slot holds two variables of different types over its lifetime")
        void oneSlotTwoVariables() {
            final LocalTable table = tableOf("reuse");
            final List<LocalTable.LocalSlot> sharing = table.slots().stream()
                    .filter(slot -> slot.name().equals("text") || slot.name().equals("number"))
                    .toList();

            assertThat(sharing).hasSize(2);
            assertThat(sharing.get(0).slot())
                    .as("javac reuses a slot once a scope ends — here for a reference and then a "
                            + "long. A scope-blind lookup for 'number' would hand back the slot "
                            + "while 'text' still occupies it")
                    .isEqualTo(sharing.get(1).slot());
            assertThat(sharing.get(0).type())
                    .isNotEqualTo(sharing.get(1).type());

            final LocalTable.LocalSlot text = sharing.stream()
                    .filter(slot -> slot.name().equals("text")).findFirst().orElseThrow();
            assertThat(table.byName("number", text.startIndex()))
                    .as("'number' is not live where 'text' begins, even though its slot is")
                    .isEmpty();
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static LocalTable tableOf(final String method) {
        final MethodModel model = ClassFile.of().parse(WITH_DEBUG).methods().stream()
                .filter(candidate -> candidate.methodName().equalsString(method))
                .findFirst().orElseThrow();
        return LocalTable.of(model.code().orElseThrow());
    }

    private static PointSpec invokeUse() {
        return PointSpec.builtIn(Point.INVOKE)
                .target("#use")
                .shift(At.Shift.BEFORE)
                .build();
    }

    private static HandlerRef handler(final String name, final ClassDesc capture) {
        return new HandlerRef(HANDLER_OWNER, name,
                MethodTypeDesc.of(ConstantDescs.CD_void, capture), Set.of(AccessFlag.STATIC));
    }

    private byte[] weave(final byte[] original,
                         final String method,
                         final PointSpec point,
                         final List<LocalSpec> locals,
                         final HandlerRef handler) {
        final ClassModel model = ClassFile.of().parse(original);
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                method, MemberSelector.parse(method), List.of(point), List.of(),
                "capture", 0, 0, "", locals);
        final PlanEntryView entry = new PlanEntry(TARGET, spec, "localfixture.Weave",
                Origin.of("test", null),
                new OrderKey(0, "localfixture.Weave", handler.name(),
                        handler.type().descriptorString()));

        final WeavingPipeline pipeline = new WeavingPipeline(
                BuiltInPoints.all()::get,
                kind -> InjectorKind.INJECT.id().equals(kind) ? new InjectInjector() : null);
        final byte[] woven = pipeline.weave(model, List.of(entry), List.of(), this.reporter);
        return woven == null ? original : woven;
    }

    private static Object invoke(final byte[] woven, final String method, final Object argument)
            throws Exception {
        final ClassLoader loader = new ClassLoader(LocalCaptureTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("localfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Class<?> type = loader.loadClass("localfixture.Target");
        final Object instance = type.getDeclaredConstructor().newInstance();
        final Class<?> parameter = argument instanceof Boolean ? boolean.class : int.class;
        final Method target = type.getDeclaredMethod(method, parameter);
        return target.invoke(instance, argument);
    }

    private static byte[] compile(final boolean debug) {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-locals");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final List<String> options = debug ? List.of("-g") : List.of("-g:none");
                final boolean ok = compiler.getTask(null, files, null, options, null,
                        List.of(new Source())).call();
                if (!ok) {
                    throw new AssertionError("the local-capture fixture must compile");
                }
            }
            return Files.readAllBytes(output.resolve("localfixture/Target.class"));
        } catch (final Exception failed) {
            throw new AssertionError("could not build the local-capture fixture", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private static final String CODE = """
                package localfixture;

                public class Target {

                    public String pick(boolean first) {
                        if (first) {
                            String s = "alpha";
                            return use(s);
                        }
                        // These exist to push the SECOND 's' into a different slot from the
                        // first, AND to leave a String in the first 's' slot while doing it.
                        // Without them javac reuses one slot and the per-site test would pass
                        // while testing nothing; without 'pad' being a String, reading the stale
                        // slot would be a VerifyError — loud, and therefore not the failure this
                        // is about.
                        String pad = "1";
                        long wide = 2L;
                        String s = "beta-" + pad + "-" + wide;
                        return use(s);
                    }

                    public String use(String value) {
                        return value;
                    }

                    // mark() exists so that a capture can be injected while the locals are
                    // still live AND still used afterwards. At RETURN the return value has already
                    // been computed, so a write-back would be correct and invisible.
                    public void mark() { }

                    public String mutates(int times) {
                        int total = times;
                        mark();
                        return "total=" + total;
                    }

                    public String mutatesText(int times) {
                        String text = "before";
                        mark();
                        return text;
                    }

                    public String mutatesWide(int times) {
                        long big = 1L;
                        mark();
                        return Long.toString(big);
                    }

                    public String compute(int times) {
                        int total = times * times;
                        String label = "total";
                        return label + "=" + total;
                    }

                    public String single(int times) {
                        String only = "only";
                        return only;
                    }

                    public String two(int times) {
                        String first = "first";
                        String second = "second";
                        return first + second;
                    }

                    public String widths(int times) {
                        long big = 9000000000L;
                        return Long.toString(big);
                    }

                    public String reuse(int times) {
                        if (times > 0) {
                            String text = "in scope";
                            System.identityHashCode(text);
                        }
                        long number = 7L;
                        return Long.toString(number);
                    }
                }
                """;

        Source() {
            super(URI.create("string:///localfixture/Target.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
