package de.splatgames.aether.weaver.idea.augment;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A method an extension holder declares, standing on the class the declaration names as its receiver.
 *
 * <p>{@link ExtensionAugmentProvider} builds one of these for each contributed method it finds for a class, so that a
 * call written on the receiver resolves in the editor rather than being reported as an unknown method. The
 * constructor settles only the name, the containing class and where navigation leads; the return type, the parameters
 * and the modifiers are put on afterwards by the provider, because they are not the declaration's own — an instance
 * contribution loses its receiver parameter and is not static, a static one keeps every parameter and is.
 *
 * <p>The documentation comment, the deprecation and equivalence come from the declaration, so that hovering a
 * contributed method shows what its author wrote and the platform treats the two as one element.
 *
 * <p>Being a type of its own is what lets a contributed method be told apart from any other light method:
 * {@link de.splatgames.aether.weaver.idea.completion.ContributedMethodCompletionContributor} recognises one in a
 * lookup element and appends the holder's name to the completion entry.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ContributedMethod extends LightMethodBuilder {

    /** The extension holder's method this one stands for. */
    private final PsiMethod implementation;

    /**
     * Builds a stand-in for one contributed method, carrying its name and nothing else of its signature.
     *
     * @param receiver       the class the method is contributed to; must not be {@code null}
     * @param implementation the method as the extension holder declares it; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ContributedMethod(@NotNull final PsiClass receiver,
                             @NotNull final PsiMethod implementation) {
        super(Objects.requireNonNull(receiver, "receiver").getManager(), JavaLanguage.INSTANCE,
                Objects.requireNonNull(implementation, "implementation").getName());
        this.implementation = implementation;
        setContainingClass(receiver);
        setNavigationElement(implementation);
    }

    /**
     * Returns the declaration this method stands for.
     *
     * @return the method as the extension holder declares it; its containing class is ordinarily the holder, but is
     *         a supertype of it when the holder is not a {@link com.intellij.psi.impl.source.PsiExtensibleClass} and
     *         the declaration was found by walking inherited members instead
     */
    @Contract(pure = true)
    @NotNull
    public PsiMethod implementation() {
        return this.implementation;
    }

    /**
     * Returns the declaration's documentation comment.
     *
     * @return the comment written on the extension holder's method, or {@code null} when it has none
     */
    @Override
    public PsiDocComment getDocComment() {
        return this.implementation.getDocComment();
    }

    /**
     * Reports whether the declaration is deprecated.
     *
     * @return {@code true} when the extension holder's method is deprecated
     */
    @Override
    public boolean isDeprecated() {
        return this.implementation.isDeprecated();
    }

    /**
     * Reports this method and the declaration it stands for as one element.
     *
     * @param another the element to compare against
     * @return {@code true} when {@code another} is the declaration, or is equivalent to this light method in its own
     *         right
     */
    @Override
    public boolean isEquivalentTo(final PsiElement another) {
        return getManager().areElementsEquivalent(this.implementation, another)
                || super.isEquivalentTo(another);
    }
}
