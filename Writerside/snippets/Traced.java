package launcher;

import acme.TracePlugin;
import de.splatgames.aether.weaver.api.Woven;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.runtime.WeaveDiscovery;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

public final class Traced {

    static final DiagnosticListener LOG =
            d -> System.out.println(d.format());

    public static void main(String[] args) throws Exception {
        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Traced.class.getClassLoader();
        WeaverConfig config = WeaverConfig.defaults();

        try (var search = new URLClassLoader(roots, parent);
             var loader = new WeavingClassLoader(roots, parent,
                     weaverFor(search, config), config, LOG)) {

            Class<?> type = loader.loadClass("fixture.Target");
            Woven stamp = type.getAnnotation(Woven.class);

            System.out.println(Arrays.toString(stamp.plugins()));
            System.out.println(Arrays.toString(stamp.extra()));
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
                .plugin(new TracePlugin())
                .diagnostics(LOG)
                .build();
    }
}
