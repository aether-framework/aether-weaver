package de.splatgames.aether.weaver.agent;

import java.lang.instrument.Instrumentation;

public final class SpikeAgent {

    private static volatile Instrumentation instrumentation;

    private SpikeAgent() {
        throw new AssertionError("no instances");
    }

    public static void premain(final String arguments, final Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation instrumentation() {
        final Instrumentation inst = instrumentation;
        if (inst == null) {
            throw new IllegalStateException("the spike agent was not installed");
        }
        return inst;
    }
}
