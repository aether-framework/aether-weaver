package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@code @Weave} class: what it targets, how it merges, and which groups it declares.
 *
 * <p>The names of the framework's annotations live here as constants, so that nothing else in the plugin spells one
 * as a literal, and every feature that has to find one — the augment provider, the inspections, the markers, the
 * reference contributors, the generator — asks these methods for it.
 *
 * <h2>Annotations are matched by qualified name</h2>
 *
 * <p>An annotation is looked up on the declaration's modifier list by its qualified name, so one reached through an
 * import and one written out in full are both found, while a different {@code Weave} from another package is not. A
 * declaration with no modifier list carries no annotation and answers {@code null}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveDeclarations {

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Weave}. */
    public static final String WEAVE = "de.splatgames.aether.weaver.api.Weave";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Inject}. */
    public static final String INJECT = "de.splatgames.aether.weaver.api.Inject";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Redirect}. */
    public static final String REDIRECT = "de.splatgames.aether.weaver.api.Redirect";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Shadow}. */
    public static final String SHADOW = "de.splatgames.aether.weaver.api.Shadow";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Accessor}. */
    public static final String ACCESSOR = "de.splatgames.aether.weaver.api.Accessor";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Invoker}. */
    public static final String INVOKER = "de.splatgames.aether.weaver.api.Invoker";

    /** The element of {@code @Inject} and {@code @Redirect} holding the target selector. */
    public static final String METHOD_ATTRIBUTE = "method";

    /** The qualified name of {@link de.splatgames.aether.weaver.api.Group}. */
    public static final String GROUP = "de.splatgames.aether.weaver.api.Group";

    /**
     * The qualified name of the container the compiler generates for a repeated {@code @Group}.
     *
     * <p>Written as the canonical name, with a {@code .} between the outer annotation and the nested one, which is
     * the form the qualified name of an annotation is compared in.
     */
    public static final String GROUP_CONTAINER = GROUP + ".Container";

    /** The {@code @Weave} element naming targets as strings rather than as class literals. */
    public static final String TARGETS_ATTRIBUTE = "targets";

    /** The {@code @Group} element holding the group's name. */
    private static final String NAME_ATTRIBUTE = "name";

    /** The element a repeatable annotation's container holds its occurrences in. */
    private static final String VALUE_ATTRIBUTE = "value";

    /** The {@code @Weave} element deciding whether the weave dissolves into its target. */
    public static final String KIND_ATTRIBUTE = "kind";

    /** The {@code Kind} constant naming a weave that contributes accessors and invokers only. */
    private static final String STATIC_KIND = "STATIC";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WeaveDeclarations() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the weave class an element is written in.
     *
     * <p>Only the nearest enclosing class is considered: a class nested inside a weave is not itself one, and
     * answering with the outer weave would attribute its members to a declaration that does not hold them.
     *
     * @param element the element to place; must not be {@code null}
     * @return the enclosing class when it carries {@code @Weave}, and {@code null} otherwise
     */
    @Nullable
    public static PsiClass enclosingWeave(@NotNull final PsiElement element) {
        final PsiClass enclosing = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        return enclosing != null && annotation(enclosing, WEAVE) != null ? enclosing : null;
    }

    /**
     * Returns an annotation written on a declaration.
     *
     * <p>The one place the plugin reads an annotation off a declaration, so that a class, a method, a field and a
     * parameter are all asked the same way.
     *
     * @param owner         the declaration to read; must not be {@code null}
     * @param qualifiedName the annotation's qualified name; must not be {@code null}
     * @return the annotation, or {@code null} when the declaration does not carry it or has no modifier list at all
     */
    @Nullable
    public static PsiAnnotation annotation(@NotNull final PsiModifierListOwner owner,
                                           @NotNull final String qualifiedName) {
        return owner.getModifierList() == null
                ? null
                : owner.getModifierList().findAnnotation(qualifiedName);
    }

    /**
     * Returns the classes a weave targets.
     *
     * <p>Both ways of naming a target are collected into one list: the class literals of {@code value()} and the
     * names of {@code targets()}, which is how a target that is not on the compile classpath is written. A name is
     * resolved over the whole project and its libraries, since the class it names is by construction not one the
     * weave can refer to directly, and it is passed to the resolver exactly as written — unlike
     * {@code PointDeclarations}, which rewrites a nested name's {@code $} as a {@code .} first. Duplicates are
     * dropped, so a class named twice appears once.
     *
     * @param weave the weave class; must not be {@code null}
     * @return the targets in the order they were written, {@code value()} first, and an empty list when the class
     *         carries no {@code @Weave} or names nothing that resolves
     */
    @Unmodifiable
    @NotNull
    public static List<PsiClass> targetsOf(@NotNull final PsiClass weave) {
        final PsiAnnotation annotation = annotation(weave, WEAVE);
        if (annotation == null) {
            return List.of();
        }

        final List<PsiClass> targets = new ArrayList<>(2);
        collect(annotation.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME),
                weave, targets);
        collect(annotation.findAttributeValue(TARGETS_ATTRIBUTE), weave, targets);
        return List.copyOf(targets);
    }

    /**
     * Reports whether a weave stays a class of its own instead of dissolving into its target.
     *
     * <p>Reads the attributes the declaration actually wrote rather than asking for a value the annotation would
     * default: a weave that names no kind is not static. The constant is compared by the reference's own name, so
     * {@code Kind.STATIC} and a statically imported {@code STATIC} are both understood.
     *
     * @param weave the class to test; must not be {@code null}
     * @return {@code true} when the class carries {@code @Weave} with {@code kind} written as {@code STATIC}
     */
    public static boolean isStaticWeave(@NotNull final PsiClass weave) {
        final PsiAnnotation declared = annotation(weave, WEAVE);
        if (declared == null) {
            return false;
        }
        for (final PsiNameValuePair attribute : declared.getParameterList().getAttributes()) {
            if (KIND_ATTRIBUTE.equals(attribute.getName())) {
                return STATIC_KIND.equals(constantNameOf(attribute.getValue()));
            }
        }
        return false;
    }

    /**
     * Returns the names of the groups a weave declares.
     *
     * <p>{@code @Group} is repeatable, so both spellings have to be read: a single occurrence written directly, and
     * several wrapped by the compiler in the generated container. Names are collected in the order they appear, a
     * blank one is dropped, and a name declared twice is kept once.
     *
     * <p>The list matters because a declaration naming a group is exempt from its own {@code require}: a group name
     * that no {@code @Group} declares leaves that injection with no match check at all.
     *
     * @param weave the weave class; must not be {@code null}
     * @return the declared group names, and an empty list when the class declares none or has no modifier list
     */
    @Unmodifiable
    @NotNull
    public static List<String> groupsOf(@NotNull final PsiClass weave) {
        final PsiModifierList modifiers = weave.getModifierList();
        if (modifiers == null) {
            return List.of();
        }

        final List<String> names = new ArrayList<>(2);
        for (final PsiAnnotation annotation : modifiers.getAnnotations()) {
            final String qualified = annotation.getQualifiedName();
            if (GROUP.equals(qualified)) {
                addGroupName(annotation, names);
            } else if (GROUP_CONTAINER.equals(qualified)
                    && annotation.findAttributeValue(VALUE_ATTRIBUTE)
                            instanceof final PsiArrayInitializerMemberValue repeated) {
                for (final PsiAnnotationMemberValue occurrence : repeated.getInitializers()) {
                    if (occurrence instanceof final PsiAnnotation group) {
                        addGroupName(group, names);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * Adds one {@code @Group}'s name to the collected list.
     *
     * <p>Only a string literal counts. A name assembled from a constant is not resolved and contributes nothing,
     * which shows as a group the editor does not know about rather than as a wrong one.
     *
     * @param group the annotation to read; must not be {@code null}
     * @param into  the list to add to; must not be {@code null}
     */
    private static void addGroupName(@NotNull final PsiAnnotation group,
                                     @NotNull final List<String> into) {
        if (group.findAttributeValue(NAME_ATTRIBUTE) instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final String name
                && !name.isBlank()
                && !into.contains(name)) {
            into.add(name);
        }
    }

    /**
     * Reads an annotation element as the simple name of the constant it names.
     *
     * <p>The reference is not resolved, so a constant written with its type and one imported and written bare are
     * read the same way.
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

    /**
     * Collects the classes an annotation element names into a list.
     *
     * <p>An element declared as an array may be written as a single value or as a braced list, so an array
     * initialiser is descended into and anything else is resolved directly.
     *
     * @param value   the element's value, or {@code null} when it has none
     * @param context the element whose project the resolution runs in; must not be {@code null}
     * @param into    the list to add to, which is left without duplicates; must not be {@code null}
     */
    private static void collect(@Nullable final PsiAnnotationMemberValue value,
                                @NotNull final PsiElement context,
                                @NotNull final List<PsiClass> into) {
        if (value instanceof final PsiArrayInitializerMemberValue array) {
            for (final PsiAnnotationMemberValue element : array.getInitializers()) {
                collect(element, context, into);
            }
            return;
        }
        final PsiClass resolved = resolve(value, context);
        if (resolved != null && !into.contains(resolved)) {
            into.add(resolved);
        }
    }

    /**
     * Resolves one target, written either as a class literal or as a name.
     *
     * <p>A class literal whose operand is not a class type — {@code void.class}, a primitive, an array — resolves
     * to nothing, since none of those is a class a weave can target.
     *
     * @param value   the element's value, or {@code null} when it has none
     * @param context the element whose project the resolution runs in; must not be {@code null}
     * @return the class, or {@code null} when the value is neither a class literal nor a non-blank string, or names
     *         nothing that resolves
     */
    @Nullable
    private static PsiClass resolve(@Nullable final PsiAnnotationMemberValue value,
                                    @NotNull final PsiElement context) {
        if (value instanceof final PsiClassObjectAccessExpression literal) {
            return literal.getOperand().getType() instanceof final PsiClassType type
                    ? type.resolve()
                    : null;
        }
        if (value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final String name && !name.isBlank()) {
            return JavaPsiFacade.getInstance(context.getProject())
                    .findClass(name, GlobalSearchScope.allScope(context.getProject()));
        }
        return null;
    }
}
