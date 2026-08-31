package de.splatgames.aether.weaver.idea.selector;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@code method} selector of a weave's handler to the target methods it names.
 *
 * <p>Polyvariant because a selector is allowed to be ambiguous: a bare {@code "charge"} is how a
 * user says whichever {@code charge} there is, so it names every overload and the platform offers
 * the user all of them rather than picking one.
 *
 * <p>The range covers the member name and stops short of the signature, so that renaming the target
 * method rewrites {@code "charge(java.math.BigDecimal)"} into {@code "settle(java.math.BigDecimal)"}
 * instead of replacing the whole string. Losing the parameter list there would leave a selector that
 * still resolves and names something else.
 *
 * <p>A selector that does not parse contributes no reference at all, so a half-typed selector puts
 * no platform error over the editor. This reference is not soft, unlike
 * {@link PointTargetReference}: {@link #multiResolve(boolean)} answers empty for a selector that
 * parses but names no method, and it is
 * {@link de.splatgames.aether.weaver.idea.inspection.SelectorInspection} that reports such a
 * selector, as {@code AW1020} or {@code AW1021}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see SelectorReferenceContributor
 */
public final class SelectorReference extends PsiReferenceBase.Poly<PsiLiteralExpression> {

    /** The parsed selector, kept because resolution needs its name and its parameter list. */
    private final MethodSelector selector;

    /**
     * Creates a reference over the member name of a selector.
     *
     * @param literal  the selector literal
     * @param range    the range covering the member name, relative to the literal's text and
     *                 therefore counting the opening quote as position zero
     * @param selector the selector parsed from that literal
     */
    SelectorReference(@NotNull final PsiLiteralExpression literal,
                      @NotNull final TextRange range,
                      @NotNull final MethodSelector selector) {
        super(literal, range, false);
        this.selector = selector;
    }

    /**
     * Resolves to the methods of the weave's targets that the selector names.
     *
     * <p>The targets are read from the enclosing {@code @Weave} on every call rather than kept in
     * the reference, because a reference outlives the edit that changes the annotation. Inherited
     * methods count: a weave may name a method the target declares in a supertype.
     *
     * @param incompleteCode ignored; the selector is matched by name and arity either way
     * @return the methods named, empty when the literal is no longer inside a weave or no target
     *         declares such a method
     */
    @Override
    public ResolveResult @NotNull [] multiResolve(final boolean incompleteCode) {
        final PsiClass weave = WeaveDeclarations.enclosingWeave(getElement());
        if (weave == null) {
            return ResolveResult.EMPTY_ARRAY;
        }

        final List<ResolveResult> found = new ArrayList<>();
        for (final PsiClass target : WeaveDeclarations.targetsOf(weave)) {
            for (final PsiMethod candidate : target.findMethodsByName(this.selector.name(), true)) {
                if (matches(candidate)) {
                    found.add(new PsiElementResolveResult(candidate));
                }
            }
        }
        return found.toArray(ResolveResult.EMPTY_ARRAY);
    }

    /**
     * Reports whether a candidate is one the selector names.
     *
     * <p>A written parameter list is compared by count and not by type. Comparing types would mean
     * comparing the source form against an erasure, which is where a plugin and a compiler part
     * company; the cost is that two overloads of the same arity both answer.
     *
     * @param candidate the method to test
     * @return {@code true} when the selector wrote no parameter list, or wrote one of the
     *         candidate's arity
     */
    private boolean matches(@NotNull final PsiMethod candidate) {
        // A selector without parameters names every overload. That is the language's design, not a
        // gap: "charge" is how a user says "whichever charge there is".
        return this.selector.parameters()
                .map(parameters -> parameters.size()
                        == candidate.getParameterList().getParametersCount())
                .orElse(true);
    }

    /**
     * Returns the member name the selector names.
     *
     * @return the parsed name, without owner, parameter list or return type, whatever spelling the
     *         literal used
     */
    @Override
    @NotNull
    public String getCanonicalText() {
        return this.selector.name();
    }

    /**
     * Reports whether the given declaration is one this selector names.
     *
     * <p>Answered by resolving, so that Find Usages on a target method reaches the selectors that
     * name it and Rename rewrites them. The kind check in front is a cheap veto for the many
     * declarations the platform asks about that could never be the answer.
     *
     * @param element the declaration to test
     * @return {@code true} when resolution yields a method the project manager considers equivalent
     *         to {@code element}
     */
    @Override
    public boolean isReferenceTo(@NotNull final PsiElement element) {
        if (!(element instanceof PsiMethod)) {
            return false;
        }
        for (final ResolveResult result : multiResolve(false)) {
            if (getElement().getManager().areElementsEquivalent(result.getElement(), element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the reference for a selector literal, when the literal holds a name to point at.
     *
     * <p>The caller has already established that this is a weave's {@code method} attribute; what is
     * decided here is whether the text can carry a reference at all.
     *
     * @param literal the literal to inspect
     * @return the reference, or {@code null} when the literal is not a constant string, is blank,
     *         does not parse, names a constructor or a static initialiser, or is a descriptor-form
     *         selector whose decoded name does not occur literally in the text
     */
    @Nullable
    static SelectorReference of(@NotNull final PsiLiteralExpression literal) {
        if (!(literal.getValue() instanceof final String text) || text.isBlank()) {
            return null;
        }
        final MemberSelector parsed;
        try {
            parsed = MemberSelector.parse(text);
        } catch (final RuntimeException malformed) {
            return null;
        }
        if (!(parsed instanceof final MethodSelector method) || method.isInitialiser()) {
            // A constructor or a static initialiser has no name a user would navigate to.
            return null;
        }

        final int start = text.indexOf(method.name());
        if (start < 0) {
            // The name is not literally in the text — a descriptor form whose name was decoded, or a
            // wildcard. Navigation for those comes with the descriptor work.
            return null;
        }
        // +1 for the opening quote: ranges are relative to the literal element, not to its value.
        return new SelectorReference(literal,
                TextRange.from(start + 1, method.name().length()), method);
    }
}
