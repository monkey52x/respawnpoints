package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client-side display names for respawn entries. Custom names (beds, warps,
 * renamed spawn) show as typed; empty names resolve to localized defaults, so
 * they follow the game language instead of freezing server-side English.
 * Used by both death screens and both map waypoint syncs — keep them in sync.
 */
public final class EntryNames {
    private EntryNames() {
    }

    public static String displayName(RespawnEntry entry) {
        if ("spawn".equals(entry.id())) {
            if (!entry.name().isEmpty()) {
                return entry.name();
            }
            return Component.translatable("respawnpoints.entry.spawn").getString();
        }
        if (entry.type() == RespawnPointsType.NEAR_DEATH) {
            return Component.translatable("respawnpoints.entry.neardeath").getString();
        }
        if (!entry.name().isEmpty()) {
            return entry.name();
        }
        BlockPos pos = entry.pos();
        String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        String type = switch (entry.type()) {
            case WARP -> Component.translatable("respawnpoints.entry.type_warp").getString();
            case BED -> Component.translatable("respawnpoints.entry.type_bed").getString();
            case ANCHOR -> Component.translatable("respawnpoints.entry.type_anchor").getString();
            case NEAR_DEATH, SPAWN ->
                    Component.translatable("respawnpoints.entry.type_neardeath").getString();
        };
        return type + " (" + coords + ")";
    }
}
