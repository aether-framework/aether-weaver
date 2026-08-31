package de.splatgames.aether.weaver.engine.select;

import de.splatgames.aether.weaver.api.select.TypePattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a selector is resolved against: the class being searched, and the imports that give an
 * unqualified type name a meaning.
 *
 * <p>The imports are supplied by the caller rather than read from anywhere, because a selector is
 * text from a weave class's source and the class file that weave was compiled to no longer records
 * what it imported. Without them an unqualified name can only be tried against {@code java.lang}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ResolutionContext {

    /** The class whose members a selector is resolved against. */
    private final ClassModel target;

    /** Simple name to type, as the weave's source imported them; copied on construction. */
    private final Map<String, ClassDesc> imports;

    /**
     * Stores the target and copies the imports.
     *
     * @param target  the class to resolve against; must not be {@code null}
     * @param imports the imports in force; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}, or any key or value of
     *                              {@code imports} is
     */
    private ResolutionContext(@NotNull final ClassModel target, @NotNull final Map<String, ClassDesc> imports) {
        this.target = Objects.requireNonNull(target, "target");
        this.imports = Map.copyOf(Objects.requireNonNull(imports, "imports"));
    }

    /**
     * Returns a context with no imports, in which an unqualified name can only mean a
     * {@code java.lang} type.
     *
     * @param target the class to resolve against; must not be {@code null}
     * @return the context
     * @throws NullPointerException if {@code target} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static ResolutionContext of(@NotNull final ClassModel target) {
        return new ResolutionContext(target, Map.of());
    }

    /**
     * Returns a context with the given imports.
     *
     * @param target  the class to resolve against; must not be {@code null}
     * @param imports simple name to type, as the weave's source imported them; must not be
     *                {@code null}
     * @return the context
     * @throws NullPointerException if either argument is {@code null}, or any key or value of
     *                              {@code imports} is
     */
    @NotNull
    public static ResolutionContext of(@NotNull final ClassModel target,
                                       @NotNull final Map<String, ClassDesc> imports) {
        return new ResolutionContext(target, imports);
    }

    /**
     * Returns the class being searched.
     *
     * @return the target model, as it was handed in
     */
    @Contract(pure = true)
    @NotNull
    public ClassModel target() {
        return this.target;
    }

    /**
     * Returns the imports in force.
     *
     * @return the imports, unmodifiable
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public Map<String, ClassDesc> imports() {
        return this.imports;
    }

    /**
     * Resolves a type pattern to the type it names.
     *
     * <p>A wildcard pattern is empty rather than resolved, so a caller matching types has to treat
     * it before asking: an empty result otherwise means the name could not be resolved, and those
     * two answers must not be confused.
     *
     * @param pattern the pattern to resolve; must not be {@code null}
     * @return the type, or empty for a wildcard and for a name nothing resolves
     * @throws NullPointerException if {@code pattern} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public Optional<ClassDesc> resolve(@NotNull final TypePattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        return switch (pattern) {
            case TypePattern.Exact(ClassDesc type) -> Optional.of(type);
            case TypePattern.Any ignored -> Optional.empty();
            case TypePattern.Named(String name, int arrayDepth) ->
                    resolveName(name).map(desc -> arrayOf(desc, arrayDepth));
        };
    }

    /**
     * Resolves a type name written in a selector.
     *
     * <p>An import wins over everything, so a weave may name a type whose simple name collides with
     * a {@code java.lang} one. A qualified name outside {@code java.lang} is taken at face value and
     * is never checked against a class path.
     *
     * @param name the name as written, qualified or not; must not be {@code null}
     * @return the type, or empty when nothing resolves it
     */
    private Optional<ClassDesc> resolveName(@NotNull final String name) {
        final ClassDesc imported = this.imports.get(name);
        if (imported != null) {
            return Optional.of(imported);
        }
        if (name.indexOf('.') >= 0) {
            return tryOf(name);
        }
        // An unqualified name with no import: java.lang is implicitly imported in every source
        // file, so it is the only remaining candidate. Anything else is genuinely unresolvable
        // and must be reported rather than guessed.
        return tryOf("java.lang." + name);
    }

    /**
     * Turns a binary name into a descriptor, refusing a {@code java.lang} name that names no class.
     *
     * <p>{@link ClassDesc#of(String)} produces a descriptor for {@code java.lang.Xyz} without
     * complaint, and {@link #resolveName(String)} prefixes {@code java.lang.} to every unqualified
     * name it cannot otherwise place, so without the existence check an unknown simple name would
     * resolve to a type that does not exist rather than to nothing. A name malformed enough for
     * {@link ClassDesc#of(String)} to reject becomes an empty result here as well, rather than an
     * exception out of a match.
     *
     * @param binaryName the candidate name; must not be {@code null}
     * @return the descriptor, or empty when the name is malformed or is an unknown
     *         {@code java.lang} type
     */
    private static Optional<ClassDesc> tryOf(@NotNull final String binaryName) {
        try {
            final ClassDesc desc = ClassDesc.of(binaryName);
            return "java.lang".equals(desc.packageName()) && !isKnownJavaLangType(desc)
                    ? Optional.empty()
                    : Optional.of(desc);
        } catch (@NotNull final RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns whether the descriptor names a class the running JVM has.
     *
     * <p>Loaded through {@link ClassDesc}'s own loader, which is the bootstrap loader, and without
     * initialising the class. A {@link LinkageError} is treated as a miss rather than propagated,
     * so a broken class on the boot class path costs a resolution rather than the run.
     *
     * @param desc the descriptor to test; must not be {@code null}
     * @return {@code true} when the class was found
     */
    private static boolean isKnownJavaLangType(@NotNull final ClassDesc desc) {
        try {
            Class.forName(desc.packageName() + '.' + desc.displayName(), false,
                    ClassDesc.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Wraps a type in the requested number of array dimensions.
     *
     * @param component the element type; must not be {@code null}
     * @param depth     the number of dimensions; {@code 0} returns the component itself
     * @return the array type
     */
    private static ClassDesc arrayOf(@NotNull final ClassDesc component, final int depth) {
        ClassDesc result = component;
        for (int i = 0; i < depth; i++) {
            result = result.arrayType();
        }
        return result;
    }

    /**
     * Compares the target by identity and the imports by value.
     *
     * <p>A {@link ClassModel} has no value equality — two parses of identical bytes are not equal —
     * so identity is the only comparison of the target that means anything.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a context over the same model instance with equal
     *         imports
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        return o instanceof ResolutionContext other
                && this.target == other.target
                && this.imports.equals(other.imports);
    }

    /**
     * Hashes the target's identity and the imports, to agree with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(this.target), this.imports);
    }

    /**
     * Returns the target's internal name and the number of imports.
     *
     * @return a short form naming the class being resolved against
     */
    @Override
    public String toString() {
        return "ResolutionContext[" + this.target.thisClass().asInternalName()
                + ", imports=" + this.imports.size() + ']';
    }
}
