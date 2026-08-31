package com.acme.payments;

/**
 * Weaving target for the declarations that draw nothing, or draw nothing until the project has been
 * compiled.
 *
 * <p>Four methods: three shaped for one reason the plugin's injection preview stays quiet or has to
 * read a class file, and a fourth they call. {@link #pick(boolean)} has two returns, which is the
 * body shape a tail is not resolved on; {@link #answer()} returns a literal that no constant
 * folding removes, so a constant point matches it in the source; {@link #twice()} calls
 * {@link #help()} twice on one line, so an ordinal is the only thing that tells the two calls apart
 * and an ordinal sends the plugin to the class file; and {@link #help()} itself is the method those
 * two calls name.
 *
 * <p>Nothing here is wrong, and no inspection reports anything about it or about
 * {@code SilentWeave}. {@code Reported.java} is the opposite discipline, and {@link Router} is the
 * body shape a tail is resolved on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public class Silent {

    /**
     * Returns one of two constants.
     *
     * <p>Two {@code return} statements, so the plugin resolves no tail here: the last exit in
     * bytecode order is not necessarily the last one in the text, and drawing a guess would be a
     * claim about where code runs. The engine still weaves a {@code Point.TAIL} declaration on this
     * method, keeping the last return in body order.
     *
     * @param first whether to answer with the first constant
     * @return {@code 1} when {@code first}, otherwise {@code 2}
     */
    public int pick(final boolean first) {
        if (first) {
            return 1;
        }
        return 2;
    }

    /**
     * Returns a literal.
     *
     * <p>The literal is neither a {@code case} label nor part of an enclosing compile-time constant
     * expression, which are the two shapes the plugin treats as folded away, so a constant point
     * written as {@code int:42} matches it without any class file being read.
     *
     * @return {@code 42}
     */
    public int answer() {
        return 42;
    }

    /**
     * Calls one method twice.
     *
     * <p>Both calls are on one line and name the same method, so a call point with no ordinal
     * matches both and only an ordinal separates them. An ordinal counts instructions rather than
     * source constructs, which is why a declaration carrying one is resolved against the compiled
     * class rather than against this text.
     *
     * @return the helper's answer twice, concatenated
     */
    public String twice() {
        return help() + help();
    }

    /**
     * Answers with a fixed string.
     *
     * <p>Package-private, and called twice from {@link #twice()} so that the two call sites are
     * indistinguishable by anything but their order.
     *
     * @return a fixed string
     */
    String help() {
        return "x";
    }
}
