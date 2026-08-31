package de.splatgames.aether.weaver.api.model;

import org.jetbrains.annotations.Contract;

import java.util.Objects;

/**
 * A bound on how many positions a set of declarations must match between them, rather than each on
 * its own.
 *
 * <p>This is the parsed form of {@link de.splatgames.aether.weaver.api.Group}, declared on the
 * weave class. It exists for the case where a target legitimately varies — two supported versions
 * of a library, a method whose name differs between them — and the weave carries one declaration
 * per variant. Each variant on its own would have to be allowed to match nothing, which switches
 * off the check entirely; a group restores it by requiring the alternatives to succeed
 * collectively.
 *
 * <h2>How the total is arrived at</h2>
 *
 * <p>Every declaration whose {@link InjectorSpec#group()} equals this group's {@link #name()} adds
 * the number of positions it matched to one running total, and the total is then offered to
 * {@link #accepts(int)}. Matches are counted per declaration after duplicate positions within that
 * declaration have been collapsed, so two injection points of one declaration that resolve to the
 * same instruction contribute one, not two.
 *
 * <p>A grouped declaration's own {@link InjectorSpec#require()} and {@link InjectorSpec#allow()}
 * are not checked at all. The group's total is the statement, and checking both would let one
 * declaration fail for matching nothing in precisely the situation the group was written to
 * tolerate.
 *
 * <h2>The bounds</h2>
 *
 * <p>{@link #min()} is the fewest matches that count as success and defaults to one when the
 * annotation omits it, which is the reason to declare a group at all: it says that failing every
 * alternative is a real failure. {@link #max()} is the most, and {@code 0} imposes no upper bound
 * rather than permitting none — the one place these numbers do not read the way they look. Both
 * must be non-negative, and a non-zero {@link #max()} below {@link #min()} is rejected on
 * construction rather than reported later, because no total could satisfy it.
 *
 * <h2>Failure</h2>
 *
 * <p>A total outside the bounds is reported as {@code AW1043}, naming the group, the total, the
 * bounds, and every declaration that contributed to it with its own count. A group that fails
 * because every alternative matched nothing means the target has changed in a way none of them
 * anticipated.
 *
 * <h2>Two ways a group silently checks nothing</h2>
 *
 * <p>A group is only checked if it is declared. A declaration naming a group the weave class does
 * not declare has its matches added to a total that nothing ever inspects, and its own
 * {@link InjectorSpec#require()} is skipped as well because it is in a group — so a misspelt group
 * name leaves that declaration entirely unaccounted, with no diagnostic. Spell the name the same
 * way in both places.
 *
 * <p>The converse holds too. A group that no declaration joins is still checked, against a total of
 * zero; with the default {@link #min()} of one, such a group fails.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Gateway.class)
 * @Group(name = "send", min = 1)          // exactly one of the two below has to work
 * public final class GatewayAudit {
 *
 *     @Inject(method = "send(Payment)",   // the 2.x spelling
 *             at = @At(Point.HEAD),
 *             require = 0,
 *             group = "send")
 *     private static void onSendV2(Callback cb) { ... }
 *
 *     @Inject(method = "dispatch(Payment)", // the 3.x spelling
 *             at = @At(Point.HEAD),
 *             require = 0,
 *             group = "send")
 *     private static void onSendV3(Callback cb) { ... }
 * }
 * }</pre>
 *
 * @param name the name declarations join this group by; never blank
 * @param min  the fewest matches the group's declarations must produce between them
 * @param max  the most they may produce, or {@code 0} for no upper bound
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Group
 */
public record GroupSpec(String name, int min, int max) {

    /**
     * Checks that the group can be joined and that its bounds admit some total.
     *
     * <p>A blank name is refused because the empty string is how a declaration says it belongs to
     * no group; a group answering to it would silently collect every ungrouped declaration in the
     * weave.
     *
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, if either bound is negative, or if
     *                                  {@code max} is non-zero and below {@code min}
     */
    public GroupSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a group name must not be blank");
        }
        if (min < 0 || max < 0) {
            throw new IllegalArgumentException(
                    "group \"" + name + "\" has a negative bound: min=" + min + ", max=" + max);
        }
        if (max != 0 && max < min) {
            throw new IllegalArgumentException(
                    "group \"" + name + "\" can never be satisfied: min=" + min + " > max=" + max);
        }
    }

    /**
     * Reports whether a total satisfies this group.
     *
     * <p>{@code true} when the total is at least {@link #min()} and, unless {@link #max()} is
     * {@code 0}, at most {@link #max()}. A negative total is not meaningful and is simply below
     * every non-negative minimum.
     *
     * @param matched the number of positions the group's declarations matched between them
     * @return {@code true} when the total lies within the bounds
     */
    @Contract(pure = true)
    public boolean accepts(final int matched) {
        return matched >= this.min && (this.max == 0 || matched <= this.max);
    }
}
