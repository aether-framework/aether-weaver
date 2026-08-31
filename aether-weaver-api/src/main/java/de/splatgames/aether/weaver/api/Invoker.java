package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a method on the target that calls one of the target's own methods.
 *
 * <p>The annotated method is a declaration, not an implementation: nothing of its body is
 * used, and the usual spelling is an {@code abstract} method on an {@code abstract} weave
 * class. The engine emits a method of the same name and descriptor onto the target, as
 * {@code public}, whose body forwards to the named method and returns what it returned.
 *
 * <p>The call is made from inside the target, so the target's own {@code private} methods are
 * reachable: a private method is called with {@code invokespecial} rather than
 * {@code invokevirtual}, which is what keeps it from dispatching to an override in a
 * subclass. A static method is called with {@code invokestatic} and an interface method with
 * {@code invokeinterface}.
 *
 * <h2>The declaration's shape</h2>
 *
 * <p>The declaration's descriptor must be the target method's descriptor: the same parameter
 * types in the same order, and the same return type. It is the same call, made from inside
 * the class, so there is nothing for a widening or a narrowing to mean. A declaration that
 * matches no method of that name and descriptor is reported as {@code AW1020}, with the
 * signatures the target does declare under that name.
 *
 * <p>The annotation processor compares the parameter types against the target's source and
 * the engine compares the whole descriptor against its class file, so a return type that
 * disagrees is caught by the engine.
 *
 * <h2>What goes wrong, and what it is called</h2>
 *
 * <ul>
 *   <li>{@code AW1020} — the target declares no method with that name and signature. An
 *       inherited method is not a declared one.
 *   <li>{@code AW1095} — the target already declares a method with the generated name and
 *       descriptor. Rename the declaration: a generated member cannot be {@link Unique},
 *       because callers reach it by the name it is declared under. This is the failure a
 *       declaration named after the method it calls runs into, which is what the {@code call}
 *       and {@code invoke} prefixes exist to avoid.
 * </ul>
 *
 * <h2>Interaction with the weave's kind</h2>
 *
 * <p>Members are only emitted for an instance weave. A weave declared
 * {@code @Weave(kind = Kind.STATIC)} is never merged into its target, and its invoker
 * declarations produce no method and no diagnostic from the engine.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public abstract class LedgerAccess {
 *
 *     // Ledger declares: private Receipt post(BigDecimal amount)
 *     @Invoker
 *     abstract Receipt callPost(BigDecimal amount);
 *
 *     @Invoker("post")
 *     abstract Receipt postAgain(BigDecimal amount);
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Accessor
 * @see Shadow
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Invoker {

    /**
     * The name of the target's method.
     *
     * <p>Left empty, the name is inferred from the declaring method's own name by removing a
     * leading {@code call} or {@code invoke} that is followed by an upper-case letter and
     * lower-casing that letter, so {@code callPost} means {@code post}. The upper-case letter
     * is required: {@code calling} carries no prefix by this rule and names a method called
     * {@code calling}. A name that carries neither prefix is used as it stands.
     *
     * @return the method's name, or an empty string to infer it from the method's name
     */
    String value() default "";
}
