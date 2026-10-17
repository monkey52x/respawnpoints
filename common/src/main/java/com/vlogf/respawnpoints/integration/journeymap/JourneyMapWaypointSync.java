package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.EntryNames;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.platform.Services;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.event.WaypointEvent;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs respawn options as client-local JourneyMap waypoints in a dedicated
 * group "Respawn Points" so they never mix with the user's "Default" group.
 * Stable linkage via waypoint custom data ({@code respawnpoints:id}).
 * <p>
 * ISOLATION: journeymap imports live only in this package. Called reflectively.
 */
public final class JourneyMapWaypointSync {
    private static final String CUSTOM_KEY = "respawnpoints:id";
    private static final String GROUP_MOD_ID = "respawnpoints";
    private static final String GROUP_NAME_FALLBACK = "Respawn Points";

    private static WaypointGroup respawnGroup;
    /** entry.id -> Waypoint, for sweep and event matching. */
    private static final Map<String, Waypoint> TRACKED_WAYPOINTS = new HashMap<>();
    /** entry.id -> last server colour (RGB), for detecting map-side recolours. */
    private static final Map<String, Integer> LAST_COLOR = new HashMap<>();
    /** Set while we are mutating waypoints internally, so our own
     *  api.addWaypoint calls do not trigger recolour packets to the server. */
    private static boolean internalUpdating = false;

    private JourneyMapWaypointSync() {
    }

    /** Called reflectively from {@code MapIntegrations.syncClientWaypoints}. */
    public static void sync(List<RespawnEntry> entries) {
        try {
            syncInner(entries);
        } catch (Throwable t) {
            Constants.LOG.warn("JourneyMap waypoint sync failed, skipping", t);
        }
    }

    /** Called by {@link RespawnJourneyMapPlugin} on WAYPOINT events. */
    public static void onWaypointEvent(WaypointEvent event) {
        if (internalUpdating) {
            return;
        }
        Waypoint wp = event.getWaypoint();
        if (wp == null) {
            return;
        }
        if (!GROUP_MOD_ID.equals(wp.getModId())) {
            return;
        }
        String entryId = wp.getCustomData(CUSTOM_KEY);
        if (entryId == null || !TRACKED_WAYPOINTS.containsKey(entryId)) {
            return;
        }
        var ctx = event.getContext();
        if (ctx != null) {
            String ctxName = ctx.name();
            switch (ctxName) {
                case "UPDATE", "UPDATED" -> {
                    int mapColor = wp.getColor() & 0xFFFFFF;
                    Integer last = LAST_COLOR.get(entryId);
                    if (last != null && last != mapColor) {
                        LAST_COLOR.put(entryId, mapColor);
                        sendRecolourToServer(entryId, mapColor);
                    }
                }
                case "DELETED", "REMOVED" -> {
                    // Player deleted our system waypoint in JourneyMap UI: restore it.
                    internalUpdating = true;
                    try {
                        IClientAPI api = RespawnJourneyMapPlugin.api();
                        if (api != null) {
                            api.addWaypoint(GROUP_MOD_ID, wp);
                        }
                        LAST_COLOR.put(entryId, wp.getColor() & 0xFFFFFF);
                    } finally {
                        internalUpdating = false;
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Called by {@link RespawnJourneyMapPlugin} on MAPPING_STARTED. */
    public static void onMappingStarted() {
        ensureGroup();
    }

    /** Called by {@link RespawnJourneyMapPlugin} on MAPPING_STOPPED. */
    public static void onMappingStopped() {
        clearTracking();
    }

    /** Remove all our waypoints from JourneyMap and reset state. */
    public static void clearTracking() {
        IClientAPI api = RespawnJourneyMapPlugin.api();
        if (api != null) {
            try {
                api.removeAllWaypoints(GROUP_MOD_ID);
            } catch (Exception e) {
                Constants.LOG.warn("Could not remove all JourneyMap waypoints for respawnpoints", e);
            }
        }
        TRACKED_WAYPOINTS.clear();
        LAST_COLOR.clear();
        respawnGroup = null;
    }

    /* ------------------------------------------------------------------ */
    private static void syncInner(List<RespawnEntry> entries) {
        IClientAPI api = RespawnJourneyMapPlugin.api();
        if (api == null) {
            return;
        }
        ensureGroup();

        Map<String, RespawnEntry> wanted = new HashMap<>();
        for (RespawnEntry entry : entries) {
            if (!entry.concealed()) {
                wanted.put(entry.id(), entry);
            }
        }

        internalUpdating = true;
        try {
            // Update or create each wanted waypoint.
            for (RespawnEntry entry : wanted.values()) {
                String entryId = entry.id();
                Waypoint wp = TRACKED_WAYPOINTS.get(entryId);
                BlockPos pos = entry.pos();
                String dim = entry.dimension();
                String name = EntryNames.displayName(entry);
                int color = entry.color();

                if (wp == null) {
                    wp = WaypointFactory.createClientWaypoint(GROUP_MOD_ID, pos, name, dim, false);
                    wp.setCustomData(CUSTOM_KEY, entryId);
                    wp.setColor(color);
                    wp.setEnabled(true);
                    respawnGroup.addWaypoint(wp);
                    api.addWaypoint(GROUP_MOD_ID, wp);
                    TRACKED_WAYPOINTS.put(entryId, wp);
                    Constants.LOG.trace("JM sync: created waypoint '{}' in group '{}'",
                            entryId, respawnGroup.getName());
                } else {
                    wp.setName(name);
                    wp.setBlockPos(pos);
                    wp.setPrimaryDimension(dim);
                    wp.setColor(color);
                    wp.setEnabled(true);
                    respawnGroup.addWaypoint(wp);
                    api.addWaypoint(GROUP_MOD_ID, wp);
                }
                LAST_COLOR.put(entryId, color);
            }

            // Sweep: remove tracked waypoints no longer in the offer.
            TRACKED_WAYPOINTS.keySet().removeIf(id -> {
                if (!wanted.containsKey(id)) {
                    Waypoint wp = TRACKED_WAYPOINTS.get(id);
                    if (wp != null) {
                        try {
                            api.removeWaypoint(GROUP_MOD_ID, wp);
                        } catch (Exception e) {
                            Constants.LOG.warn("Could not remove JourneyMap waypoint '{}'", id, e);
                        }
                    }
                    LAST_COLOR.remove(id);
                    return true;
                }
                return false;
            });
        } finally {
            internalUpdating = false;
        }
    }

    private static void ensureGroup() {
        if (respawnGroup != null) {
            return;
        }
        IClientAPI api = RespawnJourneyMapPlugin.api();
        if (api == null) {
            return;
        }
        try {
            respawnGroup = WaypointFactory.createWaypointGroup(GROUP_MOD_ID, GROUP_NAME_FALLBACK);
            api.addWaypointGroup(respawnGroup);
            Constants.LOG.trace("JM sync: created waypoint group '{}'", respawnGroup.getName());
        } catch (Throwable t) {
            Constants.LOG.warn("Could not create JourneyMap waypoint group", t);
        }
    }

    private static void sendRecolourToServer(String entryId, int color) {
        try {
            Services.NETWORK.sendToServer(new RecolorPointPacket(entryId, color));
        } catch (Exception e) {
            Constants.LOG.warn("Could not send recolour packet to server for '{}'", entryId, e);
        }
    }
}
