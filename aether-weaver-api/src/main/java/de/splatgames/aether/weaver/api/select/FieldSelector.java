package de.splatgames.aether.weaver.api.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.util.Objects;
import java.util.Optional;

/**
 * A selector that names a field.
 *
 * <p>Produced by {@link MemberSelector#parse(String)} for a source-form selector carrying a {@code :type} suffix
 * without a parameter list, and for a {@value MemberSelector#DESCRIPTOR_PREFIX} selector whose body contains no
 * opening parenthesis. Produced as well by {@link MemberSelector#parse(String, MemberKind)} for a bare name when
 * {@link MemberKind#FIELD} was asked for. {@link MemberSelector#ofFieldDescriptor(ClassDesc, String, ClassDesc)}
 * builds one directly.
 *
 * <p>A bare name is the one shape that needs the hint. {@code MemberSelector.parse("ledger")} is a
 * {@link MethodSelector} of any signature, and {@code MemberSelector.parse("ledger", MemberKind.FIELD)} is a field
 * selector constraining no type. Every other shape decides itself: {@code ledger:com.acme.Ledger} is a field
 * whatever hint is passed.
 *
 * <h2>What each part constrains</h2>
 *
 * <ul>
 *   <li><b>{@link #owner()}</b> -- the declaring class, absent when the selector names none. A source-form owner is
 *       a {@link TypePattern.Named} and a descriptor-form owner a {@link TypePattern.Exact}, and in
 *       {@code de.splatgames.aether.weaver.engine.inject.point.Targets}, the matcher behind an {@code @At} target,
 *       only the second is compared exactly.
 *   <li><b>{@link #name()}</b> -- the field name, or {@code *} for every name. Never blank. A partial wildcard such
 *       as {@code count*} is not a name and is reported as {@code AW1015}.
 *   <li><b>{@link #type()}</b> -- the field's type, absent when the selector constrains none. Unlike a method's
 *       parameter list there is no empty case: a field either constrains a type or does not.
 * </ul>
 *
 * <h2>Rendering</h2>
 *
 * <p>The source rendering is {@code owner.name:type}, with each part omitted when absent. The descriptor rendering
 * requires a type that is resolved, a name without a wildcard, and -- when an owner is present -- an owner that is
 * resolved; failing any of those, {@link #render(MemberSelector.Form)} answers with the source rendering instead.
 * That rendering is not guaranteed to omit the {@value MemberSelector#DESCRIPTOR_PREFIX} prefix: a field named
 * {@code desc} and written without an owner renders as {@code desc:type}, text that begins with the prefix and is
 * read back as a
 * malformed descriptor rather than as that field.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * FieldSelector any = (FieldSelector) MemberSelector.parse("ledger", MemberKind.FIELD);
 * assert any.type().isEmpty();                       // any type
 *
 * FieldSelector typed = (FieldSelector) MemberSelector.parse("state:int");
 * assert typed.canonical().isEmpty();                // no owner, so not fully qualified
 *
 * FieldSelector pinned = MemberSelector.ofFieldDescriptor(
 *         ClassDesc.of("com.acme.Session"), "state", ConstantDescs.CD_int);
 * assert pinned.canonical().orElseThrow().equals("desc:com/acme/Session.state:I");
 * }</pre>
 *
 * <p>Immutable and safe to share between threads.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MemberSelector
 * @see MethodSelector
 */
public final class FieldSelector implements MemberSelector {

    /** The declaring class, or {@code null} when the selector names none. */
    private final @Nullable TypePattern owner;

    /** The field name or the {@code *} wildcard; never blank. */
    private final String name;

    /** The field's type, or {@code null} when the selector constrains none. */
    private final @Nullable TypePattern type;

    /** The spelling this selector was written in, which decides only {@link #toString()}. */
    private final Form form;

    /**
     * Builds a field selector from parts that have already been parsed or resolved.
     *
     * @param owner the declaring class, or {@code null} to constrain none
     * @param name  the field name or a {@code *} wildcard; must not be blank
     * @param type  the field's type, or {@code null} to constrain none
     * @param form  the spelling this selector was written in
     * @throws NullPointerException     if {@code name} or {@code form} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    FieldSelector(final @Nullable TypePattern owner,
                  @NotNull final String name,
                  final @Nullable TypePattern type,
                  @NotNull final Form form) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.form = Objects.requireNonNull(form, "form");
    }

    /**
     * Builds a field selector whose owner and type are already resolved.
     *
     * <p>Both patterns come from a {@link ClassDesc}, so both are {@link TypePattern.Exact} and the result is
     * {@linkplain #isFullyQualified() fully qualified} whenever an owner is given. A type is required here, unlike
     * in the parsed source form where it may be omitted.
     *
     * @param owner the declaring class, or {@code null} to constrain none
     * @param name  the field name; must not be blank
     * @param type  the field's type
     * @return a field selector in {@link Form#DESCRIPTOR}
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    static FieldSelector ofDescriptor(final @Nullable ClassDesc owner,
                                      @NotNull final String name,
                                      @NotNull final ClassDesc type) {
        Objects.requireNonNull(type, "type");
        return new FieldSelector(
                owner == null ? null : TypePattern.of(owner),
                name,
                TypePattern.of(type),
                Form.DESCRIPTOR);
    }

    /**
     * Returns the declaring class this selector names.
     *
     * <p>Empty when the selector names no owner, which is the usual case for a field of the weave's own target:
     * {@code state:int} and {@code #state:int} both leave it empty.
     *
     * @return the declaring class, or {@link Optional#empty()} when the selector constrains none
     */
    @Contract(pure = true)
    @NotNull
    public Optional<TypePattern> owner() {
        return Optional.ofNullable(this.owner);
    }

    /**
     * Returns the field name this selector names.
     *
     * <p>Either a name or the single character {@code *} standing for every name. Never blank.
     *
     * @return the field name or wildcard, never blank
     */
    @Contract(pure = true)
    @NotNull
    public String name() {
        return this.name;
    }

    /**
     * Returns the type this selector constrains.
     *
     * <p>Empty when the selector carried no {@code :type} suffix, which matches a field of any type. A
     * {@link TypePattern.Any} is present rather than empty: {@code amount:*} states that a type was written and
     * that every type satisfies it.
     *
     * @return the field's type, or {@link Optional#empty()} when the selector constrains none
     */
    @Contract(pure = true)
    @NotNull
    public Optional<TypePattern> type() {
        return Optional.ofNullable(this.type);
    }

    /**
     * {@inheritDoc}
     *
     * @return the form this selector was parsed from, or {@link Form#DESCRIPTOR} when it was built from a
     *         {@link ClassDesc}
     */
    @Override
    public Form form() {
        return this.form;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Requires an owner that is resolved, a name that is not the wildcard, and a resolved type. A selector
     * parsed from the source form never satisfies the first, because an owner written there is a
     * {@link TypePattern.Named}; the descriptor form and
     * {@link MemberSelector#ofFieldDescriptor(ClassDesc, String, ClassDesc)} satisfy all three as soon as an owner
     * is given.
     *
     * @return whether this selector names exactly one field
     */
    @Override
    public boolean isFullyQualified() {
        return this.owner != null && this.owner.isResolved()
                && !"*".equals(this.name)
                && this.type != null && this.type.isResolved();
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
     * <p>The source rendering joins the parts as {@code owner.name:type} and omits whatever is absent, so the text
     * constrains exactly what the selector does. The descriptor rendering is attempted first when one is asked for,
     * and the source rendering is the answer when there is none.
     *
     * @param form the form to render in
     * @return the rendered selector; the source rendering when {@code form} is {@link Form#DESCRIPTOR} and this
     *         selector has no descriptor form
     * @throws NullPointerException if {@code form} is {@code null}
     */
    @Override
    public String render(@NotNull final Form form) {
        Objects.requireNonNull(form, "form");
        final StringBuilder sb = new StringBuilder(40);
        if (form == Form.DESCRIPTOR) {
            // Or the source form, when this selector has no descriptor form. See descriptorForm.
            final String descriptor = descriptorForm();
            if (descriptor != null) {
                return descriptor;
            }
        }
        if (this.owner != null) {
            sb.append(this.owner.renderSource()).append('.');
        }
        sb.append(this.name);
        if (this.type != null) {
            sb.append(':').append(this.type.renderSource());
        }
        return sb.toString();
    }

    /**
     * Builds the descriptor rendering, or reports that there is none.
     *
     * <p>A wildcard in the name, an absent type, an owner that is not a {@link TypePattern.Exact}, or a type that
     * is not one, all yield {@code null}: none of them names a single field, and a text carrying the
     * {@value MemberSelector#DESCRIPTOR_PREFIX} prefix is read downstream as one that does.
     *
     * @return the descriptor form including its prefix, or {@code null} when this selector has none
     */
    @Nullable
    private String descriptorForm() {
        if (this.name.indexOf('*') >= 0 || this.type == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(40).append(DESCRIPTOR_PREFIX);
        if (this.owner != null) {
            if (!(this.owner instanceof TypePattern.Exact(ClassDesc type))) {
                return null;
            }
            sb.append(SelectorRendering.internalName(type)).append('.');
        }
        final String descriptor = SelectorRendering.descriptorOrNull(this.type);
        return descriptor == null ? null : sb.append(this.name).append(':').append(descriptor).toString();
    }

    /**
     * Compares owner, name and type.
     *
     * <p>The {@linkplain #form() form} is not compared, so the same field written in the two spellings gives equal
     * selectors whenever both spellings resolve to the same type.
     *
     * @param o the object to compare with
     * @return whether {@code o} is a field selector constraining the same three parts
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FieldSelector other)) {
            return false;
        }
        return this.name.equals(other.name)
                && Objects.equals(this.owner, other.owner)
                && Objects.equals(this.type, other.type);
    }

    /**
     * Returns a hash code over the same three parts {@link #equals(Object)} compares.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.owner, this.name, this.type);
    }

    /**
     * Returns this selector in the form it was written in.
     *
     * @return the rendering in {@link #form()}
     */
    @Override
    public String toString() {
        return render(this.form);
    }
}
