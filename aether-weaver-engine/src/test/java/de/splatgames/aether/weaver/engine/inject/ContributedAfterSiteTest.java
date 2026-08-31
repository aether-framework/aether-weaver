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
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;
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
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContributedAfterSiteTest {

    private static final String NAMESPACE = "probe";

    private static final String AFTER_FIRST = NAMESPACE + ":AFTER_FIRST";

    private static final ClassDesc OPS = ClassDesc.of(Ops.class.getName());

    private static final ClassDesc TARGET = ClassDesc.of("probefixture.Target");

    private static final String INTERNAL = "probefixture/Target";

    private final List<Diagnostic> reported = new ArrayList<>();

    public static final class Ops {

        private Ops() {
        }

        public static String first() {
            return "A";
        }

        public static String second() {
            return "B";
        }

        public static String join(final String left, final String right) {
            return left + right;
        }

        public static String replaced() {
            return "X";
        }
    }

    @Test
    @DisplayName("AW1061 — a redirect at the far side of a call is refused, not silently moved")
    void anAfterSiteIsNotAnOperation() {
        final byte[] woven = Weaver.builder()
                .weaves(List.of(weave()))
                .plugin(new ProbePlugin())
                .diagnostics(this.reported::add)
                .build()
                .weave(INTERNAL, fixture());

        assertThat(codes())
                .as("""
                        Without this the probe produced "AX" — the redirect replaced second() \
                        because the site it was handed was the index after first(). No diagnostic \
                        was reported and the class ran, which is the only outcome here worse than \
                        a crash.""")
                .contains("AW1061");
        assertThat(woven)
                .as("a declaration whose every site was refused weaves nothing")
                .isNull();
    }

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static WeaveClass weave() {
        final InjectorSpec spec = new InjectorSpec(InjectorKind.REDIRECT,
                new HandlerRef(OPS, "replaced",
                        MethodTypeDesc.of(ConstantDescs.CD_String), Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(PointSpec.named(AFTER_FIRST).build()), List.of(),
                "swap", 1, 0, "", List.of());

        return new WeaveClass(ClassDesc.of("probe.Swapping"),
                List.of(TargetRef.ofClassLiteral(TARGET)),
                Weave.Kind.STATIC, 0, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("test", null));
    }

    private static final class ProbePlugin implements WeaverPlugin {

        private static final PluginId ID = new PluginId(NAMESPACE, "After Site Probe", "1.0.0");

        ProbePlugin() {
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
            ctx.points(new ProbePoints());
        }
    }

    private static final class ProbePoints implements InjectionPointFactory {

        ProbePoints() {
            // Nothing to configure.
        }

        @Override
        public String namespace() {
            return NAMESPACE;
        }

        @Override
        public Set<String> ids() {
            return Set.of(AFTER_FIRST);
        }

        @Override
        public InjectionPoint create(final String id) {
            return new AfterFirstPoint();
        }
    }

    private static final class AfterFirstPoint implements InjectionPoint {

        AfterFirstPoint() {
            // Nothing to configure.
        }

        @Override
        public String id() {
            return AFTER_FIRST;
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
                if (elements.get(i) instanceof final InvokeInstruction invoke
                        && "first".equals(invoke.name().stringValue())) {
                    return List.of(new Site(i, Site.Kind.AFTER_ELEMENT, invoke));
                }
            }
            return List.of();
        }
    }

    private static byte[] fixture() {
        return ClassFile.of().build(TARGET, builder ->
                builder.withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code
                                .invokestatic(OPS, "first",
                                        MethodTypeDesc.of(ConstantDescs.CD_String))
                                .invokestatic(OPS, "second",
                                        MethodTypeDesc.of(ConstantDescs.CD_String))
                                .invokestatic(OPS, "join",
                                        MethodTypeDesc.of(ConstantDescs.CD_String,
                                                ConstantDescs.CD_String, ConstantDescs.CD_String))
                                .areturn()));
    }
}
