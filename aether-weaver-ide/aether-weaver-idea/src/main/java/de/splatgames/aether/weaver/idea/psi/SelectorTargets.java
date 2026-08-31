package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import de.splatgames.aether.weaver.api.select.FieldSelector;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.TypePattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@code method} selector of an injection to the one method it names.
 *
 * <p>The counterpart of {@link de.splatgames.aether.weaver.idea.psi.PointDeclarations} for the other selector a weave
 * writes: {@code @At(target = ...)} names an operation inside a method, while {@code @Inject(method = ...)} names the
 * method itself, searched among the weave's own targets rather than the whole project.
 *
 * <h2>One answer or none</h2>
 *
 * <p>{@link #exact(PsiClass, String)} answers only where exactly one method matches. Everything built on it — the
 * signature inspection, the parameter fix, the qualify intention, the tool window's bound-or-unbound column — needs
 * a method it can compare against or check, and a list of candidates is not that. Two matches are therefore reported
 * the same as none, and a caller can distinguish them only by asking a wider question.
 *
 * <h2>Matching</h2>
 *
 * <p>A selector with no parameter list names every overload, which is a match here as long as exactly one candidate
 * carries the name. Parameter types are compared through {@code HandlerSignature.erasedNameOf}, so a type argument
 * plays no part, and each is accepted under its canonical name, its simple name or its binary name — the last
 * because a nested type is written {@code Outer$Inner} in every spelling the engine accepts and
 * {@code Outer.Inner} in the only one PSI hands out.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class SelectorTargets {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private SelectorTargets() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the single method a weave's selector names.
     *
     * <p>The candidates are the searched classes' own declarations, read through {@code TargetMembers}: an inherited
     * method is not found, and neither is one this plugin merges into the class.
     *
     * @param weave the weave class whose {@code @Weave} targets bound an unqualified selector; must not be
     *              {@code null}
     * @param text  the selector as written; must not be {@code null}
     * @return the method named, or {@code null} when the text is blank, does not parse, names a field or a constant,
     *         names an initialiser, names an owner that is not a qualified class name, or matches other than exactly
     *         one method
     */
    @Nullable
    public static PsiMethod exact(@NotNull final PsiClass weave, @NotNull final String text) {
        if (text.isBlank()) {
            return null;
        }
        final MemberSelector parsed;
        try {
            parsed = MemberSelector.parse(text);
        } catch (final RuntimeException malformed) {
            // A half-typed selector is the ordinary state of a file being edited. Saying what is
            // wrong with it belongs to SelectorInspection; here it simply names nothing.
            return null;
        }
        if (!(parsed instanceof final MethodSelector selector) || selector.isInitialiser()) {
            return null;
        }

        final List<PsiClass> searched = searchedClasses(weave, selector);
        final List<PsiMethod> matches = new ArrayList<>(2);
        for (final PsiClass owner : searched) {
            for (final PsiMethod candidate : TargetMembers.ownMethodsOf(owner)) {
                if (candidate.isConstructor()
                        || !candidate.getName().equals(selector.name())
                        || !matches(selector, candidate)) {
                    continue;
                }
                matches.add(candidate);
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * Returns the classes a selector's method is looked for in.
     *
     * <p>A selector with no owner is looked for in the weave's own {@code @Weave} targets, which is what an
     * unqualified {@code method = "charge"} means. An owner written as a simple name is refused rather than searched
     * by short name: this method has to produce one class or none, and every class of that name is neither. A
     * qualified owner is resolved over the whole project and its libraries, so a target in a dependency is found.
     *
     * @param weave    the weave class; must not be {@code null}
     * @param selector the parsed selector; must not be {@code null}
     * @return the classes to search, and an empty list for a simple-name owner and for one that resolves to nothing
     */
    @Unmodifiable
    @NotNull
    private static List<PsiClass> searchedClasses(@NotNull final PsiClass weave,
                                                  @NotNull final MethodSelector selector) {
        final TypePattern owner = selector.owner().orElse(null);
        if (owner == null) {
            return WeaveDeclarations.targetsOf(weave);
        }
        final String name = owner.renderSource();
        if (name.indexOf('.') < 0) {
            return List.of();
        }
        final PsiClass resolved = JavaPsiFacade.getInstance(weave.getProject())
                .findClass(name, GlobalSearchScope.allScope(weave.getProject()));
        return resolved == null ? List.of() : List.of(resolved);
    }

    /**
     * Reports whether a selector names a given method.
     *
     * <p>The owner takes no part; only the name and the parameter types are compared. The caller has already decided
     * which class it is asking about — the line marker asks per candidate method of one class.
     *
     * @param text      the selector as written; must not be {@code null}
     * @param candidate the method to test; must not be {@code null}
     * @return {@code true} when the text parses as a method selector, is not an initialiser, and agrees with the
     *         candidate's name and parameter types
     */
    public static boolean namesMethod(@NotNull final String text,
                                      @NotNull final PsiMethod candidate) {
        final MemberSelector parsed;
        try {
            parsed = MemberSelector.parse(text);
        } catch (final RuntimeException malformed) {
            return false;
        }
        return parsed instanceof final MethodSelector selector
                && !selector.isInitialiser()
                && selector.name().equals(candidate.getName())
                && matches(selector, candidate);
    }

    /**
     * Reports whether a field selector names a given field.
     *
     * <p>A selector that writes no type names the field by name alone, which is unambiguous within one class.
     *
     * @param selector  the parsed selector; must not be {@code null}
     * @param candidate the field to test; must not be {@code null}
     * @return {@code true} when the names agree and either the selector writes no type or the written type describes
     *         the field's
     */
    public static boolean namesField(@NotNull final FieldSelector selector,
                                     @NotNull final PsiField candidate) {
        return selector.name().equals(candidate.getName())
                && selector.type()
                        .map(wanted -> describes(wanted, candidate.getType()))
                        .orElse(true);
    }

    /**
     * Reports whether a method selector's parameter list describes a candidate's.
     *
     * <p>Arity is compared before the types, so an overload with a different number of parameters is dropped without
     * resolving any of them.
     *
     * @param selector  the parsed selector; must not be {@code null}
     * @param candidate the method to test; must not be {@code null}
     * @return {@code true} when the selector writes no parameter list, or writes one that describes the candidate's
     *         parameters position by position
     */
    private static boolean matches(@NotNull final MethodSelector selector,
                                   @NotNull final PsiMethod candidate) {
        final List<TypePattern> patterns = selector.parameters().orElse(null);
        if (patterns == null) {
            // A selector without a parameter list names every overload; with exactly one candidate
            // that is unambiguous, and with several this class answers null anyway.
            return true;
        }
        final PsiParameter[] parameters = candidate.getParameterList().getParameters();
        if (patterns.size() != parameters.length) {
            return false;
        }
        for (int i = 0; i < patterns.size(); i++) {
            if (!describes(patterns.get(i), parameters[i].getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether an owner pattern names a given class.
     *
     * <p>Three spellings are accepted, because all three occur in selectors the engine takes: the qualified name,
     * the binary name — which is how a nested class has to be written — and the simple name. A wildcard names every
     * class, including a member with none.
     *
     * @param pattern the owner pattern; must not be {@code null}
     * @param owner   the class to test, or {@code null} when the member has none
     * @return {@code true} when the pattern is a wildcard or names the class in one of the three spellings
     */
    @Contract(pure = true)
    public static boolean namesClass(@NotNull final TypePattern pattern,
                                     @Nullable final PsiClass owner) {
        if (pattern instanceof TypePattern.Any) {
            return true;
        }
        if (owner == null) {
            return false;
        }
        final String named = pattern.renderSource().replace('/', '.');
        return named.equals(owner.getQualifiedName())
                || named.equals(ClassUtil.getJVMClassName(owner))
                || named.equals(owner.getName());
    }

    /**
     * Reports whether a type pattern describes a declared type.
     *
     * <p>The same three spellings {@link #namesClass(TypePattern, PsiClass)} accepts, applied to a parameter or a
     * field type. The binary name is asked for only where the type resolves to a class: a primitive and an
     * unresolved type resolve to none, and the platform's encoder throws rather than answering for {@code null},
     * which would turn a widened comparison into a crash on {@code int}.
     *
     * <p>A wildcard is not honoured in this position: it renders as {@code *}, which equals no type name, so a
     * selector writing {@code charge(*)} matches nothing here, although the grammar defines the wildcard as standing
     * for any one parameter.
     *
     * @param pattern the type pattern; must not be {@code null}
     * @param type    the declared type; must not be {@code null}
     * @return {@code true} when the pattern matches the type's erased canonical name, its simple name or its binary
     *         name, and {@code false} when the type does not resolve
     */
    private static boolean describes(@NotNull final TypePattern pattern,
                                     @NotNull final PsiType type) {
        final String actual = HandlerSignature.erasedNameOf(type);
        if (actual == null) {
            return false;
        }
        final String written = pattern.renderSource();
        // The binary name too: a nested class is Outer$Inner in every spelling the engine
        // accepts, and Outer.Inner in the only one PSI hands out. Guarded, because a primitive and
        // an unresolved type both resolve to no class at all — and ClassUtil.getJVMClassName throws
        // on null rather than answering, which turned a widened comparison into a crash on `int`.
        final PsiClass declared = PsiUtil.resolveClassInClassTypeOnly(type);
        return actual.equals(written) || simpleNameOf(actual).equals(written)
                || declared != null && written.equals(ClassUtil.getJVMClassName(declared));
    }

    /**
     * Returns the last segment of a qualified name.
     *
     * @param qualified the name to shorten; must not be {@code null}
     * @return the text after the last {@code .}, or the whole name when there is none
     */
    @NotNull
    private static String simpleNameOf(@NotNull final String qualified) {
        final int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
