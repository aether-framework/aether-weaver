package de.splatgames.aether.weaver.engine.extension.fixture;

public final class Greeting implements Named {

    private final String name;

    public Greeting(final String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return this.name;
    }

    public String greet() {
        return "hello " + this.name;
    }
}
