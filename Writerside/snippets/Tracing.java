package launcher;

import acme.AcmeTracingPlugin;
import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class Tracing {

    static final DiagnosticListener LOG =
            d -> System.out.println(d.format());

    public static void traced() {
        System.out.println("acme:trace fired");
    }

    public static void main(String[] args) throws Exception {
        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Tracing.class.getClassLoader();

        Weaver weaver = Weaver.builder()
                .driver(Weaver.Driver.LOAD)
                .weaves(List.of(weave()))
                .plugin(new AcmeTracingPlugin())
                .diagnostics(LOG)
                .build();

        try (var loader = new WeavingClassLoader(roots, parent,
                weaver, WeaverConfig.defaults(), LOG)) {

            Class<?> type = loader.loadClass("fixture.Target");
            Object target = type.getDeclaredConstructor()
                    .newInstance();
            System.out.println(type.getMethod("greet")
                    .invoke(target));
        }
    }

    static WeaveClass weave() {
        HandlerRef handler = new HandlerRef(
                ClassDesc.of("launcher.Tracing"), "traced",
                MethodTypeDesc.of(ConstantDescs.CD_void),
                Set.of(AccessFlag.STATIC));

        InjectorSpec spec = new InjectorSpec(
                InjectorKind.of("acme:trace"), handler,
                "greet()", MemberSelector.parse("greet()"),
                List.of(PointSpec.named("RETURN").build()),
                List.of(), "traced", 1, 0, "", List.of());

        TargetRef target = TargetRef.ofClassLiteral(
                ClassDesc.of("fixture.Target"));

        return new WeaveClass(ClassDesc.of("acme.Tracing"),
                List.of(target), Weave.Kind.INSTANCE, 0,
                Require.REQUIRED, Phase.DEFAULT, Set.of(),
                List.of(), List.of(), List.of(spec),
                Origin.of("Tracing.weave()", null));
    }
}
