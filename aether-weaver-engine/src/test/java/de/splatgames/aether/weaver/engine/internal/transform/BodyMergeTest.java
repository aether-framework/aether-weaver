package de.splatgames.aether.weaver.engine.internal.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class BodyMergeTest {

    @SuppressWarnings("all")
    public static class Source {
        public int value = 7;

        public String compute(final int times) {
            final StringBuilder builder = new StringBuilder();
            for (int index = 0; index < times; index++) {
                final long scaled = (long) index * this.value;
                final double ratio = scaled / 2.0;
                builder.append(describe(scaled, ratio)).append(';');
            }
            return builder.isEmpty() ? "empty" : builder.toString();
        }

        private String describe(final long scaled, final double ratio) {
            return scaled + "/" + ratio;
        }
    }

    @SuppressWarnings("all")
    public static class Target {
        public int value = 3;

        public String compute(final int times) {
            return "original";
        }

        private String describe(final long scaled, final double ratio) {
            return "target:" + scaled;
        }
    }

    private static final ClassDesc SOURCE = ClassDesc.of(Source.class.getName());
    private static final ClassDesc TARGET = ClassDesc.of(Target.class.getName());

    @Test
    @DisplayName("a body remapped into a target verifies, loads and runs")
    void mergedBodyVerifiesLoadsAndRuns() throws Exception {
        final ClassFile classFile = FrameSupport.forBuildTime(getClass().getClassLoader());
        final ClassRemapper remapper = ClassRemapper.of(Map.of(SOURCE, TARGET));

        final ClassModel sourceModel = classFile.parse(readClass(Source.class));
        final ClassModel targetModel = classFile.parse(readClass(Target.class));

        final MethodModel donor = sourceModel.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();

        // Replace the target's compute() with the source's, remapped and relabelled.
        final byte[] woven = classFile.transformClass(targetModel, ClassTransform.dropping(
                        element -> element instanceof MethodModel m
                                && m.methodName().equalsString("compute"))
                .andThen(ClassTransform.endHandler(cb -> cb.withMethod(
                        "compute", donor.methodTypeSymbol(), donor.flags().flagsMask(),
                        mb -> donor.code().ifPresent(code -> mb.transformCode(code,
                                remapper.asCodeTransform().andThen(CodeRelabeler.transform())))))));

        assertThat(classFile.verify(woven))
                .as("the merged class must verify")
                .isEmpty();

        final Object instance = defineAndInstantiate(woven);
        final Object result = instance.getClass()
                .getMethod("compute", int.class).invoke(instance, 3);

        assertThat(result)
                .as("the source's loop must run, reading the target's field (value=3) and "
                        + "calling the target's describe() rather than the source's")
                .isEqualTo("target:0;target:3;target:6;");
    }

    @Test
    @DisplayName("shifting locals keeps behaviour identical")
    void shiftingLocalsPreservesBehaviour() throws Exception {
        final ClassFile classFile = FrameSupport.forBuildTime(getClass().getClassLoader());
        final ClassModel model = classFile.parse(readClass(Source.class));

        final MethodModel compute = model.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();
        final int originalMaxLocals = ((CodeAttribute) compute.code().orElseThrow()).maxLocals();

        for (final int shift : new int[]{0, 1, 4, 17}) {
            final CodeTransform shifter = LocalsShifter.forMethod(compute, shift);
            final byte[] woven = classFile.transformClass(model,
                    ClassTransform.transformingMethodBodies(
                            m -> m.methodName().equalsString("compute"), shifter));

            assertThat(classFile.verify(woven))
                    .as("shift of %d must verify", shift)
                    .isEmpty();

            final Object instance = defineAndInstantiate(woven);
            assertThat(instance.getClass().getMethod("compute", int.class).invoke(instance, 3))
                    .as("shift of %d must not change behaviour", shift)
                    .isEqualTo("0/0.0;7/3.5;14/7.0;");

            final ClassModel reparsed = classFile.parse(woven);
            final int shiftedMaxLocals = ((CodeAttribute) reparsed.methods().stream()
                    .filter(m -> m.methodName().equalsString("compute"))
                    .findFirst().orElseThrow().code().orElseThrow()).maxLocals();
            assertThat(shiftedMaxLocals)
                    .as("the frame must grow by the shift")
                    .isEqualTo(originalMaxLocals + shift);
        }
    }

    @Test
    @DisplayName("the receiver and parameters are never shifted")
    void receiverAndParametersAreNeverShifted() {
        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(readClass(Source.class));
        final MethodModel compute = model.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();

        final LocalsShifter shifter = LocalsShifter.forMethod(compute, 10);

        // compute(int) is an instance method: slot 0 is 'this', slot 1 is 'times'.
        assertThat(shifter.mapSlot(0)).as("this must stay at slot 0").isZero();
        assertThat(shifter.mapSlot(1)).as("the parameter must stay at slot 1").isEqualTo(1);
        assertThat(shifter.mapSlot(2)).as("the first real local must move").isEqualTo(12);
    }

    @Test
    @DisplayName("category-2 parameters are counted as two slots")
    void categoryTwoParametersOccupyTwoSlots() {
        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(readClass(Source.class));
        final MethodModel describe = model.methods().stream()
                .filter(m -> m.methodName().equalsString("describe"))
                .findFirst().orElseThrow();

        // describe(long, double) is an instance method: this=0, long=1..2, double=3..4.
        final LocalsShifter shifter = LocalsShifter.forMethod(describe, 5);
        assertThat(shifter.mapSlot(0)).isZero();
        assertThat(shifter.mapSlot(4)).as("the second half of the double must not move").isEqualTo(4);
        assertThat(shifter.mapSlot(5)).as("the first local beyond the frame must move").isEqualTo(10);
    }

    @Test
    @DisplayName("one body copied into two methods produces two independent, correct bodies")
    void oneBodyCopiedTwiceStaysIndependent() throws Exception {
        // Without fresh labels the second copy would reuse the first's, which is the failure
        // mode that never appears when a body is copied only once.
        final ClassFile classFile = FrameSupport.forBuildTime(getClass().getClassLoader());
        final ClassModel model = classFile.parse(readClass(Source.class));
        final MethodModel compute = model.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();

        final byte[] woven = classFile.transformClass(model, ClassTransform.endHandler(cb -> {
            for (final String name : new String[]{"copyOne", "copyTwo"}) {
                cb.withMethod(name, compute.methodTypeSymbol(), compute.flags().flagsMask(),
                        mb -> compute.code().ifPresent(
                                code -> mb.transformCode(code, CodeRelabeler.transform())));
            }
        }));

        assertThat(classFile.verify(woven)).isEmpty();

        final Object instance = defineAndInstantiate(woven);
        for (final String name : new String[]{"compute", "copyOne", "copyTwo"}) {
            assertThat(instance.getClass().getMethod(name, int.class).invoke(instance, 3))
                    .as("%s must behave like the original", name)
                    .isEqualTo("0/0.0;7/3.5;14/7.0;");
        }
    }

    @Test
    @DisplayName("the local variable table survives a shift and still names the variables")
    void debugInformationSurvivesAShift() {
        final ClassFile classFile = FrameSupport.forBuildTime(getClass().getClassLoader());
        final ClassModel model = classFile.parse(readClass(Source.class));
        final MethodModel compute = model.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();

        final LocalTable before = LocalTable.of(compute.code().orElseThrow());
        assertThat(before.isAvailable())
                .as("the fixture must be compiled with -g for this test to mean anything")
                .isTrue();
        assertThat(before.slots()).extracting(LocalTable.LocalSlot::name)
                .contains("builder", "index", "scaled", "ratio");

        final byte[] woven = classFile.transformClass(model,
                ClassTransform.transformingMethodBodies(
                        m -> m.methodName().equalsString("compute"),
                        LocalsShifter.forMethod(compute, 6)));

        final LocalTable after = LocalTable.of(classFile.parse(woven).methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow().code().orElseThrow());

        assertThat(after.slots()).extracting(LocalTable.LocalSlot::name)
                .as("a debugger must still be able to name the shifted locals")
                .containsExactlyInAnyOrderElementsOf(
                        before.slots().stream().map(LocalTable.LocalSlot::name).toList());

        final int beforeSlot = before.slots().stream()
                .filter(s -> s.name().equals("index")).findFirst().orElseThrow().slot();
        final int afterSlot = after.slots().stream()
                .filter(s -> s.name().equals("index")).findFirst().orElseThrow().slot();
        assertThat(afterSlot)
                .as("the debug entry must move with the variable, or a debugger shows the wrong one")
                .isEqualTo(beforeSlot + 6);
    }

    @Test
    @DisplayName("a class without debug information reports it rather than guessing")
    void missingDebugInformationIsReported() {
        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(readClass(Source.class));
        final MethodModel compute = model.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();

        // A method built without any LocalVariable elements, which is what a -g:none build
        // produces. Synthesising it keeps the test independent of compiler flags.
        final byte[] stripped = classFile.build(ClassDesc.of("NoDebugInfo"), cb -> cb
                .withMethodBody("compute", compute.methodTypeSymbol(),
                        java.lang.classfile.ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).areturn()));

        final LocalTable table = LocalTable.of(classFile.parse(stripped).methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow().code().orElseThrow());

        assertThat(table.isAvailable()).isFalse();
        assertThat(table.byName("index", 0))
                .as("a by-name lookup must fail rather than return an arbitrary slot")
                .isEmpty();

        // The same lookup succeeds when the table is present — asked at a point where the
        // variable is actually live, since scope is part of the answer.
        final LocalTable present = LocalTable.of(compute.code().orElseThrow());
        final int liveIndex = present.slots().stream()
                .filter(slot -> slot.name().equals("index"))
                .findFirst().orElseThrow().startIndex();
        assertThat(present.byName("index", liveIndex))
                .as("the same lookup succeeds when the table is present")
                .isPresent();
    }

    @Test
    @DisplayName("local lookups respect scope")
    void lookupsRespectScope() {
        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(readClass(Source.class));
        final MethodModel compute = model.methods().stream()
                .filter(m -> m.methodName().equalsString("compute"))
                .findFirst().orElseThrow();
        final LocalTable table = LocalTable.of(compute.code().orElseThrow());

        final LocalTable.LocalSlot index = table.slots().stream()
                .filter(s -> s.name().equals("index")).findFirst().orElseThrow();

        assertThat(table.byName("index", index.startIndex()))
                .as("live at the start of its scope").isPresent();
        assertThat(table.byName("index", index.startIndex() - 1))
                .as("not yet live before its scope").isEmpty();
        assertThat(table.byName("index", index.endIndex()))
                .as("no longer live at the end of its scope, which is exclusive").isEmpty();
    }

    // -------------------------------------------------------------------------------------

    private static byte[] readClass(final Class<?> type) {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return Objects.requireNonNull(in, resource).readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object defineAndInstantiate(final byte[] bytes)
            throws ReflectiveOperationException {
        final String name = ClassFile.of().parse(bytes).thisClass().asInternalName()
                .replace('/', '.');
        // Parent-first delegation would hand back the original class, since the fixture is on
        // the application class path. This loader defines the woven bytes itself and delegates
        // everything else.
        final ClassLoader loader = new ClassLoader(BodyMergeTest.class.getClassLoader()) {
            private Class<?> woven;

            @Override
            protected synchronized Class<?> loadClass(final String requested, final boolean resolve)
                    throws ClassNotFoundException {
                if (!requested.equals(name)) {
                    return super.loadClass(requested, resolve);
                }
                if (this.woven == null) {
                    this.woven = defineClass(requested, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(this.woven);
                }
                return this.woven;
            }
        };
        try {
            return loader.loadClass(name).getDeclaredConstructor().newInstance();
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
