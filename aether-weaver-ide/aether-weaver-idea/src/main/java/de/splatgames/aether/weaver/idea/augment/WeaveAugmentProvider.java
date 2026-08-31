package de.splatgames.aether.weaver.idea.augment;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import de.splatgames.aether.weaver.idea.index.WeaveTargetIndex;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts the members a weave gives its target on the target class, so that the editor sees the class the build
 * produces.
 *
 * <p>Registered as {@code com.intellij.lang.psiAugmentProvider}, so the platform asks it whenever anything walks a
 * class: an inspection, the structure view, the navigation bar, completion. The weaves of a class are found through
 * {@link WeaveTargetIndex}, which answers by the target's simple name and then confirms the resolved target, so a
 * weave written for a different class of the same name contributes nothing.
 *
 * <h2>What lands on the target</h2>
 *
 * <ul>
 *   <li><b>An {@code @Accessor} or an {@code @Invoker} method</b>, as {@code public}, with the shape it is declared
 *       with. A {@code Kind.STATIC} weave contributes these as well, and only these: it merges nothing, and reaching
 *       the target's state through a generated accessor is what it has instead.
 *   <li><b>Every other member of a weave that dissolves</b>, meaning one that is not {@code Kind.STATIC}, unless it
 *       is annotated {@code @Shadow}. A shadow names a member the target already has, and adding it would report a
 *       duplicate on a target whose own source is correct.
 *   <li><b>A handler</b> — a method carrying {@code @Inject} or {@code @Redirect} — as {@code private}, for a weave
 *       that dissolves; a {@code Kind.STATIC} weave's handler never lands on the target at all, since only its
 *       accessors and invokers do. The private form is a real method of the woven class and an implementation
 *       detail at the same time, and {@code private} is how completion stops offering it from outside. Every other
 *       merged method keeps whichever of {@code public}, {@code protected} and {@code private} it was declared
 *       with, and a merged member declared {@code static} stays static.
 * </ul>
 *
 * <h2>What does not</h2>
 *
 * <p>A member whose name the target already declares is withheld. Whether it reaches the target at all under
 * a different name depends on the member: declared {@code @Unique}, the engine emits it under the declared name
 * followed by {@code $aw$} and a digest of the weave, so offering the plain name would claim a member the compiled
 * class does not have under that name; without {@code @Unique} the collision is reported as {@code AW1080} and the
 * whole target is left unrebuilt, so nothing is emitted under either name. Either way the plain name is withheld.
 * The check compares names alone, for methods as well as fields, so a merged method that merely overloads one the
 * target declares is withheld with it.
 *
 * <p>A constructor is never merged; a weave that declares one is refused as {@code AW1081}.
 *
 * <p>A weave is not augmented with its own members, and a target that is not a {@link PsiExtensibleClass} — one
 * whose own members cannot be read apart from its augmented ones — receives accessors and invokers only, because
 * those need no collision check and nothing else can be checked for it.
 *
 * <h2>Indexing</h2>
 *
 * <p>{@link #isDumbAware()} is {@code true}, so the platform asks while the project is being indexed and this class
 * has to answer rather than throw. A request made then is answered empty before anything touches the index, because
 * resolving the {@code @Weave} annotation resolves the import that names it, which does. That empty answer is given
 * by {@link #getAugments(PsiElement, Class, String)} itself, before {@link #mergedInto(PsiClass)} — and with it the
 * cache — is ever reached, so nothing empty is cached on account of indexing. The cache depends on the project's
 * dumb-mode modification tracker as well as on {@link PsiModificationTracker#MODIFICATION_COUNT}.
 *
 * <p>The target's own members are read through {@code PsiExtensibleClass} and never through {@code getMethods()} or
 * {@code getFields()}: those run augmentation, and asking them here would ask this provider for the answer it is in
 * the middle of computing.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveAugmentProvider extends PsiAugmentProvider {

    /** The qualified names whose merged members are being computed on this thread, as a recursion guard. */
    private static final ThreadLocal<Set<String>> COMPUTING =
            ThreadLocal.withInitial(HashSet::new);

    /** Creates the provider; the platform instantiates it once from the extension point. */
    public WeaveAugmentProvider() {
        // Stateless; everything is cached on the target.
    }

    /**
     * Returns the members merged into {@code element} by the weaves that name it, or an empty list when there are
     * none.
     *
     * <p>Answers only for exactly {@link PsiMethod} and {@link PsiField}; any other request is empty. Both kinds are
     * computed together and cached on the target, and the request only decides which of them is handed back.
     *
     * @param <Psi>    the kind of member the platform is asking for
     * @param element  the element being augmented; must not be {@code null}
     * @param type     the kind of member requested; must not be {@code null}
     * @param nameHint the name being looked for, or {@code null} to ask for all of them
     * @return the merged members of that kind, or an empty list when {@code element} is not a class, the requested
     *         kind is neither a method nor a field, the project is being indexed, the class is itself a weave, or
     *         nothing is merged into it
     */
    @Override
    @NotNull
    protected <Psi extends PsiElement> List<Psi> getAugments(@NotNull final PsiElement element,
                                                             @NotNull final Class<Psi> type,
                                                             @Nullable final String nameHint) {
        if (!(element instanceof final PsiClass target)
                || (type != PsiMethod.class && type != PsiField.class)) {
            return List.of();
        }
        // Before anything that can touch the index, which is the very next line. Augmentation is
        // driven by PsiClass.getFields(), so it runs whenever anything walks a class — the structure
        // view, the navigation bar, an inspection — and that includes while the project is still
        // being indexed. Resolving an annotation there means resolving the import that names it,
        // which goes to the stub index and throws IndexNotReadyException.
        //
        // The guard used to sit one level down, in WeaveTargetIndex, and the reasoning above
        // isDumbAware() said that was enough. It was not: the weave check runs first and resolves.
        if (DumbService.getInstance(target.getProject()).isDumb()) {
            return List.of();
        }
        // A weave is not augmented with its own members: it declares them itself, and adding them
        // again would report every one of them as a duplicate.
        if (WeaveDeclarations.annotation(target, WeaveDeclarations.WEAVE) != null) {
            return List.of();
        }

        final List<PsiElement> merged = mergedInto(target);
        if (merged.isEmpty()) {
            return List.of();
        }
        final List<Psi> matching = new ArrayList<>();
        for (final PsiElement member : merged) {
            if (type.isInstance(member) && (nameHint == null || nameHint.equals(nameOf(member)))) {
                matching.add(type.cast(member));
            }
        }
        return matching;
    }

    /**
     * Returns the name a member would be found under.
     *
     * @param member the merged member; must not be {@code null}
     * @return the method or field name, or {@code null} for anything that is neither
     */
    @Nullable
    private static String nameOf(@NotNull final PsiElement member) {
        if (member instanceof final PsiMethod method) {
            return method.getName();
        }
        return member instanceof final PsiField field ? field.getName() : null;
    }

    /**
     * Declares that the platform may ask while the project is being indexed.
     *
     * <p>Augmentation runs whenever anything walks a class, which includes walking one during indexing. Answering
     * there is what {@link #getAugments(PsiElement, Class, String)} guards for: it reports nothing until indexing has
     * finished rather than reaching the index and throwing.
     *
     * @return {@code true}, always
     */
    @Override
    public boolean isDumbAware() {
        return true;
    }

    /**
     * Returns the merged members of one target, computed once and cached on it.
     *
     * <p>The cached value depends on {@link PsiModificationTracker#MODIFICATION_COUNT} and on the project's dumb-mode
     * modification tracker. {@link #getAugments(PsiElement, Class, String)} never calls this method while the
     * project is dumb, so nothing empty is ever cached here on account of indexing.
     *
     * @param target the class being augmented; must not be {@code null}
     * @return the merged methods and fields in one list, or an empty list when the target has no qualified name or is
     *         already being computed further up this thread's stack
     */
    @NotNull
    private static List<PsiElement> mergedInto(@NotNull final PsiClass target) {
        final String name = target.getQualifiedName();
        if (name == null || !COMPUTING.get().add(name)) {
            return List.of();
        }
        try {
            // Dependent on dumb mode as well as on PSI. Everything here answers empty while the
            // project is being indexed, and without this the emptiness would be cached until the
            // next edit — augmented members missing from a class that has them, for as long as
            // nobody types.
            return CachedValuesManager.getCachedValue(target,
                    () -> CachedValueProvider.Result.create(compute(target),
                            PsiModificationTracker.MODIFICATION_COUNT,
                            DumbService.getInstance(target.getProject()).getModificationTracker()));
        } finally {
            COMPUTING.get().remove(name);
        }
    }

    /**
     * Builds every member the weaves of one target give it, without consulting the cache.
     *
     * <p>The target's own declarations are read once into a {@code Declared}, and every collision is decided against
     * that snapshot rather than by asking the target again while it is being augmented.
     *
     * @param target the class being augmented; must not be {@code null}
     * @return the merged methods and fields, or accessors and invokers alone when the target's own members cannot be
     *         read apart from its augmented ones
     */
    @NotNull
    private static List<PsiElement> compute(@NotNull final PsiClass target) {
        // The target's own declarations, never getMethods()/getFields(). Those run augmentation,
        // so asking them here is asking this method what this method is about to return: the first
        // version did exactly that for the collision check and died with a StackOverflowError the
        // moment a weave declared a merged member. Accessors survived it only because they need no
        // collision check, which made the failure look like it was about merging.
        final Declared own = Declared.of(target);
        if (own == null) {
            // Not a class whose own members can be read apart from its augmented ones. Merged
            // members cannot be checked for collision, so none are offered; accessors do not need
            // the check and still are.
            return computeGeneratedOnly(target);
        }

        final List<PsiElement> merged = new ArrayList<>();
        for (final PsiClass weave : WeaveTargetIndex.weavesOf(target)) {
            final boolean dissolves = !WeaveDeclarations.isStaticWeave(weave);
            for (final PsiMethod declared : weave.getMethods()) {
                if (declared.isConstructor()) {
                    // A weave declaring a constructor is AW1081; it is never merged.
                    continue;
                }
                final PsiMethod contributed = methodFor(declared, target, dissolves, own);
                if (contributed != null) {
                    merged.add(contributed);
                }
            }
            if (dissolves) {
                for (final PsiField declared : weave.getFields()) {
                    // A colliding name is mangled by the engine. See the type documentation.
                    if (isMergedMember(declared) && !own.declaresField(declared.getName())) {
                        merged.add(fieldFor(declared, target));
                    }
                }
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Builds only what needs no collision check, for a target whose own members cannot be read on their own.
     *
     * @param target the class being augmented; must not be {@code null}
     * @return the {@code @Accessor} and {@code @Invoker} methods of every weave that names the target, as
     *         {@code public} methods, or an empty list when there are none
     */
    @NotNull
    private static List<PsiElement> computeGeneratedOnly(@NotNull final PsiClass target) {
        final List<PsiElement> merged = new ArrayList<>();
        for (final PsiClass weave : WeaveTargetIndex.weavesOf(target)) {
            for (final PsiMethod declared : weave.getMethods()) {
                if (!declared.isConstructor() && isGenerated(declared)) {
                    merged.add(build(declared, target, PsiModifier.PUBLIC));
                }
            }
        }
        return List.copyOf(merged);
    }

    /**
     * The names a target declares itself, read once so that collisions can be decided without asking it again.
     *
     * <p>Names only, and no signatures. That is what both collision checks compare, so a merged method sharing a name
     * with one the target declares is withheld even where their parameter lists differ.
     *
     * @param fields  the names of the target's own fields
     * @param methods the names of the target's own methods
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Declared(@NotNull Set<String> fields, @NotNull Set<String> methods) {

        /**
         * Reads the own members of one target.
         *
         * @param target the class being augmented; must not be {@code null}
         * @return the declared names, or {@code null} when the target is not a {@link PsiExtensibleClass} and its own
         *         members therefore cannot be told from its augmented ones
         */
        @Nullable
        static Declared of(@NotNull final PsiClass target) {
            if (!(target instanceof final PsiExtensibleClass extensible)) {
                return null;
            }
            final Set<String> fields = new HashSet<>();
            for (final PsiField field : extensible.getOwnFields()) {
                fields.add(field.getName());
            }
            final Set<String> methods = new HashSet<>();
            for (final PsiMethod method : extensible.getOwnMethods()) {
                methods.add(method.getName());
            }
            return new Declared(fields, methods);
        }

        /**
         * Reports whether the target declares a field of the given name.
         *
         * @param name the field name to look for; must not be {@code null}
         * @return {@code true} when the target declares it itself
         */
        boolean declaresField(@NotNull final String name) {
            return this.fields.contains(name);
        }

        /**
         * Reports whether the target declares a method of the given name, whatever its parameters.
         *
         * @param name the method name to look for; must not be {@code null}
         * @return {@code true} when the target declares one itself
         */
        boolean declaresMethod(@NotNull final String name) {
            return this.methods.contains(name);
        }
    }

    /**
     * Decides what one method of a weave becomes on the target, and with which access.
     *
     * <p>An {@code @Accessor} or an {@code @Invoker} is generated on the target from its declaration alone, so it
     * lands whether the weave dissolves or not. Everything else is a merged member and lands only for a weave that
     * dissolves.
     *
     * @param declared  the method as the weave declares it; must not be {@code null}
     * @param target    the class being augmented; must not be {@code null}
     * @param dissolves whether the weave's own code is moved into the target rather than left where it is written
     * @param own       the names the target declares itself; must not be {@code null}
     * @return the method to put on the target, or {@code null} when it is a {@code @Shadow}, when the weave does not
     *         dissolve, or when the target already declares that name
     */
    @Nullable
    private static PsiMethod methodFor(@NotNull final PsiMethod declared,
                                       @NotNull final PsiClass target,
                                       final boolean dissolves,
                                       @NotNull final Declared own) {
        // An accessor or an invoker adds a public method of exactly this shape to the target, and
        // it does so for a static weave as well — that is the whole point of them.
        if (isGenerated(declared)) {
            return build(declared, target, PsiModifier.PUBLIC);
        }
        if (!dissolves || !isMergedMember(declared)) {
            return null;
        }
        if (own.declaresMethod(declared.getName())) {
            // Collides, so the engine mangles it. See the type documentation.
            return null;
        }
        // A handler is merged like anything else, and is also an implementation detail: private, so
        // completion does not offer it from outside the class it was woven into.
        final boolean handler =
                WeaveDeclarations.annotation(declared, WeaveDeclarations.INJECT) != null
                        || WeaveDeclarations.annotation(declared, WeaveDeclarations.REDIRECT) != null;
        return build(declared, target, handler ? PsiModifier.PRIVATE : null);
    }

    /**
     * Builds the light method that stands on the target for one declaration in a weave.
     *
     * <p>Navigation leads to the declaration in the weave, and {@code static} is carried over from it.
     *
     * @param declared the method as the weave declares it; must not be {@code null}
     * @param target   the class being augmented; must not be {@code null}
     * @param access   the access modifier to force, or {@code null} to keep whichever of {@code public},
     *                 {@code protected} and {@code private} the declaration carries
     * @return the light method, with the declaration's name, return type and parameters
     */
    @NotNull
    private static PsiMethod build(@NotNull final PsiMethod declared,
                                   @NotNull final PsiClass target,
                                   @Nullable final String access) {
        final LightMethodBuilder built =
                new LightMethodBuilder(target.getManager(), JavaLanguage.INSTANCE,
                        declared.getName());
        built.setContainingClass(target);
        built.setMethodReturnType(declared.getReturnType());
        // Navigation leads to the declaration in the weave, which is where the code actually is —
        // the alternative is a member the reader can call and cannot find.
        built.setNavigationElement(declared);
        for (final PsiParameter parameter : declared.getParameterList().getParameters()) {
            built.addParameter(parameter.getName(), parameter.getType());
        }
        if (access != null) {
            built.addModifier(access);
        } else {
            copyAccess(declared, built);
        }
        if (declared.hasModifierProperty(PsiModifier.STATIC)) {
            built.addModifier(PsiModifier.STATIC);
        }
        return built;
    }

    /**
     * Builds the light field that stands on the target for one field of a weave.
     *
     * <p>Name, type and navigation come from the declaration, and {@code static} is carried over. No access modifier
     * is put on it.
     *
     * @param declared the field as the weave declares it; must not be {@code null}
     * @param target   the class being augmented; must not be {@code null}
     * @return the light field
     */
    @NotNull
    private static PsiField fieldFor(@NotNull final PsiField declared,
                                     @NotNull final PsiClass target) {
        final LightFieldBuilder built = new LightFieldBuilder(target.getManager(),
                declared.getName(), declared.getType());
        built.setContainingClass(target);
        built.setNavigationElement(declared);
        if (declared.hasModifierProperty(PsiModifier.STATIC)) {
            built.setModifiers(PsiModifier.STATIC);
        }
        return built;
    }

    /**
     * Gives the light method the access the declaration was written with.
     *
     * <p>A package-private declaration carries none of the three modifiers and is left without one.
     *
     * @param declared the method as the weave declares it; must not be {@code null}
     * @param built    the light method being assembled; must not be {@code null}
     */
    private static void copyAccess(@NotNull final PsiMethod declared,
                                   @NotNull final LightMethodBuilder built) {
        for (final String modifier
                : new String[]{PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE}) {
            if (declared.hasModifierProperty(modifier)) {
                built.addModifier(modifier);
                return;
            }
        }
    }

    /**
     * Reports whether a method describes something the engine generates on the target rather than moves there.
     *
     * @param declared the method as the weave declares it; must not be {@code null}
     * @return {@code true} for an {@code @Accessor} or an {@code @Invoker}
     */
    private static boolean isGenerated(@NotNull final PsiMethod declared) {
        return WeaveDeclarations.annotation(declared, WeaveDeclarations.ACCESSOR) != null
                || WeaveDeclarations.annotation(declared, WeaveDeclarations.INVOKER) != null;
    }

    /**
     * Reports whether a member of a weave is one the target gains rather than one it already has.
     *
     * @param declared the member as the weave declares it; must not be {@code null}
     * @return {@code true} for anything not annotated {@code @Shadow}, since a shadow names a member of the target
     *         and adds nothing to it
     */
    private static boolean isMergedMember(@NotNull final PsiModifierListOwner declared) {
        return WeaveDeclarations.annotation(declared, WeaveDeclarations.SHADOW) == null;
    }

}
