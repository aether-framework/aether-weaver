package launcher;

import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.policy.DefaultWeavePolicy;
import de.splatgames.aether.weaver.runtime.WeaveDiscovery;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class Policed {

    static final DiagnosticListener LOG =
            d -> System.out.println(d.format());

    public static void main(String[] args) throws Exception {
        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Policed.class.getClassLoader();
        WeaverConfig config = WeaverConfig.defaults();

        try (var search = new URLClassLoader(roots, parent);
             var loader = new WeavingClassLoader(roots, parent,
                     weaverFor(search, config), config, LOG)) {

            var type = loader.loadClass("javax.legacy.Target");
            var it = type.getConstructor().newInstance();
            var greet = type.getMethod("greet");

            System.out.println(greet.invoke(it));
        }
    }

    static Weaver weaverFor(URLClassLoader search,
                            WeaverConfig config) {
        var found = WeaveDiscovery.discover(search, config, LOG);
        var fallback = ClassSource.ofClassLoader(search);

        return Weaver.builder()
                .driver(Weaver.Driver.LOAD)
                .weaves(found.weaves())
                .classSource(found.classes().orElse(fallback))
                .policy(DefaultWeavePolicy.builder()
                        .allowPackage("javax.legacy")
                        .build())
                .diagnostics(LOG)
                .build();
    }
}
