package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a method on the target that reads or writes one of the target's own fields.
 *
 * <p>The annotated method is a declaration, not an implementation: nothing of its body is
 * used, and the usual spelling is an {@code abstract} method on an {@code abstract} weave
 * class. The engine emits a method of the same name and descriptor onto the target, as
 * {@code public}, whose body is the single field access the declaration describes. The
 * weave's own handlers, which are merged into the same target, can then call it like any
 * other method of that class.
 *
 * <p>Where {@link Shadow} declares that the target already has a member and binds to it,
 * an accessor adds something the target did not have: a way in from outside its own package,
 * or a name for a field the weave means to expose. Where a weave only needs to read a field
 * for itself, {@link Shadow} is the smaller tool and adds nothing to the target.
 *
 * <h2>The declaration's shape</h2>
 *
 * <p>The parameter count decides which of the two shapes is meant, and the field's type
 * decides whether it fits.
 *
 * <ul>
 *   <li><b>A getter takes nothing and returns the field's type.</b>
 *   <li><b>A setter takes exactly one parameter of the field's type and returns
 *       {@code void}.</b>
 * </ul>
 *
 * <p>A declaration that is neither is reported as {@code AW1031}, with the field's type and
 * the descriptor that was written.
 *
 * <h2>What goes wrong, and what it is called</h2>
 *
 * <ul>
 *   <li>{@code AW1030} — the target declares no field of that name. Both the annotation
 *       processor and the engine report it, but only the processor's message lists the fields
 *       the target does declare; where the name was inferred from the method's own name rather
 *       than written, {@link #value()} is how to correct it.
 *   <li>{@code AW1031} — the declaration describes neither a read nor a write of that field.
 *       Reported by the engine alone, against the target's class file; the annotation
 *       processor does not check the declaration's shape against the field's type.
 *   <li>{@code AW1097} — the declaration is a setter and the target's field is {@code final}.
 *       Both the annotation processor and the engine report it. The generated class verifies
 *       and throws {@code IllegalAccessError} the first time the setter is called, which is why
 *       this is refused rather than emitted. Removing {@code final} is what
 *       {@link Shadow#mutable()} does, deliberately and visibly; an accessor has no way to
 *       express that intent.
 *   <li>{@code AW1095} — the target already declares a method with the generated name and
 *       descriptor. Both the annotation processor and the engine report it. Rename the
 *       declaration: a generated member cannot be {@link Unique}, because callers reach it by
 *       the name it is declared under.
 * </ul>
 *
 * <p>Where both sides check a code, a target that changed after the weave was compiled is still
 * caught by the engine even if the processor saw an older shape.
 *
 * <h2>Interaction with the weave's kind</h2>
 *
 * <p>Members are only emitted for an instance weave. A weave declared
 * {@code @Weave(kind = Kind.STATIC)} is never merged into its target, and its accessor
 * declarations produce no method and no diagnostic from the engine.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public abstract class LedgerAccess {
 *
 *     // Ledger declares: private BigDecimal balance;
 *     @Accessor
 *     abstract BigDecimal getBalance();
 *
 *     @Accessor("balance")
 *     abstract void overwriteBalance(BigDecimal value);
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Invoker
 * @see Shadow
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Accessor {

    /**
     * The name of the target's field.
     *
     * <p>Left empty, the name is inferred from the declaring method's own name by removing a
     * leading {@code get}, {@code set} or {@code is} that is followed by an upper-case letter
     * and lower-casing that letter, so {@code getBalance} means {@code balance} and
     * {@code isOpen} means {@code open}. A name that carries none of those prefixes is used
     * as it stands, which is what makes {@code @Accessor} on a method called {@code balance}
     * work without an argument.
     *
     * <p>The upper-case letter is required: {@code getting} carries no prefix by this rule and
     * names a field called {@code getting}. The inference is textual and knows nothing about
     * the target, so a setter named {@code setUp} names the field {@code up}. Write the name
     * out where the inference would be wrong or merely unclear.
     *
     * @return the field's name, or an empty string to infer it from the method's name
     */
    String value() default "";
}
