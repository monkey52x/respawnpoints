package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.model.RespawnEntry;

import java.util.List;

/**
 * Structural diff for respawn lists. Data-only = same ids in the same order
 * with the same names/positions/dimensions/symbols/types/visibility; only
 * charges and colors may differ. Anything else needs a full rebuild.
 */
public final class EntriesDiff {
    private EntriesDiff() {
    }

    public static boolean sameStructure(List<RespawnEntry> oldList, List<RespawnEntry> newList) {
        if (oldList.size() != newList.size()) {
            return false;
        }
        for (int i = 0; i < oldList.size(); i++) {
            RespawnEntry a = oldList.get(i);
            RespawnEntry b = newList.get(i);
            if (!a.id().equals(b.id())
                    || a.type() != b.type()
                    || !a.name().equals(b.name())
                    || !a.symbol().equals(b.symbol())
                    || !a.dimension().equals(b.dimension())
                    || !a.pos().equals(b.pos())
                    || a.concealed() != b.concealed()) {
                return false;
            }
        }
        return true;
    }

    public static int indexOfId(List<RespawnEntry> list, String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id())) {
                return i;
            }
        }
        return -1;
    }
}
