package fixture;

import java.util.ArrayList;
import java.util.List;

public final class Trace {

    public static final List<String> RECORD = new ArrayList<>();

    private Trace() {
        throw new AssertionError("no instances");
    }

    public static void record(final String what) {
        RECORD.add(what);
    }

    public static void clear() {
        RECORD.clear();
    }
}
