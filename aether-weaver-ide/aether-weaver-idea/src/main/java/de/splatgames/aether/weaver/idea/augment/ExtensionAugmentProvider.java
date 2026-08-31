package de.splatgames.aether.weaver.idea.augment;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import de.splatgames.aether.weaver.idea.index.ExtensionReceiverIndex;
import de.splatgames.aether.weaver.idea.library.LibraryExtensions;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts the methods and constants an extension holder declares on the class each of them names as its receiver.
 *
 * <p>Registered as {@code com.intellij.lang.psiAugmentProvider}, so the platform asks it whenever anything walks a
 * class. Without it a call written on the receiver is reported as an unknown method although the build compiles and
 * runs it.
 *
 * <h2>Where holders come from</h2>
 *
 * <p>Two places, and the receiver cannot tell them apart. {@link ExtensionReceiverIndex} answers for the project's
 * own sources, {@link LibraryExtensions} for the weave manifest of an attached library; a library without that
 * manifest contributes nothing. The two lists are merged and deduplicated through
 * {@code PsiManager.areElementsEquivalent}, which recognises a source class and its compiled form as one class, so a
 * project that both builds a holder and has it on the classpath contributes each method once.
 *
 * <h2>What lands on the receiver</h2>
 *
 * <ul>
 *   <li><b>A contributed method</b>, as recognised by {@link ExtensionDeclarations#contributedBy(PsiClass)}, whose
 *       receiver type resolves to this very class. The index answers by simple name, which is a superset; the
 *       equivalence check is what stops an extension declared on somebody else's class of the same name. Matching is
 *       on the class itself and never on its hierarchy, so an extension declared on a supertype stays on the
 *       supertype and reaches a subtype the way any inherited method does.
 *   <li><b>A contributed constant</b>, as {@code public static final}, built as a {@link ContributedField}.
 *   <li>No method at all for a holder asking about itself: it declares its own contributions already, and adding
 *       them back would report every one of them as a duplicate.
 * </ul>
 *
 * <p>An instance contribution is offered without its receiver parameter and without {@code static}, so that a call on
 * a value passes the arguments that are written at the call site. A static contribution keeps every parameter and is
 * offered as {@code static}, so that a call on the type is not reported as an instance method referenced from a
 * static context.
 *
 * <h2>What is withheld</h2>
 *
 * <p>A method the receiver already declares under the same name and the same parameter types is not offered: javac
 * resolves such a call to the receiver's own method, and a second member of that signature would be a duplicate on
 * source that is perfectly correct. The comparison is by name and parameter types together, so a contribution that
 * only overloads an existing method is still offered. A constant is withheld when the receiver declares a field of
 * that name.
 *
 * <p>A receiver that is not a {@link PsiExtensibleClass} — one whose own members cannot be read apart from its
 * augmented ones — receives nothing, because neither check can be made for it.
 *
 * <p>{@link ExtensionDeclarations#contributes(PsiMethod)} filters a method declaration on shape alone: it recognises
 * no contribution in a method that is not {@code public static} or that names a receiver both on the method and on
 * a parameter ({@code AW1313}), and {@link ExtensionDeclarations#contributesConstant(PsiField)} recognises no
 * constant in a field that is not {@code public static final} ({@code AW1314}). Several declarations the build
 * refuses pass that filter and reach this class regardless, and are offered as if they were valid: a contributed
 * method with its own type parameters ({@code AW1310}), a receiver written as a parameterised type, which
 * {@link ExtensionDeclarations#receiverOf(PsiMethod)} resolves to its raw class ({@code AW1311}), a class-level
 * {@code @Extension} holder whose method takes something other than the receiver first ({@code AW1316}), and a
 * constant colliding with a field the receiver inherits rather than declares itself, since {@link #declaresField}
 * reads only the receiver's own fields ({@code AW1305}).
 *
 * <h2>Indexing and recursion</h2>
 *
 * <p>Both holder lookups answer with an empty list while the project is being indexed. Working out the contributed
 * methods of a class can ask for the members of that same class again, so the computation in progress is recorded per
 * thread and a re-entrant request is answered empty rather than allowed to recur; a class with no qualified name is
 * answered the same way. For the same reason the receiver's own members are read through
 * {@code PsiExtensibleClass}, never through {@code getMethods()} or {@code findFieldByName}, both of which would run
 * augmentation again.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionAugmentProvider extends PsiAugmentProvider {

    /** The qualified names whose contributions are being computed on this thread, as a recursion guard. */
    private static final ThreadLocal<Set<String>> COMPUTING =
            ThreadLocal.withInitial(HashSet::new);

    /** Creates the provider; the platform instantiates it once from the extension point. */
    public ExtensionAugmentProvider() {
        // Stateless; everything is cached on the receiver.
    }

    /**
     * Returns the members contributed to {@code element}, or an empty list when nothing is.
     *
     * <p>Answers only for exactly {@link PsiMethod} and {@link PsiField}; any other request is empty. A method
     * request is filtered by {@code nameHint} when the platform gives one and is cached on the receiver until the
     * next PSI modification. A field request is neither filtered nor cached: what the cache would hold is an empty
     * list for very nearly every class in the project.
     *
     * <p>The index is consulted before the receiver is tested for {@code @Extension}. That test resolves an
     * annotation reference, which is a stub-index query, and this method runs for every class the platform touches,
     * including from inside its unsaved-document indexing, where resolution is not allowed at all.
     *
     * @param <Psi>    the kind of member the platform is asking for
     * @param element  the element being augmented; must not be {@code null}
     * @param type     the kind of member requested; must not be {@code null}
     * @param nameHint the name being looked for, or {@code null} to ask for all of them
     * @return the contributed members, or an empty list when {@code element} is not a class, the requested kind is
     *         neither a method nor a field, nothing contributes to the class, or a method was asked for and the class
     *         is itself an extension holder
     */
    @Override
    @NotNull
    protected <Psi extends PsiElement> List<Psi> getAugments(@NotNull final PsiElement element,
                                                             @NotNull final Class<Psi> type,
                                                             @Nullable final String nameHint) {
        if (!(element instanceof final PsiClass receiver)
                || (type != PsiMethod.class && type != PsiField.class)) {
            return List.of();
        }

        if (type == PsiField.class) {
            // Constants are far rarer than contributed methods and are asked for far less often,
            // so they take the same index gate and skip the cache: what it would hold is an empty
            // list for very nearly every class in the project.
            @SuppressWarnings("unchecked")
            final List<Psi> constants = (List<Psi>) (List<?>) constantsFor(receiver);
            return constants;
        }

        // The index lookup comes first, and the order is not cosmetic. isExtension() resolves an
        // annotation reference — a stub-index query — and this method runs for every class the
        // platform touches. Asking it first put a resolve in front of every one of them, and put
        // one several frames inside the platform's unsaved-document indexing, where resolution is
        // not allowed at all.
        final List<PsiMethod> contributed = contributedTo(receiver);
        if (contributed.isEmpty()) {
            return List.of();
        }

        // An extension class is not augmented with what it contributes: those methods are declared
        // in it already, and adding them back would report every one as a duplicate. Reached only
        // when something genuinely contributes to this class, which a holder rarely does.
        if (ExtensionDeclarations.isExtension(receiver)) {
            return List.of();
        }
        final List<Psi> matching = new ArrayList<>();
        for (final PsiMethod method : contributed) {
            if (nameHint == null || nameHint.equals(method.getName())) {
                matching.add(type.cast(method));
            }
        }
        return matching;
    }

    /**
     * Returns the contributed methods of one receiver, computed once and cached on it.
     *
     * <p>The cached value depends on {@link PsiModificationTracker#MODIFICATION_COUNT} rather than on the receiver's
     * own file: a contribution appears and disappears by editing the holder, which is another file entirely.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @return the contributed methods, or an empty list when the receiver has no qualified name or is already being
     *         computed further up this thread's stack
     */
    @NotNull
    private static List<PsiMethod> contributedTo(@NotNull final PsiClass receiver) {
        final String name = receiver.getQualifiedName();
        if (name == null || !COMPUTING.get().add(name)) {
            return List.of();
        }
        try {
            return CachedValuesManager.getCachedValue(receiver,
                    () -> CachedValueProvider.Result.create(
                            compute(receiver), PsiModificationTracker.MODIFICATION_COUNT));
        } finally {
            COMPUTING.get().remove(name);
        }
    }

    /**
     * Reports whether the receiver declares a field of the given name itself.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @param name     the field name to look for; must not be {@code null}
     * @return {@code true} when the receiver declares such a field, and also when its own fields cannot be read apart
     *         from its augmented ones, since a duplicate field is a hard error on otherwise correct source
     */
    private static boolean declaresField(@NotNull final PsiClass receiver,
                                         @NotNull final String name) {
        if (!(receiver instanceof final PsiExtensibleClass extensible)) {
            return true;
        }
        for (final PsiField own : extensible.getOwnFields()) {
            if (name.equals(own.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns every extension holder that might contribute to the receiver, from source and from a library.
     *
     * <p>A library holder is added only when no source holder is equivalent to it, so a project that builds a holder
     * and also has it on the classpath contributes each method once rather than twice.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @return the holders, those from source first and those from a library after them, or an empty list while the
     *         project is being indexed
     */
    @NotNull
    private static List<PsiClass> holdersFor(@NotNull final PsiClass receiver) {
        // Two places an extension can come from, and the receiver cannot tell them apart. Source
        // is the one hit while developing — extensions must be compiled before their callers, so
        // within one project they are another module, which IntelliJ resolves to source. A library
        // is what the same extensions become once they are published.
        final List<PsiClass> holders = new ArrayList<>(
                ExtensionReceiverIndex.contributingTo(receiver));
        for (final PsiClass fromLibrary : LibraryExtensions.contributingTo(receiver)) {
            // Through the platform's own equivalence, which is exactly what recognises a
            // source class and its compiled form as one class. A project that both builds a holder
            // and has it on the classpath would otherwise contribute every method twice, and a
            // duplicated method reads as an ambiguous call on code that is perfectly fine.
            if (holders.stream().noneMatch(
                    known -> receiver.getManager().areElementsEquivalent(known, fromLibrary))) {
                holders.add(fromLibrary);
            }
        }
        return holders;
    }

    /**
     * Builds the constants contributed to the receiver.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @return one {@link ContributedField} per constant whose declared receiver is this class and whose name the
     *         class does not already use for a field of its own, or an empty list when there is none
     */
    @NotNull
    private static List<PsiField> constantsFor(@NotNull final PsiClass receiver) {
        final List<PsiField> contributed = new ArrayList<>();
        for (final PsiClass holder : holdersFor(receiver)) {
            for (final PsiField declared : ExtensionDeclarations.constantsOf(holder)) {
                // The receiver's own fields, never findFieldByName: that runs augmentation,
                // and this *is* augmentation — asking a class for its fields while working out
                // what fields it has is the same loop the indexer was taught not to close.
                if (receiver.getManager().areElementsEquivalent(
                        ExtensionDeclarations.receiverOf(declared), receiver)
                        && !declaresField(receiver, declared.getName())) {
                    contributed.add(new ContributedField(receiver, declared));
                }
            }
        }
        return contributed;
    }

    /**
     * Builds the methods contributed to the receiver, without consulting the cache.
     *
     * <p>The index answers by simple name and is therefore a superset. Resolving the declared receiver type and
     * comparing it with this class is what decides, and what stops an extension written for a different class of the
     * same name. A declaration whose receiver type does not resolve to a class contributes nothing.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @return one {@link ContributedMethod} per contribution that names this class and does not collide with a method
     *         it declares itself, or an empty list when no holder contributes to it
     */
    @NotNull
    private static List<PsiMethod> compute(@NotNull final PsiClass receiver) {
        // Two places an extension can come from, and the receiver cannot tell them apart. Source
        // is the one hit while developing — extensions must be compiled before their callers, so
        // within one project they are another module, which IntelliJ resolves to source. A library
        // is what the same extensions become once they are published.
        final List<PsiClass> holders = holdersFor(receiver);
        if (holders.isEmpty()) {
            return List.of();
        }

        final List<PsiMethod> contributed = new ArrayList<>();
        for (final PsiClass holder : holders) {
            for (final PsiMethod declared : ExtensionDeclarations.contributedBy(holder)) {
                // The index answered by simple name, which is a superset. This is where the real
                // type decides, and where an extension on somebody else's `String` stops.
                final PsiClass target = ExtensionDeclarations.receiverOf(declared);
                if (target == null
                        || !receiver.getManager().areElementsEquivalent(target, receiver)) {
                    continue;
                }
                if (alreadyDeclared(receiver, declared)) {
                    continue;
                }
                contributed.add(build(receiver, declared));
            }
        }
        return List.copyOf(contributed);
    }

    /**
     * Reports whether the receiver already declares the method a contribution would add.
     *
     * <p>The signatures are compared as they appear at the call site: for an instance contribution the receiver
     * parameter is skipped, for a static one every parameter counts. Names alone would not do — a contribution that
     * only overloads an existing method is valid and would be lost.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @param declared the contribution as the holder declares it; must not be {@code null}
     * @return {@code true} when the receiver declares a method of that name and those parameter types, and also when
     *         its own methods cannot be read apart from its augmented ones
     */
    private static boolean alreadyDeclared(@NotNull final PsiClass receiver,
                                           @NotNull final PsiMethod declared) {
        if (!(receiver instanceof final PsiExtensibleClass extensible)) {
            // Not a class whose own methods can be read apart from its augmented ones. Offering
            // nothing is the conservative answer: a duplicate member is a hard error on code that
            // is otherwise correct.
            return true;
        }
        final PsiParameter[] contributed = declared.getParameterList().getParameters();
        final int from = ExtensionDeclarations.isStaticContribution(declared) ? 0 : 1;
        for (final PsiMethod candidate : extensible.getOwnMethods()) {
            if (candidate.getName().equals(declared.getName())
                    && sameParameters(candidate.getParameterList().getParameters(), contributed,
                    from)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares an existing parameter list with the tail of a contributed one.
     *
     * <p>Positions are compared by {@code equals} on the declared type, not by assignability.
     *
     * @param existing    the parameters of a method the receiver declares; must not be {@code null}
     * @param contributed the parameters of the contribution; must not be {@code null}
     * @param from        the first index of {@code contributed} that reaches the call site, {@code 1} for an instance
     *                    contribution and {@code 0} for a static one
     * @return {@code true} when both lists have the same length from {@code from} onwards and equal types at every
     *         position
     */
    private static boolean sameParameters(final PsiParameter @NotNull [] existing,
                                          final PsiParameter @NotNull [] contributed,
                                          final int from) {
        if (existing.length != contributed.length - from) {
            return false;
        }
        for (int i = 0; i < existing.length; i++) {
            if (!existing[i].getType().equals(contributed[i + from].getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the method as the call site sees it.
     *
     * <p>Always {@code public}, whatever the holder wrote, and {@code static} only for a static contribution. The
     * instance form is deliberately not static although the method it stands for is: the call is written on a value,
     * and a static method called that way is exactly the warning this feature exists to remove. The receiver
     * parameter is dropped from the instance form, because the call site does not write it.
     *
     * @param receiver the class being augmented; must not be {@code null}
     * @param declared the contribution as the holder declares it; must not be {@code null}
     * @return the light method to put on the receiver, carrying the declaration's return type
     */
    @NotNull
    private static PsiMethod build(@NotNull final PsiClass receiver,
                                   @NotNull final PsiMethod declared) {
        final ContributedMethod built = new ContributedMethod(receiver, declared);
        built.setMethodReturnType(declared.getReturnType());
        built.addModifier(PsiModifier.PUBLIC);

        final boolean contributedStatically = ExtensionDeclarations.isStaticContribution(declared);
        if (contributedStatically) {
            // Static here, and deliberately not static above. The call site writes
            // `BigDecimal.parse("1.00")` — a static call — and a method that did not say so would
            // be reported as an instance method referenced from a static context. The instance form
            // needs the exact opposite: it is a static method called on a value, and marking it
            // static would produce the very warning this feature exists to remove.
            built.addModifier(PsiModifier.STATIC);
        }

        final PsiParameter[] parameters = declared.getParameterList().getParameters();
        for (int i = contributedStatically ? 0 : 1; i < parameters.length; i++) {
            built.addParameter(parameters[i].getName(), parameters[i].getType());
        }
        return built;
    }
}
