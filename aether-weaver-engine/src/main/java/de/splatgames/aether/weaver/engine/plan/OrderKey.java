package de.splatgames.aether.weaver.engine.plan;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Objects;

/**
 * Decides which of two declarations meeting at one place is applied first.
 *
 * <p>The key identifies a handler rather than a plan entry: nothing in it names the target, the
 * injector kind or the injection point. Two entries of one weave whose handlers have the same name
 * and descriptor therefore compare equal — which is what a weave naming several targets produces
 * for each of its declarations — so {@link PlanEntry#compareByOrder} is an order with ties. What
 * makes a build reproducible is that {@link WeavePlanner} sorts with {@link java.util.List#sort},
 * which is stable, over entries built in the order the weaves, their targets and their injectors
 * were parsed.
 *
 * @param priority          the declared {@code @Weave(priority)}; higher is applied first
 * @param weaveClassName    the declaring weave's binary name, as {@code com.acme.AuditWeave}
 * @param handlerName       the handler method's name
 * @param handlerDescriptor the handler method's descriptor, which separates overloads
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record OrderKey(int priority,
                       @NotNull String weaveClassName,
                       @NotNull String handlerName,
                       @NotNull String handlerDescriptor) implements Comparable<OrderKey> {

    /** Priority descending, then the three names ascending. */
    private static final Comparator<OrderKey> ORDER =
            Comparator.comparingInt(OrderKey::priority).reversed()
                    .thenComparing(OrderKey::weaveClassName)
                    .thenComparing(OrderKey::handlerName)
                    .thenComparing(OrderKey::handlerDescriptor);

    /**
     * Checks that every tie-breaker carries text.
     *
     * @throws NullPointerException     if any of the three names is {@code null}
     * @throws IllegalArgumentException if any of the three names is blank
     */
    public OrderKey {
        Objects.requireNonNull(weaveClassName, "weaveClassName");
        Objects.requireNonNull(handlerName, "handlerName");
        Objects.requireNonNull(handlerDescriptor, "handlerDescriptor");
        if (weaveClassName.isBlank() || handlerName.isBlank() || handlerDescriptor.isBlank()) {
            throw new IllegalArgumentException(
                    "no component of an order key may be blank; the tie-breakers are what make "
                            + "the order total");
        }
    }

    /**
     * Compares according to {@link #ORDER}.
     *
     * @param other the key to compare against; must not be {@code null}
     * @return a negative number when this key is applied first, zero when the two name the same
     *         handler of the same weave
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Contract(pure = true)
    @Override
    public int compareTo(@NotNull final OrderKey other) {
        return ORDER.compare(this, Objects.requireNonNull(other, "other"));
    }

    /**
     * Renders the key for a diagnostic or a report, as {@code [50] com.acme.AuditWeave#onCharge()V}.
     *
     * @return the human-readable rendering
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        return "[" + this.priority + "] " + this.weaveClassName + '#' + this.handlerName
                + this.handlerDescriptor;
    }

    /**
     * Renders the key for the digest, pipe-separated.
     *
     * <p>Reached from {@link PlanEntry#canonical()} and therefore from every plan fingerprint, so a
     * change to this text changes the fingerprint of every build.
     *
     * @return the digest rendering
     */
    @Contract(pure = true)
    @NotNull
    public String canonical() {
        return this.priority + "|" + this.weaveClassName + '|' + this.handlerName + '|'
                + this.handlerDescriptor;
    }

    /**
     * Returns {@link #describe()}.
     *
     * @return the human-readable rendering
     */
    @Override
    @NotNull
    public String toString() {
        return describe();
    }
}
