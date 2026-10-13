package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.EntryNames;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;
import net.minecraft.client.Minecraft;

/** Shared helpers for both respawn selection screens (deduplicated). */
public final class ScreenUtils {
    private ScreenUtils() {
    }

    public static String shortDimName(String dim) {
        // Dimension names stay English on every client (map/world convention).
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> {
                int colon = dim.indexOf(':');
                yield colon >= 0 ? dim.substring(colon + 1) : dim;
            }
        };
    }

    public static boolean isManageable(RespawnEntry entry) {
        return entry.type() == RespawnPointsType.BED || entry.type() == RespawnPointsType.ANCHOR;
    }

    public static boolean isDepleted(RespawnEntry entry) {
        return entry.type() == RespawnPointsType.ANCHOR && entry.charges() == 0;
    }

    /** Distance text: concealed -> ???, foreign dim -> dim name, own dim -> meters (3D from player). */
    public static String distanceText(RespawnEntry entry) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return "";
        }
        if (entry.concealed()) {
            return "???";
        }
        String playerDim = player.level().dimension().location().toString();
        if (!playerDim.equals(entry.dimension())) {
            return shortDimName(entry.dimension());
        }
        return (int) Math.sqrt(player.blockPosition().distSqr(entry.pos())) + "m";
    }

    public static String displayName(RespawnEntry entry) {
        return EntryNames.displayName(entry);
    }
}
