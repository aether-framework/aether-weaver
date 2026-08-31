package de.splatgames.aether.weaver.engine.extension.fixture;

import de.splatgames.aether.weaver.api.experimental.Receiver;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

public final class GreetingExtensions {

    public static final Greeting DEFAULT = new Greeting("world");

    public static final String PREFIX = "hello ";

    private GreetingExtensions() {
        throw new AssertionError("no instances");
    }

    public static String shout(final Greeting self, final int times) {
        return (self.greet().toUpperCase() + "! ").repeat(times).trim();
    }

    public static List<String> words(final Greeting self) {
        return List.of(self.greet().split(" "));
    }

    public static String initial(final Named self) {
        return self.name().substring(0, 1);
    }

    public static Greeting of(final String name) {
        return new Greeting(name);
    }

    public static String read(final Greeting self) throws IOException {
        return self.greet();
    }

    @Deprecated
    public static String legacy(@Receiver final Greeting self, @Nullable final String note) {
        return note == null ? self.greet() : self.greet() + ' ' + note;
    }

    public static String join(final Greeting self, final String... parts) {
        return self.greet() + ' ' + String.join(" ", parts);
    }
}
