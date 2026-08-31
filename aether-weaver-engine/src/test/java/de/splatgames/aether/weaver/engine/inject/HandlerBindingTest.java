package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerBindingTest {

    static final List<Object> RECEIVED = new ArrayList<>();

    private static final ClassDesc TARGET = ClassDesc.of("bindingfixture.Target");

    private static final ClassDesc OWNER = ClassDesc.of(Handlers.class.getName());

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void wide(final int a, final long b, final int c) {
            RECEIVED.add(a);
            RECEIVED.add(b);
            RECEIVED.add(c);
        }

        public static void prefix(final int first) {
            RECEIVED.add(first);
        }

        public static void mixed(final double d, final String s) {
            RECEIVED.add(d);
            RECEIVED.add(s);
        }
    }

    @Nested
    @DisplayName("the values that arrive are the values that were passed")
    class Values {

        @Test
        @DisplayName("an int, a long and an int survive the two-slot gap")
        void wideParameterDoesNotShiftTheNextOne() throws Exception {
            RECEIVED.clear();
            invoke(weave("takesWide", "wide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_long, ConstantDescs.CD_int)),
                    "takesWide", new Class<?>[]{int.class, long.class, int.class},
                    11, 22L, 33);

            assertThat(RECEIVED)
                    .as("using the parameter index instead of its slot loads the high half of the "
                            + "long where it meant the third parameter — a valid int in a valid "
                            + "slot, which the verifier accepts and nothing reports")
                    .containsExactly(11, 22L, 33);
        }

        @Test
        @DisplayName("a double and a reference survive it too")
        void doubleAndReference() throws Exception {
            RECEIVED.clear();
            invoke(weave("takesMixed", "mixed",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_double,
                            ConstantDescs.CD_String)),
                    "takesMixed", new Class<?>[]{double.class, String.class},
                    1.5d, "text");

            assertThat(RECEIVED).containsExactly(1.5d, "text");
        }

        @Test
        @DisplayName("a handler may take a prefix and ignore the rest")
        void prefixOnly() throws Exception {
            RECEIVED.clear();
            invoke(weave("takesWide", "prefix",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int)),
                    "takesWide", new Class<?>[]{int.class, long.class, int.class},
                    7, 8L, 9);

            assertThat(RECEIVED).containsExactly(7);
        }
    }

    @Nested
    @DisplayName("slot arithmetic")
    class Slots {

        @Test
        @DisplayName("an instance method's parameters start at slot 1")
        void instanceMethodSkipsThis() {
            final HandlerBinding binding = bind("takesWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_long, ConstantDescs.CD_int));

            assertThat(binding).isNotNull();
            assertThat(binding.loads())
                    .as("slot 0 is `this`; a long then occupies 2 and 3, so the third parameter "
                            + "lives at 4")
                    .containsExactly(
                            new HandlerBinding.Load(1, TypeKind.INT),
                            new HandlerBinding.Load(2, TypeKind.LONG),
                            new HandlerBinding.Load(4, TypeKind.INT));
        }

        @Test
        @DisplayName("a static method's parameters start at slot 0")
        void staticMethodStartsAtZero() {
            final HandlerBinding binding = bind("staticWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_long, ConstantDescs.CD_int));

            assertThat(binding).isNotNull();
            assertThat(binding.loads()).containsExactly(
                    new HandlerBinding.Load(0, TypeKind.INT),
                    new HandlerBinding.Load(1, TypeKind.LONG),
                    new HandlerBinding.Load(3, TypeKind.INT));
        }

        @Test
        @DisplayName("a no-argument handler binds to nothing")
        void noArguments() {
            final HandlerBinding binding = bind("takesWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void));

            assertThat(binding).isNotNull();
            assertThat(binding.arity()).isZero();
        }
    }

    @Nested
    @DisplayName("mismatches are refused with a usable message")
    class Mismatches {

        @Test
        @DisplayName("a handler taking more than the target has")
        void tooManyParameters() {
            assertThat(bind("takesMixed",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_double,
                            ConstantDescs.CD_String, ConstantDescs.CD_int)))
                    .isNull();
            assertThat(codes()).containsExactly("AW1040");
        }

        @Test
        @DisplayName("a handler taking the wrong type in a position")
        void wrongType() {
            assertThat(bind("takesWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String)))
                    .isNull();
            assertThat(codes()).containsExactly("AW1040");
            assertThat(reported.getFirst().details())
                    .anySatisfy(d -> assertThat(d).contains("parameter 0"));
        }

        @Test
        @DisplayName("a suffix is refused, not silently reinterpreted")
        void suffixIsRefused() {
            assertThat(bind("takesWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long)))
                    .as("a handler taking the target's SECOND parameter looks reasonable and is "
                            + "not expressible: a parameter has no identity beyond its position")
                    .isNull();
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(r -> assertThat(r).contains("PREFIX"));
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private HandlerBinding bind(final String method, final MethodTypeDesc handlerType) {
        return HandlerBinding.bind(
                new HandlerRef(OWNER, "handler", handlerType, Set.of(AccessFlag.STATIC)),
                method(method), this.reporter);
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private static byte[] weave(final String method, final String handlerName,
                                final MethodTypeDesc handlerType) {
        final HandlerRef handler =
                new HandlerRef(OWNER, handlerName, handlerType, Set.of(AccessFlag.STATIC));
        final HandlerBinding binding =
                HandlerBinding.bind(handler, method(method), Reporter.NOOP);
        assertThat(binding).as("the fixture binding must be valid").isNotNull();

        final CodeTransform calls =
                new InjectInjector().codeTransform(handler, Set.of(0), binding);

        return ClassFile.of().transformClass(ClassFile.of().parse(fixture()),
                ClassTransform.transformingMethodBodies(
                        m -> method.equals(m.methodName().stringValue()),
                        CodeTransform.ofStateful(() -> calls)));
    }

    private static void invoke(final byte[] woven, final String method,
                               final Class<?>[] signature, final Object... arguments)
            throws Exception {
        assertThat(ClassFile.of().verify(woven))
                .as("nothing is executed that has not verified first")
                .isEmpty();

        final ClassLoader loader = new ClassLoader(HandlerBindingTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("bindingfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Class<?> type = loader.loadClass("bindingfixture.Target");
        final Method target = type.getDeclaredMethod(method, signature);
        if (java.lang.reflect.Modifier.isStatic(target.getModifiers())) {
            target.invoke(null, arguments);
        } else {
            target.invoke(type.getDeclaredConstructor().newInstance(), arguments);
        }
    }

    private static MethodView method(final String name) {
        final TargetView target = ModelViews.of(ClassFile.of().parse(fixture()));
        return target.methods().stream()
                .filter(m -> name.equals(m.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture must declare " + name));
    }

    private static byte[] fixture() {
        return ClassFile.of().build(TARGET, builder -> {
            builder.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .return_());
            builder.withMethodBody("takesWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_long, ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC, code -> code.return_());
            builder.withMethodBody("staticWide",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_long, ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_());
            builder.withMethodBody("takesMixed",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_double,
                            ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code.return_());
        });
    }
}
