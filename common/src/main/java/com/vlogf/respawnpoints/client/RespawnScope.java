package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.model.RespawnEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Death-dimension scoping for the respawn screens: the death dimension plus
 * the overworld are shown (died in overworld - only it; died in the Nether -
 * Nether + overworld; died in the End - End + overworld). Server offer and
 * validation are untouched - display only.
 */
public final class RespawnScope {
    private static final String OVERWORLD = "minecraft:overworld";

    private RespawnScope() {
    }

    public static List<RespawnEntry> filter(List<RespawnEntry> entries, String deathDim) {
        if (deathDim == null) {
            return List.copyOf(entries);
        }
        List<RespawnEntry> visible = new ArrayList<>();
        for (RespawnEntry entry : entries) {
            if (deathDim.equals(entry.dimension()) || OVERWORLD.equals(entry.dimension())) {
                visible.add(entry);
            }
        }
        return visible.isEmpty() ? List.copyOf(entries) : visible;
    }
}
