package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * A selector that names a method, a constructor or a static initialiser.
 *
 * <p>Produced by {@link MemberSelector#parse(String)} for a bare name and for any selector carrying a parameter
 * list, by {@link MemberSelector#parse(String, MemberKind)} for a bare name unless {@link MemberKind#FIELD} was
 * asked for, and by either for every {@value MemberSelector#DESCRIPTOR_PREFIX} selector whose body contains an
 * opening parenthesis. {@link MemberSelector#ofDescriptor(ClassDesc, String, MethodTypeDesc)} builds one directly.
 *
 * <h2>What each part constrains</h2>
 *
 * <p>Three of the four parts are optional, and an absent part is a part the selector says nothing about. The
 * distinction that costs the most to get wrong is {@link #parameters()}: an empty {@link Optional} means any
 * signature, while a present empty {@link List} means a signature with no parameters at all.
 *
 * <table border="1">
 *   <caption>Selector text and the constraints it carries</caption>
 *   <tr><th>Text</th><th>{@link #owner()}</th><th>{@link #name()}</th><th>{@link #parameters()}</th>
 *       <th>{@link #returnType()}</th></tr>
 *   <tr><td>{@code charge}</td><td>absent</td><td>{@code charge}</td><td>absent: any signature</td>
 *       <td>absent: any</td></tr>
 *   <tr><td>{@code charge()}</td><td>absent</td><td>{@code charge}</td><td>present, empty: no parameters</td>
 *       <td>absent: any</td></tr>
 *   <tr><td>{@code charge(int):void}</td><td>absent</td><td>{@code charge}</td><td>one {@code int}</td>
 *       <td>{@code void}</td></tr>
 *   <tr><td>{@code Gateway.send(*)}</td><td>{@code Gateway}, unresolved</td><td>{@code send}</td>
 *       <td>one, any type</td><td>absent: any</td></tr>
 *   <tr><td>{@code desc:com/acme/G.send(I)V}</td><td>{@code com.acme.G}, resolved</td><td>{@code send}</td>
 *       <td>one {@code int}</td><td>{@code void}</td></tr>
 * </table>
 *
 * <h2>Rendering</h2>
 *
 * <p>The source rendering writes the owner, the name, the parameter list when one is present, and the return type
 * after a colon when one is present. Parameters are separated by {@code ", "}. The descriptor rendering requires
 * every type to be resolved, the parameter list and the return type to be present, the owner -- when there is one
 * -- to be resolved, and the name to hold no wildcard; failing any of those, {@link #render(MemberSelector.Form)}
 * answers with the source rendering rather than an approximate descriptor.
 *
 * <p>An owner is optional in the descriptor rendering, so {@code m(int):void} renders as {@code desc:m(I)V} even
 * though it names no class. That text is exact in its signature and still not {@linkplain #isFullyQualified()
 * fully qualified}, which is why {@link #canonical()} is empty for it.
 *
 * <h2>Equality</h2>
 *
 * <p>Owner, name, parameters and return type decide equality; the {@linkplain #form() form} does not. A selector
 * parsed from {@code desc:m()I} equals one parsed from {@code m():int}.
 *
 * <h2>Resolution failures</h2>
 *
 * <p>A selector that resolves to no method is reported as {@code AW1020}, listing every method the target
 * declares, and one that resolves to more than one is reported as {@code AW1021}, listing the methods that
 * matched; naming the descriptor form, with its exact parameter and return types, disambiguates an overload. At
 * compile time the annotation processor reports {@code AW1022} instead of {@code AW1021} when the name is the
 * {@code *} wildcard and the match count exceeds what is allowed -- {@code AW1021} there is reserved for a
 * non-wildcard name matching more than one method.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * MethodSelector overload = (MethodSelector) MemberSelector.parse("get():int");
 * assert !overload.equals(MemberSelector.parse("get():String"));   // the return type separates them
 *
 * MethodSelector ctor = (MethodSelector) MemberSelector.parse("<init>(String, int)");
 * assert ctor.isInitialiser();
 *
 * MethodSelector pinned = MemberSelector.ofDescriptor(
 *         ClassDesc.of("com.acme.Gateway"), "send",
 *         MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("com.acme.Payment")));
 * assert pinned.canonical().orElseThrow()
 *         .equals("desc:com/acme/Gateway.send(Lcom/acme/Payment;)V");
 * }</pre>
 *
 * <p>Immutable and safe to share between threads.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector
 * @see FieldSelector
 */
public final class MethodSelector implements MemberSelector {

    /**
     * The name a constructor carries in a class file.
     *
     * <p>Written literally in a selector, in either form: {@code <init>(String, int)} and
     * {@code desc:<init>(Ljava/lang/String;I)V} both name one.
     */
    public static final String CONSTRUCTOR_NAME = "<init>";

    /**
     * The name a class initialiser carries in a class file.
     *
     * <p>Written literally in a selector, as {@code <clinit>()} or {@code desc:<clinit>()V}.
     */
    public static final String STATIC_INITIALISER_NAME = "<clinit>";

    /** The declaring class, or {@code null} when the selector names none. */
    private final @Nullable TypePattern owner;

    /** The method name, a wildcard, or an initialiser name; never blank. */
    private final String name;

    /** The parameter types in declaration order, or {@code null} when the selector constrains no signature. */
    private final @Nullable List<TypePattern> parameters;

    /** The return type, or {@code null} when the selector constrains none. */
    private final @Nullable TypePattern returnType;

    /** The spelling this selector was written in, which decides only {@link #toString()}. */
    private final Form form;

    /**
     * Builds a method selector from parts that have already been parsed or resolved.
     *
     * <p>The parameter list is copied through {@link List#copyOf(java.util.Collection)}, so the result is
     * unmodifiable and independent of the caller's list, and a {@code null} element is rejected.
     *
     * @param owner      the declaring class, or {@code null} to constrain none
     * @param name       the method name, a {@code *} wildcard, or an initialiser name; must not be blank
     * @param parameters the parameter types in declaration order, an empty list for a method taking nothing, or
     *                   {@code null} to constrain no signature
     * @param returnType the return type, or {@code null} to constrain none
     * @param form       the spelling this selector was written in
     * @throws NullPointerException     if {@code name} or {@code form} is {@code null}, or {@code parameters}
     *                                  holds a {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    MethodSelector(final @Nullable TypePattern owner,
                   @NotNull final String name,
                   final @Nullable List<TypePattern> parameters,
                   final @Nullable TypePattern returnType,
                   @NotNull final Form form) {
        this.owner = owner;
        this.name = requireName(name);
        this.parameters = parameters == null ? null : List.copyOf(parameters);
        this.returnType = returnType;
        this.form = Objects.requireNonNull(form, "form");
    }

    /**
     * Checks that a method name is usable.
     *
     * <p>Blankness is the only rule enforced here. The descriptor form deliberately imposes no more, which is what
     * lets a selector name a synthetic method such as {@code lambda$process$0}; the source grammar applies its own
     * stricter rule while parsing.
     *
     * @param name the name to check
     * @return the name, unchanged
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    private static String requireName(@NotNull final String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }

    /**
     * Builds a method selector whose owner and signature are already resolved.
     *
     * <p>The parameter and return types are taken from the descriptor, so every one of them is a
     * {@link TypePattern.Exact} and the result is {@linkplain #isFullyQualified() fully qualified} whenever an
     * owner is given.
     *
     * @param owner the declaring class, or {@code null} to constrain none
     * @param name  the method name; must not be blank
     * @param type  the erased signature
     * @return a method selector in {@link Form#DESCRIPTOR}
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    static MethodSelector ofDescriptor(final @Nullable ClassDesc owner,
                                       @NotNull final String name,
                                       @NotNull final MethodTypeDesc type) {
        Objects.requireNonNull(type, "type");
        return new MethodSelector(
                owner == null ? null : TypePattern.of(owner),
                name,
                type.parameterList().stream().map(TypePattern::of).toList(),
                TypePattern.of(type.returnType()),
                Form.DESCRIPTOR);
    }

    /**
     * Returns the declaring class this selector names.
     *
     * <p>Empty when the selector names no owner, which is the usual case for a member of the weave's own target:
     * {@code charge(int)} and {@code #charge(int)} both leave it empty. A source-form owner arrives as a
     * {@link TypePattern.Named} and a descriptor-form owner as a {@link TypePattern.Exact}, and in
     * {@code de.splatgames.aether.weaver.engine.inject.point.Targets}, the matcher behind an {@code @At} target,
     * the two are matched by different rules.
     *
     * @return the declaring class, or {@link Optional#empty()} when the selector constrains none
     */
    @Contract(pure = true)
    @NotNull
    public Optional<TypePattern> owner() {
        return Optional.ofNullable(this.owner);
    }

    /**
     * Returns the method name this selector names.
     *
     * <p>Either a name, one of {@link #CONSTRUCTOR_NAME} and {@link #STATIC_INITIALISER_NAME}, or the single
     * character {@code *} standing for every name. Never blank, and never a partial wildcard: the source grammar
     * rejects {@code get*} as {@code AW1015}.
     *
     * @return the method name or wildcard, never blank
     */
    @Contract(pure = true)
    @NotNull
    public String name() {
        return this.name;
    }

    /**
     * Returns the parameter types this selector constrains.
     *
     * <p>Empty when the selector carried no parameter list, which matches every signature. Present and empty when
     * it carried {@code ()}, which matches only a method taking nothing. The list is unmodifiable.
     *
     * @return the parameter types in declaration order, or {@link Optional#empty()} when the selector constrains no
     *         signature
     */
    @Contract(pure = true)
    @NotNull
    public Optional<List<TypePattern>> parameters() {
        return Optional.ofNullable(this.parameters);
    }

    /**
     * Returns the return type this selector constrains.
     *
     * <p>Empty when the selector carried no {@code :type} suffix. Naming it is how two overloads that differ only
     * in their return type are told apart, which the Java language forbids in source but a class file permits.
     *
     * @return the return type, or {@link Optional#empty()} when the selector constrains none
     */
    @Contract(pure = true)
    @NotNull
    public Optional<TypePattern> returnType() {
        return Optional.ofNullable(this.returnType);
    }

    /**
     * Reports whether this selector names a constructor or the static initialiser.
     *
     * <p>Decided by the name alone, so it holds for {@code <init>} written without a parameter list as well.
     *
     * @return whether the name is {@link #CONSTRUCTOR_NAME} or {@link #STATIC_INITIALISER_NAME}
     */
    @Contract(pure = true)
    public boolean isInitialiser() {
        return CONSTRUCTOR_NAME.equals(this.name) || STATIC_INITIALISER_NAME.equals(this.name);
    }

    /**
     * {@inheritDoc}
     *
     * @return the form this selector was parsed from, or {@link Form#DESCRIPTOR} when it was built from a
     *         {@link MethodTypeDesc}
     */
    @Override
    public Form form() {
        return this.form;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Requires all four parts: an owner that is resolved, a name that is not the wildcard, a parameter list
     * whose every entry is resolved, and a resolved return type. A selector parsed from the source form never
     * satisfies the first, because an owner written there is a {@link TypePattern.Named}; the descriptor form and
     * {@link MemberSelector#ofDescriptor(ClassDesc, String, MethodTypeDesc)} satisfy all four as soon as an owner
     * is given.
     *
     * @return whether this selector names exactly one method
     */
    @Override
    public boolean isFullyQualified() {
        return this.owner != null && this.owner.isResolved()
                && !"*".equals(this.name)
                && this.parameters != null && this.parameters.stream().allMatch(TypePattern::isResolved)
                && this.returnType != null && this.returnType.isResolved();
    }

    /**
     * {@inheritDoc}
     *
     * @return the descriptor form when {@link #isFullyQualified()} holds, and {@link Optional#empty()} otherwise
     */
    @Override
    public Optional<String> canonical() {
        return isFullyQualified() ? Optional.of(render(Form.DESCRIPTOR)) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @param form the form to render in
     * @return the rendered selector; the source rendering when {@code form} is {@link Form#DESCRIPTOR} and this
     *         selector has no descriptor form
     * @throws NullPointerException if {@code form} is {@code null}
     */
    @Override
    public String render(@NotNull final Form form) {
        Objects.requireNonNull(form, "form");
        return form == Form.DESCRIPTOR ? renderDescriptor() : renderSource();
    }

    /**
     * Renders the Java-like form.
     *
     * <p>Always possible, because every part renders through {@link TypePattern#renderSource()}, which answers for
     * a wildcard and an unresolved name alike. An absent parameter list and an absent return type are omitted, so
     * the text says exactly as much as the selector constrains.
     *
     * @return the source rendering, never blank
     */
    private String renderSource() {
        final StringBuilder sb = new StringBuilder(48);
        if (this.owner != null) {
            sb.append(this.owner.renderSource()).append('.');
        }
        sb.append(this.name);
        if (this.parameters != null) {
            final StringJoiner joiner = new StringJoiner(", ", "(", ")");
            this.parameters.forEach(p -> joiner.add(p.renderSource()));
            sb.append(joiner);
        }
        if (this.returnType != null) {
            sb.append(':').append(this.returnType.renderSource());
        }
        return sb.toString();
    }

    /**
     * Renders the descriptor form, falling back to the source form when there is none.
     *
     * <p>The fallback is deliberate: an approximate text carrying the {@value MemberSelector#DESCRIPTOR_PREFIX}
     * prefix would be read downstream as an exact name, so {@link #renderSource()} is used instead. For a method
     * selector that source rendering can never itself begin with the prefix, since a method name is always
     * followed immediately by a parameter list or by nothing, never by a bare colon -- unlike a
     * {@link FieldSelector}, whose {@code owner.name:type} shape lets a field literally named {@code desc} render
     * as text starting with {@value MemberSelector#DESCRIPTOR_PREFIX}.
     *
     * @return the descriptor rendering, or the source rendering when this selector has no descriptor form
     */
    private String renderDescriptor() {
        final String descriptor = descriptorForm();
        return descriptor == null ? renderSource() : descriptor;
    }

    /**
     * Builds the descriptor rendering, or reports that there is none.
     *
     * <p>Every part has to be exact for the result to name one method: a wildcard in the name, an absent parameter
     * list, an absent return type, an owner that is not a {@link TypePattern.Exact}, or any parameter or return
     * type that is not one, all yield {@code null}.
     *
     * @return the descriptor form including its prefix, or {@code null} when this selector has none
     */
    @Nullable
    private String descriptorForm() {
        if (this.name.indexOf('*') >= 0 || this.parameters == null || this.returnType == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(48).append(DESCRIPTOR_PREFIX);
        if (this.owner != null) {
            if (!(this.owner instanceof TypePattern.Exact(ClassDesc type))) {
                return null;
            }
            sb.append(SelectorRendering.internalName(type)).append('.');
        }
        sb.append(this.name).append('(');
        for (final TypePattern parameter : this.parameters) {
            final String descriptor = SelectorRendering.descriptorOrNull(parameter);
            if (descriptor == null) {
                return null;
            }
            sb.append(descriptor);
        }
        final String returned = SelectorRendering.descriptorOrNull(this.returnType);
        return returned == null ? null : sb.append(')').append(returned).toString();
    }

    /**
     * Compares owner, name, parameters and return type.
     *
     * <p>The {@linkplain #form() form} is not compared, so the same method written in the two spellings gives equal
     * selectors whenever both spellings resolve to the same types.
     *
     * @param o the object to compare with
     * @return whether {@code o} is a method selector constraining the same four parts
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodSelector other)) {
            return false;
        }
        return this.name.equals(other.name)
                && Objects.equals(this.owner, other.owner)
                && Objects.equals(this.parameters, other.parameters)
                && Objects.equals(this.returnType, other.returnType);
    }

    /**
     * Returns a hash code over the same four parts {@link #equals(Object)} compares.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.owner, this.name, this.parameters, this.returnType);
    }

    /**
     * Returns this selector in the form it was written in.
     *
     * <p>Reading back a selector as it arrived is what makes a diagnostic quotable against the annotation the
     * author wrote, so the form is preserved rather than normalised here.
     *
     * @return the rendering in {@link #form()}
     */
    @Override
    public String toString() {
        return render(this.form);
    }
}
