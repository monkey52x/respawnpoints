package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.model.RespawnEntry;

import java.util.List;

/**
 * Shared two-step delete logic: first click arms, second click confirms.
 * Used by all respawn selection screens to keep behaviour identical.
 */
public final class DeleteArm {
    private String armedId;

    private DeleteArm() {
    }

    public static DeleteArm create() {
        return new DeleteArm();
    }

    public String armedId() {
        return armedId;
    }

    public void clear() {
        armedId = null;
    }

    public void clearIfGone(List<RespawnEntry> entries) {
        if (armedId != null && EntriesDiff.indexOfId(entries, armedId) < 0) {
            armedId = null;
        }
    }

    /**
     * @return true when the click confirms (caller sends DeletePointPacket),
     * false when it only armed (caller repaints).
     */
    public boolean click(String id) {
        if (id != null && id.equals(armedId)) {
            armedId = null;
            return true;
        }
        armedId = id;
        return false;
    }

    public void onSelectedOther(String selectedId) {
        if (armedId != null && !armedId.equals(selectedId)) {
            armedId = null;
        }
    }
}