package de.splatgames.aether.weaver.engine.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Objects;
import java.util.Set;

/**
 * A member a weave declares, in one of the four shapes the weaver acts on.
 *
 * <p>Every shape carries the member as the weave wrote it: its own name, its own descriptor and its
 * own flags. Where a declaration also refers to something in the target, that name is a component
 * of its own — {@link Shadowed#targetName()}, {@link Accessor#targetField()},
 * {@link Invoker#targetMethod()} — because the two are allowed to differ and a defaulted one is
 * filled in by the parser rather than inferred later.
 *
 * <p>{@link Accessor} and {@link Invoker} narrow {@link #type()} to a {@link MethodTypeDesc}: both
 * describe a method that is generated onto the target from the declaration's shape alone, which is
 * why a weave of nothing but those needs no class file of its own.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public sealed interface WeaveMember {

    /**
     * Returns the name the member is declared under in the weave class.
     *
     * @return the declared name, never blank
     */
    @Contract(pure = true)
    @NotNull
    String name();

    /**
     * Returns the member's declared type.
     *
     * <p>A {@link ClassDesc} for a field and a {@link MethodTypeDesc} for a method; nothing else
     * survives construction, so a cast to either after asking {@link #isField()} is safe.
     *
     * @return the field type or the method descriptor
     */
    @Contract(pure = true)
    @NotNull
    Object type();

    /**
     * Returns the flags the member carries in the weave class.
     *
     * <p>These are the weave's flags, not the target's. They decide the opcode a merged method is
     * called with, while the flags of a shadowed member come from the target's own declaration.
     *
     * @return an unmodifiable set of the declared flags
     */
    @Contract(pure = true)
    @NotNull
    Set<AccessFlag> flags();

    /**
     * Reports whether this member is a field.
     *
     * @return whether {@link #type()} is a {@link ClassDesc}
     */
    @Contract(pure = true)
    default boolean isField() {
        return type() instanceof ClassDesc;
    }

    /**
     * A member of the weave that is copied into the target.
     *
     * <p>{@code unique} is permission to rename, not a request to: a merged member whose name is free
     * keeps it, and only a collision makes the weaver append a suffix and report {@code AW1094}. The
     * same collision without {@code unique} is refused as {@code AW1080}.
     *
     * @param name   the name declared in the weave class
     * @param type   the field type or the method descriptor
     * @param flags  the flags declared in the weave class
     * @param unique whether the member may be renamed to get out of the target's way
     * @param silent whether a rename is carried out without reporting {@code AW1094}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Merged(String name, Object type, Set<AccessFlag> flags, boolean unique, boolean silent)
            implements WeaveMember {

        /**
         * Checks the name and the type, and copies the flags.
         *
         * @throws NullPointerException     if {@code name}, {@code type} or {@code flags} is {@code null}
         * @throws IllegalArgumentException if {@code name} is blank, or if {@code type} is neither a
         *                                  {@link ClassDesc} nor a {@link MethodTypeDesc}
         */
        public Merged {
            checkMember(name, type);
            flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
        }
    }

    /**
     * A declaration that the target already has this member, so that the weave's own code may name it.
     *
     * <p>A shadow is never copied into the target; it is a promise, and the weaver checks it —
     * {@code AW1030} or {@code AW1020} when the target declares no such member, {@code AW1031} when it
     * declares one of another type. {@code mutable} is the one shadow property that can change the
     * target: when the target's field is actually declared final there, it is rewritten without that
     * flag and the change is reported as {@code AW1033}; a field that was never final is left alone
     * and nothing is reported.
     *
     * @param name       the name declared in the weave class
     * @param type       the field type or the method descriptor, which must match the target's exactly
     * @param flags      the flags declared in the weave class
     * @param targetName the member's name in the target, defaulting to {@code name}
     * @param mutable    whether the target's field is to lose its final flag
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Shadowed(String name, Object type, Set<AccessFlag> flags, String targetName,
                    boolean mutable) implements WeaveMember {

        /**
         * Checks the name, the type and the target name, and copies the flags.
         *
         * @throws NullPointerException     if {@code name}, {@code type}, {@code flags} or
         *                                  {@code targetName} is {@code null}
         * @throws IllegalArgumentException if {@code name} or {@code targetName} is blank, or if
         *                                  {@code type} is neither a {@link ClassDesc} nor a
         *                                  {@link MethodTypeDesc}
         */
        public Shadowed {
            checkMember(name, type);
            Objects.requireNonNull(targetName, "targetName");
            if (targetName.isBlank()) {
                throw new IllegalArgumentException("a shadowed target name must not be blank");
            }
            flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
        }
    }

    /**
     * A method generated onto the target that reads or writes one of its fields.
     *
     * <p>The declaration is not copied and its body is never read, so the usual spelling — an abstract
     * method — is exactly what the weaver expects. Which of the two operations is meant follows from
     * the descriptor alone; see {@link #isGetter()}.
     *
     * @param name        the name the generated method is given on the target
     * @param type        the descriptor, which decides whether this reads or writes
     * @param flags       the flags declared in the weave class
     * @param targetField the field to read or write, named explicitly or inferred from {@code name}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Accessor(String name, MethodTypeDesc type, Set<AccessFlag> flags, String targetField)
            implements WeaveMember {

        /**
         * Checks the name and the target field, and copies the flags.
         *
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if {@code name} or {@code targetField} is blank
         */
        public Accessor {
            checkMember(name, type);
            Objects.requireNonNull(targetField, "targetField");
            if (targetField.isBlank()) {
                throw new IllegalArgumentException("an accessor's target field must not be blank");
            }
            flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
        }

        /**
         * Reports whether this accessor reads rather than writes.
         *
         * <p>Decided by arity alone. Whether the descriptor then fits the field it names is checked where
         * the method is generated, and reported as {@code AW1031}.
         *
         * @return whether the declaration takes no parameter
         */
        @Contract(pure = true)
        public boolean isGetter() {
            return this.type.parameterCount() == 0;
        }
    }

    /**
     * A method generated onto the target that calls one of its methods.
     *
     * <p>The descriptor is the target method's, unchanged: the generated body reloads its own
     * parameters and makes the same call from inside the class, so a signature that differs names no
     * method and is reported as {@code AW1020}.
     *
     * @param name         the name the generated method is given on the target
     * @param type         the descriptor, which must be the target method's exactly
     * @param flags        the flags declared in the weave class
     * @param targetMethod the method to call, named explicitly or inferred from {@code name}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Invoker(String name, MethodTypeDesc type, Set<AccessFlag> flags, String targetMethod)
            implements WeaveMember {

        /**
         * Checks the name and the target method, and copies the flags.
         *
         * @throws NullPointerException     if any argument is {@code null}
         * @throws IllegalArgumentException if {@code name} or {@code targetMethod} is blank
         */
        public Invoker {
            checkMember(name, type);
            Objects.requireNonNull(targetMethod, "targetMethod");
            if (targetMethod.isBlank()) {
                throw new IllegalArgumentException("an invoker's target method must not be blank");
            }
            flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
        }
    }

    /**
     * Checks what every shape has in common: a usable name and one of the two type descriptors.
     *
     * @param name the declared name
     * @param type the declared type
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, or if {@code type} is neither a
     *                                  {@link ClassDesc} nor a {@link MethodTypeDesc}
     */
    private static void checkMember(final String name, final Object type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a member name must not be blank");
        }
        if (!(type instanceof ClassDesc) && !(type instanceof MethodTypeDesc)) {
            throw new IllegalArgumentException(
                    "a member's type is a ClassDesc for a field or a MethodTypeDesc for a method, "
                            + "but was " + type.getClass().getSimpleName());
        }
    }
}
