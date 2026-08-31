package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.callback.Callback;
import de.splatgames.aether.weaver.api.callback.CallbackSupport;
import de.splatgames.aether.weaver.api.callback.ReturnableCallback;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import de.splatgames.aether.weaver.engine.inject.point.PointResolver;
import de.splatgames.aether.weaver.engine.plan.OrderKey;
import de.splatgames.aether.weaver.engine.plan.PlanEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InjectInjectorTest {

    static final List<String> CALLS = new ArrayList<>();

    private static final ClassDesc TARGET = ClassDesc.of("wovenfixture.Target");

    private static final ClassDesc HANDLER_OWNER = ClassDesc.of(Handlers.class.getName());

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    public static final class Handlers {

        private Handlers() {
        }

        public static void onWork() {
            CALLS.add("onWork");
        }
    }

    @Nested
    @DisplayName("the woven class loads and runs")
    class EndToEnd {

        @Test
        @DisplayName("a HEAD injection calls the handler before the target's own code")
        void headInjectionRunsFirst() throws Exception {
            CALLS.clear();
            final byte[] woven = weave("work");

            assertThat(ClassFile.of().verify(woven))
                    .as("nothing is defined into a JVM that has not verified first")
                    .isEmpty();

            final Object result = invoke(woven, "work");

            assertThat(CALLS)
                    .as("the handler ran, which is only observable because the class actually "
                            + "loaded and executed")
                    .containsExactly("onWork");
            assertThat(result)
                    .as("the target's own behaviour is unchanged")
                    .isEqualTo("done");
        }

        @Test
        @DisplayName("the target's other methods are untouched")
        void otherMethodsAreUntouched() throws Exception {
            CALLS.clear();
            final byte[] woven = weave("work");

            assertThat(invoke(woven, "untouched")).isEqualTo("untouched");
            assertThat(CALLS)
                    .as("a transform that leaked across methods would fire here too")
                    .isEmpty();
        }

        @Test
        @DisplayName("weaving twice from the same inputs is byte-identical")
        void emissionIsDeterministic() {
            assertThat(weave("work"))
                    .as("guarantee G7 is about the bytes, so it is asserted on the bytes")
                    .isEqualTo(weave("work"));
        }

        @Test
        @DisplayName("a RETURN injection calls the handler on the way out")
        void returnInjection() throws Exception {
            CALLS.clear();
            final byte[] woven = weave("work", Point.RETURN);

            assertThat(ClassFile.of().verify(woven)).isEmpty();
            assertThat(invoke(woven, "work")).isEqualTo("done");
            assertThat(CALLS).containsExactly("onWork");
        }
    }

    @Nested
    @DisplayName("validation refuses what it cannot emit")
    class Validation {

        @Test
        @DisplayName("an instance handler is refused")
        void instanceHandlerRefused() {
            validate(new HandlerRef(HANDLER_OWNER, "onWork",
                    MethodTypeDesc.of(ConstantDescs.CD_void), Set.of()));

            assertThat(codes()).contains("AW1005");
        }

        @Test
        @DisplayName("a non-void handler is refused")
        void nonVoidHandlerRefused() {
            validate(new HandlerRef(HANDLER_OWNER, "onWork",
                    MethodTypeDesc.of(ConstantDescs.CD_int), Set.of(AccessFlag.STATIC)));

            assertThat(codes()).contains("AW1041");
        }

        @Test
        @DisplayName("parameters are NOT judged here — that needs a target method")
        void parametersAreNotJudgedHere() {
            validate(new HandlerRef(HANDLER_OWNER, "onWork",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int),
                    Set.of(AccessFlag.STATIC)));

            assertThat(codes())
                    .as("whether a handler's parameters fit is a question about ONE target method, "
                            + "so it belongs where the method is known — HandlerBinding. Answering "
                            + "it here could only mean 'refuse all parameters', which is what this "
                            + "build used to do and was a limitation, not a rule")
                    .doesNotContain("AW1040");
        }

        @Test
        @DisplayName("a valid handler passes")
        void validHandlerPasses() {
            validate(staticVoidHandler());
            assertThat(reported).isEmpty();
        }
    }

    @Nested
    @DisplayName("the callback carrier")
    class Callbacks {

        @Test
        @DisplayName("it satisfies a plain Callback without widening the sealed hierarchy")
        void supportIsACallback() {
            final CallbackSupport<String> support = new CallbackSupport<>("onWork");

            assertThat(support)
                    .as("Callback is sealed permits ReturnableCallback, so the framework cannot "
                            + "add a second direct implementation — implementing the non-sealed "
                            + "ReturnableCallback satisfies both")
                    .isInstanceOf(Callback.class)
                    .isInstanceOf(ReturnableCallback.class);
        }

        @Test
        @DisplayName("cancellation records the value and is visible to later handlers")
        void cancellationIsObservable() {
            final CallbackSupport<String> support = new CallbackSupport<>("onWork");

            assertThat(support.isCancelled()).isFalse();
            support.cancel("instead");

            assertThat(support.isCancelled())
                    .as("a later handler at the same site still runs and still sees this; silently "
                            + "skipping it would make a weave's behaviour depend on which other "
                            + "weaves happen to be installed")
                    .isTrue();
            assertThat(support.value()).isEqualTo("instead");
            assertThat(support.id()).isEqualTo("onWork");
        }

        @Test
        @DisplayName("a void cancellation leaves no value")
        void voidCancellation() {
            final CallbackSupport<Void> support = new CallbackSupport<>("onWork");
            support.cancel();

            assertThat(support.isCancelled()).isTrue();
            assertThat(support.value()).isNull();
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private static byte[] weave(final String method) {
        return weave(method, Point.HEAD);
    }

    private static byte[] weave(final String method, final Point point) {
        final byte[] original = fixture();
        final ClassModel model = ClassFile.of().parse(original);
        final TargetView target = ModelViews.of(model);
        final MethodView view = target.methods().stream()
                .filter(m -> method.equals(m.name()))
                .findFirst()
                .orElseThrow();
        final CodeView body = view.code().orElseThrow();

        final PointSpec spec = PointSpec.builtIn(point).build();
        final InjectorSpec injector = injectorSpec(spec);
        final List<Site> sites = new PointResolver(BuiltInPoints.all()::get)
                .resolve(view, body, injector, spec, Reporter.NOOP);
        assertThat(sites).as("the fixture must offer at least one site").isNotEmpty();

        final Set<Integer> indices = new LinkedHashSet<>();
        sites.forEach(site -> indices.add(site.index()));

        final CodeTransform calls =
                new InjectInjector().codeTransform(staticVoidHandler(), indices);

        return ClassFile.of().transformClass(model, ClassTransform.transformingMethodBodies(
                m -> method.equals(m.methodName().stringValue()),
                // ofStateful is mandatory: the transform counts elements, and that count belongs to
                // one method. Sharing it would inject into whichever method came second.
                CodeTransform.ofStateful(() -> calls)));
    }

    private static Object invoke(final byte[] woven, final String method) throws Exception {
        final ClassLoader loader = new ClassLoader(InjectInjectorTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if ("wovenfixture.Target".equals(name)) {
                    return defineClass(name, woven, 0, woven.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Class<?> type = loader.loadClass("wovenfixture.Target");
        final Object instance = type.getDeclaredConstructor().newInstance();
        final Method target = type.getDeclaredMethod(method);
        return target.invoke(instance);
    }

    private void validate(final HandlerRef handler) {
        final PointSpec spec = PointSpec.builtIn(Point.HEAD).build();
        final InjectorSpec injection = new InjectorSpec(InjectorKind.INJECT, handler, "work()",
                MemberSelector.parse("work()"), List.of(spec), List.of(),
                "onWork", 1, 0, "", List.of());
        new InjectInjector().validate(
                new PlanEntry(TARGET, injection, "wovenfixture.Weave", Origin.of("test", null),
                        new OrderKey(0, "wovenfixture.Weave", handler.name(),
                                handler.type().descriptorString())),
                ModelViews.of(ClassFile.of().parse(fixture())),
                this.reporter);
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private static InjectorSpec injectorSpec(final PointSpec spec) {
        return new InjectorSpec(InjectorKind.INJECT, staticVoidHandler(), "work()",
                MemberSelector.parse("work()"), List.of(spec), List.of(),
                "onWork", 1, 0, "", List.of());
    }

    private static HandlerRef staticVoidHandler() {
        return new HandlerRef(HANDLER_OWNER, "onWork",
                MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC));
    }

    private static byte[] fixture() {
        return ClassFile.of().build(TARGET, builder -> {
            builder.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .return_());
            builder.withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code.ldc("done").areturn());
            builder.withMethodBody("untouched", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code.ldc("untouched").areturn());
        });
    }
}
