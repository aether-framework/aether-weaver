package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ContributedFailureTest {

    private static final String NAMESPACE = "acme";

    private static final String BROKEN_POINT = NAMESPACE + ":BROKEN";

    private static final InjectorKind BROKEN_KIND = InjectorKind.of(NAMESPACE + ":broken");

    private static final ClassDesc TARGET = ClassDesc.of("failfixture.Target");

    private static final String INTERNAL = "failfixture/Target";

    private static final ClassDesc HANDLERS = ClassDesc.of("failfixture.Handlers");

    private final List<Diagnostic> reported = new ArrayList<>();

    @Test
    @DisplayName("AW3116 — a contributed point that throws while resolving is contained")
    void aThrowingPointIsContained() {
        final byte[][] woven = new byte[1][];

        assertThatCode(() -> woven[0] = weave(PointSpec.named(BROKEN_POINT).build(),
                InjectorKind.INJECT))
                .as("""
                        Before this it threw straight out of Weaver.weave. A build that stops \
                        with a third party's stack trace has told the author neither what failed \
                        nor that it was not their own weave — and every other weave in the same \
                        run dies with it.""")
                .doesNotThrowAnyException();

        assertThat(codes())
                .as("containment without a diagnostic is a class that was silently not woven")
                .contains("AW3116");
        assertThat(woven[0])
                .as("the class is left as it was, rather than half-woven")
                .isNull();
    }

    @Test
    @DisplayName("AW3117 — a contributed injector that throws while emitting is contained")
    void aThrowingInjectorIsContained() {
        final byte[][] woven = new byte[1][];

        assertThatCode(() -> woven[0] = weave(PointSpec.named(WorkingPoint.ID).build(), BROKEN_KIND))
                .as("""
                        The sister phase, and it was open in the same way. An emitter runs inside \
                        the Class-File API's own transform, so what escaped was a third party's \
                        exception wrapped in nothing, from the middle of building a class.""")
                .doesNotThrowAnyException();

        assertThat(codes()).contains("AW3117");
        assertThat(woven[0])
                .as("emission builds into a fresh class builder, so abandoning it leaves the "
                        + "original bytes standing rather than a partial rewrite")
                .isNull();
    }

    @Test
    @DisplayName("a contributed point and injector that behave are not contained into silence")
    void aWorkingContributionStillWeaves() {
        final byte[] woven = weave(PointSpec.named(WorkingPoint.ID).build(), InjectorKind.INJECT);

        assertThat(this.reported)
                .as("without this the two tests above would pass against isolation that "
                        + "refused every contributed point and injector outright, which reports "
                        + "the same codes for a completely different reason")
                .isEmpty();
        assertThat(woven).isNotNull();
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private byte[] weave(final PointSpec point, final InjectorKind kind) {
        return Weaver.builder()
                .weaves(List.of(weaveClass(point, kind)))
                .plugin(new AcmePlugin())
                .diagnostics(this.reported::add)
                .build()
                .weave(INTERNAL, fixture());
    }

    private static WeaveClass weaveClass(final PointSpec point, final InjectorKind kind) {
        final InjectorSpec spec = new InjectorSpec(kind,
                new HandlerRef(HANDLERS, "onWork", MethodTypeDesc.of(ConstantDescs.CD_void),
                        Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(point), List.of(), "failing", 1, 0, "", List.of());

        return new WeaveClass(ClassDesc.of("acme.Failing"),
                List.of(TargetRef.ofClassLiteral(TARGET)),
                Weave.Kind.STATIC, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
    }

    private static byte[] fixture() {
        return ClassFile.of().build(TARGET, builder ->
                builder.withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.return_()));
    }

    private static final class AcmePlugin implements WeaverPlugin {

        private static final PluginId ID = new PluginId(NAMESPACE, "Acme Failing", "1.0.0");

        AcmePlugin() {
            // Nothing to configure.
        }

        @Override
        public PluginId id() {
            return ID;
        }

        @Override
        public int apiLevel() {
            return WeaverApi.LEVEL;
        }

        @Override
        public void contribute(final PluginContext ctx) {
            ctx.points(new AcmePoints()).injectors(new AcmeInjectors());
        }
    }

    private static final class AcmePoints implements InjectionPointFactory {

        AcmePoints() {
            // Nothing to configure.
        }

        @Override
        public String namespace() {
            return NAMESPACE;
        }

        @Override
        public Set<String> ids() {
            return Set.of(BROKEN_POINT, WorkingPoint.ID);
        }

        @Override
        public InjectionPoint create(final String id) {
            return BROKEN_POINT.equals(id) ? new BrokenPoint() : new WorkingPoint();
        }
    }

    private static final class BrokenPoint implements InjectionPoint {

        BrokenPoint() {
            // Nothing to configure.
        }

        @Override
        public String id() {
            return BROKEN_POINT;
        }

        @Override
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        @Override
        public List<Site> find(final MethodView method, final CodeView code,
                               final PointSpec spec, final Reporter reporter) {
            throw new IllegalStateException("a contributed point with a defect in it");
        }
    }

    private static final class WorkingPoint implements InjectionPoint {

        static final String ID = NAMESPACE + ":WORKING";

        WorkingPoint() {
            // Nothing to configure.
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public TargetRequirement targetRequirement() {
            return TargetRequirement.FORBIDDEN;
        }

        @Override
        public List<Site> find(final MethodView method, final CodeView code,
                               final PointSpec spec, final Reporter reporter) {
            final List<CodeElement> elements = code.elements();
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) instanceof final ReturnInstruction returning) {
                    return List.of(new Site(i, Site.Kind.METHOD_EXIT, returning));
                }
            }
            return List.of();
        }
    }

    private static final class AcmeInjectors implements InjectorFactory {

        AcmeInjectors() {
            // Nothing to configure.
        }

        @Override
        public String namespace() {
            return NAMESPACE;
        }

        @Override
        public Set<InjectorKind> kinds() {
            return Set.of(BROKEN_KIND);
        }

        @Override
        public Injector create(final InjectorKind kind) {
            return new BrokenInjector();
        }
    }

    private static final class BrokenInjector implements Injector {

        BrokenInjector() {
            // Nothing to configure.
        }

        @Override
        public InjectorKind kind() {
            return BROKEN_KIND;
        }

        @Override
        public Emitter emitter(final InjectionContext context) {
            throw new IllegalStateException("a contributed injector with a defect in it");
        }
    }
}
