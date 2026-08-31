package de.splatgames.aether.weaver.engine.plan;

import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.util.Objects;

/**
 * One injector declaration paired with one class it applies to.
 *
 * <p>{@link WeavePlanner} flattens the declaration model into these: a weave naming three targets
 * and declaring two injectors becomes six entries, each of which the weaver can act on without
 * looking at the weave again. This is the engine's own view of what
 * {@link de.splatgames.aether.weaver.api.spi.PlanEntryView} publishes, and it carries what that
 * interface does not: the {@linkplain #order() order key}, the {@linkplain #canonical() digest
 * rendering} and whether the declaring weave dissolves.
 *
 * @param target         the class this entry modifies
 * @param spec           the declaration, unchanged from the parse
 * @param weaveClassName the declaring weave's binary name
 * @param origin         where the declaration was found; carried for diagnostics and deliberately
 *                       absent from {@link #canonical()}
 * @param order          the position this entry takes where several meet at one place
 * @param dissolved      whether the declaring weave is folded into its target, which is what moves
 *                       a handler out of the weave class
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record PlanEntry(@NotNull ClassDesc target,
                        @NotNull InjectorSpec spec,
                        @NotNull String weaveClassName,
                        @NotNull Origin origin,
                        @NotNull OrderKey order,
                        boolean dissolved) implements PlanEntryView {

    /**
     * Creates an entry of a weave that stays a class of its own.
     *
     * @param target         the class this entry modifies; must not be {@code null}
     * @param spec           the declaration; must not be {@code null}
     * @param weaveClassName the declaring weave's binary name; must not be blank
     * @param origin         where the declaration was found; must not be {@code null}
     * @param order          the position this entry takes; must not be {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code weaveClassName} is blank
     */
    public PlanEntry(@NotNull final ClassDesc target,
                     @NotNull final InjectorSpec spec,
                     @NotNull final String weaveClassName,
                     @NotNull final Origin origin,
                     @NotNull final OrderKey order) {
        this(target, spec, weaveClassName, origin, order, false);
    }

    /**
     * Checks that the weave is named, since {@code belongsToTheWeave()} compares against that name.
     *
     * @throws NullPointerException     if any reference component is {@code null}
     * @throws IllegalArgumentException if {@code weaveClassName} is blank
     */
    public PlanEntry {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(weaveClassName, "weaveClassName");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(order, "order");
        if (weaveClassName.isBlank()) {
            throw new IllegalArgumentException("weaveClassName must not be blank");
        }
    }

    /**
     * Returns the handler this entry calls, as the declaration named it.
     *
     * @return the handler reference
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public HandlerRef handler() {
        return this.spec.handler();
    }

    /**
     * Returns the class the emitted call names as the handler's owner.
     *
     * <p>This is the handler's declaring class except for a handler that the weaving moves: a
     * dissolving weave's own methods become methods of the target, so the call names the target
     * instead. The injectors compare this against the target rather than {@code handler().owner()}
     * when deciding whether an instance handler is reachable.
     *
     * @return the owner to name at the call site
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public ClassDesc handlerOwner() {
        return this.dissolved && belongsToTheWeave() ? this.target : handler().owner();
    }

    /**
     * Reports whether the handler is declared by the weave itself rather than by a helper class.
     *
     * <p>Compares binary names, because {@link #weaveClassName} is one and a {@link ClassDesc} is a
     * descriptor.
     *
     * @return whether the handler's owner is the declaring weave
     */
    @Contract(pure = true)
    private boolean belongsToTheWeave() {
        final String owner = handler().owner().descriptorString();
        return this.weaveClassName.equals(
                owner.substring(1, owner.length() - 1).replace('/', '.'));
    }

    /**
     * Returns the declared priority, which lives in the order key rather than on the entry.
     *
     * @return the priority, higher first
     */
    @Contract(pure = true)
    @Override
    public int priority() {
        return this.order.priority();
    }

    /**
     * Compares two entries by their order keys alone.
     *
     * <p>Two entries of one weave whose handlers share a name and descriptor compare equal, so this
     * is only usable with a stable sort; see {@link OrderKey}.
     *
     * @param first  the first entry; must not be {@code null}
     * @param second the second entry; must not be {@code null}
     * @return a negative number when {@code first} is applied first, zero when the two keys are
     *         equal
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    public static int compareByOrder(@NotNull final PlanEntry first,
                                     @NotNull final PlanEntry second) {
        return Objects.requireNonNull(first, "first").order()
                .compareTo(Objects.requireNonNull(second, "second").order());
    }

    /**
     * Returns the target's internal name, as {@code com/acme/Ledger}.
     *
     * <p>The key the weaver looks a loading class up by, and the spelling every index in
     * {@link WeavePlan} uses.
     *
     * @return the internal name, stripped out of the descriptor
     */
    @Contract(pure = true)
    @NotNull
    public String targetInternalName() {
        return this.target.descriptorString()
                .substring(1, this.target.descriptorString().length() - 1);
    }

    /**
     * Renders everything about this entry that a rebuild must reproduce to count as the same
     * modification.
     *
     * <p>Consumed by {@code PlanFingerprint.of}, so any change here changes every fingerprint. The
     * target, the injector kind, the order key, the selector, the match bounds, the group and each
     * point are folded in; {@link #origin()} is not, because the same weave found in two directories
     * is the same modification and a path in the digest would make two machines disagree.
     *
     * @return the digest rendering
     */
    @Contract(pure = true)
    @NotNull
    public String canonical() {
        final StringBuilder sb = new StringBuilder(128)
                .append(targetInternalName()).append('|')
                .append(this.spec.kind().id()).append('|')
                .append(this.order.canonical()).append('|')
                .append(canonicalSelector()).append('|')
                .append(this.spec.require()).append(',').append(this.spec.allow()).append('|')
                .append(this.spec.group());
        this.spec.points().forEach(point -> sb.append('|').append(point.point())
                .append(':').append(point.rawTarget() == null ? "" : point.rawTarget())
                .append(':').append(point.ordinal())
                .append(':').append(point.shift())
                .append(':').append(point.by())
                .append(':').append(point.access())
                .append(':').append(point.slice()));
        return sb.toString();
    }

    /**
     * Renders the target selector for the digest.
     *
     * <p>Prefers {@link MemberSelector#canonical()} so that two spellings of one member hash alike,
     * and falls back to {@link MemberSelector.Form#SOURCE} for a selector that does not
     * canonicalise — that form always answers.
     *
     * @return the selector text to fold into the digest
     */
    @Contract(pure = true)
    @NotNull
    private String canonicalSelector() {
        return this.spec.method().canonical()
                .orElseGet(() -> this.spec.method().render(MemberSelector.Form.SOURCE));
    }

    /**
     * Renders the entry for a report line, target first.
     *
     * @return the human-readable rendering
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        return targetInternalName() + "  ← " + this.order.describe()
                + "  " + this.spec.kind().id() + ' ' + this.spec.rawMethod();
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
