package launcher;

import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.ConfigParser;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.net.URL;
import java.nio.file.Path;

public final class Launcher {

    public static void main(String[] args) throws Exception {
        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Launcher.class.getClassLoader();
        WeaverConfig config = ConfigParser.ofSystemProperties(
                System.getProperties(),
                d -> System.out.println(d.format())).resolve();

        try (WeavingClassLoader loader = WeavingClassLoader.create(
                roots, parent, config,
                d -> System.out.println(d.format()))) {

            System.out.println(loader);

            Class<?> type = loader.loadClass("fixture.Target");
            Object target = type.getConstructor().newInstance();
            Object text = type.getMethod("greet").invoke(target);

            System.out.println(text);
        }
    }
}
