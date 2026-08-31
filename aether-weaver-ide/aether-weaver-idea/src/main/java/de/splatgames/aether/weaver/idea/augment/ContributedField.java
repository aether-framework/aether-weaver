package de.splatgames.aether.weaver.idea.augment;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A constant an extension holder declares, standing on the class the declaration names as its receiver.
 *
 * <p>{@link ExtensionAugmentProvider} builds one of these for each contributed constant it finds for a class, so that
 * a reference written on the receiver resolves in the editor rather than being reported as an unknown symbol. The
 * light field takes its name and its type from the declaration and is {@code public static final}.
 *
 * <p>Everything a synthesised member would otherwise have to invent is taken from the declaration instead: the
 * documentation comment, the deprecation, the element navigation lands on, and equivalence, which reports the two as
 * one element to the platform.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ContributedField extends LightFieldBuilder {

    /** The extension holder's field this one stands for. */
    private final PsiField implementation;

    /**
     * Builds a stand-in for one contributed constant.
     *
     * <p>The receiver becomes the containing class of the result and the declaration becomes its navigation target,
     * so that navigating the contributed constant leads to the holder rather than to nothing.
     *
     * @param receiver       the class the constant is contributed to; must not be {@code null}
     * @param implementation the constant as the extension holder declares it; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ContributedField(@NotNull final PsiClass receiver,
                            @NotNull final PsiField implementation) {
        super(Objects.requireNonNull(receiver, "receiver").getManager(),
                Objects.requireNonNull(implementation, "implementation").getName(),
                implementation.getType());
        this.implementation = implementation;
        setContainingClass(receiver);
        setNavigationElement(implementation);
        setModifiers(PsiModifier.PUBLIC, PsiModifier.STATIC, PsiModifier.FINAL);
    }

    /**
     * Returns the declaration this field stands for.
     *
     * @return the constant as the extension holder declares it; its containing class is ordinarily the holder, but
     *         is a supertype of it when the holder is not a {@link com.intellij.psi.impl.source.PsiExtensibleClass}
     *         and the declaration was found by walking inherited members instead
     */
    @Contract(pure = true)
    @NotNull
    public PsiField implementation() {
        return this.implementation;
    }

    /**
     * Returns the declaration's documentation comment.
     *
     * @return the comment written on the extension holder's constant, or {@code null} when it has none
     */
    @Override
    public PsiDocComment getDocComment() {
        return this.implementation.getDocComment();
    }

    /**
     * Reports whether the declaration is deprecated.
     *
     * @return {@code true} when the extension holder's constant is deprecated
     */
    @Override
    public boolean isDeprecated() {
        return this.implementation.isDeprecated();
    }

    /**
     * Reports this field and the declaration it stands for as one element.
     *
     * @param another the element to compare against
     * @return {@code true} when {@code another} is the declaration, or is equivalent to this light field in its own
     *         right
     */
    @Override
    public boolean isEquivalentTo(final PsiElement another) {
        return getManager().areElementsEquivalent(this.implementation, another)
                || super.isEquivalentTo(another);
    }
}
