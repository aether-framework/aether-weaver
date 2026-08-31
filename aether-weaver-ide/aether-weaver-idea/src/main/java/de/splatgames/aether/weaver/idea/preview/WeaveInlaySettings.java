package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * The single flag behind the "Show Injected Code" toggle.
 *
 * <p>Two readers act on it. {@link WeaveInlayPass#doCollectInformation} consults it before it
 * collects anything, so a collapsed setting yields no blocks at all rather than shorter ones, and
 * {@link WeaveBlockRenderer} consults it again when measuring and painting a block it already
 * holds. {@link ToggleInjectedCodeAction} is the only writer, and it restarts the daemon of every
 * open project immediately after flipping the flag, so a block that exists when the flag is set
 * is disposed on the next pass rather than left to draw as a collapsed strip.
 *
 * <p>The value lives in {@link Stored}, the state this service publishes under the name
 * {@code AetherWeaverInlayPreview} into {@code aether-weaver.xml}, as declared by the
 * {@link State} annotation on this class.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Service(Service.Level.APP)
@State(name = "AetherWeaverInlayPreview", storages = @Storage("aether-weaver.xml"))
public final class WeaveInlaySettings
        implements PersistentStateComponent<WeaveInlaySettings.Stored> {

    /** The state this service reads and publishes; replaced wholesale by {@link #loadState(Stored)}. */
    private Stored stored = new Stored();

    /**
     * Creates the service holding a fresh {@link Stored}, whose flag is clear.
     */
    public WeaveInlaySettings() {
        // Defaults are the field initialisers of Stored.
    }

    /**
     * Returns the application-level instance of this service.
     *
     * @return the service instance
     */
    @NotNull
    public static WeaveInlaySettings getInstance() {
        return ApplicationManager.getApplication().getService(WeaveInlaySettings.class);
    }

    /**
     * Reports whether injected code is switched off.
     *
     * @return {@code true} when injected code is collapsed, {@code false} when it is shown
     */
    public boolean isCollapsed() {
        return this.stored.collapsed;
    }

    /**
     * Sets whether injected code is switched off.
     *
     * <p>Writes the flag and nothing else; no editor is touched here.
     * {@link ToggleInjectedCodeAction} is what pairs this call with a request to redo the open editors.
     *
     * @param collapsed {@code true} to collapse injected code, {@code false} to show it
     */
    public void setCollapsed(final boolean collapsed) {
        this.stored.collapsed = collapsed;
    }

    /**
     * Returns the state currently held.
     *
     * <p>The instance itself rather than a copy, so a later {@link #setCollapsed(boolean)} is visible
     * through a reference obtained here.
     *
     * @return the held state
     */
    @Override
    @NotNull
    public Stored getState() {
        return this.stored;
    }

    /**
     * Replaces the held state with the given one.
     *
     * @param state the state to hold from now on; must not be {@code null}
     */
    @Override
    public void loadState(@NotNull final Stored state) {
        this.stored = state;
    }

    /**
     * The serialised form of the setting.
     *
     * <p>A mutable holder with one public field, named as the {@link PersistentStateComponent} type
     * argument of the enclosing service.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Stored {

        /**
         * Whether injected code is collapsed.
         *
         * <p>{@code false} on a fresh instance, so injected code is shown until something sets it.
         */
        public volatile boolean collapsed;

        /**
         * Creates the state with the flag clear.
         */
        public Stored() {
            // Expanded by default: a feature nobody sees is a feature nobody uses.
        }
    }
}
