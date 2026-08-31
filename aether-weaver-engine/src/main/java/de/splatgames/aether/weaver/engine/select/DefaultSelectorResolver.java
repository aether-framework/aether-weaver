package de.splatgames.aether.weaver.engine.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import de.splatgames.aether.weaver.api.select.ConstantSelector;
import de.splatgames.aether.weaver.api.select.FieldSelector;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.TypePattern;
import de.splatgames.aether.weaver.api.spi.SelectorResolver;

import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;

/**
 * Turns a member selector into the members of one class that it names.
 *
 * <p>{@link SelectorResolver} declares no resolution method, so the resolution entry points are
 * declared here and are reached through this type rather than through the interface. What the
 * interface contributes is a name and a priority, as {@link #name()} and {@link #priority()} below
 * implement them for this resolver.
 *
 * <p>Matching is exact-arity and by resolved type identity, which is not how an {@code @At} target
 * is matched: that goes through
 * {@link de.splatgames.aether.weaver.engine.inject.point.Targets}, which compares an unresolved
 * name by rendered source name. A selector can match under one and not the other.
 *
 * <p>Stateless, so one instance serves every thread.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class DefaultSelectorResolver implements SelectorResolver {

    /** Creates a resolver. It holds no state, so one instance can be shared by every thread. */
    public DefaultSelectorResolver() {
        // No state: every method resolves against the context it is handed.
    }

    /**
     * Returns every member of the context's class that the selector names.
     *
     * <p>A part the selector omitted is not a constraint, so a name alone matches every overload of
     * that name and the caller is the one that has to decide whether several matches are an error.
     * A constant selector resolves to nothing at all: a constant is an instruction operand rather
     * than a declared member, so no class member can answer for it.
     *
     * <p>An owner written into the selector is not a constraint either, and is never read: the
     * class searched is the one the context carries, so a selector naming some other class still
     * matches this one's members.
     *
     * @param selector the selector to resolve; must not be {@code null}
     * @param context  the class and imports to resolve against; must not be {@code null}
     * @return the matching members, in the order the class file declares them; empty when none
     *         match
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<MemberRef> resolveAll(@NotNull final MemberSelector selector,
                                      @NotNull final ResolutionContext context) {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(context, "context");

        return switch (selector) {
            case MethodSelector method -> methods(context).stream()
                    .filter(candidate -> matches(method, candidate, context))
                    .toList();
            case FieldSelector field -> fields(context).stream()
                    .filter(candidate -> matches(field, candidate, context))
                    .toList();
            case ConstantSelector ignored -> List.of();
        };
    }

    /**
     * Returns every method the context's class declares, as references.
     *
     * <p>Only the class's own methods; nothing inherited, since a class file lists no member it did
     * not declare. Constructors and the static initialiser are among them, under the names the
     * class file gives them.
     *
     * @param context the class to list; must not be {@code null}
     * @return the methods, in the order the class file declares them
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<MemberRef> methods(@NotNull final ResolutionContext context) {
        Objects.requireNonNull(context, "context");
        final ClassModel model = context.target();
        final ClassDesc owner = model.thisClass().asSymbol();
        final boolean isInterface = model.flags().flags()
                .contains(java.lang.reflect.AccessFlag.INTERFACE);
        return model.methods().stream()
                .map(method -> MemberRef.ofMethod(owner,
                        method.methodName().stringValue(),
                        method.methodTypeSymbol(),
                        method.flags().flags(),
                        isInterface))
                .toList();
    }

    /**
     * Returns every field the context's class declares, as references.
     *
     * <p>Only the class's own fields; nothing inherited.
     *
     * @param context the class to list; must not be {@code null}
     * @return the fields, in the order the class file declares them
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<MemberRef> fields(@NotNull final ResolutionContext context) {
        Objects.requireNonNull(context, "context");
        final ClassModel model = context.target();
        final ClassDesc owner = model.thisClass().asSymbol();
        final boolean isInterface = model.flags().flags()
                .contains(java.lang.reflect.AccessFlag.INTERFACE);
        return model.fields().stream()
                .map(field -> MemberRef.ofField(owner,
                        field.fieldName().stringValue(),
                        ClassDesc.ofDescriptor(field.fieldType().stringValue()),
                        field.flags().flags(),
                        isInterface))
                .toList();
    }

    /**
     * Returns {@link SelectorResolver#DEFAULT_PRIORITY}.
     *
     * @return the default priority
     */
    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * Returns {@code default}, rather than the class's simple name the interface would supply.
     *
     * @return the name this resolver is known by
     */
    @Override
    public String name() {
        return "default";
    }

    // -------------------------------------------------------------------------------------

    /**
     * Returns whether a method matches a method selector.
     *
     * <p>A parameter list that was written matches on exact arity and then position by position, so
     * a prefix of the parameters never matches. A parameter list that was omitted constrains
     * nothing, which is what makes {@code name} alone match every overload while {@code name()}
     * matches only the one taking no arguments.
     *
     * @param selector  the selector; must not be {@code null}
     * @param candidate the method to test; must not be {@code null}
     * @param context   the context that resolves type names; must not be {@code null}
     * @return {@code true} when name, parameters and return type all match
     */
    private static boolean matches(@NotNull final MethodSelector selector,
                                   @NotNull final MemberRef candidate,
                                   @NotNull final ResolutionContext context) {
        if (!nameMatches(selector.name(), candidate.name())) {
            return false;
        }
        final MethodTypeDesc actual = candidate.methodType();

        if (selector.parameters().isPresent()) {
            final List<TypePattern> wanted = selector.parameters().orElseThrow();
            if (wanted.size() != actual.parameterCount()) {
                return false;
            }
            for (int i = 0; i < wanted.size(); i++) {
                if (!typeMatches(wanted.get(i), actual.parameterType(i), context)) {
                    return false;
                }
            }
        }
        return selector.returnType()
                .map(wanted -> typeMatches(wanted, actual.returnType(), context))
                .orElse(true);
    }

    /**
     * Returns whether a field matches a field selector.
     *
     * @param selector  the selector; must not be {@code null}
     * @param candidate the field to test; must not be {@code null}
     * @param context   the context that resolves type names; must not be {@code null}
     * @return {@code true} when the name matches and the type matches or was omitted
     */
    private static boolean matches(@NotNull final FieldSelector selector,
                                   @NotNull final MemberRef candidate,
                                   @NotNull final ResolutionContext context) {
        return nameMatches(selector.name(), candidate.name())
                && selector.type()
                        .map(wanted -> typeMatches(wanted, candidate.fieldType(), context))
                        .orElse(true);
    }

    /**
     * Returns whether a member name matches the name a selector wrote.
     *
     * <p>{@code *} is the only pattern; every other name is compared literally, so there is no
     * prefix, suffix or substring match to fall foul of.
     *
     * @param selectorName the name as written; must not be {@code null}
     * @param actual       the member's name; must not be {@code null}
     * @return {@code true} when the names are equal or the selector wrote {@code *}
     */
    private static boolean nameMatches(@NotNull final String selectorName, @NotNull final String actual) {
        return "*".equals(selectorName) || selectorName.equals(actual);
    }

    /**
     * Returns whether an actual type satisfies a type pattern.
     *
     * <p>Comparison is by descriptor equality after resolution, so it is exact and erased: a type
     * argument written in the selector is gone by the time the two are compared, and no subtype
     * matches a supertype.
     *
     * @param pattern the pattern as written; must not be {@code null}
     * @param actual  the type from the class file; must not be {@code null}
     * @param context the context that resolves the name; must not be {@code null}
     * @return {@code true} for a wildcard, or when the pattern resolves to exactly {@code actual}
     */
    private static boolean typeMatches(@NotNull final TypePattern pattern,
                                       @NotNull final ClassDesc actual,
                                       @NotNull final ResolutionContext context) {
        if (pattern instanceof TypePattern.Any) {
            return true;
        }
        return context.resolve(pattern)
                .map(actual::equals)
                // An unresolvable name cannot match anything. Treating it as a wildcard would
                // silently bind a weave to a member the author never named.
                .orElse(false);
    }
}
