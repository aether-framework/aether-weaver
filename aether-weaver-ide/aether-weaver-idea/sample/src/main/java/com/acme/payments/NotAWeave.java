package com.acme.payments;

/**
 * Counter-probe: a class that looks like a weave to anything matching on shape rather than on the
 * annotation type.
 *
 * <p>It carries no {@code @Weave}, and its handler-shaped method is annotated with an unrelated
 * annotation that happens to declare an element called {@code method} whose value happens to be the
 * name of a real {@link Gateway} method. A feature keyed on the element name, on the string's
 * contents, or on a method's resemblance to a handler lights up here; one keyed on the framework's
 * own annotations does nothing, which is what this file is opened to check.
 *
 * <p>A false report costs more than a missing one, because the remedy a reader reaches for is
 * switching the inspection off, and the true reports go with it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class NotAWeave {

    /**
     * An annotation belonging to nobody, declaring the element name the framework's own annotations
     * use.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @interface Unrelated {

        /**
         * Carries a string that reads like a selector and is not one.
         *
         * @return the value written at the use site
         */
        String method();
    }

    /**
     * A method annotated with {@code Unrelated}, naming a method of {@link Gateway} that exists.
     *
     * <p>Navigation from inside that string must do nothing: the annotation is not the framework's,
     * so the value means nothing to the plugin however much it resembles a selector.
     */
    @Unrelated(method = "charge")
    void notOurs() {
        // Ctrl+B here must do nothing.
    }
}
