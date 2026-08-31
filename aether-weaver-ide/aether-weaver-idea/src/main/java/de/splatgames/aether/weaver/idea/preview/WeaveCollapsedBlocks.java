package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of individual weave blocks the reader has folded away.
 *
 * <p>Distinct from {@link WeaveInlaySettings}, which is one flag for the whole feature. This is per
 * block: {@link WeaveBlockRenderer} asks whether the identity of the block it is drawing is in this
 * set, and folds that block alone. Either answer folds it, so the global flag cannot be overridden
 * from here.
 *
 * <p>The identity is {@link WeaveBlock#id()}, which {@code WeaveBlocks} builds out of the target's
 * signature and the block's section headers rather than out of an offset, so a collapsed block
 * stays collapsed while the file above it is edited. It is not unique: two blocks in one method
 * that carry the same handlers at the same point share an identity and fold together.
 *
 * <p>Nothing here is written to disk; the class declares no state to persist.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Service(Service.Level.APP)
public final class WeaveCollapsedBlocks {

    /** The identities currently folded away. */
    private final Set<String> collapsed = ConcurrentHashMap.newKeySet();

    /**
     * Creates the service with nothing folded.
     */
    public WeaveCollapsedBlocks() {
        // Nothing is collapsed until somebody asks for it.
    }

    /**
     * Returns the application-level instance of this service.
     *
     * @return the service instance
     */
    @NotNull
    public static WeaveCollapsedBlocks getInstance() {
        return ApplicationManager.getApplication().getService(WeaveCollapsedBlocks.class);
    }

    /**
     * Reports whether the block with the given identity is folded away.
     *
     * @param id the block identity, as {@link WeaveBlock#id()} carries it; must not be {@code null}
     * @return {@code true} when that identity has been collapsed and not expanded since
     */
    public boolean isCollapsed(@NotNull final String id) {
        return this.collapsed.contains(id);
    }

    /**
     * Folds the given identity away if it is not folded, and unfolds it if it is.
     *
     * <p>The state is not read before it is written: the identity is added, and only if the set already
     * held it is it removed instead. Where two callers toggle the same identity at once, the one whose
     * removal loses the race is told the identity is collapsed although the other call removed it.
     *
     * @param id the block identity to toggle; must not be {@code null}
     * @return {@code true} when this call added the identity, {@code false} when it removed it
     */
    public boolean toggle(@NotNull final String id) {
        return this.collapsed.add(id) || !this.collapsed.remove(id);
    }
}
