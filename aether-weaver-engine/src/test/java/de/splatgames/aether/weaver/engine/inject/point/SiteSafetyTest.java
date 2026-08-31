package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SiteSafetyTest {

    private static final ClassDesc SUBJECT = ClassDesc.of("safety.Subject");

    private static final ClassDesc BUILDER = ClassDesc.of("java.lang.StringBuilder");

    private final List<Diagnostic> reported = new ArrayList<>();

    @Test
    @DisplayName("AW1105 — a call inside a constructor's argument list is refused")
    void aSiteInsideAnUninitialisedWindowIsRefused() {
        final List<Site> sites = resolve(code -> code
                .new_(BUILDER)
                .dup()
                // The argument is computed here, which is after the `new` and before the
                // constructor call — the window the JVM tracks as uninitialised.
                .invokestatic(SUBJECT, "helper", MethodTypeDesc.of(ConstantDescs.CD_int))
                .invokespecial(BUILDER, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int))
                .pop()
                .return_(), "#helper");

        assertThat(codes()).containsExactly("AW1105");
        assertThat(sites)
                .as("a site that cannot be injected at is not a site; leaving it in means the "
                        + "injector emits there anyway and the class fails to verify")
                .isEmpty();
    }

    @Test
    @DisplayName("the same call outside the window resolves normally")
    void aSiteOutsideTheWindowIsKept() {
        final List<Site> sites = resolve(code -> code
                .invokestatic(SUBJECT, "helper", MethodTypeDesc.of(ConstantDescs.CD_int))
                .pop()
                .return_(), "#helper");

        assertThat(this.reported)
                .as("without this the test above would pass against a check that refused every "
                        + "call, which is a different defect with the same diagnostic")
                .isEmpty();
        assertThat(sites).hasSize(1);
    }

    @Test
    @DisplayName("AW1130 — an instruction after an unconditional return is refused")
    void aSiteInDeadCodeIsRefused() {
        final List<Site> sites = resolveIn(withDeadCode(), "#helper");

        assertThat(codes()).containsExactly("AW1130");
        assertThat(sites)
                .as("a handler injected there never runs, and the build would be green — which is "
                        + "the one failure mode nothing else in the framework would report")
                .isEmpty();
    }

    @Test
    @DisplayName("an instruction a label can be jumped to is not called dead")
    void aLabelledInstructionIsReachable() {
        final List<Site> sites = resolve(code -> {
            final java.lang.classfile.Label target = code.newLabel();
            code.goto_(target)
                    .labelBinding(target)
                    .invokestatic(SUBJECT, "helper", MethodTypeDesc.of(ConstantDescs.CD_int))
                    .pop()
                    .return_();
        }, "#helper");

        assertThat(this.reported)
                .as("the check must prove unreachability rather than estimate it: a label means "
                        + "something can aim here, and refusing this would break working code")
                .isEmpty();
        assertThat(sites).hasSize(1);
    }

    @Test
    @DisplayName("AW1026 — a site landing ON the super() call is before it, not at it")
    void aSiteAtTheInitialiserIsRefused() {
        final List<Site> sites = resolveInConstructor(true);

        assertThat(codes())
                .as("""
                        The check read `site.index() < initialiser`, and a site's index is the \
                        position code is emitted BEFORE — so a site at the initialiser's own index \
                        puts the handler immediately before super() and passed the test for being \
                        after it. INVOKE_AFTER on the last call inside a constructor's own \
                        argument list resolves to exactly that index.

                        Nothing else would have caught it: the uninitialised-window check counts \
                        `new`/`<init>` pairs and a super() call has no `new`. The JVM would have \
                        refused the class at load time, long after the build went green.""")
                .containsExactly("AW1026");
        assertThat(sites).isEmpty();
    }

    @Test
    @DisplayName("a static handler needs no `this` and is left alone there")
    void aStaticHandlerAtTheInitialiserIsKept() {
        final List<Site> sites = resolveInConstructor(false);

        assertThat(this.reported)
                .as("the rule is about `this`, not about the position. Refusing a static "
                        + "handler here would make the check above pass against something that "
                        + "simply refuses every site in a constructor")
                .isEmpty();
        assertThat(sites).hasSize(1);
    }

    private List<Site> resolveInConstructor(final boolean instance) {
        final byte[] bytes = ClassFile.of().build(SUBJECT, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC);
            // void, and that is the whole fixture. A helper returning a value needs a `pop`
            // between the call and super(), so the after-site lands on the `pop` — one BEFORE the
            // initialiser, which the old `<` already refused. Written that way first, this test
            // passed against the defect it exists for.
            builder.withMethodBody("act", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.return_());
            builder.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                    code -> code
                            .aload(0)
                            .invokestatic(SUBJECT, "act", MethodTypeDesc.of(ConstantDescs.CD_void))
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .return_());
        });

        final TargetView view = ModelViews.of(ClassFile.of().parse(bytes));
        final MethodView method = view.methods().stream()
                .filter(candidate -> ConstantDescs.INIT_NAME.equals(candidate.name()))
                .findFirst()
                .orElseThrow();
        final CodeView code = method.code().orElseThrow();

        final HandlerRef handler = new HandlerRef(SUBJECT, "onCall",
                MethodTypeDesc.of(ConstantDescs.CD_void),
                instance ? Set.of() : Set.of(AccessFlag.STATIC));
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                "<init>()", MemberSelector.parse("<init>()"),
                List.of(PointSpec.builtIn(Point.INVOKE_AFTER).target("#act").build()), List.of(),
                "safety", 0, 0, "", List.<LocalSpec>of());

        return new PointResolver(BuiltInPoints.all()::get)
                .resolve(method, code, spec, spec.points().getFirst(), this.reported::add);
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private List<Site> resolve(final Consumer<java.lang.classfile.CodeBuilder> body,
                               final String target) {
        return resolveIn(build(body), target);
    }

    private static byte[] withDeadCode() {
        final byte[] built = build(code -> code
                .nop()
                .invokestatic(SUBJECT, "helper", MethodTypeDesc.of(ConstantDescs.CD_int))
                .pop()
                .return_());
        final byte[] body = ClassFile.of().parse(built).methods().stream()
                .filter(method -> "run".equals(method.methodName().stringValue()))
                .findFirst().orElseThrow()
                .code().map(code -> ((CodeAttribute) code).codeArray()).orElseThrow();

        final int at = onlyOffsetOf(built, body);
        final byte[] patched = built.clone();
        patched[at] = (byte) 0xB1;
        return patched;
    }

    private static int onlyOffsetOf(final byte[] haystack, final byte[] needle) {
        int found = -1;
        int count = 0;
        for (int index = 0; index + needle.length <= haystack.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length && matches; offset++) {
                matches = haystack[index + offset] == needle[offset];
            }
            if (matches) {
                count++;
                if (found < 0) {
                    found = index;
                }
            }
        }
        assertThat(count)
                .as("the body has to be findable without ambiguity for the patch to be safe")
                .isEqualTo(1);
        return found;
    }

    private static byte[] build(final Consumer<java.lang.classfile.CodeBuilder> body) {
        return ClassFile.of().build(SUBJECT, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC);
            builder.withMethodBody("helper", MethodTypeDesc.of(ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.loadConstant(1).ireturn());
            builder.withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, body::accept);
        });
    }

    private List<Site> resolveIn(final byte[] bytes, final String target) {
        final ClassModel model = ClassFile.of().parse(bytes);
        final TargetView view = ModelViews.of(model);
        final MethodView method = view.methods().stream()
                .filter(candidate -> "run".equals(candidate.name()))
                .findFirst()
                .orElseThrow();
        final CodeView code = method.code().orElseThrow();

        final HandlerRef handler = new HandlerRef(ClassDesc.of("safety.Weave"), "onCall",
                MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC));
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT, handler,
                "run()", MemberSelector.parse("run()"),
                List.of(PointSpec.builtIn(Point.INVOKE).target(target).build()), List.of(),
                "safety", 0, 0, "", List.<LocalSpec>of());
        final Reporter reporter = this.reported::add;

        return new PointResolver(BuiltInPoints.all()::get)
                .resolve(method, code, spec, spec.points().getFirst(), reporter);
    }
}
