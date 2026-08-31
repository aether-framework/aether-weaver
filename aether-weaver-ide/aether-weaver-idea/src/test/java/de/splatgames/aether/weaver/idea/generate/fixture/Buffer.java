package de.splatgames.aether.weaver.idea.generate.fixture;

public class Buffer {

    public Buffer() {
        // Nothing to buffer.
    }

    public Item add(final Item item) {
        return item;
    }

    public int count(final int first, final int second, final boolean flag) {
        return flag ? first + second : first;
    }

    public void begin() {
        // Nothing to begin.
    }

    public void commit() {
        // Nothing to commit.
    }

    public void end() {
        // Nothing to end.
    }
}
