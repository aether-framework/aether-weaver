package launcher;

import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.explain.ExplainReport;
import de.splatgames.aether.weaver.runtime.WeavingClassLoader;
import de.splatgames.aether.weaver.runtime.config.ConfigLayers;
import de.splatgames.aether.weaver.runtime.config.ConfigParser;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.io.Reader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Configured {

    public static void main(String[] args) throws Exception {
        Reporter log = d -> System.out.println(d.format());

        Properties file = new Properties();
        Path path = Path.of("weaver.properties");
        try (Reader in = Files.newBufferedReader(path)) {
            file.load(in);
        }

        ConfigLayers layers = ConfigLayers.of()
                .add("weaver.properties",
                     ConfigParser.ofProperties(file, log))
                .add("system properties",
                     ConfigParser.ofSystemProperties(
                             System.getProperties(), log));

        WeaverConfig config = layers.resolve();
        for (ExplainReport.Setting decided : layers.settings()) {
            System.out.println(decided.name() + "=" + decided.value()
                    + "  <- " + decided.source());
        }

        URL[] roots = {Path.of("plugins").toUri().toURL()};
        ClassLoader parent = Configured.class.getClassLoader();
        try (WeavingClassLoader loader = WeavingClassLoader.create(
                roots, parent, config, log)) {

            Class<?> type = loader.loadClass("fixture.Target");
            Object target = type.getConstructor().newInstance();

            Object text = type.getMethod("greet").invoke(target);

            System.out.println(text);
        }
    }
}
