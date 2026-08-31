package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.FieldSelector;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.TypePattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether a declaration's target names a particular member, type or instruction.
 *
 * <p>Two matching schemes live here, and which one runs is decided by whether the target was
 * parsed. A {@link PointSpec} carrying a {@link MemberSelector} is matched structurally, component
 * by component; one carrying only text is matched by the text rules in {@code matchesText}. Both
 * have to work, because a target may be left unparsed deliberately and because a point may be given
 * a selector of the wrong kind — a {@link MethodSelector} where a field access is being matched
 * fails to match rather than throwing.
 *
 * <p>A target that is absent matches everything. That is the answer for a point whose target
 * requirement is optional and which was written without one; a point that must have a target never
 * gets here, because {@code PointResolver} refuses it with {@code AW1043} first.
 *
 * <h2>How a name is compared</h2>
 *
 * <p>An owner is compared leniently and a member name exactly, apart from the wildcard. An owner
 * written as a simple name matches any package, which is what lets {@code Gateway.send} match
 * {@code com.acme.Gateway.send}; an owner written in descriptor form is compared as a descriptor
 * and matches nothing else.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Targets {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private Targets() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports whether a declaration's target names the given invocation.
     *
     * <p>A selector that is not a {@link MethodSelector} matches nothing here, which is how a
     * target parsed for a different kind of member fails quietly instead of throwing.
     *
     * @param spec          the declaration's point; must not be {@code null}
     * @param memberName    the invoked method's name; must not be {@code null}
     * @param ownerInternal the invoked method's owner as an internal name, or a descriptor for an
     *                      array owner; must not be {@code null}
     * @param type          the invoked method's descriptor; must not be {@code null}
     * @param described     the rendering the text form is compared against, owner and name in
     *                      binary form followed by the descriptor; must not be {@code null}
     * @return {@code true} when the target is absent or names this invocation
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    static boolean matchesInvocation(@NotNull final PointSpec spec,
                                     @NotNull final String memberName,
                                     @NotNull final String ownerInternal,
                                     @NotNull final MethodTypeDesc type,
                                     @NotNull final String described) {
        requireArguments(spec, memberName, ownerInternal, described);
        Objects.requireNonNull(type, "type");
        if (!spec.hasTarget()) {
            return true;
        }
        if (!spec.hasSelector()) {
            return matchesText(spec.rawTarget(), memberName, ownerInternal, described);
        }
        return spec.target() instanceof final MethodSelector method
                && matchesMethod(method, memberName, ownerInternal, type);
    }

    /**
     * Reports whether a declaration's target names the given field access.
     *
     * <p>A selector that is not a {@link FieldSelector} matches nothing here. The access kind —
     * read or write, static or not — is not considered; that is filtered separately, from
     * {@code At.access}.
     *
     * @param spec          the declaration's point; must not be {@code null}
     * @param memberName    the field's name; must not be {@code null}
     * @param ownerInternal the field's owner as an internal name; must not be {@code null}
     * @param type          the field's type; must not be {@code null}
     * @param described     the rendering the text form is compared against, owner and name in
     *                      binary form; must not be {@code null}
     * @return {@code true} when the target is absent or names this field
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    static boolean matchesFieldAccess(@NotNull final PointSpec spec,
                                      @NotNull final String memberName,
                                      @NotNull final String ownerInternal,
                                      @NotNull final ClassDesc type,
                                      @NotNull final String described) {
        requireArguments(spec, memberName, ownerInternal, described);
        Objects.requireNonNull(type, "type");
        if (!spec.hasTarget()) {
            return true;
        }
        if (!spec.hasSelector()) {
            return matchesText(spec.rawTarget(), memberName, ownerInternal, described);
        }
        return spec.target() instanceof final FieldSelector field
                && matchesField(field, memberName, ownerInternal, type);
    }

    /**
     * Reports whether a declaration's target names the given type.
     *
     * <p>Compares the raw text and never the parsed selector, whether or not one is present: the
     * target of a point that matches a type is a type name, and a type name parsed as a member
     * selector would have to be taken apart again to be compared. A simple name matches by suffix,
     * so {@code StringBuilder} matches {@code java.lang.StringBuilder}, and a suffix that is not
     * preceded by a dot does not match — {@code Builder} does not match {@code StringBuilder}.
     *
     * @param spec       the declaration's point; must not be {@code null}
     * @param binaryName the type's binary name; must not be {@code null}
     * @return {@code true} when the target is absent, equals the name, or is a dot-separated suffix
     *         of it
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    static boolean matchesType(@NotNull final PointSpec spec, @NotNull final String binaryName) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(binaryName, "binaryName");

        if (!spec.hasTarget()) {
            return true;
        }
        final String target = spec.rawTarget();
        return binaryName.equals(target)
                || binaryName.endsWith('.' + target);
    }

    /**
     * Rejects a null argument common to the member-matching entry points.
     *
     * @param spec          the declaration's point
     * @param memberName    the member's name
     * @param ownerInternal the owner's internal name
     * @param described     the rendering for the text form
     * @throws NullPointerException if any argument is {@code null}
     */
    private static void requireArguments(@NotNull final PointSpec spec,
                                         @NotNull final String memberName,
                                         @NotNull final String ownerInternal,
                                         @NotNull final String described) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(ownerInternal, "ownerInternal");
        Objects.requireNonNull(described, "described");
    }

    /**
     * Reports whether a method selector names a method with the given name, owner and descriptor.
     *
     * <p>The same comparison the built-in points use for an invocation, exposed so that the stage
     * that picks a declaration's target method can use one implementation rather than a second one
     * that drifts. There the owner is the class being woven, so an owner clause in the selector is
     * a check that the declaration names the class it is being applied to.
     *
     * @param selector      the selector; must not be {@code null}
     * @param memberName    the candidate method's name; must not be {@code null}
     * @param ownerInternal the candidate method's owner as an internal name; must not be
     *                      {@code null}
     * @param type          the candidate method's descriptor; must not be {@code null}
     * @return {@code true} when the selector names this method
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    public static boolean selects(@NotNull final MethodSelector selector,
                                  @NotNull final String memberName,
                                  @NotNull final String ownerInternal,
                                  @NotNull final MethodTypeDesc type) {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(ownerInternal, "ownerInternal");
        Objects.requireNonNull(type, "type");
        return matchesMethod(selector, memberName, ownerInternal, type);
    }

    /**
     * Reports whether a method selector matches a method, component by component.
     *
     * <p>An omitted parameter list and an empty one are different, and the difference is the
     * feature: {@code send} matches any signature while {@code send()} matches the one that takes
     * nothing. An omitted return type matches any.
     *
     * @param selector      the selector; must not be {@code null}
     * @param memberName    the candidate's name; must not be {@code null}
     * @param ownerInternal the candidate's owner as an internal name; must not be {@code null}
     * @param type          the candidate's descriptor; must not be {@code null}
     * @return {@code true} when every clause the selector states matches
     */
    @Contract(pure = true)
    private static boolean matchesMethod(@NotNull final MethodSelector selector,
                                         @NotNull final String memberName,
                                         @NotNull final String ownerInternal,
                                         @NotNull final MethodTypeDesc type) {
        if (!matchesName(selector.name(), memberName)
                || !matchesOwner(selector.owner().orElse(null), ownerInternal)) {
            return false;
        }
        final List<TypePattern> parameters = selector.parameters().orElse(null);
        // Absent and empty are different, and the difference is the feature: `send` matches any
        // signature, `send()` matches the one that takes nothing. A selector that collapsed the two
        // would give a user no way to name the no-argument overload.
        if (parameters != null) {
            if (parameters.size() != type.parameterCount()) {
                return false;
            }
            for (int index = 0; index < parameters.size(); index++) {
                if (!matchesTypePattern(parameters.get(index), type.parameterType(index))) {
                    return false;
                }
            }
        }
        return selector.returnType()
                .map(wanted -> matchesTypePattern(wanted, type.returnType()))
                .orElse(true);
    }

    /**
     * Reports whether a field selector matches a field, component by component.
     *
     * <p>An omitted type clause matches any type, the way an omitted return type does for a method.
     *
     * @param selector      the selector; must not be {@code null}
     * @param memberName    the candidate's name; must not be {@code null}
     * @param ownerInternal the candidate's owner as an internal name; must not be {@code null}
     * @param type          the candidate's type; must not be {@code null}
     * @return {@code true} when every clause the selector states matches
     */
    @Contract(pure = true)
    private static boolean matchesField(@NotNull final FieldSelector selector,
                                        @NotNull final String memberName,
                                        @NotNull final String ownerInternal,
                                        @NotNull final ClassDesc type) {
        return matchesName(selector.name(), memberName)
                && matchesOwner(selector.owner().orElse(null), ownerInternal)
                && selector.type()
                        .map(wanted -> matchesTypePattern(wanted, type))
                        .orElse(true);
    }

    /**
     * Reports whether a declared member name matches an actual one.
     *
     * <p>{@code *} matches every name and is the only pattern; there is no partial wildcard. It is
     * treated as a name like any other and not distinguished afterwards, which is why a wildcard
     * target-method selector matching several methods is reported as {@code AW1021} here, while the
     * annotation processor reports the wildcard-specific {@code AW1022} for the same declaration.
     *
     * @param declared the name the selector wrote; must not be {@code null}
     * @param actual   the candidate's name; must not be {@code null}
     * @return {@code true} for the wildcard or an exact match
     */
    @Contract(pure = true)
    private static boolean matchesName(@NotNull final String declared,
                                       @NotNull final String actual) {
        return "*".equals(declared) || declared.equals(actual);
    }

    /**
     * Reports whether a declared owner matches the owner of a candidate.
     *
     * <p>The three patterns are three different comparisons. An absent clause and
     * {@link TypePattern.Any} match anything. {@link TypePattern.Exact} is compared as a
     * descriptor, not as a name, because an owner in a class file is an internal name for a class
     * and a full descriptor for an array — {@code [Ljava/lang/String;} is a legal owner, of
     * {@code clone()} — and building the descriptor is the one comparison that is right for both.
     * {@link TypePattern.Named} is compared by suffix, so a simple name matches any package.
     *
     * @param declared      the owner clause, or {@code null} when the selector states none
     * @param ownerInternal the candidate's owner as an internal name; must not be {@code null}
     * @return {@code true} when the clause is absent or matches
     */
    @Contract(pure = true)
    private static boolean matchesOwner(@Nullable final TypePattern declared,
                                        @NotNull final String ownerInternal) {
        return switch (declared) {
            case null -> true;
            case TypePattern.Any ignored -> true;
            // Compared as a descriptor rather than as a name. An owner in a class file is an
            // internal name for a class and a full descriptor for an array — `[Ljava/lang/String;`
            // is a legal owner, of `clone()` — so building the descriptor is the one comparison
            // that is right for both.
            case TypePattern.Exact(final ClassDesc type) ->
                    type.descriptorString().equals(descriptorOf(ownerInternal));
            case TypePattern.Named named -> matchesOwnerText(named.renderSource(), ownerInternal);
        };
    }

    /**
     * Reports whether a declared type pattern matches an actual type.
     *
     * <p>Used for a parameter, a return type and a field type, where — unlike an owner — the actual
     * type is already a {@link ClassDesc}. {@link TypePattern.Exact} is therefore descriptor
     * equality, and {@link TypePattern.Named} renders both sides through the API's own pattern so
     * that a simple name here means what it means everywhere else a selector is printed.
     *
     * @param declared the pattern the selector wrote; must not be {@code null}
     * @param actual   the candidate's type; must not be {@code null}
     * @return {@code true} when the pattern matches
     */
    @Contract(pure = true)
    private static boolean matchesTypePattern(@NotNull final TypePattern declared,
                                              @NotNull final ClassDesc actual) {
        return switch (declared) {
            case TypePattern.Any ignored -> true;
            case TypePattern.Exact(final ClassDesc type) -> type.equals(actual);
            // Rendered through the API's own pattern, so a simple name here means exactly what it
            // means everywhere else a selector is printed.
            case TypePattern.Named named ->
                    matchesSourceName(named.renderSource(), TypePattern.of(actual).renderSource());
        };
    }

    /**
     * Reports whether an unparsed target text names a member.
     *
     * <p>Four forms, tried in this order.
     *
     * <ul>
     *   <li>{@code #name} — the member name alone, matching any owner and any signature.
     *   <li>The full rendering, compared literally, which is how a descriptor-carrying target
     *       matches.
     *   <li>A text with no dot — the member name, matching any owner.
     *   <li>{@code owner.name} — the part after the last dot is the member name and the part before
     *       it is matched as an owner by suffix.
     * </ul>
     *
     * @param target        the target as written; must not be {@code null}
     * @param memberName    the candidate's name; must not be {@code null}
     * @param ownerInternal the candidate's owner as an internal name; must not be {@code null}
     * @param described     the candidate's full rendering; must not be {@code null}
     * @return {@code true} when one of the four forms matches
     */
    @Contract(pure = true)
    private static boolean matchesText(@NotNull final String target,
                                       @NotNull final String memberName,
                                       @NotNull final String ownerInternal,
                                       @NotNull final String described) {
        if (target.startsWith("#")) {
            return memberName.equals(target.substring(1));
        }
        if (described.equals(target)) {
            return true;
        }
        final int lastDot = target.lastIndexOf('.');
        if (lastDot < 0) {
            return memberName.equals(target);
        }
        return memberName.equals(target.substring(lastDot + 1))
                && matchesOwnerText(target.substring(0, lastDot), ownerInternal);
    }

    /**
     * Reports whether a declared owner name matches an internal name.
     *
     * @param declared      the owner as the selector wrote it, in source form; must not be
     *                      {@code null}
     * @param ownerInternal the candidate's owner as an internal name; must not be {@code null}
     * @return {@code true} when the names match exactly or the declared one is a dot-separated
     *         suffix
     */
    @Contract(pure = true)
    private static boolean matchesOwnerText(@NotNull final String declared,
                                            @NotNull final String ownerInternal) {
        return matchesSourceName(declared, ownerInternal.replace('/', '.'));
    }

    /**
     * Reports whether one source-form name matches another, allowing a shortened prefix.
     *
     * <p>The dot in the suffix test is what keeps {@code Builder} from matching
     * {@code StringBuilder}, while {@code StringBuilder} still matches the fully qualified name. A
     * nested type appears with a {@code $} rather than a dot, so its simple name alone is not a
     * suffix of it.
     *
     * @param declared the name the selector wrote; must not be {@code null}
     * @param actual   the candidate's name in source form; must not be {@code null}
     * @return {@code true} when the names are equal or the declared one is a dot-separated suffix
     */
    @Contract(pure = true)
    private static boolean matchesSourceName(@NotNull final String declared,
                                             @NotNull final String actual) {
        return actual.equals(declared) || actual.endsWith('.' + declared);
    }

    /**
     * Returns the descriptor for an owner as a class file writes it.
     *
     * <p>An array owner is already a descriptor and is left alone; anything else is a bare internal
     * name and is wrapped.
     *
     * @param ownerInternal the owner as it appears in the class file; must not be {@code null}
     * @return the owner as a descriptor
     */
    @Contract(pure = true)
    @NotNull
    private static String descriptorOf(@NotNull final String ownerInternal) {
        return ownerInternal.startsWith("[") ? ownerInternal : 'L' + ownerInternal + ';';
    }

    /**
     * Reports whether a selector narrows on a signature.
     *
     * <p>Asked when nothing matched, to decide whether the diagnostic should say that a member of
     * the same name with different parameters was skipped. Only a {@link MethodSelector} with a
     * parameter list does; a return type alone does not count, and a field selector never does.
     *
     * @param selector the selector to examine; must not be {@code null}
     * @return {@code true} when the selector states a parameter list
     * @throws NullPointerException if {@code selector} is {@code null}
     */
    @Contract(pure = true)
    static boolean constrainsSignature(@NotNull final MemberSelector selector) {
        return Objects.requireNonNull(selector, "selector") instanceof MethodSelector method
                && method.parameters().isPresent();
    }
}
