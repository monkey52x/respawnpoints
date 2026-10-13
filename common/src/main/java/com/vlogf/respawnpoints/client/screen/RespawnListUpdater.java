package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.model.RespawnEntry;

import java.util.List;

/**
 * Incremental respawn-list updates. Full-screen rebuilds reset the map camera
 * (zoom jump), so data-only changes (anchor charges, colors) are applied
 * in place; structural changes still rebuild.
 */
public interface RespawnListUpdater {
    /**
     * @param freshRaw the latest server offer (unfiltered, server order).
     * @return true if accepted incrementally (camera/zoom/scroll/selection kept).
     */
    boolean updateIfDataOnly(List<RespawnEntry> freshRaw);

    /**
     * Structural in-place update (add/remove/rename/reorder) without
     * recreating the screen: no {@code setScreen}, no map re-init, no camera
     * jump. Default returns false for a full rebuild via the factory.
     *
     * @param freshRaw the latest server offer (unfiltered, server order).
     * @return true if applied in place.
     */
    default boolean updateIfStructural(List<RespawnEntry> freshRaw) {
        return false;
    }

    /** Currently selected entry id, or null. Survives full rebuilds. */
    String selectedEntryId();

    /** Re-select by id after a rebuild; unknown id = keep current selection. */
    void selectEntryId(String id);

    /** Death-offer generation this screen was built for (0 = unknown/live). */
    default long respawnpoints$offerId() {
        return 0L;
    }

    /**
     * A newer death offer arrived while this screen is open: unfreeze
     * (clear sent/respawning) so the new choice can be sent. Called before
     * the data/structural updates run.
     */
    default void respawnpoints$resetForNewOffer() {
    }
}
