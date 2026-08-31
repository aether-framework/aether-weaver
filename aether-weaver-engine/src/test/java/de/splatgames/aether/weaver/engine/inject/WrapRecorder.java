package de.splatgames.aether.weaver.engine.inject;

import java.util.ArrayList;
import java.util.List;

public final class WrapRecorder {

    public static final List<String> TRACE = new ArrayList<>();

    public static final List<Object> OPERATIONS = new ArrayList<>();

    private WrapRecorder() {
        throw new AssertionError("no instances");
    }

    public static void record(final String event) {
        TRACE.add(event);
    }

    public static void clear() {
        TRACE.clear();
        OPERATIONS.clear();
    }
}
