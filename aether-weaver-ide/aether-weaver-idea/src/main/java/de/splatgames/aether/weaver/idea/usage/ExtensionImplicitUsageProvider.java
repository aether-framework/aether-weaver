package de.splatgames.aether.weaver.idea.usage;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps the unused-declaration inspection off extension holders and the members they contribute.
 *
 * <p>An extension is published for other code to call, and a holder that is a library's whole
 * purpose has none of its call sites in the project that declares it. The inspection would report
 * every one of those members as dead, which in an IDE is an invitation to delete them.
 *
 * <p>The claim is narrow on purpose: only the class itself and the members that actually contribute
 * something. A private helper in the same class stays reportable, because that is the only warning
 * an extension holder gets about the dead code it accumulates like any other class.
 *
 * <p>Declared in {@code plugin.xml} as an {@code implicitUsageProvider}, so it is asked about every
 * declaration the inspection examines.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaveImplicitUsageProvider
 */
public final class ExtensionImplicitUsageProvider implements ImplicitUsageProvider {

    /** Creates the provider; the platform requires a no-argument constructor. */
    public ExtensionImplicitUsageProvider() {
        // Stateless.
    }

    /**
     * Reports whether the framework uses this declaration without any code naming it.
     *
     * <p>A method is judged from its declaring class first, which is both the correct rule — a
     * {@code @Receiver} means nothing outside an {@code @Extension} class — and the one that costs
     * least in a method the inspection calls for every declaration it visits.
     *
     * @param element the declaration the inspection is examining
     * @return {@code true} for a class carrying {@code @Extension} and for a contributing method
     *         inside one; {@code false} for anything else, a contributed constant included, since
     *         only classes and methods are claimed here
     */
    @Override
    public boolean isImplicitUsage(@NotNull final PsiElement element) {
        if (element instanceof final PsiClass candidate) {
            return ExtensionDeclarations.isExtension(candidate);
        }
        if (element instanceof final PsiMethod method) {
            final PsiClass holder = method.getContainingClass();
            // Gated on the enclosing class, which is both the correct rule — @Receiver means
            // nothing outside an @Extension class — and the cheap one: a walk to the containing
            // class before anything is resolved.
            return holder != null
                    && ExtensionDeclarations.isExtension(holder)
                    && ExtensionDeclarations.contributes(method);
        }
        return false;
    }

    /**
     * Claims no field read.
     *
     * @param element the declaration the inspection is examining, ignored
     * @return {@code false} always
     */
    @Override
    public boolean isImplicitRead(@NotNull final PsiElement element) {
        return false;
    }

    /**
     * Claims no field write.
     *
     * @param element the declaration the inspection is examining, ignored
     * @return {@code false} always
     */
    @Override
    public boolean isImplicitWrite(@NotNull final PsiElement element) {
        return false;
    }
}
