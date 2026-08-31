package de.splatgames.aether.weaver.idea.selector;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.engine.parse.PointTargets;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.psi.PointDeclarations;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offers the operations an {@code @At} target could name, while it is being typed.
 *
 * <p>Two sources answer, and they answer different questions. The compiled body of the woven method
 * knows which operations are actually there and how many times each occurs, and is available only
 * once the target has been built. The class the target already names knows its own members, and is
 * available always. Both feed one map keyed by the complete target text, filled in that order and
 * with {@code putIfAbsent}, so where the two produce the same string the compiled entry keeps its
 * occurrence count.
 *
 * <p>An entry is a whole target rather than the fragment under the caret, so the prefix matcher is
 * replaced by everything written between the opening quote and the caret. With the platform's own
 * matcher, inserting {@code Ledger.commit} over a caret sitting after {@code Ledger.com} would
 * produce {@code Ledger.Ledger.commit}.
 *
 * <p>Declared in {@code plugin.xml} as a {@code completion.contributor} for the Java language,
 * alongside {@link SelectorCompletionContributor}: both register the same coarse pattern and each
 * decides for itself whether the literal is one of its own.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.idea.bytecode.TargetOperations
 */
public final class PointTargetCompletionContributor extends CompletionContributor {

    /** Registers the provider for basic completion; the platform requires a no-argument constructor. */
    public PointTargetCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement().withParent(
                        PlatformPatterns.psiElement(PsiLiteralExpression.class)
                                .withParent(PsiNameValuePair.class)),
                new PointTargetCompletionProvider());
    }

    /**
     * Produces the entries for one {@code @At} target literal.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class PointTargetCompletionProvider
            extends CompletionProvider<CompletionParameters> {

        /** Creates the provider; it holds no state and is created once by the contributor. */
        PointTargetCompletionProvider() {
            // Stateless.
        }

        /**
         * Adds every target the two sources can name for this point.
         *
         * <p>Adds nothing unless the literal is the {@code target} of an {@code @At}, which is
         * established from the literal's own two enclosing nodes rather than from a tree walk.
         *
         * @param parameters the platform's completion parameters, carrying the position and the
         *                   caret offset
         * @param context    the platform's processing context, unused
         * @param result     the result set the entries are added to, after its prefix matcher has
         *                   been widened to the whole written target
         */
        @Override
        protected void addCompletions(@NotNull final CompletionParameters parameters,
                                      @NotNull final ProcessingContext context,
                                      @NotNull final CompletionResultSet result) {
            if (!(parameters.getPosition().getParent() instanceof final PsiLiteralExpression literal)) {
                return;
            }
            final PsiAnnotation at = PointDeclarations.atOf(literal);
            if (at == null) {
                return;
            }
            final String point = PointDeclarations.pointOf(at);
            final CompletionResultSet entries =
                    result.withPrefixMatcher(writtenBefore(literal, parameters.getOffset()));

            final Map<String, LookupElementBuilder> offered = new LinkedHashMap<>();
            fromCompiledTargets(at, point, offered);
            fromNamedOwner(literal, point, offered);
            offered.values().forEach(entries::addElement);
        }

        /**
         * Adds the operations found in the compiled bodies of the methods the injection selects.
         *
         * <p>What was verified against the class file is the pair of target and ordinal together, by
         * {@link TargetOperations}: that pair resolves to exactly the instruction it was derived
         * from. The bare target string offered by {@link #add} is not unique on its own whenever an
         * ordinal was needed, which is exactly the case its tail text calls out. Nothing is offered
         * where the bytes are missing — no build yet, unsaved changes, or a class file older than its
         * source — and nothing where the point is a custom one, which names something only the
         * engine's own resolver enumerates.
         *
         * <p>Both the qualified and the simple spelling of every operation are offered, and counted
         * separately, because the ordinals are counted separately: an owner written as a simple name
         * matches by suffix and can select a wider set than the qualified form of the same call.
         *
         * @param at      the {@code @At} whose target is being completed
         * @param point   the point written in that {@code @At}: a constant's name, a custom point's
         *                text, or {@code HEAD} where none was written
         * @param offered the map entries are added to, keyed by the complete target text
         */
        private static void fromCompiledTargets(@NotNull final PsiAnnotation at,
                                                @NotNull final String point,
                                                @NotNull final Map<String, LookupElementBuilder> offered) {
            final Point known = pointOrNull(point);
            if (known == null) {
                return;
            }
            for (final PsiMethod target : targetMethodsOf(at)) {
                final MethodView compiled = compiledMethodOf(target);
                if (compiled == null) {
                    continue;
                }
                // Counted per spelling, because the ordinals are: a simple-name owner matches by
                // suffix and can select a wider set than a qualified one.
                for (final TargetOperations.Spelling spelling : new TargetOperations.Spelling[]{
                        TargetOperations.Spelling.QUALIFIED, TargetOperations.Spelling.SIMPLE}) {
                    add(TargetOperations.of(compiled, known, spelling), target, offered);
                }
            }
        }

        /**
         * Turns the operations of one method into entries, one per distinct target.
         *
         * <p>The occurrence count is the half of this that a user cannot work out for themselves. A
         * target matched by several instructions selects all of them unless an ordinal narrows it,
         * so an entry offered without that count is how an author ends up with a handler that fires
         * three times.
         *
         * @param operations the operations enumerated for one method and one spelling
         * @param target     the woven method they were found in, shown as the entry's type text
         * @param offered    the map entries are added to; an existing entry for the same target text
         *                   is kept
         */
        private static void add(@NotNull final List<TargetOperations.Operation> operations,
                                @NotNull final PsiMethod target,
                                @NotNull final Map<String, LookupElementBuilder> offered) {
            final Map<String, Integer> counted = new LinkedHashMap<>();
            for (final TargetOperations.Operation operation : operations) {
                counted.merge(operation.target(), 1, Integer::sum);
            }
            for (final Map.Entry<String, Integer> entry : counted.entrySet()) {
                final int occurrences = entry.getValue();
                offered.putIfAbsent(entry.getKey(), LookupElementBuilder.create(entry.getKey())
                        // The count is the useful half. One occurrence needs no ordinal; several
                        // mean the annotation names all of them unless one is picked, and a user who
                        // does not know that writes a weave that fires three times.
                        .withTailText(occurrences == 1
                                ? " (once)"
                                : " (" + occurrences + " occurrences — needs an ordinal)", true)
                        .withTypeText(target.getName()));
            }
        }

        /**
         * Adds the members of the class the half-written target already names.
         *
         * <p>This is the source that survives an unbuilt project: once {@code Ledger.} has been
         * typed, the members of {@code Ledger} are the only things that can follow, whatever the
         * build state is. Inherited members are included; constructors are not, because
         * {@link PsiMethod#getName()} answers a constructor's simple class name, and offering one
         * would spell a target as {@code Ledger.Ledger}. This has no bearing on {@code Point.NEW},
         * which {@link PointTargets#selectorKindFor} already refuses before this method is reached: a
         * constructor call under {@code INVOKE} or {@code INVOKE_AFTER} is a different point and is
         * still offered by {@link #fromCompiledTargets}.
         *
         * <p>Requires the owner to resolve to exactly one class. Two candidates mean the entries
         * would be a mixture of two unrelated classes' members, which is worse than none.
         *
         * @param literal the target literal as written so far
         * @param point   the point written in the enclosing {@code @At}; one that names no member
         *                kind contributes nothing
         * @param offered the map entries are added to, keyed by the complete target text
         */
        private static void fromNamedOwner(@NotNull final PsiLiteralExpression literal,
                                           @NotNull final String point,
                                           @NotNull final Map<String, LookupElementBuilder> offered) {
            final MemberKind kind = PointTargets.selectorKindFor(point);
            if (kind == null || !(literal.getValue() instanceof final String text)
                    || text.isBlank()) {
                return;
            }
            final MemberSelector selector;
            try {
                selector = MemberSelector.parse(text, kind);
            } catch (final RuntimeException malformed) {
                return;
            }
            final List<PsiClass> owners = PointDeclarations.ownersOf(selector, literal);
            if (owners.size() != 1) {
                return;
            }
            final PsiClass owner = owners.getFirst();
            final String prefix = owner.getQualifiedName() == null
                    ? owner.getName() + '.'
                    : written(text, owner);
            if (kind == MemberKind.FIELD) {
                for (final PsiField field : owner.getAllFields()) {
                    offered.putIfAbsent(prefix + field.getName(),
                            LookupElementBuilder.create(prefix + field.getName())
                                    .withTypeText(owner.getName()));
                }
                return;
            }
            for (final PsiMethod method : owner.getAllMethods()) {
                if (method.isConstructor()) {
                    continue;
                }
                offered.putIfAbsent(prefix + method.getName(),
                        LookupElementBuilder.create(prefix + method.getName())
                                .withTypeText(owner.getName()));
            }
        }

        /**
         * Returns the owner exactly as the author wrote it, so that completing does not respell it.
         *
         * <p>The written prefix is kept rather than rebuilt from the resolved class: a target
         * written with a simple name stays simple, and a descriptor form keeps its slashes.
         *
         * @param text  the target as written so far
         * @param owner the class the target names
         * @return the text up to and including the last {@code .} or {@code /}, or the owner's
         *         simple name and a dot when neither separator was written
         */
        @NotNull
        private static String written(@NotNull final String text, @NotNull final PsiClass owner) {
            final int dot = Math.max(text.lastIndexOf('.'), text.lastIndexOf('/'));
            return dot < 0 ? owner.getName() + '.' : text.substring(0, dot + 1);
        }

        /**
         * Returns the methods whose bodies this {@code @At} looks inside.
         *
         * <p>Those are the methods of the weave's targets that the enclosing injection's selector
         * names — a bare selector names several, and all of them are searched.
         *
         * @param at the {@code @At} whose target is being completed
         * @return the woven methods, empty when the literal is not inside a weave or the injection
         *         carries no usable selector
         */
        @NotNull
        private static List<PsiMethod> targetMethodsOf(@NotNull final PsiAnnotation at) {
            final PsiClass weave = WeaveDeclarations.enclosingWeave(at);
            final String selector = selectorOfInjection(at);
            if (weave == null || selector == null || selector.isBlank()) {
                return List.of();
            }
            final List<PsiMethod> found = new ArrayList<>(2);
            for (final PsiClass target : WeaveDeclarations.targetsOf(weave)) {
                for (final PsiMethod candidate : TargetMembers.ownMethodsOf(target)) {
                    if (!candidate.isConstructor()
                            && SelectorTargets.namesMethod(selector, candidate)) {
                        found.add(candidate);
                    }
                }
            }
            return found;
        }

        /**
         * Returns the selector of the {@code @Inject} or {@code @Redirect} this {@code @At} belongs
         * to.
         *
         * <p>Walked outwards rather than read from a fixed parent, because an {@code @At} sits
         * inside its injection's attribute list and may sit inside an array initialiser as well. The
         * walk stops at the first injection annotation, so a nested {@code @Slice} or a second
         * {@code @At} on the way out is skipped rather than mistaken for one.
         *
         * @param at the {@code @At} to start from
         * @return the selector text, or {@code null} when no injection encloses it or its
         *         {@code method} is not a constant string
         */
        @Nullable
        private static String selectorOfInjection(@NotNull final PsiAnnotation at) {
            for (PsiAnnotation enclosing = PsiTreeUtil.getParentOfType(at, PsiAnnotation.class);
                 enclosing != null;
                 enclosing = PsiTreeUtil.getParentOfType(enclosing, PsiAnnotation.class)) {
                final String qualified = enclosing.getQualifiedName();
                if (!WeaveDeclarations.INJECT.equals(qualified)
                        && !WeaveDeclarations.REDIRECT.equals(qualified)) {
                    continue;
                }
                return enclosing.findAttributeValue(WeaveDeclarations.METHOD_ATTRIBUTE)
                        instanceof final PsiLiteralExpression written
                        && written.getValue() instanceof final String text
                        ? text
                        : null;
            }
            return null;
        }

        /**
         * Finds the compiled form of a woven method.
         *
         * <p>Matched on name and descriptor together: two overloads share a name and hold entirely
         * different instructions, and offering one's operations for the other would name positions
         * that are not there.
         *
         * @param target the method in the editor
         * @return the compiled method, or {@code null} when the class has no usable class file or
         *         holds no method with that name and descriptor
         */
        @Nullable
        private static MethodView compiledMethodOf(@NotNull final PsiMethod target) {
            final PsiClass owner = target.getContainingClass();
            if (owner == null) {
                return null;
            }
            final CompiledClasses.Lookup lookup = CompiledClasses.of(owner);
            if (!lookup.isAvailable()) {
                return null;
            }
            // Name and descriptor together: two overloads hold entirely different instructions.
            final String descriptor = com.intellij.psi.util.ClassUtil.getAsmMethodSignature(target);
            for (final MethodView candidate : lookup.view().methods()) {
                if (candidate.name().equals(target.getName())
                        && candidate.type().descriptorString().equals(descriptor)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Resolves a written point to its constant.
         *
         * @param point the point name, as {@code PointDeclarations} read it out of the annotation
         * @return the matching constant, or {@code null} for a custom point, which names something
         *         only the engine's own resolver understands
         */
        @Nullable
        private static Point pointOrNull(@NotNull final String point) {
            for (final Point candidate : Point.values()) {
                if (candidate.name().equals(point)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Returns the part of the target the author has already typed.
         *
         * <p>Taken from the literal's text rather than its value, so the offset counts the opening
         * quote; an escape sequence therefore counts as the characters it is written with, which is
         * what the caret sits among.
         *
         * @param literal the target literal
         * @param caret   the absolute caret offset
         * @return the text between the opening quote and the caret, empty when the caret is at or
         *         before the opening quote or beyond the literal's end offset; at the end offset
         *         itself it is the whole literal minus the opening quote, closing quote included
         */
        @NotNull
        private static String writtenBefore(@NotNull final PsiLiteralExpression literal,
                                            final int caret) {
            final String text = literal.getText();
            // +1 for the opening quote, which is part of the element's text and not of its value.
            final int relative = caret - literal.getTextRange().getStartOffset();
            return relative < 1 || relative > text.length() ? "" : text.substring(1, relative);
        }
    }
}
