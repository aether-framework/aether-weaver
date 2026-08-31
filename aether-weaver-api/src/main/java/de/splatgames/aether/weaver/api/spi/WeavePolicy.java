package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.diagnostic.DiagnosticId;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Decides whether a class the plan matched may be woven at all.
 *
 * <p>The policy is the gate between having decided what to do to a class and doing it. It runs
 * after the plan has been consulted and before any bytes are emitted, so a refusal costs the class
 * nothing but the parse that had already happened, and the driver hands back the class it was
 * given.
 *
 * <p>A functional interface: {@link #decide(WeaveTarget)} is the whole contract, and everything
 * else on this type composes it. Implementations are supplied to the engine by whoever builds the
 * weaver; the engine's own default is {@code DefaultWeavePolicy}, and the drivers in this project
 * do not replace it.
 *
 * <h2>When it is called</h2>
 *
 * <p>Once per class that the plan names and that the driver offers, on the thread doing the
 * weaving. A class the plan does not name never reaches the policy: the weaver answers from two map
 * lookups, which is what keeps the load-time path cheap when almost nothing is woven. Where no
 * extension is configured that miss also returns before parsing anything; where one is, the class
 * is still parsed and possibly rewritten for the extension calls and receiver guards it may hold,
 * but the policy is never consulted about it. The engine remembers nothing about a decision, so a
 * policy is asked once for every offer of every class the plan matched, and caching an answer is
 * the policy's own business.
 *
 * <p>Nothing in the engine calls {@link #decide(WeaveTarget)} more than once per offer, and nothing
 * calls it after weaving has begun.
 *
 * <h2>What a refusal produces</h2>
 *
 * <p>A {@link Decision.Deny} becomes a diagnostic built from the denial's own
 * {@link Decision.Deny#code()} and {@link Decision.Deny#reason()}, with a detail naming how many
 * modifications the plan had for that class, and the class is returned unchanged. The severity is
 * whichever the code declares, so a denial carrying a warning-severity code still leaves the class
 * unwoven while printing a warning — the decision, not the severity, is what stops the weave.
 *
 * <p>The engine tests the returned decision for being a {@link Decision.Deny}, not for being a
 * {@link Decision.Allow}. A policy that returns {@code null} therefore lets the class through
 * silently, and so does one that returns some other allowing decision.
 *
 * <h2>Throwing</h2>
 *
 * <p>A throw out of {@link #decide(WeaveTarget)} is not contained by the engine. It leaves the
 * weaver's {@code weave} call, and what happens next belongs to the driver:
 *
 * <ul>
 *   <li>The agent's transformer catches every {@link Throwable}, reports {@code AW4090}, and then
 *       either leaves the class unwoven or halts the JVM, according to
 *       {@code aether.weaver.onError}.
 *   <li>The load-time class loader catches {@link RuntimeException} and {@link LinkageError},
 *       reports {@code AW4090}, and either defines the class unwoven or throws
 *       {@link ClassNotFoundException} under {@code aether.weaver.onError=fail}.
 *   <li>The build plugin catches only the engine's own {@code WeaveException}, so anything else a
 *       policy throws ends the build with a stack trace naming the policy.
 * </ul>
 *
 * <p>Refusing with a {@link Decision.Deny} is the way to say no. It carries a code and a reason the
 * user can act on; an exception carries neither and costs the whole run rather than the one class.
 *
 * <h2>Composition</h2>
 *
 * <p>{@link #and(WeavePolicy)} composes two policies so that either may refuse and neither may
 * permit what the other refused. Composition can only narrow, which is the property that makes it
 * safe to hand a policy to a third party: a rule added on top of the default one cannot reopen
 * {@code java.*} or make the framework weave itself.
 *
 * <h2>What the default policy decides</h2>
 *
 * <p>{@code DefaultWeavePolicy}, in the engine, applies these rules in this order and stops at the
 * first that refuses. The order is what decides which code a class denied by several rules is
 * reported under.
 *
 * <ol>
 *   <li>{@link WeaveTarget#declaredWeaveClass()} — {@code AW1087}. A weave is a declaration folded
 *       into its own targets and is never loaded as itself.
 *   <li>A class of Aether Weaver itself — {@code AW3003}. No setting reopens it.
 *   <li>{@code java.*} — {@code AW3001}. No setting reopens it either: those classes load before
 *       any transformer can be installed.
 *   <li>A class file older than major version 50 — {@code AW2003}. Its stack map frames are absent
 *       or inferred, which the engine's transforms assume are present.
 *   <li>{@link WeaveTarget#signed()} without the signed override — {@code AW3002}.
 *   <li>Any other JDK prefix — {@code javax.}, {@code jdk.}, {@code sun.}, {@code com.sun.} —
 *       unless that exact package has been reopened, which is {@code AW3001} again. The override
 *       names one package and never a wildcard.
 * </ol>
 *
 * <p>Using the signed override is reported as {@code AW3020} by the build plugin, which weaves
 * dependency jars and knows their signatures. The load-time class loader and the default policy
 * accept the override without reporting it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * final class AcmePolicies {
 *
 *     static final DiagnosticId GENERATED = new PluginDiagnosticId(
 *             "acme", "GENERATED_CODE", Severity.ERROR, DiagnosticCode.Category.POLICY,
 *             "generated code is regenerated on every build and is not woven");
 *
 *     static WeavePolicy notGenerated() {
 *         return target -> target.binaryName().contains(".generated.")
 *                 ? new WeavePolicy.Decision.Deny(GENERATED,
 *                         target.binaryName() + " is generated and would lose its weave on the "
 *                                 + "next build")
 *                 : WeavePolicy.Decision.allow();
 *     }
 *
 *     static void build(java.util.List<WeaveClass> weaves) {
 *         // Narrows the engine's default rather than replacing it.
 *         Weaver.builder()
 *                 .weaves(weaves)
 *                 .policy(DefaultWeavePolicy.standard().and(notGenerated()))
 *                 .build();
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaveTarget
 */
@FunctionalInterface
public interface WeavePolicy {

    /**
     * Decides whether the given class may be woven.
     *
     * <p>Called on the weaving thread, once per class the plan matched, before anything is emitted.
     * Under the load-time driver more than one class can be loaded at a time, so a policy holding
     * mutable state must expect concurrent calls.
     *
     * <p>Returning {@link Decision#allow()} is a statement that this policy has nothing against the
     * class, not that the class will be woven: a composed policy may still refuse it, and the
     * engine has further gates of its own after this one.
     *
     * <p>Declared {@code @NotNull}, so a correct implementation never returns {@code null}. The
     * engine, though, does not test for {@link Decision.Allow}; it tests only whether the result is
     * a {@link Decision.Deny}, so an implementation that returns {@code null} in violation of this
     * contract has the same effect as one that returns {@link Decision#allow()} — the class is let
     * through either way.
     *
     * @param target what is known about the class; never {@code null}, and its {@link
     *               WeaveTarget#signed()} and {@link WeaveTarget#declaredWeaveClass()} are
     *               {@code false} when the engine's own gate asks
     * @return the decision; a {@link Decision.Deny} refuses the class and anything else lets it
     *         through
     */
    @Contract(pure = true)
    @NotNull
    Decision decide(@NotNull WeaveTarget target);

    /**
     * Returns a policy that refuses whatever either this policy or the given one refuses.
     *
     * <p>This policy decides first. Its denial is returned as it stands and {@code other} is not
     * consulted, so the reason a user reads is the first one that applied and the second policy
     * cannot overwrite it with a less useful one. Where this policy does not refuse, the other's
     * decision is returned unchanged.
     *
     * <p>Composition never widens: {@code other} has no way to turn a denial into permission. That
     * is what makes it safe to compose a policy supplied by someone else on top of the default one.
     *
     * @param other the policy to consult when this one does not refuse; must not be {@code null}
     * @return a new policy refusing the union of what the two refuse
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    default WeavePolicy and(@NotNull final WeavePolicy other) {
        Objects.requireNonNull(other, "other");
        return target -> {
            final Decision mine = decide(target);
            return mine instanceof Decision.Deny ? mine : other.decide(target);
        };
    }

    /**
     * The answer a policy gives about one class: permission, or a refusal carrying its reason.
     *
     * <p>Sealed to exactly {@link Allow} and {@link Deny}, so a caller may switch over it
     * exhaustively and a third implementation cannot appear. Both are records, so two decisions
     * describing the same thing are equal.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    sealed interface Decision {

        /**
         * Returns the permitting decision.
         *
         * <p>{@link Allow} carries nothing, so one shared instance answers for every call and no
         * allocation is made on the path a policy takes for most classes.
         *
         * @return the shared {@link Allow}
         */
        @Contract(pure = true)
        @NotNull
        static Decision allow() {
            return Allow.INSTANCE;
        }

        /**
         * Returns whether this decision permits weaving.
         *
         * <p>Answers on the type of the decision, so any {@link Allow} permits and any {@link Deny}
         * refuses, whatever code and reason the denial carries.
         *
         * @return {@code true} for an {@link Allow}, {@code false} for a {@link Deny}
         */
        @Contract(pure = true)
        default boolean isAllowed() {
            return this instanceof Allow;
        }

        /**
         * The decision that permits weaving.
         *
         * <p>A component-less record: every instance is equal to every other, so
         * {@link Decision#allow()} and {@code new Allow()} are interchangeable.
         *
         * @author Erik Pförtner
         * @since 0.1.0
         */
        record Allow() implements Decision {

            /** The instance {@link Decision#allow()} hands out, so that permission allocates nothing. */
            private static final Allow INSTANCE = new Allow();
        }

        /**
         * The decision that refuses weaving, with the code and the reason the refusal is reported
         * under.
         *
         * <p>Both components end up in the user's build output: the code is what a reader searches
         * for and the reason is the message of the diagnostic the engine raises. A denial reported
         * by the engine's own gate also gains a detail line naming how many modifications the plan
         * had for that class.
         *
         * <p>{@link #code()} may be a {@code DiagnosticCode} of the framework's own catalogue or a
         * {@code PluginDiagnosticId} of a plugin's; a policy contributed by a third party should
         * use the second, so that its refusals are attributable to it.
         *
         * @param code   the identity the refusal is reported under
         * @param reason a sentence saying what was refused and why, which becomes the diagnostic's
         *               message
         * @author Erik Pförtner
         * @since 0.1.0
         */
        record Deny(@NotNull DiagnosticId code, @NotNull String reason) implements Decision {

            /**
             * Checks that a refusal carries something a user can act on.
             *
             * <p>A blank reason is refused rather than tolerated: the reason is the whole of the
             * message the user sees, and a denial with none leaves them a code and a class name.
             *
             * @throws NullPointerException     if either component is {@code null}
             * @throws IllegalArgumentException if {@code reason} is blank
             */
            public Deny {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(reason, "reason");
                if (reason.isBlank()) {
                    throw new IllegalArgumentException(
                            "a denial must carry a reason a user can act on");
                }
            }
        }
    }
}
