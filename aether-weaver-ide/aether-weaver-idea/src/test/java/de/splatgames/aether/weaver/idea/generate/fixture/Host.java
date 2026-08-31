package de.splatgames.aether.weaver.idea.generate.fixture;

public class Host {

    public Host() {
        // Nothing to host.
    }

    public static void sample(final Buffer buffer, final Item item) {
        buffer.add(item);
        buffer.add(item);
    }

    public static Object constants(final Buffer buffer) {
        final int number = 0;
        final String text = "retry";
        final Class<?> type = Item.class;
        final Item made = new Item();
        buffer.add(made);
        return number + text + type + made;
    }

    public static int primitives(final Buffer buffer) {
        return buffer.count(1, 2, true);
    }

    public static void bounded(final Buffer buffer, final Item item) {
        buffer.begin();
        buffer.add(item);
        buffer.commit();
        buffer.add(item);
        buffer.end();
    }

    public static void withLocals(final Buffer buffer, final Item item) {
        final Item first = buffer.add(item);
        final int count = 1;
        buffer.add(first);
        if (count > 0) {
            buffer.add(first);
        }
    }
}
