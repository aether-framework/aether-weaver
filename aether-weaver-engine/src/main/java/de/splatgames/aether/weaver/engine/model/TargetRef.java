package de.splatgames.aether.weaver.engine.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.util.Objects;

/**
 * A class a weave declares as its target, and the spelling its declaration used.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser} produces either class
 * literals or names for one weave and refuses a declaration that mixes the two, so
 * {@code declaredAsClassLiteral} is uniform across the targets of a single weave.
 *
 * @param type                   the target type; a class or an interface, never a primitive or an
 *                               array
 * @param declaredAsClassLiteral whether the weave named the target with a class literal rather than
 *                               with a string
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record TargetRef(ClassDesc type, boolean declaredAsClassLiteral) {

    /**
     * Checks that the descriptor names something a weave can be applied to.
     *
     * @throws NullPointerException     if {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code type} is a primitive or an array type
     */
    public TargetRef {
        Objects.requireNonNull(type, "type");
        if (!type.isClassOrInterface()) {
            throw new IllegalArgumentException(
                    "a weave target must be a class or interface, but was " + type.displayName());
        }
    }

    /**
     * Returns a reference to a target written as a class literal.
     *
     * @param type the target type; must not be {@code null}
     * @return the reference
     * @throws NullPointerException     if {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code type} is a primitive or an array type
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static TargetRef ofClassLiteral(@NotNull final ClassDesc type) {
        return new TargetRef(type, true);
    }

    /**
     * Returns a reference to a target written as a name.
     *
     * @param type the target type; must not be {@code null}
     * @return the reference
     * @throws NullPointerException     if {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code type} is a primitive or an array type
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static TargetRef ofName(@NotNull final ClassDesc type) {
        return new TargetRef(type, false);
    }

    /**
     * Returns the target's binary name, as {@code com.acme.Session}.
     *
     * <p>Taken from the descriptor rather than from {@link ClassDesc#displayName()}, which drops the
     * package. The constructor's class-or-interface check is what makes stripping the leading
     * {@code L} and the trailing {@code ;} correct here.
     *
     * @return the binary name, with a nested class separated by a dollar sign
     */
    @Contract(pure = true)
    @NotNull
    public String binaryName() {
        final String descriptor = this.type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /**
     * Returns the target's internal name, as {@code com/acme/Session}.
     *
     * @return the internal name, the form in which the plan and {@code Weaver.weave} name a class
     */
    @Contract(pure = true)
    @NotNull
    public String internalName() {
        final String descriptor = this.type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }
}
