package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.callback.Callback;
import de.splatgames.aether.weaver.api.callback.ReturnableCallback;
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
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackEmissionTest {

    static Consumer<Object> BEHAVIOUR = callback -> { };

    private static final ClassDesc TARGET = ClassDesc.of("callbackfixture.Target");

    private static final ClassDesc OWNER = ClassDesc.of(Handlers.class.getName());

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void onVoid(final Callback callback) {
            BEHAVIOUR.accept(callback);
        }

        public static void onString(final ReturnableCallback<String> callback) {
            BEHAVIOUR.accept(callback);
        }

        public static void onInt(final ReturnableCallback<Integer> callback) {
            BEHAVIOUR.accept(callback);
        }

        public static void onLong(final ReturnableCallback<Long> callback) {
            BEHAVIOUR.accept(callback);
        }
    }

    @Nested
    @DisplayName("cancellation changes what the target returns")
    class Cancelling {

        @Test
        @DisplayName("a reference return is cast and returned")
        void referenceReturn() throws Exception {
            BEHAVIOUR = cb -> ((ReturnableCallback<String>) cb).cancel("instead");

            assertThat(run("returnsString", "onString", ConstantDescs.CD_String,
                    TypeKind.REFERENCE))
                    .isEqualTo("instead");
        }

        @Test
        @DisplayName("an int return is cast to Integer and unboxed")
        void intReturn() throws Exception {
            BEHAVIOUR = cb -> ((ReturnableCallback<Integer>) cb).cancel(42);

            assertThat(run("returnsInt", "onInt", ConstantDescs.CD_int, TypeKind.INT))
                    .as("value() erases to Object, so a primitive return needs a cast to the BOXED "
                            + "type and then an unboxing call — the wrong box verifies and fails "
                            + "only when a handler actually cancels")
                    .isEqualTo(42);
        }

        @Test
        @DisplayName("a long return works too, and it is the wide case")
        void longReturn() throws Exception {
            BEHAVIOUR = cb -> ((ReturnableCallback<Long>) cb).cancel(77L);

            assertThat(runAtReturn("returnsLong", "onLong", ConstantDescs.CD_long, TypeKind.LONG))
                    .isEqualTo(77L);
        }

        @Test
        @DisplayName("a void target simply returns early")
        void voidReturn() throws Exception {
            BEHAVIOUR = cb -> ((Callback) cb).cancel();

            assertThat(run("returnsVoid", "onVoid", ConstantDescs.CD_void, TypeKind.VOID)).isNull();
        }
    }

    @Nested
    @DisplayName("not cancelling leaves the target alone")
    class NotCancelling {

        @Test
        @DisplayName("the target's own value is returned when nothing cancelled")
        void ownValueSurvives() throws Exception {
            BEHAVIOUR = cb -> { };

            assertThat(run("returnsString", "onString", ConstantDescs.CD_String,
                    TypeKind.REFERENCE))
                    .as("the branch must fall through to the target's own code")
                    .isEqualTo("original");
            assertThat(run("returnsInt", "onInt", ConstantDescs.CD_int, TypeKind.INT))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the handler sees the value the target was about to return")
        void theComputedValueIsAvailable() throws Exception {
            final List<Object> seen = new ArrayList<>();
            BEHAVIOUR = cb -> seen.add(((ReturnableCallback<?>) cb).value());

            assertThat(runAtReturn("returnsString", "onString", ConstantDescs.CD_String,
                    TypeKind.REFERENCE))
                    .as("reading the value must not consume it")
                    .isEqualTo("original");
            assertThat(seen)
                    .as("ReturnableCallback.value() documents this as available at RETURN, and "
                            + "carries an example that audits the result. Before the emission "
                            + "captured the stack value it answered null there — so the documented "
                            + "shape threw a NullPointerException on its own example")
                    .containsExactly("original");
        }

        @Test
        @DisplayName("a primitive return arrives boxed rather than as a default")
        void aPrimitiveValueIsBoxed() throws Exception {
            final List<Object> seen = new ArrayList<>();
            BEHAVIOUR = cb -> seen.add(((ReturnableCallback<?>) cb).value());

            assertThat(runAtReturn("returnsInt", "onInt", ConstantDescs.CD_int, TypeKind.INT))
                    .isEqualTo(1);
            assertThat(seen)
                    .as("null or 0 here would be indistinguishable from a target that really "
                            + "computed them")
                    .containsExactly(1);
        }

        @Test
        @DisplayName("a two-slot return is captured without disturbing the stack")
        void aWideValueIsCaptured() throws Exception {
            final List<Object> seen = new ArrayList<>();
            BEHAVIOUR = cb -> seen.add(((ReturnableCallback<?>) cb).value());

            assertThat(runAtReturn("returnsLong", "onLong", ConstantDescs.CD_long, TypeKind.LONG))
                    .as("a long occupies two slots, so copying it aside needs dup2 — dup would "
                            + "split the value and the verifier would reject the method")
                    .isEqualTo(2L);
            assertThat(seen).containsExactly(2L);
        }

        @Test
        @DisplayName("the handler still sees a callback it can query")
        void callbackIsUsable() throws Exception {
            final List<Boolean> seen = new ArrayList<>();
            BEHAVIOUR = cb -> seen.add(((Callback) cb).isCancelled());

            run("returnsString", "onString", ConstantDescs.CD_String, TypeKind.REFERENCE);

            assertThat(seen).containsExactly(false);
        }

        @Test
        @DisplayName("the callback carries the injection's id")
        void callbackCarriesTheId() throws Exception {
            final List<String> ids = new ArrayList<>();
            BEHAVIOUR = cb -> ids.add(((Callback) cb).id());

            run("returnsString", "onString", ConstantDescs.CD_String, TypeKind.REFERENCE);

            assertThat(ids)
                    .as("a handler shared between sites uses this to tell them apart")
                    .containsExactly("onString");
        }
    }

    @Nested
    @DisplayName("the callback flavour must suit the target")
    class Flavours {

        @Test
        @DisplayName("a plain Callback on a value-returning target is AW1070")
        void plainCallbackOnReturningTarget() {
            assertThat(bind("returnsString", ClassDesc.of(
                    "de.splatgames.aether.weaver.api.callback.Callback"))).isNull();

            assertThat(codes()).containsExactly("AW1070");
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(r -> assertThat(r).contains("ReturnableCallback"));
        }

        @Test
        @DisplayName("a ReturnableCallback on a void target is AW1071")
        void returnableCallbackOnVoidTarget() {
            assertThat(bind("returnsVoid", ClassDesc.of(
                    "de.splatgames.aether.weaver.api.callback.ReturnableCallback"))).isNull();

            assertThat(codes()).containsExactly("AW1071");
        }

        @Test
        @DisplayName("the right flavour binds and is recognised")
        void rightFlavourBinds() {
            final HandlerBinding binding = bind("returnsString", ClassDesc.of(
                    "de.splatgames.aether.weaver.api.callback.ReturnableCallback"));

            assertThat(binding).isNotNull();
            assertThat(binding.takesCallback()).isTrue();
            assertThat(binding.callbackKind())
                    .isEqualTo(HandlerBinding.CallbackKind.RETURNABLE);
            assertThat(binding.arity())
                    .as("the callback is supplied by the engine, so it is not a target argument")
                    .isZero();
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private static Object run(final String method, final String handlerName,
                              final ClassDesc returnType, final TypeKind returnKind)
            throws Exception {
        return run(method, handlerName, returnType, returnKind, 0);
    }

    private static Object runAtReturn(final String method, final String handlerName,
                                      final ClassDesc returnType, final TypeKind returnKind)
            throws Exception {
        return run(method, handlerName, returnType, returnKind, returnIndexOf(method));
    }

    private static int returnIndexOf(final String method) {
        final List<java.lang.classfile.CodeElement> elements =
                ClassFile.of().parse(fixture()).methods().stream()
                        .filter(candidate -> method.equals(candidate.methodName().stringValue()))
                        .findFirst().orElseThrow()
                        .code().orElseThrow().elementList();
        for (int index = elements.size() - 1; index >= 0; index--) {
            if (elements.get(index) instanceof java.lang.classfile.instruction.ReturnInstruction) {
                return index;
            }
        }
        throw new AssertionError("the fixture's " + method + " must return");
    }

    private static Object run(final String method, final String handlerName,
                              final ClassDesc returnType, final TypeKind returnKind,
                              final int site)
            throws Exception {
        assertThat(TypeKind.from(returnType))
                .as("the fixture's declared kind and the requested one must agree")
                .isEqualTo(returnKind);
        final ClassDesc callbackType = returnKind == TypeKind.VOID
                ? ClassDesc.of("de.splatgames.aether.weaver.api.callback.Callback")
                : ClassDesc.of("de.splatgames.aether.weaver.api.callback.ReturnableCallback");
        final HandlerRef handler = new HandlerRef(OWNER, handlerName,
                MethodTypeDesc.of(ConstantDescs.CD_void, callbackType),
                Set.of(AccessFlag.STATIC));

        final HandlerBinding binding =
                HandlerBinding.bind(handler, method(method), Reporter.NOOP);
        assertThat(binding).as("the fixture binding must be valid").isNotNull();

        final CodeTransform calls = new InjectInjector()
                .codeTransform(handler, Set.of(site), binding, returnType, handlerName);

        final byte[] woven = ClassFile.of().transformClass(ClassFile.of().parse(fixture()),
                ClassTransform.transformingMethodBodies(
                        m -> method.equals(m.methodName().stringValue()),
                        CodeTransform.ofStateful(() -> calls)));

        assertThat(ClassFile.of().verify(woven))
                .as("a wrong box type or a mismatched branch shows up here")
                .isEmpty();

        final ClassLoader loader = new ClassLoader(CallbackEmissionTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("callbackfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Class<?> type = loader.loadClass("callbackfixture.Target");
        final Method target = type.getDeclaredMethod(method);
        return target.invoke(type.getDeclaredConstructor().newInstance());
    }

    private HandlerBinding bind(final String method, final ClassDesc callbackType) {
        return HandlerBinding.bind(
                new HandlerRef(OWNER, "handler",
                        MethodTypeDesc.of(ConstantDescs.CD_void, callbackType),
                        Set.of(AccessFlag.STATIC)),
                method(method), this.reporter);
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
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
            builder.withMethodBody("returnsVoid", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, code -> code.return_());
            builder.withMethodBody("returnsString", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code.ldc("original").areturn());
            builder.withMethodBody("returnsInt", MethodTypeDesc.of(ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC, code -> code.iconst_1().ireturn());
            builder.withMethodBody("returnsLong", MethodTypeDesc.of(ConstantDescs.CD_long),
                    ClassFile.ACC_PUBLIC, code -> code.loadConstant(2L).lreturn());
        });
    }
}
