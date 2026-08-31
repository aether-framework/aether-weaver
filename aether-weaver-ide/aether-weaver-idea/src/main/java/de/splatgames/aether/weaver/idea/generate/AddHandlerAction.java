package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.generation.actions.BaseGenerateAction;
import com.intellij.psi.PsiClass;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

/**
 * Contributes the "Weave Handler..." entry to the Generate menu.
 *
 * <p>Registered in {@code GenerateGroup}, which is what Alt+Insert opens, so the entry is
 * evaluated in every Java file and shows itself only where it can do something. All of the work
 * is {@link AddHandlerHandler}'s; this class exists to decide where the entry appears.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class AddHandlerAction extends BaseGenerateAction {

    /**
     * Creates the action, handing the Generate framework a fresh {@link AddHandlerHandler}.
     */
    public AddHandlerAction() {
        super(new AddHandlerHandler());
    }

    /**
     * Reports whether the menu entry is offered inside the given class.
     *
     * <p>Both halves are required. Without the {@code @Weave} annotation the entry would appear in
     * every Java class, and without a target that resolves it would open a chooser with nothing in
     * it, since the methods offered are read off the targets.
     *
     * @param targetClass the class the caret is in
     * @return {@code true} when the class carries {@code @Weave} and at least one of its targets
     *         resolves
     */
    @Override
    protected boolean isValidForClass(@NotNull final PsiClass targetClass) {
        return WeaveDeclarations.annotation(targetClass, WeaveDeclarations.WEAVE) != null
                && !WeaveDeclarations.targetsOf(targetClass).isEmpty();
    }
}
