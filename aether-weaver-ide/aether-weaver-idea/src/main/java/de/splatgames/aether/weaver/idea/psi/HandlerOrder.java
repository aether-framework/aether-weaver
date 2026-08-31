package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/**
 * Orders handler methods the way the build applies them.
 *
 * <p>The editor's counterpart to {@link de.splatgames.aether.weaver.engine.plan.OrderKey}: priority descending, then
 * the weave's qualified name, then the handler's name, then its parameter types. The same four keys in the same
 * order, so a list shown in the tool window, a line marker's tooltip and the inlay preview all read the way the
 * woven class will run.
 *
 * <p>Two of the keys are approximations, because the editor has no class file to read the exact one from.
 *
 * <ul>
 *   <li><b>The priority</b> is read only from an integer literal. A {@code @Weave(priority = Priorities.HIGH)} that
 *       names a constant instead is not resolved and counts as {@code 0}, so a project ordering its weaves by named
 *       constants is shown in an order the build does not use.
 *   <li><b>The descriptor</b> is the parameter types as the editor presents them, comma-separated, rather than a
 *       JVM descriptor. It breaks ties between overloads consistently within one editor session, which is all the
 *       last key has to do; it does not sort the way descriptors sort.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class HandlerOrder {

    /**
     * Orders handlers as the build applies them: highest priority first, then by weave class, handler name and
     * parameter types.
     *
     * <p>Reproducible between openings of the same files, but not total the way {@link
     * de.splatgames.aether.weaver.engine.plan.OrderKey} is: {@link #descriptorOf} renders a parameter type by its
     * presentable text, which drops the package, so two overloads distinguished only by parameter types from
     * different packages with the same simple name — {@code java.util.List} and {@code java.awt.List}, both
     * rendered as {@code List} — compare equal despite being distinct declarations. {@link #weaveClassNameOf} adds a
     * second collapse: every weave class with no qualified name renders as the same empty string.
     */
    public static final Comparator<PsiMethod> EXECUTION_ORDER =
            Comparator.comparingInt(HandlerOrder::priorityOf).reversed()
                    .thenComparing(HandlerOrder::weaveClassNameOf)
                    .thenComparing(PsiMethod::getName)
                    .thenComparing(HandlerOrder::descriptorOf);

    /** The {@code @Weave} element carrying the priority. */
    private static final String PRIORITY_ATTRIBUTE = "priority";

    /** The priority of a handler whose weave declares none, matching {@code @Weave}'s own default. */
    private static final int DEFAULT_PRIORITY = 0;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private HandlerOrder() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the priority the handler's weave declares.
     *
     * <p>Declared on the weave class rather than on the handler, so every handler of one weave has one priority.
     *
     * @param handler the handler method; must not be {@code null}
     * @return the declared priority, and {@code 0} when the handler is in no class, its class carries no
     *         {@code @Weave}, or the {@code priority} element is anything other than an integer literal
     */
    public static int priorityOf(@NotNull final PsiMethod handler) {
        final PsiClass weave = handler.getContainingClass();
        if (weave == null) {
            return DEFAULT_PRIORITY;
        }
        final PsiAnnotation declared = WeaveDeclarations.annotation(weave, WeaveDeclarations.WEAVE);
        return declared == null
                ? DEFAULT_PRIORITY
                : intOf(declared.findAttributeValue(PRIORITY_ATTRIBUTE));
    }

    /**
     * Returns the qualified name of the weave a handler belongs to.
     *
     * @param handler the handler method; must not be {@code null}
     * @return the qualified name, or an empty string when the handler is in no class or in one with no qualified
     *         name, which sorts such a handler first among its priority
     */
    @NotNull
    private static String weaveClassNameOf(@NotNull final PsiMethod handler) {
        final PsiClass weave = handler.getContainingClass();
        final String qualified = weave == null ? null : weave.getQualifiedName();
        return qualified == null ? "" : qualified;
    }

    /**
     * Renders a handler's parameter types as the last tie-breaker.
     *
     * <p>Presentable text rather than a JVM descriptor: nothing reads this string, and a tie-breaker only has to
     * separate two overloads consistently.
     *
     * @param handler the handler method; must not be {@code null}
     * @return the parameter types, each followed by a comma, and an empty string for a handler taking none
     */
    @NotNull
    private static String descriptorOf(@NotNull final PsiMethod handler) {
        final StringBuilder rendered = new StringBuilder();
        for (final PsiParameter parameter : handler.getParameterList().getParameters()) {
            rendered.append(parameter.getType().getPresentableText()).append(',');
        }
        return rendered.toString();
    }

    /**
     * Reads an annotation element as an integer.
     *
     * @param value the element's value, or {@code null} when it has none
     * @return the literal's value, or {@code 0} when the element is not an integer literal
     */
    private static int intOf(@Nullable final PsiAnnotationMemberValue value) {
        return value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final Integer number
                ? number
                : DEFAULT_PRIORITY;
    }
}
