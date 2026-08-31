package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the target already has this member, so that the weave's own code may name it.
 *
 * <p>A shadow is a promise, not a contribution. The declaration exists to give the weave's source a
 * name and a type to compile against; the member itself belongs to the target and is never copied
 * there. When the weave is dissolved into its target, every read, write and call of a shadowed
 * member in the copied bodies is rewritten to the target's own member, resolved by the target's own
 * flags rather than by how the weave happened to spell the access.
 *
 * <p>The body a shadow declaration carries is therefore dead weight and is discarded. A field's
 * initialiser is reported as {@code AW1032}, a warning: a shadowed field is the target's, so
 * nothing would ever write the value. A shadowed method's body needs no declaration of intent —
 * throwing from it is the usual way to make the weave's own source compile — and is dropped along
 * with the rest of the declaration.
 *
 * <h2>What the target must declare</h2>
 *
 * <p>The member is looked up on the target by the name {@link #value()} gives, or by the weave
 * member's own name when {@link #value()} is empty. It must be <em>declared</em> by the target
 * class: an inherited member is not a declared one, because resolving the hierarchy would mean
 * loading classes from inside class loading, and a member the target only inherits has to be
 * shadowed where it is declared.
 *
 * <ul>
 *   <li><b>A field the target does not declare</b> is reported as {@code AW1030}. The annotation
 *       processor's diagnostic lists the fields the target does declare; the engine's own
 *       {@code AW1030} carries a message and a remedy but no such listing.
 *   <li><b>A field whose type differs</b> is {@code AW1031}. The comparison is by erased type and
 *       is exact; a shadow of {@code List} does not bind to a field declared {@code ArrayList}.
 *   <li><b>A method the target does not declare</b> is {@code AW1020}, and the message lists the
 *       methods that were found. The engine matches the whole descriptor, return type included;
 *       the annotation processor compares the name and the erased parameter types, so a shadow
 *       that differs only in its return type is caught by the engine rather than at compile time.
 * </ul>
 *
 * <p>Each of these is checked twice: by the annotation processor against the target's source or
 * class file at compile time, and again by the engine against the class it is about to rewrite.
 * The second check is the one that matters when the target changes between the two, but its
 * diagnostics are not always as detailed as the processor's own.
 *
 * <h2>Only an instance weave has anything to shadow</h2>
 *
 * <p>A shadow binds when the weave is dissolved into its target, which is what
 * {@code @Weave(kind = Kind.INSTANCE)} does. A {@code @Weave(kind = Kind.STATIC)} weave stays where
 * it is and its members are never merged, so there is nothing for the declaration to bind to; a
 * shadow in one is reported as {@code AW1090}, an error that discards the whole weave rather than
 * only the offending member — nothing else the weave declares is parsed or applied either.
 * Reaching the target's state from a static weave is what the handler's own parameters,
 * {@link Accessor} and {@link Invoker} are for.
 *
 * <h2>Interaction with another weave</h2>
 *
 * <p>A shadow may name a member that a different weave merges into the same target rather than one
 * the target declares itself, and then the order of the two decides whether the member exists in
 * time. Shadowing a member added by a weave whose {@code @Weave(priority)} is not strictly higher
 * is reported as {@code AW1034}. Equal priority is not enough: the tie is broken by weave class
 * name, which is stable but arbitrary, so a weave that relied on it would be correct by
 * coincidence.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class LedgerAudit {
 *
 *     // Ledger declares: private final java.math.BigDecimal balance;
 *     @Shadow
 *     private java.math.BigDecimal balance;
 *
 *     // Ledger declares: private void record(java.math.BigDecimal amount)
 *     @Shadow
 *     private void record(java.math.BigDecimal amount) {
 *         throw new AssertionError("shadow");   // never copied into the target
 *     }
 *
 *     @Inject(method = "charge(java.math.BigDecimal)", at = @At(Point.HEAD), require = 1)
 *     private void onCharge(java.math.BigDecimal amount) {
 *         if (this.balance.compareTo(amount) < 0) {
 *             record(amount);                   // the target's own field and method
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Accessor
 * @see Invoker
 * @see Unique
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Shadow {

    /**
     * The name the member has on the target.
     *
     * <p>Left empty, the weave member's own name is used, which is the ordinary case: a shadow is
     * normally spelled exactly as the target spells it. Naming it here is what allows a weave to
     * declare a shadow under a different name, for instance when the target's own name collides
     * with something else in the weave.
     *
     * <p>Only the name is redirected. The type of a shadowed field and the full descriptor of a
     * shadowed method still have to match the target's, and a mismatch is {@code AW1031} or
     * {@code AW1020}.
     *
     * @return the target's name for the member, or an empty string to use the declaration's own
     */
    String value() default "";

    /**
     * Rewrites the target so that the shadowed field is no longer {@code final}.
     *
     * <p>Read for a field only; a method has no {@code final} for a shadow to remove and this
     * element is not consulted on one. Setting it where the target's field is not {@code final}
     * changes nothing and reports nothing.
     *
     * <p>Where the target's field <em>is</em> {@code final}, the class is rebuilt with the flag
     * dropped and {@code AW1033} is reported — a warning, because nothing needs doing, but the
     * target no longer holds the guarantee it declared. Two consequences come with it. Dropping
     * the flag rewrites the target's own field, which the JVM does not permit on a class that is
     * already loaded, so an agent attaching to a running JVM reports one {@code AW2101} for this
     * weave, naming every already-loaded target together in that single diagnostic, and weaves the
     * rest in full. And a
     * {@code static final} field of constant type is inlined by {@code javac} at every site that
     * reads it, so code compiled against the old value keeps that value however the field is later
     * written.
     *
     * <p>Every other writer of the field is unaffected, including the target's own constructor:
     * the access flag is all that changes.
     *
     * @return whether to remove {@code final} from the target's field
     */
    boolean mutable() default false;
}
