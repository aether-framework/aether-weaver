package de.splatgames.aether.weaver.engine.explain;

import org.jetbrains.annotations.NotNull;

/**
 * Hears which instructions an injection point matched, while the class that contains them still
 * exists.
 *
 * <p>An index is meaningful only against the body it was resolved in, and the pipeline rebuilds that
 * body immediately afterwards. An observer therefore has to be told during weaving; there is no
 * later moment at which the same question could be asked again.
 *
 * <p>Weaving may run on any thread the driver loads classes on, and an implementation is called from
 * that thread.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@FunctionalInterface
public interface SiteObserver {

    /** The observer installed when nothing is listening, so that the pipeline never tests for null. */
    SiteObserver NONE = resolution -> {
    };

    /**
     * Records what one point of one injection matched in one target.
     *
     * <p>Called once per {@code PointSpec} of an entry that reached point resolution, including one
     * that matched nothing: a target whose method the selector never found still reports, with an
     * empty index list, so that a reader is not left unable to tell "matched nothing" from "not yet
     * woven". A namespaced point whose resolution fails before that — inside plugin isolation — is
     * not reported here at all.
     *
     * @param resolution what the point matched; must not be {@code null}
     */
    void resolved(@NotNull Resolution resolution);
}
