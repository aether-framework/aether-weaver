package de.splatgames.aether.weaver.api.select;

/**
 * Which kind of member a selector is expected to name.
 *
 * <p>Passed to {@link MemberSelector#parse(String, MemberKind)} to settle the one shape the grammar cannot settle
 * on its own: a source-form selector that carries neither a parameter list nor a type. {@code ledger} could name a
 * field or a method of any signature, and only the declaration the text came from knows which.
 *
 * <p>The hint decides that case and no other. A parameter list makes a selector a {@link MethodSelector} and a
 * {@code :type} suffix makes it a {@link FieldSelector} whatever the hint says; the
 * {@value MemberSelector#DESCRIPTOR_PREFIX} form takes its answer from the presence of an opening parenthesis; and
 * a constant ignores the hint entirely. Parsing without a hint, through {@link MemberSelector#parse(String)},
 * reads a bare name as a method, which is what an injection's {@code method} element means.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector#parse(String, MemberKind)
 */
public enum MemberKind {

    /**
     * A method, a constructor or a static initialiser.
     *
     * <p>What a bare name reads as when no kind is given, and what an injection's {@code method} element asks for.
     */
    METHOD,

    /**
     * A field.
     *
     * <p>What a field-shaped injection point asks for, so that {@code ledger} names a field rather than a method of
     * any signature.
     */
    FIELD
}
