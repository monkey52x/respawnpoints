package com.vlogf.respawnpoints.integration.xaero;

import java.util.ArrayList;
import java.util.List;
import xaero.hud.minimap.waypoint.WaypointColor;

/**
 * Xaero waypoint palette mirrored for our own pickers (e.g. the rename
 * screen). Loaded reflectively only when the minimap is present, so direct
 * Xaero references are safe here. Same hexes the waypoint sync pins, so a
 * picked color round-trips exactly instead of nearest-matching.
 */
public final class XaeroPalette {
    private XaeroPalette() {
    }

    /** Exact Xaero palette hexes in enum order (usually 21). */
    public static List<Integer> fetchHexes() {
        List<Integer> hexes = new ArrayList<>();
        try {
            for (WaypointColor color : WaypointColor.values()) {
                try {
                    int hex = color.getHex() & 0xFFFFFF;
                    if (!hexes.contains(hex)) {
                        hexes.add(hex);
                    }
                } catch (Exception ignored) {
                    // Skip unreadable entries.
                }
            }
        } catch (Exception | LinkageError ignored) {
            // Fall back to vanilla dyes at the call site.
        }
        return hexes;
    }
}
