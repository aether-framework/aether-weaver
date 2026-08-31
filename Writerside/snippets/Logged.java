package launcher;

import acme.AcmePlugin;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.runtime.WeaveDiscovery;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class Logged {

    static final DiagnosticListener LOG =
            d -> System.out.println(d.format());

    public static void main(String[] args)
            throws Exception {
        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Logged.class.getClassLoader();
        WeaverConfig config = WeaverConfig.defaults();

        try (var search = new URLClassLoader(roots, parent);
             var loader = new WeavingClassLoader(roots, parent,
                     weaverFor(search, config), config, LOG)) {

            Object service = loader
                    .loadClass("fixture.Service")
                    .getDeclaredConstructor()
                    .newInstance();

            service.getClass()
                    .getMethod("run")
                    .invoke(service);
        }
    }

    static Weaver weaverFor(URLClassLoader search,
                            WeaverConfig config) {
        var seen = WeaveDiscovery
                .discover(search, config, LOG);
        var fallback = ClassSource.ofClassLoader(search);

        return Weaver.builder()
                .driver(Weaver.Driver.LOAD)
                .weaves(seen.weaves())
                .classSource(seen.classes().orElse(fallback))
                .plugin(new AcmePlugin())
                .diagnostics(LOG)
                .build();
    }
}
