package de.splatgames.aether.weaver.idea.psi;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationParameterList;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.select.FieldSelector;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.TypePattern;
import de.splatgames.aether.weaver.engine.parse.PointTargets;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@code target} of an {@code @At} to the members it names.
 *
 * <p>What turns the string inside {@code @At(value = Point.INVOKE, target = "com.acme.Ledger.commit()")} into
 * something the editor can navigate to, rename with and complete inside. The grammar the string is parsed in is
 * decided by the point, and by the engine's own
 * {@link de.splatgames.aether.weaver.engine.parse.PointTargets#selectorKindFor(String)} rather than by a copy of
 * that rule kept here, so the editor and the build never disagree about what a target is allowed to say.
 *
 * <h2>What each point's target is</h2>
 *
 * <ul>
 *   <li>{@code FIELD} takes a field selector.
 *   <li>{@code INVOKE}, {@code INVOKE_AFTER} and {@code CONSTANT} take a method selector. A target that parses as a
 *       constant selector instead names a value rather than a member, so there is nothing to resolve to and the
 *       methods here answer empty.
 *   <li>{@code NEW} takes a class name as raw text, with no selector grammar involved at all.
 *   <li>Every other point takes no target, and a string written on one resolves to nothing.
 * </ul>
 *
 * <h2>How an owner is searched for</h2>
 *
 * <p>A qualified owner is resolved once, in the literal's own resolve scope. A simple name is looked up in the short
 * names cache and every class of that name is returned, because the engine matches a simple owner by suffix: listing
 * them is not a failure to disambiguate but the same answer the build would give.
 *
 * <p>A malformed target is not a failure here. A file being edited is malformed most of the time, and saying what is
 * wrong with a selector belongs to {@code de.splatgames.aether.weaver.idea.inspection.SelectorInspection}; the
 * methods below simply name nothing.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class PointDeclarations {

    /** The qualified name of {@link de.splatgames.aether.weaver.api.At}. */
    public static final String AT = "de.splatgames.aether.weaver.api.At";

    /** The {@code @At} element holding the member a point names. */
    public static final String TARGET_ATTRIBUTE = "target";

    /** The {@code @At} element holding the point itself. */
    private static final String VALUE_ATTRIBUTE = "value";

    /** The {@code @At} element naming a point no {@code Point} constant declares. */
    private static final String CUSTOM_ATTRIBUTE = "custom";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private PointDeclarations() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the {@code @At} whose {@code target} a literal is.
     *
     * <p>Two fixed steps up the tree and a name comparison, rather than a search for an enclosing annotation: an
     * {@code @At} is written inside {@code @Inject}, so a walk upwards would climb out of the one being examined and
     * report the wrong annotation for a string that is not a target at all.
     *
     * @param literal the literal to place; must not be {@code null}
     * @return the enclosing {@code @At}, or {@code null} when the literal is not directly the value of its
     *         {@code target} element
     */
    @Nullable
    public static PsiAnnotation atOf(@NotNull final PsiLiteralExpression literal) {
        // Two fixed steps up — pair, parameter list, annotation — and a name comparison, rather than
        // a tree walk that would climb out of a nested annotation into whatever encloses it.
        if (!(literal.getParent() instanceof final PsiNameValuePair pair)
                || !TARGET_ATTRIBUTE.equals(pair.getName())
                || !(pair.getParent() instanceof final PsiAnnotationParameterList parameters)
                || !(parameters.getParent() instanceof final PsiAnnotation annotation)) {
            return null;
        }
        return AT.equals(annotation.getQualifiedName()) ? annotation : null;
    }

    /**
     * Returns the point an {@code @At} names.
     *
     * <p>A non-blank {@code custom} wins over {@code value}, which is how a point no {@code Point} constant declares
     * is written. The constant is read by the reference's own name rather than by resolving it, so both
     * {@code Point.INVOKE} and a statically imported {@code INVOKE} are understood.
     *
     * @param at the annotation to read; must not be {@code null}
     * @return the custom point, the named constant, or {@code Point.HEAD} when neither was written — which is the
     *         annotation's own default
     */
    @NotNull
    public static String pointOf(@NotNull final PsiAnnotation at) {
        final String custom = textOf(at.findAttributeValue(CUSTOM_ATTRIBUTE));
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        final String named = constantNameOf(at.findAttributeValue(VALUE_ATTRIBUTE));
        return named == null ? Point.HEAD.name() : named;
    }

    /**
     * Returns the members an {@code @At} target names, for a literal that is one.
     *
     * @param literal the literal to resolve; must not be {@code null}
     * @return the members named, and an empty list when the literal is not an {@code @At} target, is not a string,
     *         is blank, or names nothing that resolves
     */
    @Unmodifiable
    @NotNull
    public static List<PsiElement> membersNamedBy(@NotNull final PsiLiteralExpression literal) {
        final PsiAnnotation at = atOf(literal);
        if (at == null || !(literal.getValue() instanceof final String text) || text.isBlank()) {
            return List.of();
        }
        return membersNamedBy(pointOf(at), text, literal);
    }

    /**
     * Returns the members a target names under a given point.
     *
     * <p>The form a caller uses when the point and the text are known but no literal holds them, such as a target
     * being completed. Several members can be named at once: a simple owner matches every class of that name, and
     * an overload set matched by a selector with no parameter list matches every overload.
     *
     * @param point   the point's name, which need not be one a {@code Point} constant declares; must not be
     *                {@code null}
     * @param text    the target as written; must not be {@code null}
     * @param context the element whose resolve scope and project the search runs in; must not be {@code null}
     * @return the classes for {@code NEW} and the members otherwise, and an empty list when the point takes no
     *         target, the text does not parse, or nothing matches
     */
    @Unmodifiable
    @NotNull
    public static List<PsiElement> membersNamedBy(@NotNull final String point,
                                                  @NotNull final String text,
                                                  @NotNull final PsiElement context) {
        // NEW names a type rather than a member, and its target is raw text: the whole string is the
        // class name, so there is no selector to parse.
        if (Point.NEW.name().equals(point)) {
            return List.copyOf(classesNamed(text, context));
        }
        final MemberKind kind = PointTargets.selectorKindFor(point);
        if (kind == null) {
            return List.of();
        }
        final MemberSelector selector = parsed(text, kind);
        return switch (selector) {
            case final MethodSelector method -> methodsNamedBy(method, text, context);
            case final FieldSelector field -> fieldsNamedBy(field, context);
            // A constant names a value, not a member. There is nothing to navigate to.
            case null, default -> List.of();
        };
    }

    /**
     * Returns the part of a target literal that is the member's own name.
     *
     * <p>The range a reference is built over, so it is also the text a rename rewrites. It is located structurally,
     * by finding where the owner ends, and never by searching the literal for the name: {@code flush(Flusher)}
     * contains its member name twice by substring, and rewriting the wrong occurrence would silently change a
     * parameter type instead. The offset is relative to the literal element and includes its opening quote.
     *
     * @param literal the literal to measure; must not be {@code null}
     * @return the range covering the name, or {@code null} when the literal is not an {@code @At} target, does not
     *         parse, names an initialiser, names {@code *}, or is a point whose target carries no member name
     */
    @Nullable
    public static TextRange nameRangeIn(@NotNull final PsiLiteralExpression literal) {
        final PsiAnnotation at = atOf(literal);
        if (at == null || !(literal.getValue() instanceof final String text) || text.isBlank()) {
            return null;
        }
        final String name = nameWrittenIn(pointOf(at), text);
        if (name == null || name.isEmpty() || "*".equals(name)) {
            return null;
        }
        // Located structurally rather than by searching for the name. `flush(Flusher)` contains
        // its own member name twice by substring, and rewriting the wrong one is a rename that
        // silently changes a parameter type.
        final int start = nameStartIn(text);
        if (start < 0 || !text.startsWith(name, start)) {
            return null;
        }
        // +1 for the opening quote: ranges are relative to the literal element, not to its value.
        return TextRange.from(start + 1, name.length());
    }

    /**
     * Returns the offset in a target at which the member's name begins.
     *
     * <p>Works on the text rather than on the parsed selector, because a parsed selector has lost where it was
     * written. The owner is whatever precedes the last {@code .} or {@code /} before the parameter list or the field
     * type, so both the source and the descriptor spelling are handled, and a prefix — {@code desc:}, {@code src:}
     * or a leading {@code #} — is stepped over first.
     *
     * @param text the target as written; must not be {@code null}
     * @return the offset of the first character of the name, or {@code -1} when the text is nothing but a prefix
     */
    private static int nameStartIn(@NotNull final String text) {
        int from = 0;
        if (text.startsWith(MemberSelector.DESCRIPTOR_PREFIX)) {
            from = MemberSelector.DESCRIPTOR_PREFIX.length();
        } else if (text.startsWith(MemberSelector.SOURCE_PREFIX)) {
            from = MemberSelector.SOURCE_PREFIX.length();
        } else if (text.startsWith("#")) {
            from = 1;
        }
        if (from >= text.length()) {
            return -1;
        }
        int end = text.indexOf('(', from);
        if (end < 0) {
            end = text.indexOf(':', from);
        }
        if (end < 0) {
            end = text.length();
        }
        // Both separators, because a descriptor form writes internal names with slashes.
        final int dot = Math.max(text.lastIndexOf('.', end - 1), text.lastIndexOf('/', end - 1));
        return Math.max(dot + 1, from);
    }

    /**
     * Returns the member name a target carries.
     *
     * <p>For {@code NEW} the name is the class's simple name, taken from the text; for the member points it is the
     * parsed selector's name. An initialiser selector carries {@code <init>} or {@code <clinit>}, which is not a
     * name anything can be renamed to, and is refused.
     *
     * @param point the point's name; must not be {@code null}
     * @param text  the target as written; must not be {@code null}
     * @return the name, or {@code null} when the point takes no member target, the text does not parse, or it names
     *         an initialiser
     */
    @Nullable
    private static String nameWrittenIn(@NotNull final String point, @NotNull final String text) {
        if (Point.NEW.name().equals(point)) {
            final int dot = text.lastIndexOf('.');
            return dot < 0 ? text : text.substring(dot + 1);
        }
        final MemberKind kind = PointTargets.selectorKindFor(point);
        final MemberSelector selector = kind == null ? null : parsed(text, kind);
        return switch (selector) {
            case final MethodSelector method -> method.isInitialiser() ? null : method.name();
            case final FieldSelector field -> field.name();
            case null, default -> null;
        };
    }

    /**
     * Returns the methods a method selector names.
     *
     * <p>An owner is required: a target with none names every method of that name in the project, which is not an
     * answer worth navigating to. Inherited methods are searched as well, since a selector may name the class the
     * call is written on rather than the one declaring the method. A selector naming an initialiser is refused
     * outright, and a constructor is never among the candidates.
     *
     * @param selector the parsed selector; must not be {@code null}
     * @param text     the target as written, re-parsed for the signature comparison; must not be {@code null}
     * @param context  the element whose resolve scope and project the search runs in; must not be {@code null}
     * @return the matching methods, and an empty list when the selector names no owner, names an initialiser, or
     *         matches nothing
     */
    @Unmodifiable
    @NotNull
    private static List<PsiElement> methodsNamedBy(@NotNull final MethodSelector selector,
                                                   @NotNull final String text,
                                                   @NotNull final PsiElement context) {
        final TypePattern owner = selector.owner().orElse(null);
        if (owner == null || selector.isInitialiser()) {
            return List.of();
        }
        final List<PsiElement> found = new ArrayList<>(2);
        for (final PsiClass declaring : classesOf(owner, context)) {
            for (final PsiMethod candidate : declaring.findMethodsByName(selector.name(), true)) {
                if (!candidate.isConstructor() && SelectorTargets.namesMethod(text, candidate)) {
                    found.add(candidate);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns the fields a field selector names.
     *
     * <p>One field per candidate owner at most: a field name is unique within a class, and the search includes
     * inherited fields for the same reason the method search does.
     *
     * @param selector the parsed selector; must not be {@code null}
     * @param context  the element whose resolve scope and project the search runs in; must not be {@code null}
     * @return the matching fields, and an empty list when the selector names no owner or nothing matches
     */
    @Unmodifiable
    @NotNull
    private static List<PsiElement> fieldsNamedBy(@NotNull final FieldSelector selector,
                                                  @NotNull final PsiElement context) {
        final TypePattern owner = selector.owner().orElse(null);
        if (owner == null) {
            return List.of();
        }
        final List<PsiElement> found = new ArrayList<>(2);
        for (final PsiClass declaring : classesOf(owner, context)) {
            final PsiField candidate = declaring.findFieldByName(selector.name(), true);
            if (candidate != null && SelectorTargets.namesField(selector, candidate)) {
                found.add(candidate);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns the classes a selector's owner could name.
     *
     * <p>Offered for a caller that has to look inside those classes rather than at the member — completing a member
     * name, for instance, needs the owners before there is a name to match.
     *
     * @param selector the parsed selector; must not be {@code null}
     * @param context  the element whose resolve scope and project the search runs in; must not be {@code null}
     * @return the owners, and an empty list for a constant selector, for a selector with no owner, and for an owner
     *         that resolves to nothing
     */
    @Unmodifiable
    @NotNull
    public static List<PsiClass> ownersOf(@NotNull final MemberSelector selector,
                                          @NotNull final PsiElement context) {
        final TypePattern owner = switch (selector) {
            case final MethodSelector method -> method.owner().orElse(null);
            case final FieldSelector field -> field.owner().orElse(null);
            default -> null;
        };
        return owner == null ? List.of() : classesOf(owner, context);
    }

    /**
     * Returns the classes an owner pattern names.
     *
     * <p>An exact pattern — the descriptor form — resolves to one class or to none. A named pattern goes through
     * the same search a raw class name does. A wildcard names no class in particular and is answered empty rather
     * than with every class in the project.
     *
     * @param owner   the owner pattern; must not be {@code null}
     * @param context the element whose resolve scope and project the search runs in; must not be {@code null}
     * @return the classes named, and an empty list for a wildcard and for a name that resolves to nothing
     */
    @Unmodifiable
    @NotNull
    private static List<PsiClass> classesOf(@NotNull final TypePattern owner,
                                            @NotNull final PsiElement context) {
        return switch (owner) {
            // Exact, because a resolved type is exact. This is the desc: form, and the whole
            // point of it is that it names one class and not a family of similarly named ones.
            case final TypePattern.Exact exact -> {
                final PsiClass resolved = JavaPsiFacade.getInstance(context.getProject())
                        .findClass(qualifiedNameOf(exact.renderSource()), scopeOf(context));
                yield resolved == null ? List.of() : List.of(resolved);
            }
            case final TypePattern.Named named -> classesNamed(named.renderSource(), context);
            default -> List.of();
        };
    }

    /**
     * Returns the classes a written name could refer to.
     *
     * <p>A name carrying a {@code .} or a {@code $} is qualified and is resolved once. A bare name goes to the short
     * names cache, which is also the only path that can answer with several classes. An array name is refused: a
     * {@code NEW} point names the type an instruction creates, and an array creation is not one.
     *
     * @param name    the class name as written; must not be {@code null}
     * @param context the element whose resolve scope and project the search runs in; must not be {@code null}
     * @return the classes of that name, and an empty list when the name is blank, names an array, or resolves to
     *         nothing
     */
    @Unmodifiable
    @NotNull
    private static List<PsiClass> classesNamed(@NotNull final String name,
                                               @NotNull final PsiElement context) {
        if (name.isBlank() || name.endsWith("[]")) {
            return List.of();
        }
        final GlobalSearchScope scope = scopeOf(context);
        if (name.indexOf('.') >= 0 || name.indexOf('$') >= 0) {
            final PsiClass resolved = JavaPsiFacade.getInstance(context.getProject())
                    .findClass(qualifiedNameOf(name), scope);
            return resolved == null ? List.of() : List.of(resolved);
        }
        // A simple name matches an owner by suffix in the engine, so every class of that name is a
        // class this target could bind to. Listing them is not a failure to disambiguate; it is the
        // same answer the engine would give.
        return List.of(PsiShortNamesCache.getInstance(context.getProject())
                .getClassesByName(name, scope));
    }

    /**
     * Converts a written class name to the spelling the resolver takes.
     *
     * <p>A nested class is written {@code Outer$Inner} in a selector and looked up as {@code Outer.Inner}, which is
     * the only form {@code JavaPsiFacade} accepts.
     *
     * @param name the name as written; must not be {@code null}
     * @return the name with {@code $} replaced by {@code .}
     */
    @Contract(pure = true)
    @NotNull
    private static String qualifiedNameOf(@NotNull final String name) {
        return name.replace('$', '.');
    }

    /**
     * Returns the scope a search runs in.
     *
     * <p>The context element's own resolve scope, so a target written in a test source can name a test class and one
     * written in a production source cannot.
     *
     * @param context the element the search is made from; must not be {@code null}
     * @return the scope to search
     */
    @NotNull
    private static GlobalSearchScope scopeOf(@NotNull final PsiElement context) {
        return context.getResolveScope();
    }

    /**
     * Parses a target, treating a malformed one as naming nothing.
     *
     * <p>Every runtime exception the parser can raise is caught, not only
     * {@code de.splatgames.aether.weaver.api.select.SelectorSyntaxException}: the grammar also rejects a few shapes
     * with a plain {@link IllegalArgumentException}, and an editor asking this question has no use for either.
     *
     * @param text the target as written; must not be {@code null}
     * @param kind the grammar a bare name is read in; must not be {@code null}
     * @return the parsed selector, or {@code null} when the text does not parse
     */
    @Contract(pure = true)
    @Nullable
    private static MemberSelector parsed(@NotNull final String text,
                                         @NotNull final MemberKind kind) {
        try {
            return MemberSelector.parse(text, kind);
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Reads an annotation element as a string.
     *
     * @param value the element's value, or {@code null} when it has none
     * @return the string it holds, or {@code null} when the value is not a string literal
     */
    @Nullable
    private static String textOf(@Nullable final PsiElement value) {
        return value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final String text
                ? text
                : null;
    }

    /**
     * Reads an annotation element as the simple name of the constant it names.
     *
     * <p>The reference is not resolved, so an enum constant is understood whether it was written {@code Point.HEAD}
     * or imported and written {@code HEAD}, and an unresolved reference in a file being edited still yields a name.
     *
     * @param value the element's value, or {@code null} when it has none
     * @return the reference's name, or {@code null} when the value is not a reference
     */
    @Nullable
    private static String constantNameOf(@Nullable final PsiElement value) {
        if (value instanceof final PsiReferenceExpression reference) {
            return reference.getReferenceName();
        }
        return value instanceof final PsiJavaCodeReferenceElement reference
                ? reference.getReferenceName()
                : null;
    }
}
