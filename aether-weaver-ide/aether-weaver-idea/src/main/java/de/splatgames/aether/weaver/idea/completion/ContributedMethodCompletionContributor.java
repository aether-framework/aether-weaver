package de.splatgames.aether.weaver.idea.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResult;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupElementRenderer;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import de.splatgames.aether.weaver.idea.augment.ContributedMethod;
import org.jetbrains.annotations.NotNull;

/**
 * Marks the completion rows that stand for extension methods with the class that declares them.
 *
 * <p>An extension method is offered on a type that does not declare it, and a row indistinguishable
 * from that type's own members hides both where the method comes from and that it needs the weaver
 * in the build. The row keeps everything the Java contributors gave it and gains a tail reading
 * {@code extension in} followed by the declaring class's simple name.
 *
 * <p>Registered with {@code order="first"}, which is load-bearing: this decorates what the
 * contributors after it produce, and a contributor that runs last has nothing left to decorate.
 * It therefore stands in front of every Java completion in the IDE, and every result it does not
 * recognise is passed through untouched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ContributedMethodCompletionContributor extends CompletionContributor {

    /** Holds no state, which is what lets one instance serve every completion in the IDE. */
    public ContributedMethodCompletionContributor() {
        // Stateless.
    }

    /**
     * Runs the remaining contributors and marks what they produce.
     *
     * <p>Nothing is added and nothing is dropped: every result is passed on, decorated or as it
     * came.
     *
     * @param parameters the completion being computed; must not be {@code null}
     * @param result     where results are passed; must not be {@code null}
     */
    @Override
    public void fillCompletionVariants(@NotNull final CompletionParameters parameters,
                                       @NotNull final CompletionResultSet result) {
        result.runRemainingContributors(parameters, produced -> result.passResult(marked(produced)));
    }

    /**
     * Decorates one result when it stands for an extension method.
     *
     * <p>Recognised by the element the row points at rather than by its text, so a method of the
     * receiver that happens to share a name with an extension is not marked.
     *
     * @param produced the result a later contributor produced; must not be {@code null}
     * @return the decorated result, or {@code produced} unchanged when its element is not a
     *         contributed method or the class declaring it has no name to show
     */
    @NotNull
    private static CompletionResult marked(@NotNull final CompletionResult produced) {
        final LookupElement element = produced.getLookupElement();
        final PsiElement declaration = element.getPsiElement();
        if (!(declaration instanceof final ContributedMethod contributed)) {
            return produced;
        }
        final PsiClass holder = contributed.implementation().getContainingClass();
        if (holder == null || holder.getName() == null) {
            return produced;
        }
        return produced.withLookupElement(
                LookupElementDecorator.withRenderer(element, renderer(holder.getName())));
    }

    /**
     * Builds a renderer that appends the declaring class to whatever the row already shows.
     *
     * <p>The delegate renders first and the tail is added to its result, so the row keeps the
     * signature, the icon and the type text the Java contributor chose. The tail is grey, which is
     * what distinguishes it from the part of the row that is code.
     *
     * @param holder the simple name of the class declaring the extension method; must not be
     *               {@code null}
     * @return a renderer that decorates the delegate's presentation
     */
    @NotNull
    private static LookupElementRenderer<LookupElementDecorator<LookupElement>> renderer(
            @NotNull final String holder) {
        return new LookupElementRenderer<>() {
            /**
             * Renders the delegate and appends the declaring class.
             *
             * @param element      the decorated row being rendered
             * @param presentation the presentation to fill in
             */
            @Override
            public void renderElement(final LookupElementDecorator<LookupElement> element,
                                      final LookupElementPresentation presentation) {
                element.getDelegate().renderElement(presentation);
                presentation.appendTailText("  extension in " + holder, true);
            }
        };
    }
}
