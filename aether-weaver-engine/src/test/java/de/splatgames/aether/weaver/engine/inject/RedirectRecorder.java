package de.splatgames.aether.weaver.engine.inject;

import java.util.ArrayList;
import java.util.List;

public final class RedirectRecorder {

    public static final List<String> ORIGINAL = new ArrayList<>();

    public static final List<String> HANDLED = new ArrayList<>();

    private RedirectRecorder() {
        throw new AssertionError("no instances");
    }

    public static void clear() {
        ORIGINAL.clear();
        HANDLED.clear();
    }
}
