package de.splatgames.aether.weaver.idea.preview;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

/**
 * The "Show Injected Code" check item.
 *
 * <p>Registered in {@code plugin.xml} as {@code AetherWeaver.ToggleInjectedCode} and added to both
 * the View menu and the editor popup. Its state is {@link WeaveInlaySettings}, inverted: the item
 * is ticked when the setting is not collapsed, because the item says "show" and the setting says
 * "collapsed".
 *
 * <p>The setting is application-wide, so the item shows and hides injected code in every open
 * project rather than in the one the event came from. {@link #setSelected} accordingly walks
 * {@link ProjectManager#getOpenProjects()} rather than taking the project out of the event.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ToggleInjectedCodeAction extends ToggleAction {

    /** The reason handed to {@link DaemonCodeAnalyzer#restart(String)} after the setting changes. */
    private static final String RESTART_REASON = "Aether Weaver: injected code display toggled";

    /**
     * Creates the action.
     *
     * <p>Carries no state of its own; {@link WeaveInlaySettings} holds all of it.
     */
    public ToggleInjectedCodeAction() {
        // Stateless; the setting is the state.
    }

    /**
     * Reports whether the item is ticked.
     *
     * <p>Reads only the setting; the event is not consulted, so every editor and every project
     * answers the same.
     *
     * @param event the event the state is being computed for; must not be {@code null}
     * @return {@code true} when {@link WeaveInlaySettings#isCollapsed()} is {@code false}
     */
    @Override
    public boolean isSelected(@NotNull final AnActionEvent event) {
        return !WeaveInlaySettings.getInstance().isCollapsed();
    }

    /**
     * Writes the setting and restarts the daemon of every open project.
     *
     * <p>{@code selected} is inverted into {@link WeaveInlaySettings#setCollapsed(boolean)}.
     * {@link DaemonCodeAnalyzer#restart(String)} is then called for each project of
     * {@link ProjectManager#getOpenProjects()} that is not already disposed, with
     * {@code "Aether Weaver: injected code display toggled"} as the reason; the event's own project
     * is not treated differently from the rest.
     *
     * @param event    the event that invoked the item; must not be {@code null}
     * @param selected {@code true} to show injected code, {@code false} to collapse it
     */
    @Override
    public void setSelected(@NotNull final AnActionEvent event, final boolean selected) {
        WeaveInlaySettings.getInstance().setCollapsed(!selected);
        for (final Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                // The reason is passed because the no-argument overload is deprecated: the platform
                // logs why the daemon was restarted, and "somebody toggled this" is what turns a
                // line in a performance trace into an explanation. Caught by the Plugin Verifier,
                // which reported it as the one deprecated call in the plugin.
                DaemonCodeAnalyzer.getInstance(project).restart(RESTART_REASON);
            }
        }
    }

    /**
     * Returns the update thread this action declares.
     *
     * @return {@link ActionUpdateThread#BGT}
     */
    @Override
    @NotNull
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
