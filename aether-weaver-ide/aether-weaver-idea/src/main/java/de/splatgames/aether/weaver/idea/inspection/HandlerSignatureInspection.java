package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports an {@code @Inject} handler whose signature disagrees with the method it is injected into.
 *
 * <p>A method is examined only when its containing class carries {@code @Weave} and the method itself carries at
 * least one {@code @Inject}. No other annotation is claimed: a {@code @Redirect} handler obeys different rules and
 * is passed over here.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>{@code AW1041} on the return type element, whenever the handler declares a return type other than
 *       {@code void}. This one needs no target, so it stands while the selector is still half-typed.
 *   <li>{@code AW1040} on the first parameter that disagrees, or on the whole parameter list when the handler
 *       simply takes too many. Carries the quick fix that rewrites the list.
 *   <li>{@code AW1071} on the callback parameter, when its single type argument is not the target's return type,
 *       boxed. Silent as well when the target's return type is {@code null} or {@code void}, since a target that
 *       returns nothing has no boxed type to compare the argument against.
 *   <li>{@code AW1070} on a {@code cancel()} call written on the handler's own callback parameter, when the target
 *       returns something.
 * </ul>
 *
 * <p>Every report is registered as {@link ProblemHighlightType#GENERIC_ERROR_OR_WARNING}; the severity the
 * {@link DiagnosticCode} itself declares is not consulted, and the code is carried in the message instead.
 *
 * <h2>What is deliberately not reported</h2>
 *
 * <p>Everything but {@code AW1041} needs the target, and the target is resolved by
 * {@link SelectorTargets#exact(PsiClass, String)}, which answers only where exactly one method matches. A malformed
 * selector, a selector naming a type the module cannot see, and a bare name matching several overloads all leave
 * the annotation silent here — the build reports those, and guessing which overload was meant is how a plugin ends
 * up underlining correct code.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class HandlerSignatureInspection extends AbstractBaseJavaLocalInspectionTool {

    /** The callback method whose no-argument form cannot cancel a target that returns a value. */
    private static final String CANCEL = "cancel";

    /**
     * Creates the inspection.
     *
     * <p>Held by the platform for the lifetime of the IDE and used from every inspection run, so it carries no state
     * of its own.
     */
    public HandlerSignatureInspection() {
        // Stateless.
    }

    /**
     * Returns a visitor that examines every method in the file.
     *
     * @param holder     the holder every report is registered on
     * @param isOnTheFly whether the run is an editor pass rather than a batch one; not used, because every check
     *                   here is local to one method and costs the same either way
     * @return a visitor whose {@code visitMethod} performs the whole inspection
     */
    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                          final boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull final PsiMethod method) {
                inspect(method, holder);
            }
        };
    }

    /**
     * Runs every check on one candidate handler.
     *
     * <p>The return type is checked first and unconditionally. Each {@code @Inject} is then resolved in turn, and an
     * annotation whose selector names no single method contributes nothing. The first parameter mismatch found ends
     * the inspection of this handler, so a handler carrying two {@code @Inject} annotations that both disagree is
     * underlined once rather than twice.
     *
     * @param handler the method to examine
     * @param holder  the holder reports are registered on
     */
    private static void inspect(@NotNull final PsiMethod handler,
                                @NotNull final ProblemsHolder holder) {
        final PsiClass weave = handler.getContainingClass();
        if (weave == null || WeaveDeclarations.annotation(weave, WeaveDeclarations.WEAVE) == null) {
            return;
        }
        final List<PsiAnnotation> injections = injectionsOn(handler);
        if (injections.isEmpty()) {
            return;
        }

        reportReturnType(handler, holder);
        for (final PsiAnnotation injection : injections) {
            final String selector = selectorOf(injection);
            final PsiMethod target = selector == null ? null : SelectorTargets.exact(weave, selector);
            if (target == null) {
                continue;
            }
            if (reportParameters(handler, target, selector, holder)) {
                // One report per handler. A handler carrying two @Inject annotations that both
                // disagree has one thing wrong with it, and two underlines on one parameter list
                // would say so twice.
                return;
            }
            reportCallback(handler, target, holder);
            reportValuelessCancel(handler, target, holder);
        }
    }

    /**
     * Reports {@code AW1041} when the handler returns anything.
     *
     * <p>Silent for a constructor and for a handler whose return type element is missing, neither of which has a
     * type element to underline.
     *
     * @param handler the handler to examine
     * @param holder  the holder the report is registered on
     */
    private static void reportReturnType(@NotNull final PsiMethod handler,
                                         @NotNull final ProblemsHolder holder) {
        final PsiType returned = handler.getReturnType();
        final PsiTypeElement element = handler.getReturnTypeElement();
        if (returned == null || element == null || PsiTypes.voidType().equals(returned)) {
            return;
        }
        holder.registerProblem(element,
                DiagnosticCode.HANDLER_RETURN_TYPE_NOT_VOID.code()
                        + ": an @Inject handler returns void; the injected call is a statement in "
                        + "the middle of the target's own code, so a returned value would have "
                        + "nowhere to go — to change what the target returns, take a "
                        + "ReturnableCallback and cancel with a value",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }

    /**
     * Reports {@code AW1040} when the handler's arguments are not a prefix of the target's.
     *
     * <p>The comparison is {@link HandlerSignature#prefixFailure(PsiMethod, PsiMethod)}, which counts neither a
     * receiver first parameter, nor the callback, nor a {@code @Local} capture as an argument, and which answers
     * nothing where a type does not resolve. The report carries the fix that rewrites the parameter list.
     *
     * @param handler  the handler to examine
     * @param target   the method the selector resolved to
     * @param selector the selector text, handed to the fix so that it can resolve the target again
     * @param holder   the holder the report is registered on
     * @return whether a report was made, which ends the inspection of this handler
     */
    private static boolean reportParameters(@NotNull final PsiMethod handler,
                                            @NotNull final PsiMethod target,
                                            @NotNull final String selector,
                                            @NotNull final ProblemsHolder holder) {
        final HandlerSignature.Mismatch mismatch = HandlerSignature.prefixFailure(handler, target);
        if (mismatch == null) {
            return false;
        }
        final PsiElement anchor = mismatch.parameter() != null
                ? mismatch.parameter()
                : handler.getParameterList();
        holder.registerProblem(anchor,
                DiagnosticCode.HANDLER_PARAMETERS_NOT_PREFIX.code()
                        + ": " + handler.getName() + " does not take a prefix of the target's "
                        + "arguments: " + mismatch.detail()
                        + " — the injected call pushes the target's own arguments in order, so a "
                        + "handler may take the first n of them and nothing else",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                new AdjustHandlerParametersFix(handler, selector));
        return true;
    }

    /**
     * Reports {@code AW1071} when the callback parameter names the wrong value type.
     *
     * <p>Silent when the handler takes no callback, silent for a raw {@code ReturnableCallback}, which names
     * nothing there is anything to disagree with, and silent when the target's return type is {@code null} or
     * {@code void} — a target that returns nothing gives the callback nothing to be wrong about. Also silent when
     * either the target's return type or the callback's type argument fails to resolve to a name. The message quotes
     * the type argument that would be right.
     *
     * @param handler the handler to examine
     * @param target  the method the selector resolved to
     * @param holder  the holder the report is registered on
     */
    private static void reportCallback(@NotNull final PsiMethod handler,
                                       @NotNull final PsiMethod target,
                                       @NotNull final ProblemsHolder holder) {
        final HandlerSignature.Shape shape = HandlerSignature.shapeOf(handler, target);
        final PsiParameter callback = shape == null ? null : shape.callback();
        if (callback == null) {
            return;
        }
        final String expected = HandlerSignature.callbackMismatch(callback, target);
        if (expected == null) {
            return;
        }
        holder.registerProblem(callback,
                DiagnosticCode.CALLBACK_TYPE_MISMATCH.code()
                        + ": the callback does not match the target's return type; declare "
                        + "ReturnableCallback<" + expected + '>',
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }

    /**
     * Reports {@code AW1070} on a {@code cancel()} that says nothing about what the target returns instead.
     *
     * <p>The call has to be written on the handler's own callback parameter: the qualifier is resolved and compared
     * against that parameter, so a cancellation performed inside a helper the handler calls is not seen here. The
     * first such call is reported and the search stops, so one handler yields at most one report per injection.
     *
     * @param handler the handler whose body is searched
     * @param target  the method the selector resolved to
     * @param holder  the holder the report is registered on
     */
    private static void reportValuelessCancel(@NotNull final PsiMethod handler,
                                              @NotNull final PsiMethod target,
                                              @NotNull final ProblemsHolder holder) {
        final PsiType returned = target.getReturnType();
        final HandlerSignature.Shape shape = HandlerSignature.shapeOf(handler, target);
        final PsiParameter callback = shape == null ? null : shape.callback();
        if (callback == null || handler.getBody() == null
                || returned == null || PsiTypes.voidType().equals(returned)) {
            return;
        }

        for (final PsiMethodCallExpression call
                : PsiTreeUtil.findChildrenOfType(handler.getBody(), PsiMethodCallExpression.class)) {
            final PsiReferenceExpression callee = call.getMethodExpression();
            if (!CANCEL.equals(callee.getReferenceName())
                    || call.getArgumentList().getExpressionCount() != 0
                    || !(callee.getQualifierExpression() instanceof final PsiReferenceExpression on)
                    || !callback.equals(on.resolve())) {
                continue;
            }
            holder.registerProblem(call,
                    DiagnosticCode.CANCEL_ON_NON_VOID_TARGET.code()
                            + ": the target returns " + returned.getPresentableText()
                            + ", so cancelling has to say what it returns instead — call "
                            + "cancel(value) on a ReturnableCallback<"
                            + returned.getPresentableText() + '>',
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            return;
        }
    }

    /**
     * Collects the {@code @Inject} annotations written on a method.
     *
     * <p>Matched by qualified name, so an annotation of the same simple name from another library is not claimed.
     *
     * @param handler the method to read
     * @return the annotations in the order they are written, empty when there are none
     */
    @NotNull
    private static List<PsiAnnotation> injectionsOn(@NotNull final PsiMethod handler) {
        final PsiModifierList modifiers = handler.getModifierList();
        final List<PsiAnnotation> found = new ArrayList<>(1);
        for (final PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (WeaveDeclarations.INJECT.equals(annotation.getQualifiedName())) {
                found.add(annotation);
            }
        }
        return found;
    }

    /**
     * Reads the selector out of one {@code @Inject}.
     *
     * @param injection the annotation to read
     * @return the value of its {@code method} attribute, or {@code null} when the attribute is anything but a string
     *         literal — a constant reference is a spelling this inspection does not follow
     */
    @Nullable
    private static String selectorOf(@NotNull final PsiAnnotation injection) {
        final PsiElement value =
                injection.findAttributeValue(WeaveDeclarations.METHOD_ATTRIBUTE);
        return value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final String text
                ? text
                : null;
    }
}
