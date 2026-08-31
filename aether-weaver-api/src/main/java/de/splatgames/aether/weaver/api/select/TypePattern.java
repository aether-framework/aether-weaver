package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import java.lang.constant.ClassDesc;
import java.util.Objects;

/**
 * One type position inside a selector: an owner, a parameter, a return type or a field type.
 *
 * <p>A selector is written before anything is known about the classes it names, so a type position holds one of
 * three things. It is resolved to a {@link ClassDesc} ({@link Exact}), it is a name that still has to be resolved
 * ({@link Named}), or it is a wildcard that constrains nothing ({@link Any}). The interface is sealed to those
 * three, so a {@code switch} over a pattern is exhaustive without a default branch.
 *
 * <p>Which one a position becomes is decided by how the selector was written:
 *
 * <ul>
 *   <li>Every type in a {@value MemberSelector#DESCRIPTOR_PREFIX} selector is {@link Exact}, because a descriptor
 *       carries no ambiguity to resolve.
 *   <li>A primitive keyword in the source form is {@link Exact} as well: {@code charge(int)} needs no context to
 *       be understood, and {@code charge(int[])} resolves to the array type.
 *   <li>Every other name in the source form is {@link Named}, whether it is written simple as {@code BigDecimal}
 *       or qualified as {@code java.math.BigDecimal}.
 *   <li>{@code *} is {@link Any}.
 * </ul>
 *
 * <h2>What each one matches</h2>
 *
 * <p>Matching is the consumer's decision, and different engine matchers are free to make it differently. In
 * {@code de.splatgames.aether.weaver.engine.inject.point.Targets}, the matcher behind an {@code @At} target, an
 * {@link Any} matches every type, an {@link Exact} matches by equality of the {@link ClassDesc} so a descriptor
 * selector never matches a type it does not name outright, and a {@link Named} is compared by its rendered source
 * name against the candidate's, matching when the two are equal or when the candidate ends with a dot followed by
 * the name -- which is what lets {@code charge(BigDecimal)} select a method taking {@code java.math.BigDecimal},
 * and what makes a simple name ambiguous where two packages hold the same class name.
 *
 * <p>{@link #isResolved()} is what {@link MemberSelector#isFullyQualified()} and therefore
 * {@link MemberSelector#canonical()} are built from: a selector canonicalises only when every one of its type
 * positions is {@link Exact}.
 *
 * <p>Every implementation is a record, so all three are immutable, comparable by value and safe to share.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector
 * @see MethodSelector
 * @see FieldSelector
 */
public sealed interface TypePattern {

    /**
     * Returns the wildcard pattern.
     *
     * <p>Answers one shared instance. Nothing depends on that -- {@link Any} is a record with no components, so
     * every instance is equal to every other -- and it keeps a selector with several wildcards from allocating one
     * object per position.
     *
     * @return the pattern that matches every type
     */
    @Contract(pure = true)
    static TypePattern any() {
        return Any.INSTANCE;
    }

    /**
     * Returns a pattern for a type that is already known.
     *
     * <p>The array dimensions live in the {@link ClassDesc} itself, so {@code of(CD_int.arrayType())} is a pattern
     * for {@code int[]}.
     *
     * @param type the type; must not be {@code null}
     * @return a resolved pattern for that type
     * @throws NullPointerException if {@code type} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    static TypePattern of(@NotNull final ClassDesc type) {
        return new Exact(type);
    }

    /**
     * Returns a pattern for a type named but not resolved.
     *
     * <p>The name is kept exactly as it was written, simple or qualified, and the array dimensions are counted
     * separately because there is no {@link ClassDesc} yet to hold them.
     *
     * @param sourceName the type name as written; must not be blank
     * @param arrayDepth the number of array dimensions; must not be negative
     * @return an unresolved pattern for that name
     * @throws NullPointerException     if {@code sourceName} is {@code null}
     * @throws IllegalArgumentException if {@code sourceName} is blank or {@code arrayDepth} is negative
     */
    @Contract(value = "_, _ -> new", pure = true)
    static TypePattern named(@NotNull final String sourceName, final int arrayDepth) {
        return new Named(sourceName, arrayDepth);
    }

    /**
     * Reports whether this pattern names a type that needs no further resolution.
     *
     * <p>True only for {@link Exact}. A wildcard is not resolved because it names no type at all, and a name is not
     * resolved because what it refers to depends on the context the selector is matched in.
     *
     * @return whether this pattern is an {@link Exact}
     */
    @Contract(pure = true)
    boolean isResolved();

    /**
     * Renders this pattern the way Java source writes the type.
     *
     * <p>Always answers, for all three kinds, which is why a selector always has a source rendering. The text is
     * what {@link MemberSelector#render(MemberSelector.Form)} embeds, and what
     * {@code de.splatgames.aether.weaver.engine.inject.point.Targets} compares an unresolved name against when
     * matching an {@code @At} target.
     *
     * @return the source spelling: {@code *} for a wildcard, the qualified name for a resolved type, and the name
     *         as written followed by its brackets for an unresolved one
     */
    @Contract(pure = true)
    String renderSource();

    /**
     * The wildcard: every type satisfies it.
     *
     * <p>Written {@code *}, and the only pattern a selector can carry in place of a type without naming one.
     * Present in a parameter list it still occupies a position, so {@code charge(*)} matches a method of one
     * parameter rather than a method of any arity.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Any() implements TypePattern {

        /** The shared instance {@link TypePattern#any()} returns. */
        private static final Any INSTANCE = new Any();

        /**
         * {@inheritDoc}
         *
         * @return always {@code false}: a wildcard names no type to resolve
         */
        @Override
        public boolean isResolved() {
            return false;
        }

        /**
         * {@inheritDoc}
         *
         * @return {@code *}
         */
        @Override
        public String renderSource() {
            return "*";
        }

        /**
         * Returns the wildcard character.
         *
         * @return {@code *}
         */
        @Override
        public String toString() {
            return "*";
        }
    }

    /**
     * A type that is already resolved to a {@link ClassDesc}.
     *
     * <p>What every type in a {@value MemberSelector#DESCRIPTOR_PREFIX} selector is, and what a primitive keyword
     * in the source form becomes. This is the only pattern that carries a descriptor, so it is the only one a
     * selector can be rendered into the descriptor form from.
     *
     * @param type the resolved type, including any array dimensions
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Exact(ClassDesc type) implements TypePattern {

        /**
         * Checks the component.
         *
         * @throws NullPointerException if {@code type} is {@code null}
         */
        public Exact {
            Objects.requireNonNull(type, "type");
        }

        /**
         * {@inheritDoc}
         *
         * @return always {@code true}
         */
        @Override
        public boolean isResolved() {
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * <p>A primitive renders as its keyword, an array appends {@code []} per dimension, and a class renders
         * with its package, so {@code Lcom/acme/Outer$Inner;} renders as {@code com.acme.Outer$Inner} -- the
         * binary name, which is what a selector is parsed back from.
         *
         * @return the qualified source name of the type
         */
        @Override
        public String renderSource() {
            return SelectorRendering.sourceName(this.type);
        }

        /**
         * Returns the descriptor, which identifies the type without depending on how a selector spells it.
         *
         * @return the descriptor string, as in {@code I} or {@code Ljava/lang/String;}
         */
        @Override
        public String toString() {
            return this.type.descriptorString();
        }
    }

    /**
     * A type named in source form and not yet resolved.
     *
     * <p>The name is kept as written, so a simple name stays simple: resolving it needs the imports of the file the
     * selector was written in, or a suffix comparison against the candidate, and neither is available while
     * parsing. The array dimensions are held separately because there is no {@link ClassDesc} to put them in.
     *
     * @param sourceName the type name exactly as written, simple or qualified; never blank
     * @param arrayDepth the number of array dimensions, zero for a non-array; never negative
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Named(String sourceName, int arrayDepth) implements TypePattern {

        /**
         * Checks the components.
         *
         * @throws NullPointerException     if {@code sourceName} is {@code null}
         * @throws IllegalArgumentException if {@code sourceName} is blank or {@code arrayDepth} is negative
         */
        public Named {
            Objects.requireNonNull(sourceName, "sourceName");
            if (sourceName.isBlank()) {
                throw new IllegalArgumentException("sourceName must not be blank");
            }
            if (arrayDepth < 0) {
                throw new IllegalArgumentException("arrayDepth must not be negative: " + arrayDepth);
            }
        }

        /**
         * {@inheritDoc}
         *
         * @return always {@code false}: what the name refers to depends on the context the selector is matched in
         */
        @Override
        public boolean isResolved() {
            return false;
        }

        /**
         * {@inheritDoc}
         *
         * @return the name as written, followed by one {@code []} per array dimension
         */
        @Override
        public String renderSource() {
            return this.sourceName + "[]".repeat(this.arrayDepth);
        }

        /**
         * Returns the source spelling, which is the only text this pattern has.
         *
         * @return the result of {@link #renderSource()}
         */
        @Override
        public String toString() {
            return renderSource();
        }
    }
}
