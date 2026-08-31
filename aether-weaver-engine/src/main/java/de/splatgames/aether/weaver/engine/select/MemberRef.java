package de.splatgames.aether.weaver.engine.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Objects;
import java.util.Set;

/**
 * One member a selector resolved to, with everything a call site needs to reference it.
 *
 * <p>{@link #type()} is declared as {@link Object} because a method is typed by a
 * {@link MethodTypeDesc} and a field by a {@link ClassDesc}, and a record cannot vary a component's
 * type by another component. The compact constructor enforces the pairing against {@link #kind()},
 * so {@link #methodType()} succeeds for every method reference and {@link #fieldType()} for every
 * field reference; each refuses only the other kind.
 *
 * <p>{@link #flags()} and {@link #ownerIsInterface()} are carried rather than derived: the access
 * flags and whether the owner is an interface are properties of the declaration this reference
 * names, and neither one can be recovered from the owner, name and type alone.
 *
 * @param owner            the class declaring the member
 * @param name             the member's name as it appears in the class file; never blank
 * @param kind             whether the member is a method or a field
 * @param type             a {@link MethodTypeDesc} for a method, a {@link ClassDesc} for a field
 * @param flags            the member's access flags, copied into an unmodifiable set
 * @param ownerIsInterface whether the declaring class is an interface
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record MemberRef(ClassDesc owner,
                        String name,
                        Kind kind,
                        Object type,
                        Set<AccessFlag> flags,
                        boolean ownerIsInterface) {

    /**
     * Checks that the type given matches the kind, and copies the flags.
     *
     * @throws NullPointerException     if any reference component is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, or if {@code type} is not the
     *                                  descriptor class that {@code kind} requires
     */
    public MemberRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        final boolean typeMatchesKind = kind == Kind.METHOD
                ? type instanceof MethodTypeDesc
                : type instanceof ClassDesc;
        if (!typeMatchesKind) {
            throw new IllegalArgumentException(
                    "a " + kind + " reference needs a "
                            + (kind == Kind.METHOD ? "MethodTypeDesc" : "ClassDesc")
                            + " but got " + type.getClass().getSimpleName());
        }
        flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
    }

    /**
     * Returns a reference to a method.
     *
     * @param owner            the declaring class; must not be {@code null}
     * @param name             the method name; must not be {@code null} or blank
     * @param type             the erased method type; must not be {@code null}
     * @param flags            the method's access flags; must not be {@code null}
     * @param ownerIsInterface whether the declaring class is an interface
     * @return the reference
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    @NotNull
    public static MemberRef ofMethod(@NotNull final ClassDesc owner, @NotNull final String name,
                                     @NotNull final MethodTypeDesc type, @NotNull final Set<AccessFlag> flags,
                                     final boolean ownerIsInterface) {
        return new MemberRef(owner, name, Kind.METHOD, type, flags, ownerIsInterface);
    }

    /**
     * Returns a reference to a field.
     *
     * @param owner            the declaring class; must not be {@code null}
     * @param name             the field name; must not be {@code null} or blank
     * @param type             the field's type; must not be {@code null}
     * @param flags            the field's access flags; must not be {@code null}
     * @param ownerIsInterface whether the declaring class is an interface
     * @return the reference
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    @NotNull
    public static MemberRef ofField(@NotNull final ClassDesc owner, @NotNull final String name,
                                    @NotNull final ClassDesc type, @NotNull final Set<AccessFlag> flags,
                                    final boolean ownerIsInterface) {
        return new MemberRef(owner, name, Kind.FIELD, type, flags, ownerIsInterface);
    }

    /**
     * Returns the method type of a method reference.
     *
     * @return the erased method type
     * @throws IllegalStateException if this is a field reference
     */
    @Contract(pure = true)
    @NotNull
    public MethodTypeDesc methodType() {
        if (!(this.type instanceof MethodTypeDesc methodType)) {
            throw new IllegalStateException("not a method reference: " + this);
        }
        return methodType;
    }

    /**
     * Returns the type of a field reference.
     *
     * @return the field's type
     * @throws IllegalStateException if this is a method reference
     */
    @Contract(pure = true)
    @NotNull
    public ClassDesc fieldType() {
        if (!(this.type instanceof ClassDesc fieldType)) {
            throw new IllegalStateException("not a field reference: " + this);
        }
        return fieldType;
    }

    /**
     * Returns whether the member is static.
     *
     * @return {@code true} when {@link AccessFlag#STATIC} is set
     */
    @Contract(pure = true)
    public boolean isStatic() {
        return this.flags.contains(AccessFlag.STATIC);
    }

    /**
     * Returns whether the member is private.
     *
     * @return {@code true} when {@link AccessFlag#PRIVATE} is set
     */
    @Contract(pure = true)
    public boolean isPrivate() {
        return this.flags.contains(AccessFlag.PRIVATE);
    }

    /**
     * Returns whether the member is abstract.
     *
     * @return {@code true} when {@link AccessFlag#ABSTRACT} is set
     */
    @Contract(pure = true)
    public boolean isAbstract() {
        return this.flags.contains(AccessFlag.ABSTRACT);
    }

    /**
     * Returns whether the member is compiler-generated.
     *
     * <p>Either flag answers, so a bridge counts as compiler-generated even where it carries
     * {@link AccessFlag#BRIDGE} without {@link AccessFlag#SYNTHETIC} beside it.
     *
     * @return {@code true} when {@link AccessFlag#SYNTHETIC} or {@link AccessFlag#BRIDGE} is set
     */
    @Contract(pure = true)
    public boolean isSynthetic() {
        return this.flags.contains(AccessFlag.SYNTHETIC) || this.flags.contains(AccessFlag.BRIDGE);
    }

    /**
     * Returns whether the declaring class is an interface.
     *
     * @return {@code true} when the owner is an interface
     */
    @Contract(pure = true)
    public boolean isInterfaceMember() {
        return this.ownerIsInterface;
    }

    /**
     * Renders the member as a descriptor selector.
     *
     * <p>The result parses back through
     * {@code de.splatgames.aether.weaver.api.select.MemberSelector}, which is what makes it usable
     * both in a diagnostic and as text a caller can feed to the parser again. An array owner keeps
     * its descriptor spelling, since only a class descriptor carries the {@code L...;} wrapper that
     * is stripped here.
     *
     * @return the {@code desc:}-prefixed form naming owner, member and type
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        final String internal = this.owner.isArray()
                ? this.owner.descriptorString()
                : this.owner.descriptorString().substring(1, this.owner.descriptorString().length() - 1);
        return this.kind == Kind.METHOD
                ? "desc:" + internal + '.' + this.name + methodType().descriptorString()
                : "desc:" + internal + '.' + this.name + ':' + fieldType().descriptorString();
    }

    /**
     * Compares all six components, so two references are equal only when they name the same member
     * of the same class with the same flags.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a {@link MemberRef} agreeing in every component
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        return o instanceof MemberRef other
                && this.kind == other.kind
                && this.ownerIsInterface == other.ownerIsInterface
                && this.owner.equals(other.owner)
                && this.name.equals(other.name)
                && this.type.equals(other.type)
                && this.flags.equals(other.flags);
    }

    /**
     * Hashes the same six components {@link #equals(Object)} compares.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.owner, this.name, this.kind, this.type, this.flags,
                this.ownerIsInterface);
    }

    /**
     * Returns {@link #describe()}, so a reference reads as a selector wherever it is printed.
     *
     * @return the descriptor selector form
     */
    @Override
    public String toString() {
        return describe();
    }

    /**
     * Which kind of member a reference names, and therefore which descriptor class its type is.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Kind {

        /** A method, typed by a {@link MethodTypeDesc}. */
        METHOD,

        /** A field, typed by a {@link ClassDesc}. */
        FIELD
    }
}
