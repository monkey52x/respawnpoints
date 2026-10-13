package com.vlogf.respawnpoints.integration;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.MapManagerChoice;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.platform.Services;

import java.util.List;

/**
 * Optional map integrations. Implementation classes reference third-party APIs
 * directly, so they are only ever loaded reflectively and only when the
 * corresponding mod is present. Missing map mods can never cause
 * ClassNotFoundException / NoClassDefFoundError.
 */
public final class MapIntegrations {
    private static final String XAERO_SYNC = "com.vlogf.respawnpoints.integration.xaero.XaeroWaypointSync";
    private static final String JOURNEYMAP_SYNC = "com.vlogf.respawnpoints.integration.journeymap.JourneyMapWaypointSync";

    private MapIntegrations() {
    }

    public static void init() {
        // Presence is checked lazily at sync/resolve time; nothing to do here.
    }

    /** Both Xaero mods are mandatory: the screen needs WorldMap, markers need Minimap. */
    public static boolean isXaeroPresent() {
        return Services.PLATFORM.isModLoaded("xaerominimap")
                && Services.PLATFORM.isModLoaded("xaeroworldmap");
    }

    public static boolean isJourneyMapPresent() {
        return Services.PLATFORM.isModLoaded("journeymap");
    }

    /** Client side only: mirror the offered respawn points in the chosen manager. */
    public static void syncClientWaypoints(List<RespawnEntry> entries) {
        RespawnClientConfig.ActiveMap map = MapManagerChoice.resolve();
        if (map == RespawnClientConfig.ActiveMap.XAERO && isXaeroPresent()) {
            // Waypoint sync itself needs the minimap mod; the worldmap alone
            // only drives the respawn screen.
            if (Services.PLATFORM.isModLoaded("xaerominimap")) {
                invoke(XAERO_SYNC, entries);
            }
        } else if (map == RespawnClientConfig.ActiveMap.JOURNEYMAP
                && isJourneyMapPresent()) {
            invoke(JOURNEYMAP_SYNC, entries);
        }
    }

    /** Reflective Xaero session check (common code must not import xaero APIs). */
    public static boolean isXaeroSessionReady() {
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object session = sessionClass.getDeclaredMethod("getCurrentSession").invoke(null);
            if (session == null) {
                return false;
            }
            Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
            if (manager == null) {
                return false;
            }
            Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
            return world != null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static void invoke(String className, List<RespawnEntry> entries) {
        try {
            Class<?> clazz = Class.forName(className);
            var method = clazz.getDeclaredMethod("sync", List.class);
            method.invoke(null, entries);
        } catch (ReflectiveOperationException | LinkageError e) {
            Constants.LOG.warn("Map waypoint sync '{}' failed, skipping", className, e);
        }
    }
}
