package fixture;

import java.util.List;
import java.util.function.IntUnaryOperator;

public class Target {

    private int balance = 100;

    public Target() {
        // Nothing to set up beyond the field initialiser, which is itself part of the fixture:
        // a HEAD injection into a constructor lands after the superclass initialiser and before
        // this runs.
    }

    public int charge(final int amount) {
        if (amount <= 0) {
            return this.balance;
        }
        for (int i = 0; i < amount; i++) {
            this.balance--;
        }
        return this.balance;
    }

    public String classify(final int code) {
        final String dense = switch (code) {
            case 0 -> "zero";
            case 1 -> "one";
            case 2 -> "two";
            case 3 -> "three";
            default -> "many";
        };
        return switch (code) {
            case 1000 -> dense + "-k";
            case 1000000 -> dense + "-m";
            default -> dense;
        };
    }

    public int guarded(final int divisor) {
        try {
            return 100 / divisor;
        } catch (final ArithmeticException impossible) {
            return -1;
        } finally {
            this.balance++;
        }
    }

    public String describe(final String who) {
        return "balance of " + who + " is " + this.balance;
    }

    public int apply(final int value) {
        final IntUnaryOperator doubling = input -> input * 2;
        return doubling.applyAsInt(value);
    }

    public long total(final long first, final long second) {
        final long sum = first + second;
        return sum;
    }

    public List<String> build() {
        return new java.util.ArrayList<>(List.of("a", "b"));
    }

    public int balance() {
        return this.balance;
    }
}
