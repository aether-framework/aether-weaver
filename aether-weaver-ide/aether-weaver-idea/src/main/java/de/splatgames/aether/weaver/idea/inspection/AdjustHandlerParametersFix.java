package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiType;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites an {@code @Inject} handler's parameter list so that its arguments are a prefix of the target's.
 *
 * <p>Offered by {@link HandlerSignatureInspection} beside {@code AW1040}, which it reports when the handler's
 * argument parameters are not the first n of the target's, either because one has the wrong type or because there
 * are more of them than the target has arguments.
 *
 * <p>The rewrite is deliberately narrow. It never lengthens the handler: a handler taking fewer arguments than the
 * target already satisfies the rule, so the arguments the author wrote are retyped in place rather than topped up
 * from the target. Arguments beyond the target's arity are dropped, since there is nothing for them to correspond
 * to. Parameter names are the author's own and survive, which is what keeps the body compiling.
 *
 * <p>The list is rebuilt rather than edited: the new list is built from a name and a type for each parameter, and
 * carries nothing else — no modifier list is read from the old parameters or written to the new ones. A receiver
 * first parameter and every parameter that is not one of the arguments kept from the target keep the type they
 * were declared with; an argument kept from the target is the exception, and takes the target's own type at that
 * position instead, which is the point of the fix. Every kept parameter keeps its own name. What is not the
 * receiver and not an argument otherwise keeps the order the author wrote it in, following the arguments rather
 * than its own original position — so a handler that wrote its callback before an argument comes back with it
 * after.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class AdjustHandlerParametersFix extends LocalQuickFixOnPsiElement {

    /** The selector the reporting {@code @Inject} named, used to find the target again when the fix is applied. */
    private final String selector;

    /**
     * Anchors the fix to the handler and records the selector to resolve.
     *
     * @param handler  the handler method whose parameter list is wrong
     * @param selector the text of the {@code method} attribute on the {@code @Inject} that reported the mismatch
     */
    AdjustHandlerParametersFix(@NotNull final PsiMethod handler, @NotNull final String selector) {
        super(handler);
        this.selector = selector;
    }

    /**
     * Returns the text of this single action, which is the family name.
     *
     * @return the same text as {@link #getFamilyName()}, there being one form of this fix
     */
    @Override
    @NotNull
    public String getText() {
        return getFamilyName();
    }

    /**
     * Returns the name this fix is grouped and looked up under.
     *
     * @return {@code "Adjust handler parameters to the target"}
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Adjust handler parameters to the target";
    }

    /**
     * Replaces the handler's parameter list with one that takes a prefix of the target's arguments.
     *
     * <p>The target is resolved from the recorded selector against the enclosing weave's targets, so the fix reads
     * the file as it stands rather than trusting what the inspection saw. It does nothing at all when the anchor is
     * no longer a method, when the handler has no containing class, when the selector names no single method, when
     * the handler's shape cannot be read, or when a replacement list cannot be built.
     *
     * <p>Class references in the new list are shortened, so a type the file already imports is written by its
     * simple name.
     *
     * @param project      the project the file belongs to
     * @param file         the file the handler lives in
     * @param startElement the handler the fix was created with
     * @param endElement   the end of the anchored range, unused
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        if (!(startElement instanceof final PsiMethod handler)) {
            return;
        }
        final PsiClass weave = handler.getContainingClass();
        final PsiMethod target = weave == null ? null : SelectorTargets.exact(weave, this.selector);
        if (target == null) {
            return;
        }
        final HandlerSignature.Shape shape = HandlerSignature.shapeOf(handler, target);
        if (shape == null) {
            return;
        }

        final PsiParameterList replacement = build(handler, target, shape, project);
        if (replacement == null) {
            return;
        }
        JavaCodeStyleManager.getInstance(project)
                .shortenClassReferences(handler.getParameterList().replace(replacement));
    }

    /**
     * Builds the parameter list the handler should have.
     *
     * <p>The number of arguments kept is the smaller of what the author wrote and what the target takes, so the
     * result is always a prefix and never grows. Each kept argument takes the target's type at that position and the
     * author's own name.
     *
     * @param handler the handler being rewritten
     * @param target  the method the selector resolved to
     * @param shape   the handler's parameters sorted into receiver, arguments and callback
     * @param project the project whose element factory builds the new list
     * @return the new parameter list, or {@code null} when one of the target's parameter types cannot be written
     *         back as source, which leaves the handler untouched rather than half-corrected
     */
    private static PsiParameterList build(@NotNull final PsiMethod handler,
                                          @NotNull final PsiMethod target,
                                          @NotNull final HandlerSignature.Shape shape,
                                          @NotNull final Project project) {
        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final PsiParameter[] declared = handler.getParameterList().getParameters();
        final PsiParameter[] expected = target.getParameterList().getParameters();
        final int keep = Math.min(shape.arguments().size(), expected.length);

        final List<String> names = new ArrayList<>(declared.length);
        final List<PsiType> types = new ArrayList<>(declared.length);
        if (shape.receiver()) {
            names.add(declared[0].getName());
            types.add(declared[0].getType());
        }
        for (int i = 0; i < keep; i++) {
            final String text = HandlerSignature.writableTextOf(expected[i].getType());
            if (text == null) {
                return null;
            }
            names.add(shape.arguments().get(i).getName());
            types.add(factory.createTypeFromText(text, handler));
        }
        // Everything that is not an argument keeps the place the author gave it, relative to the
        // others: the callback and the @Local captures follow the arguments, in written order.
        for (final PsiParameter parameter : declared) {
            if (shape.arguments().contains(parameter)
                    || (shape.receiver() && parameter == declared[0])) {
                continue;
            }
            names.add(parameter.getName());
            types.add(parameter.getType());
        }
        return factory.createParameterList(names.toArray(String[]::new),
                types.toArray(PsiType.EMPTY_ARRAY));
    }
}
