package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.RespawnScope;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared ordering and two-step delete for both selection screens.
 * Rendering stays in the screens — only filter + sort + armed delete live here,
 * so Xaero and the fallback list can never diverge again.
 */
public final class RespawnScreenModel {
    private RespawnScreenModel() {
    }

    public static int sortKey(RespawnEntry entry, BlockPos deathPos) {
        if (entry.concealed() || entry.type() == RespawnPointsType.NEAR_DEATH) {
            return Integer.MIN_VALUE;
        }
        if (entry.type() == RespawnPointsType.SPAWN) {
            return Integer.MAX_VALUE;
        }
        if (!entry.pos().equals(BlockPos.ZERO)) {
            double d = Math.sqrt(entry.pos().distSqr(deathPos));
            return (int) Math.min(d, Integer.MAX_VALUE - 1);
        }
        return 0;
    }

    /** Filter by scope, then near-death first / spawn last / rest by distance. */
    public static List<RespawnEntry> sorted(List<RespawnEntry> raw, String dimContext, BlockPos deathPos) {
        List<RespawnEntry> scoped = new ArrayList<>(RespawnScope.filter(raw, dimContext));
        scoped.sort((a, b) -> Integer.compare(sortKey(a, deathPos), sortKey(b, deathPos)));
        return scoped;
    }

    /** Two-step delete: first click arms, second click confirms. */
    public static final class DeleteArm {
        private String armedId;

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
}
