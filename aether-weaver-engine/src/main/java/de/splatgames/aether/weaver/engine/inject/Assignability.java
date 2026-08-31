package de.splatgames.aether.weaver.engine.inject;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.util.Objects;

/**
 * Decides whether a value of one descriptor may stand where another was declared.
 *
 * <p>Exact for primitives and permissive for references. The permissive half is what the inputs
 * allow rather than a shortcut: a {@link ClassDesc} names a class and says nothing about its
 * supertypes, and this helper is handed two descriptors and nothing else, so the real subtype
 * question cannot be asked here. Refusing every unequal pair of references would refuse a handler
 * that declares a supertype or an interface of what the target holds, which is a correct
 * declaration; accepting instead leaves a genuinely unrelated reference type to the verifier or to
 * a {@link ClassCastException} rather than to a diagnostic.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class Assignability {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private Assignability() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports whether {@code found} may be used where {@code declared} was written.
     *
     * <p>Two primitives must be identical, with no widening: an {@code int} does not satisfy a
     * declared {@code long}, and a {@code byte} does not satisfy a declared {@code int}. That is
     * what makes a mutable capture through {@code LocalIntRef} match an {@code int} variable and
     * nothing else, since the carrier fixes the descriptor the target's slot is compared against. A
     * primitive and a reference never agree, which is what rejects a boxed declaration against a
     * primitive slot.
     *
     * @param found    the descriptor of the value that would be supplied; must not be {@code null}
     * @param declared the descriptor of the position it would fill; must not be {@code null}
     * @return {@code true} when the two are equal, or when neither is primitive
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    static boolean allows(@NotNull final ClassDesc found, @NotNull final ClassDesc declared) {
        Objects.requireNonNull(found, "found");
        Objects.requireNonNull(declared, "declared");
        return found.equals(declared) || (!found.isPrimitive() && !declared.isPrimitive());
    }
}
