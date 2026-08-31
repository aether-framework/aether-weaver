package de.splatgames.aether.weaver.idea.selector;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiParameter;
import com.intellij.util.ProcessingContext;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offers the target's methods while a weave's {@code method} selector is being typed.
 *
 * <p>Declared in {@code plugin.xml} as a {@code completion.contributor} for the Java language. The
 * pattern it registers — a string literal that is an annotation attribute value — says nothing about
 * which annotation that is; everything deciding whether the literal belongs to a weave happens in
 * the provider, which runs only for literals the pattern already matched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PointTargetCompletionContributor
 */
public final class SelectorCompletionContributor extends CompletionContributor {

    /** Registers the provider for basic completion; the platform requires a no-argument constructor. */
    public SelectorCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement().withParent(
                        PlatformPatterns.psiElement(PsiLiteralExpression.class)
                                .withParent(PsiNameValuePair.class)),
                new SelectorCompletionProvider());
    }

    /**
     * Produces one entry per method a selector inside this weave could name.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class SelectorCompletionProvider
            extends CompletionProvider<CompletionParameters> {

        /** Creates the provider; it holds no state and is created once by the contributor. */
        SelectorCompletionProvider() {
            // Stateless.
        }

        /**
         * Adds the methods of every target of the enclosing weave.
         *
         * <p>Adds nothing unless the literal is the {@code method} attribute of an annotation inside
         * a class carrying {@code @Weave}: {@code method} is an attribute name other frameworks use,
         * and offering a target's members inside their annotation would be an intrusion.
         *
         * <p>Each entry is either a bare member name or a name followed by its parameter list, as
         * {@link #offer(PsiClass, CompletionResultSet)} builds it; the platform's own prefix matcher
         * still filters on the segment being typed, and a qualified selector completes on that
         * segment as well.
         *
         * @param parameters the platform's completion parameters, carrying the position and the
         *                   caret offset
         * @param context    the platform's processing context, unused
         * @param result     the result set the entries are added to
         */
        @Override
        protected void addCompletions(@NotNull final CompletionParameters parameters,
                                      @NotNull final ProcessingContext context,
                                      @NotNull final CompletionResultSet result) {
            final PsiElement position = parameters.getPosition();
            if (!(position.getParent() instanceof final PsiLiteralExpression literal)) {
                return;
            }
            if (!(literal.getParent() instanceof final PsiNameValuePair pair)
                    || !WeaveDeclarations.METHOD_ATTRIBUTE.equals(pair.getName())) {
                return;
            }
            final PsiClass weave = WeaveDeclarations.enclosingWeave(literal);
            if (weave == null) {
                return;
            }

            if (!inMemberName(literal, parameters.getOffset())) {
                return;
            }

            for (final PsiClass target : WeaveDeclarations.targetsOf(weave)) {
                offer(target, result);
            }
        }

        /**
         * Reports whether the caret is still in the member name of the selector.
         *
         * <p>An opening parenthesis before the caret means a parameter type is being written
         * rather than a member name, and the two have nothing in common to offer.
         *
         * @param literal the selector literal
         * @param caret   the absolute caret offset
         * @return {@code true} when the caret is inside the literal and no {@code (} precedes it
         */
        private static boolean inMemberName(@NotNull final PsiLiteralExpression literal,
                                            final int caret) {
            final int relative = caret - literal.getTextRange().getStartOffset();
            final String text = literal.getText();
            if (relative < 1 || relative > text.length()) {
                return false;
            }
            return text.lastIndexOf('(', relative - 1) < 0;
        }

        /**
         * Adds one entry per method name of a target, and one per overload where a name has several.
         *
         * <p>A unique name is offered on its own, with its signature as tail text only, because the
         * bare name is a legal selector and the shorter one. An overloaded name is offered once
         * bare, labelled with how many methods it would name, and again once per overload with the
         * parameter list written into the entry, so that narrowing to one of them is a decision the
         * author can make here rather than one they discover from a diagnostic.
         *
         * <p>{@code target.getMethods()} runs augmentation, so the list also carries the members
         * {@link de.splatgames.aether.weaver.idea.augment.WeaveAugmentProvider} merges into the
         * target — handlers, accessors and invokers of the target's own weaves — alongside what it
         * declares in source. What it excludes is inherited methods: a selector may also name one,
         * and {@link SelectorReference} resolves one, but a completion list carrying every method of
         * every supertype would be a list of {@link Object} methods with the target's own members
         * somewhere inside it.
         *
         * @param target the target class to offer the methods of
         * @param result the result set the entries are added to
         */
        private static void offer(@NotNull final PsiClass target,
                                  @NotNull final CompletionResultSet result) {
            // Grouped by name, because whether a name is overloaded decides what is worth offering
            // for it: a unique name needs no signature, an overloaded one needs every signature.
            final Map<String, List<PsiMethod>> byName = new LinkedHashMap<>();
            for (final PsiMethod method : target.getMethods()) {
                if (!method.isConstructor()) {
                    byName.computeIfAbsent(method.getName(), name -> new ArrayList<>()).add(method);
                }
            }

            for (final Map.Entry<String, List<PsiMethod>> entry : byName.entrySet()) {
                final List<PsiMethod> overloads = entry.getValue();
                final PsiMethod first = overloads.getFirst();
                result.addElement(LookupElementBuilder.create(first, entry.getKey())
                        .withTailText(overloads.size() == 1
                                ? signature(first)
                                : " (any of " + overloads.size() + " overloads)", true)
                        .withTypeText(target.getName()));
                if (overloads.size() > 1) {
                    for (final PsiMethod overload : overloads) {
                        result.addElement(LookupElementBuilder
                                .create(overload, entry.getKey() + signature(overload))
                                .withTypeText(target.getName()));
                    }
                }
            }
        }

        /**
         * Renders a method's parameter list the way a selector writes it.
         *
         * <p>Presentable types, so a parameter reads {@code BigDecimal} rather than
         * {@code java.math.BigDecimal}: the simple form is what the selector grammar accepts and
         * what a reader scanning a list of overloads can tell apart.
         *
         * @param method the method to render
         * @return the parameter list, parentheses included and empty for a method taking nothing
         */
        @NotNull
        private static String signature(@NotNull final PsiMethod method) {
            final StringBuilder rendered = new StringBuilder("(");
            final PsiParameter[] parameters = method.getParameterList().getParameters();
            for (int index = 0; index < parameters.length; index++) {
                if (index > 0) {
                    rendered.append(',');
                }
                rendered.append(parameters[index].getType().getPresentableText());
            }
            return rendered.append(')').toString();
        }
    }
}
