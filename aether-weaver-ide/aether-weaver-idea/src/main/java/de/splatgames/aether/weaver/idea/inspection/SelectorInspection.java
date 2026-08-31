package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.SelectorSyntaxException;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports a {@code method} selector that does not bind to a method of the weave's targets.
 *
 * <p>Registered in {@code plugin.xml} under the short name {@code AetherWeaverSelector}, enabled by
 * default and at {@code ERROR} level.
 *
 * <h2>What is inspected</h2>
 *
 * <p>Any string literal written as the {@code method} attribute of any annotation inside a class
 * carrying {@code @Weave}. The attribute name and the enclosing weave are the whole filter: the
 * annotation the literal belongs to is not identified, so a {@code method} attribute of an
 * unrelated annotation written inside a weave class is inspected too, and the same attribute in a
 * class that carries no {@code @Weave} is not inspected at all. "Enclosing weave" is the nearest
 * enclosing class, so a {@code method} literal written inside a nested, local or anonymous class
 * declared within a {@code @Weave} class is not inspected unless that inner class itself carries
 * {@code @Weave}.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>The parser's own code where the text does not parse — {@code AW1015}, {@code AW1017},
 *       {@code AW1018} or {@code AW1019} — carrying the parser's message. Where the parser offers a
 *       corrected spelling, and that spelling itself parses, a fix applying it is offered; a pasted
 *       descriptor reported as {@code AW1017} is the case that has one.
 *   <li>{@code AW1020} where no target has a method of that name, declared or inherited, with a fix
 *       changing the name to the nearest one that exists.
 *   <li>{@code AW1020} where the selector names a parameter list and no method of that name on any
 *       target takes that many parameters. Only the count is compared, never the types.
 *   <li>{@code AW1021} where the selector is a bare name and the targets declare more than one
 *       method under it. Add the parameter types so that exactly one overload is named.
 * </ul>
 *
 * <h2>What silences it</h2>
 *
 * <p>A blank literal. A selector that parses to something other than a method, and a method
 * selector naming a constructor or the static initialiser. A weave none of whose targets resolve,
 * where there is nothing to compare against. A parsed name that does not occur as a substring of
 * the written text, where there is no range to underline. And a text the parser refuses with a
 * runtime exception that is not a {@link SelectorSyntaxException}, which carries no code to report
 * and is left to the build to explain.
 *
 * <p>The two searches are deliberately different. Whether a method of the name exists is asked of
 * each target and its supertypes, so a selector naming an inherited method is not reported as
 * missing. How many overloads exist is counted over each target's own declared methods only, and
 * excludes constructors: counting inherited methods, or the members this plugin's own augmentation
 * shows on a target, would report an ambiguity the build does not see.
 *
 * <p>A selector written as a bare {@code "*"} is reported {@code AW1020} even though the build
 * accepts it: a wildcard is a legal method name, but nothing on a target is ever named {@code *}, so
 * the existence search always comes back empty.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class SelectorInspection extends AbstractBaseJavaLocalInspectionTool {

    /** Holds no state: no instance field is declared. */
    public SelectorInspection() {
        // Stateless.
    }

    /**
     * Returns the visitor the platform drives over the file being analysed.
     *
     * @param holder     where problems are registered; must not be {@code null}
     * @param isOnTheFly whether the analysis runs in the editor rather than in a batch run; unused,
     *                   because the same problems are reported either way
     * @return a visitor over string literals
     */
    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                          final boolean isOnTheFly) {
        return new JavaElementVisitor() {
            /**
             * Inspects a literal that may be a selector.
             *
             * @param literal the literal being visited
             */
            @Override
            public void visitLiteralExpression(@NotNull final PsiLiteralExpression literal) {
                inspect(literal, holder);
            }
        };
    }

    /**
     * Decides whether a literal is a selector worth checking, and parses it.
     *
     * <p>Everything the class comment lists as silencing the inspection is decided here. A syntax
     * failure is reported from here with the parser's own code and message, so the code a user sees
     * in the editor is the code they would have seen in the build.
     *
     * @param literal the literal being visited; must not be {@code null}
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void inspect(@NotNull final PsiLiteralExpression literal,
                                @NotNull final ProblemsHolder holder) {
        if (!(literal.getParent() instanceof final PsiNameValuePair pair)
                || !WeaveDeclarations.METHOD_ATTRIBUTE.equals(pair.getName())) {
            return;
        }
        final PsiClass weave = WeaveDeclarations.enclosingWeave(literal);
        if (weave == null) {
            return;
        }
        if (!(literal.getValue() instanceof final String text) || text.isBlank()) {
            return;
        }

        final MemberSelector parsed;
        try {
            parsed = MemberSelector.parse(text);
        } catch (final SelectorSyntaxException malformed) {
            // The parser knows what the author meant far more often than a plugin could guess —
            // AW1017 arrives carrying the same selector with "desc:" in front of it. Where it says
            // so, that becomes a fix; where it does not, the message stands on its own.
            final ApplySelectorSuggestionFix fix = ApplySelectorSuggestionFix.of(literal,
                    malformed.suggestion().orElse(null));
            holder.registerProblem(literal,
                    malformed.code().code() + ": " + malformed.getMessage(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fix == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[]{fix});
            return;
        } catch (final RuntimeException unusable) {
            // Not a syntax failure this inspection understands; the build will say what it is.
            return;
        }

        if (!(parsed instanceof final MethodSelector method) || method.isInitialiser()) {
            return;
        }
        final int start = text.indexOf(method.name());
        if (start < 0) {
            // A wildcard or a decoded descriptor: the name is not literally here, so neither is a
            // range to underline, and matching it is the resolver's job.
            return;
        }

        final List<PsiClass> targets = WeaveDeclarations.targetsOf(weave);
        if (targets.isEmpty()) {
            // Nothing resolved to compare against. Silence is the only honest answer.
            return;
        }
        report(literal, method, targets, start, holder);
    }

    /**
     * Reports what a parsed method selector fails to bind to.
     *
     * <p>At most one problem is registered, and the three cases are exclusive: no method of the
     * name, then a bare name over several overloads, then a parameter count no overload takes.
     *
     * <p>The range underlined is the first occurrence of the name as a substring of the written
     * text, offset by one for the opening quote, because the range a problem carries is relative to
     * the literal element rather than to its value. A name that also occurs earlier as part of
     * something else — inside a package segment of a fully qualified selector, for instance — is
     * underlined, and would be rewritten, there instead of at the name's own position.
     *
     * <p>Where several targets are named, the message says how many rather than listing them, and
     * the methods of all of them are pooled: a selector binding on one target and not on another is
     * accepted.
     *
     * @param literal the literal the selector was written in; must not be {@code null}
     * @param method  the parsed selector; must not be {@code null}
     * @param targets the classes the weave names, at least one; must not be {@code null}
     * @param start   the offset of the name within the literal's value
     * @param holder  where the problem is registered; must not be {@code null}
     */
    private static void report(@NotNull final PsiLiteralExpression literal,
                               @NotNull final MethodSelector method,
                               @NotNull final List<PsiClass> targets,
                               final int start,
                               @NotNull final ProblemsHolder holder) {
        final List<PsiMethod> named = new ArrayList<>();
        for (final PsiClass target : targets) {
            named.addAll(List.of(target.findMethodsByName(method.name(), true)));
        }
        // +1 for the opening quote: the range is relative to the literal element, not its value.
        final TextRange range = TextRange.from(start + 1, method.name().length());
        final String where = targets.size() == 1
                ? targets.getFirst().getName()
                : targets.size() + " targets";

        if (named.isEmpty()) {
            holder.registerProblem(literal, range,
                    DiagnosticCode.METHOD_NOT_FOUND.code() + ": no method named '"
                            + method.name() + "' in " + where,
                    fixesFor(literal, method.name(), targets, range));
            return;
        }
        if (method.parameters().isEmpty()) {
            // A bare name is convenient right up to the moment the target gains an overload, and
            // then it is AW1021 rather than an arbitrary binding — the API says so under
            // Inject#method(), and HandlerChecks.resolve reports it. An earlier comment here read
            // "a bare name deliberately matches every overload; that is the language, not a defect",
            // which is true of the grammar and false of what the build does with it.
            //
            // Counted over the targets' *declared* methods, not over `named`. That list comes
            // from findMethodsByName(name, true), which walks superclasses — and runs this plugin's
            // own augmentation, so a weave's merged handlers would be counted as overloads of the
            // target's method. Either would report an ambiguity the build does not see.
            final int declared = declaredOverloads(targets, method.name());
            if (declared > 1) {
                holder.registerProblem(literal, range,
                        DiagnosticCode.SELECTOR_AMBIGUOUS.code() + ": '" + method.name()
                                + "' matches " + declared + " methods on " + where
                                + " — add the parameter types so that exactly one overload is named");
            }
            return;
        }

        final int wanted = method.parameters().orElseThrow().size();
        for (final PsiMethod candidate : named) {
            if (candidate.getParameterList().getParametersCount() == wanted) {
                return;
            }
        }
        holder.registerProblem(literal, range,
                DiagnosticCode.METHOD_NOT_FOUND.code() + ": '" + method.name() + "' in " + where
                        + " has no overload taking " + wanted + " parameter"
                        + (wanted == 1 ? "" : "s"));
    }

    /**
     * Counts the methods of a name that the targets declare themselves.
     *
     * <p>Constructors are skipped, and only the methods each class declares itself are read. That
     * is not the list the existence check uses: {@code findMethodsByName} walks supertypes and runs
     * this plugin's own augmentation, so counting over it would treat an inherited overload, or a
     * handler the plugin shows on the target, as an overload of the target's own method and report
     * an ambiguity the build does not see.
     *
     * <p>The counts of all targets are summed rather than taken per target, so two targets each
     * declaring one method of the name total two and are reported as ambiguous.
     *
     * @param targets the classes the weave names; must not be {@code null}
     * @param name    the method name to count; must not be {@code null}
     * @return how many methods of that name the targets declare between them
     */
    private static int declaredOverloads(@NotNull final List<PsiClass> targets,
                                         @NotNull final String name) {
        int found = 0;
        for (final PsiClass target : targets) {
            for (final PsiMethod candidate : TargetMembers.ownMethodsOf(target)) {
                if (!candidate.isConstructor() && name.equals(candidate.getName())) {
                    found++;
                }
            }
        }
        return found;
    }

    /**
     * Offers a rename to the nearest method name the targets actually have.
     *
     * <p>The candidates are every method of every target including inherited ones, and the nearest
     * by Levenshtein distance wins. It is offered only when that distance is within one edit per
     * three characters of the written name, and at least one: a short name earns one edit, a longer
     * one more, because a longer name has more room to be mistyped and less chance of
     * coincidentally resembling a different method.
     *
     * <p>The fix replaces the name alone and leaves the rest of the selector, so a written
     * parameter list survives a rename and continues to narrow the overload it was added for.
     *
     * @param literal the literal being reported; must not be {@code null}
     * @param written the name as written; must not be {@code null}
     * @param targets the classes the weave names; must not be {@code null}
     * @param range   the name's range within the literal element; must not be {@code null}
     * @return one fix, or an empty array when the targets declare no method or none within the
     *         allowed distance
     */
    @NotNull
    private static LocalQuickFix[] fixesFor(@NotNull final PsiLiteralExpression literal,
                                            @NotNull final String written,
                                            @NotNull final List<PsiClass> targets,
                                            @NotNull final TextRange range) {
        String nearest = null;
        int best = Integer.MAX_VALUE;
        for (final PsiClass target : targets) {
            for (final PsiMethod candidate : target.getAllMethods()) {
                final int distance = editDistance(written, candidate.getName());
                if (distance < best) {
                    best = distance;
                    nearest = candidate.getName();
                }
            }
        }
        // One edit for a short name, more as the name grows: a longer name has more room to be
        // mistyped and less chance of coincidentally resembling a different one.
        final int allowed = Math.max(1, written.length() / 3);
        return nearest == null || best > allowed
                ? LocalQuickFix.EMPTY_ARRAY
                : new LocalQuickFix[]{new ChangeSelectorNameFix(
                        literal, range.getStartOffset(), range.getLength(), nearest)};
    }

    /**
     * Returns the Levenshtein distance between two names.
     *
     * <p>Insertion, deletion and substitution each cost one. Two rows of the matrix are held at a
     * time rather than the whole of it, which is all the caller needs: it wants the distance and
     * never the alignment that produced it.
     *
     * @param from the name as written; must not be {@code null}
     * @param to   the candidate name; must not be {@code null}
     * @return the number of single-character edits between them
     */
    private static int editDistance(@NotNull final String from, @NotNull final String to) {
        int[] previous = new int[to.length() + 1];
        for (int column = 0; column <= to.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= from.length(); row++) {
            final int[] current = new int[to.length() + 1];
            current[0] = row;
            for (int column = 1; column <= to.length(); column++) {
                final int substitution = previous[column - 1]
                        + (from.charAt(row - 1) == to.charAt(column - 1) ? 0 : 1);
                current[column] =
                        Math.min(substitution, Math.min(previous[column] + 1, current[column - 1] + 1));
            }
            previous = current;
        }
        return previous[to.length()];
    }
}
