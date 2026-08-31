package de.splatgames.aether.weaver.runtime;

import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;

import java.net.URL;

public final class AotProbe {

    private AotProbe() {
        throw new AssertionError("no instances");
    }

    public static void main(final String[] arguments) throws Exception {
        final DiagnosticListener listener =
                diagnostic -> System.out.println("probe: " + diagnostic.format());

        final Weaver weaver = Weaver.builder().diagnostics(listener).build();
        try (WeavingClassLoader loader = new WeavingClassLoader(
                new URL[0], AotProbe.class.getClassLoader(), weaver,
                WeaverConfig.defaults(), listener)) {
            System.out.println("probe: constructed " + loader);
        }
        System.out.println("probe: done");
    }
}
