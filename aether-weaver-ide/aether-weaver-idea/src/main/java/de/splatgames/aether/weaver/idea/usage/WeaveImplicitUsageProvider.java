package de.splatgames.aether.weaver.idea.usage;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps the unused-declaration inspection off the parts of a weave that only woven code reaches.
 *
 * <p>Nothing in the editor names a weave class, calls a handler, or assigns a shadow field: the
 * code that does is produced at build time. Left alone, the inspection greys out a correct weave
 * from top to bottom, which is how an IDE says delete this.
 *
 * <p>Every claim about a member is gated on its declaring class carrying {@code @Weave}. The
 * annotations have names other frameworks use as well, and a plugin that claimed them anywhere
 * would silence the warning on somebody else's genuinely dead method.
 *
 * <p>What is deliberately not claimed matters as much. An ordinary private method of a weave is
 * still reported, and so is a {@code @Shadow} method nothing calls — a shadow declaration exists to
 * be called from the weave, so one that is not is dead in the ordinary sense. A {@code @Shadow}
 * field is claimed as read and written but never as used, so a field the weave never mentions at all
 * is still reported.
 *
 * <p>Declared in {@code plugin.xml} as an {@code implicitUsageProvider}, so it is asked about every
 * declaration the inspection examines.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see ExtensionImplicitUsageProvider
 */
public final class WeaveImplicitUsageProvider implements ImplicitUsageProvider {

    /** Creates the provider; the platform requires a no-argument constructor. */
    public WeaveImplicitUsageProvider() {
        // Stateless.
    }

    /**
     * Reports whether the framework uses this declaration without any code naming it.
     *
     * <p>The parameters of an entry point are claimed as well: the list is shaped by the binding
     * contract rather than by the body, so using only some of them is ordinary. A parameter that
     * does not belong there at all is reported by the processor as {@code AW1040} instead.
     *
     * @param element the declaration the inspection is examining
     * @return {@code true} for a class carrying {@code @Weave}, for an entry point of such a class,
     *         and for the parameters of one; {@code false} otherwise
     */
    @Override
    public boolean isImplicitUsage(@NotNull final PsiElement element) {
        if (element instanceof final PsiClass candidate) {
            return WeaveDeclarations.annotation(candidate, WeaveDeclarations.WEAVE) != null;
        }
        if (element instanceof final PsiMethod method) {
            return isFrameworkEntryPoint(method);
        }
        // A handler's parameter list is dictated by the binding contract, not by the body: it holds
        // the target's parameters and, for a non-void target, the callback. Using only some of them
        // is ordinary. A parameter that does not belong there at all is a different problem, and one
        // the processor reports as AW1040 — a far stronger signal than grey text.
        if (element instanceof final PsiParameter parameter) {
            return parameter.getDeclarationScope() instanceof final PsiMethod method
                    && isFrameworkEntryPoint(method);
        }
        return false;
    }

    /**
     * Reports whether the framework reads this field without any code reading it.
     *
     * <p>A {@code @Shadow} field stands for a field of the target and is assigned by the woven
     * class, so a weave that only reads it must not be told the field is never assigned.
     *
     * @param element the declaration the inspection is examining
     * @return {@code true} for a {@code @Shadow} field of a weave
     */
    @Override
    public boolean isImplicitRead(@NotNull final PsiElement element) {
        return isShadowField(element);
    }

    /**
     * Reports whether the framework writes this field without any code writing it.
     *
     * <p>The mirror of the read: a weave that only assigns a shadow field must not be told the value
     * is never used, since the target reads it.
     *
     * @param element the declaration the inspection is examining
     * @return {@code true} for a {@code @Shadow} field of a weave
     */
    @Override
    public boolean isImplicitWrite(@NotNull final PsiElement element) {
        return isShadowField(element);
    }

    /**
     * Reports whether a method is one the framework calls or generates a body for.
     *
     * <p>The four annotations are what the build reaches: a handler is called from the woven target,
     * and an accessor or an invoker is a declaration whose body is not used, with the method it
     * describes emitted onto the target. Neither is called by anything the editor can see.
     *
     * @param method the method to test
     * @return {@code true} when the method is inside a weave and carries one of the four
     */
    private static boolean isFrameworkEntryPoint(@NotNull final PsiMethod method) {
        return isInWeave(method)
                && (has(method, WeaveDeclarations.INJECT)
                || has(method, WeaveDeclarations.REDIRECT)
                || has(method, WeaveDeclarations.ACCESSOR)
                || has(method, WeaveDeclarations.INVOKER));
    }

    /**
     * Reports whether an element is a weave's shadow field.
     *
     * @param element the declaration to test
     * @return {@code true} for a field inside a weave carrying {@code @Shadow}
     */
    private static boolean isShadowField(@NotNull final PsiElement element) {
        return element instanceof final PsiField field
                && isInWeave(field)
                && has(field, WeaveDeclarations.SHADOW);
    }

    /**
     * Reports whether a member is declared by a weave.
     *
     * <p>The gate in front of every claim this provider makes, and the reason a member of an
     * ordinary class carrying the same annotation keeps its warning.
     *
     * @param member the member to test
     * @return {@code true} when the containing class carries {@code @Weave}
     */
    private static boolean isInWeave(@NotNull final PsiMember member) {
        final PsiClass containing = member.getContainingClass();
        return containing != null
                && WeaveDeclarations.annotation(containing, WeaveDeclarations.WEAVE) != null;
    }

    /**
     * Reports whether a declaration carries an annotation.
     *
     * @param owner         the declaration to inspect
     * @param qualifiedName the qualified name of the annotation type
     * @return {@code true} when the annotation is present
     */
    private static boolean has(@NotNull final PsiModifierListOwner owner,
                               @NotNull final String qualifiedName) {
        return WeaveDeclarations.annotation(owner, qualifiedName) != null;
    }
}
