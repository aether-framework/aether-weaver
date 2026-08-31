package de.splatgames.aether.weaver.api;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a bound on the matches of several injection declarations together, so that they
 * can answer for one another.
 *
 * <p>An injection declaration normally has to succeed on its own: {@link Inject#require()}
 * is the fewest matches that count, and falling short is reported as {@code AW1043}. That is
 * the wrong shape for a weave written against a target that legitimately varies — two
 * library versions, one method name in each of them, a call that only one build of the
 * target makes. A group is the way to say "at least one of these had to work": each
 * alternative names the group through {@link Inject#group()}, {@link Redirect#group()} or
 * {@link Wrap#group()}, and the bound is checked once against their combined total.
 *
 * <p>The annotation goes on the weave class and is repeatable, so one weave may declare
 * several groups.
 *
 * <h2>How a group's accounting differs from a declaration's own</h2>
 *
 * <p>A declaration that names a group is accounted only through it. Its matches are added to
 * the group's total, and its own {@link Inject#require()} and {@link Inject#allow()} are not
 * checked at all — the group's own accounting cannot report {@code AW1044} for it, and cannot
 * report a shortfall of {@code AW1043} for it either. This is why a grouped declaration is
 * normally written with {@code require = 0}: an omitted {@code require} means one, which reads
 * as a requirement the group is there to replace.
 *
 * <p>{@code AW1043} can still reach a grouped declaration by a different path. Each of a
 * declaration's own {@link At} entries is resolved before accounting runs at all, and a point
 * that finds no position is reported as {@code AW1043} there, unconditionally — grouping a
 * declaration changes what its own zero-match <em>count</em> means, but it does not stop the
 * point resolver from treating that declaration's target as one that has to exist. A group
 * therefore does not, by itself, let one alternative answer for a target the current build
 * genuinely lacks: every target named by a member of the group still has to be found in
 * whichever build the weave runs against, or that declaration's own {@code AW1043} fails the
 * build regardless of what the rest of the group matched.
 *
 * <p>The total is then compared against {@link #min()} and {@link #max()}, once accounting is
 * reached at all. A total outside those bounds is reported as {@code AW1043}, and the message
 * lists every declaration naming the group with the number of positions each of them matched,
 * which is what shows whether one alternative failed or all of them did.
 *
 * <h2>What the total counts</h2>
 *
 * <p>The check runs once for each class the weaver rewrites, over the declarations that apply
 * to that class, while the bounds are collected from every weave the weaver was given. A
 * group whose declarations all name a different target therefore contributes nothing to the
 * total for the class being woven, and a {@link #min()} of one is then unmet. Keep a group's
 * alternatives on declarations that target the same class.
 *
 * <p>A group that no declaration names is checked in the same way, against a total of zero.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Gateway.class)
 * @Group(name = "handshake", min = 1, max = 1)
 * public final class GatewayAudit {
 *
 *     @Inject(method = "send(Payment)",
 *             at = @At(value = Point.INVOKE, target = "Socket.connect"),
 *             require = 0,
 *             group = "handshake")
 *     private void beforeConnect() {
 *     }
 *
 *     @Inject(method = "send(Payment)",
 *             at = @At(value = Point.INVOKE, target = "Socket.open"),
 *             require = 0,
 *             group = "handshake")
 *     private void beforeOpen() {
 *     }
 * }
 * }</pre>
 *
 * <p><b>Both {@code Socket.connect} and {@code Socket.open} have to exist in the build the
 * weave runs against for this to succeed.</b> A group cannot make one alternative answer for a
 * target the current build genuinely lacks: whichever of the two calls is absent leaves its own
 * declaration with no matched position, and the point resolver reports that as {@code AW1043}
 * before the group's accounting is ever reached. A group's {@link #min()} and {@link #max()}
 * only relax how many matches each declaration must individually produce when it does match at
 * least once; they do not tolerate a declaration matching zero times.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Inject#group()
 * @see Redirect#group()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Group.Container.class)
public @interface Group {

    /**
     * The name declarations use to join this group.
     *
     * <p>Matched literally against {@link Inject#group()}, {@link Redirect#group()} and
     * {@link Wrap#group()}. A blank name is rejected when the weave class is read, because
     * an empty group name is how a declaration says it belongs to no group at all.
     *
     * @return the group's name
     */
    String name();

    /**
     * The fewest matches the group's declarations must produce between them.
     *
     * <p>The default of one is the reason to declare a group: it says that the alternatives
     * cover a target that varies, and that failing all of them is a real failure. A value of
     * zero makes the group unable to fail on the lower side, which leaves {@link #max()} as
     * the only statement it makes.
     *
     * @return the minimum total number of matches
     */
    int min() default 1;

    /**
     * The most matches the group's declarations may produce between them.
     *
     * <p>{@code 0} imposes no upper bound rather than permitting none, which is the one place
     * these numbers do not read the way they look. A maximum below {@link #min()} is rejected
     * when the weave class is read rather than reported later, because no total could satisfy
     * it.
     *
     * @return the maximum total number of matches; {@code 0} for unbounded
     */
    int max() default 0;

    /**
     * Holds the repetitions of {@link Group} on one weave class.
     *
     * <p>Written by the compiler when a class carries more than one {@link Group}, and read
     * by the engine, which flattens it back into the individual declarations. There is no
     * reason to write it by hand.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @ApiStatus.Internal
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Container {

        /**
         * The groups the weave class declares, in declaration order.
         *
         * @return the repeated annotations
         */
        Group[] value();
    }
}
