package de.splatgames.aether.weaver.idea.selector;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import de.splatgames.aether.weaver.idea.psi.PointDeclarations;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the references that make a weave's annotation strings navigable.
 *
 * <p>One contributor for both kinds, because the platform pattern that can be registered is the same
 * for both: a string literal inside an annotation attribute. Which attribute it is cannot be
 * expressed cheaply as a pattern, so the provider decides that itself, in a fixed number of node
 * steps rather than a tree walk.
 *
 * <p>Declared in {@code plugin.xml} as a {@code psi.referenceContributor} for the Java language.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see SelectorReference
 * @see PointTargetReference
 */
public final class SelectorReferenceContributor extends PsiReferenceContributor {

    /** Creates the contributor; the platform requires a no-argument constructor. */
    public SelectorReferenceContributor() {
        // Stateless.
    }

    /**
     * Registers the provider for every string literal that is an annotation attribute value.
     *
     * <p>Called by the platform while it builds the reference registrar.
     *
     * @param registrar the registrar to add the provider to
     */
    @Override
    public void registerReferenceProviders(@NotNull final PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(PsiLiteralExpression.class)
                        .withParent(PlatformPatterns.psiElement(PsiNameValuePair.class)),
                new SelectorReferenceProvider());
    }

    /**
     * Decides which of the two references a given annotation string carries, if either.
     *
     * <p>The attribute name is the first discriminator and it is compared before anything is parsed
     * or resolved: this provider is asked about every string literal in every annotation in every
     * Java file the editor touches.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class SelectorReferenceProvider extends PsiReferenceProvider {

        /** Creates the provider; it holds no state and one instance serves the whole registrar. */
        SelectorReferenceProvider() {
            // Stateless.
        }

        /**
         * Returns the reference this literal carries.
         *
         * <p>A {@code target} is claimed on the strength of the enclosing {@code @At} alone, which
         * {@link de.splatgames.aether.weaver.idea.psi.PointDeclarations} establishes in two node
         * steps. A {@code method} is claimed only inside a class carrying {@code @Weave}: the
         * attribute name is one other frameworks use as well, and claiming theirs would break their
         * navigation.
         *
         * @param element the element the pattern matched
         * @param context the platform's processing context, unused
         * @return an array holding the one reference the literal carries, or an empty array when it
         *         carries none
         */
        @Override
        public PsiReference @NotNull [] getReferencesByElement(@NotNull final PsiElement element,
                                                              @NotNull final ProcessingContext context) {
            if (!(element instanceof final PsiLiteralExpression literal)) {
                return PsiReference.EMPTY_ARRAY;
            }
            // The name the pattern could not decide. A string comparison against an already-loaded
            // name is as cheap as the pattern condition would have been.
            if (!(literal.getParent() instanceof final PsiNameValuePair pair)) {
                return PsiReference.EMPTY_ARRAY;
            }
            if (PointDeclarations.TARGET_ATTRIBUTE.equals(pair.getName())) {
                // No enclosing-weave check. An @At is nested inside the @Inject that owns it, so
                // walking up to a class would work — but PointDeclarations.atOf already establishes
                // that this literal is the target of *our* @At, which is a stronger statement than
                // "somewhere inside a weave" and reached in two node steps instead of a tree walk.
                final PointTargetReference target = PointTargetReference.of(literal);
                return target == null
                        ? PsiReference.EMPTY_ARRAY
                        : new PsiReference[]{target};
            }
            if (!WeaveDeclarations.METHOD_ATTRIBUTE.equals(pair.getName())) {
                return PsiReference.EMPTY_ARRAY;
            }
            // Checked before parsing. `method` is an attribute name other annotations in other
            // frameworks also have; the enclosing @Weave is what says this one is ours, and it is a
            // cheap walk up a fixed number of nodes.
            if (WeaveDeclarations.enclosingWeave(literal) == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            final SelectorReference reference = SelectorReference.of(literal);
            return reference == null
                    ? PsiReference.EMPTY_ARRAY
                    : new PsiReference[]{reference};
        }
    }
}
