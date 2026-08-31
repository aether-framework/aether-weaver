package launcher;

import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.runtime.WeaveDiscovery;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class Explained {

    static final DiagnosticListener LOG =
            d -> System.out.println(d.format());

    public static void main(String[] args) throws Exception {
        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Explained.class.getClassLoader();
        WeaverConfig config = WeaverConfig.defaults();

        try (var search = new URLClassLoader(roots, parent)) {
            Weaver weaver = weaverFor(search, config);

            try (var loader = new WeavingClassLoader(
                    roots, parent, weaver, config, LOG)) {
                loader.loadClass("fixture.Target");
            }

            System.out.println(weaver.explain());
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
                .verification(config.verification())
                .explain(true)
                .diagnostics(LOG)
                .build();
    }
}
