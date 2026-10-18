package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.EntryNames;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.platform.Services;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.event.WaypointEvent;
import journeymap.api.v2.common.event.common.WaypointGroupEvent;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Syncs respawn options as client-local JourneyMap waypoints in a dedicated,
 * locked "Respawn Points" group so they never mix with the user's "Default"
 * group. Stable linkage via waypoint custom data ({@code respawnpoints:id}).
 * <p>
 * ISOLATION: journeymap imports live only in this package. Called reflectively.
 */
public final class JourneyMapWaypointSync {
    private static final String CUSTOM_KEY = "respawnpoints:id";
    private static final String GROUP_MOD_ID = "respawnpoints";
    private static final String GROUP_NAME = "Respawn Points";

    private static volatile WaypointGroup respawnGroup;
    /** entry.id -> Waypoint, for sweep and event matching. Thread-safe. */
    private static final Map<String, Waypoint> TRACKED_WAYPOINTS = new ConcurrentHashMap<>();
    /** entry.id -> last server colour (RGB), for detecting map-side recolours. Thread-safe. */
    private static final Map<String, Integer> LAST_COLOR = new ConcurrentHashMap<>();
    /** Set while we are mutating waypoints internally, so our own
     *  api.addWaypoint calls do not trigger recolour packets to the server. */
    private static volatile boolean internalUpdating = false;

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
        switch (event.getContext()) {
            case UPDATE -> {
                int mapColor = wp.getColor() & 0xFFFFFF;
                Integer last = LAST_COLOR.get(entryId);
                if (last != null && last != mapColor) {
                    LAST_COLOR.put(entryId, mapColor);
                    sendRecolourToServer(entryId, mapColor);
                }
            }
            case DELETED -> {
                // Player deleted our system waypoint in JourneyMap UI: restore it
                // inside the Respawn Points group so it never falls into Default.
                internalUpdating = true;
                try {
                    if (respawnGroup != null) {
                        respawnGroup.addWaypoint(wp);
                    }
                    IClientAPI api = RespawnJourneyMapPlugin.api();
                    if (api != null) {
                        api.addWaypoint(GROUP_MOD_ID, wp);
                    }
                    LAST_COLOR.put(entryId, wp.getColor() & 0xFFFFFF);
                } finally {
                    internalUpdating = false;
                }
            }
            case CREATE, READ -> {
            }
        }
    }

    /** Called by {@link RespawnJourneyMapPlugin} on WAYPOINT_GROUP events. */
    public static void onWaypointGroupEvent(WaypointGroupEvent event) {
        if (internalUpdating) {
            return;
        }
        if (event.getContext() != WaypointGroupEvent.Context.DELETED) {
            return;
        }
        WaypointGroup group = event.getGroup();
        if (group == null || !GROUP_MOD_ID.equals(group.getModId())) {
            return;
        }
        if (!GROUP_NAME.equals(group.getName())) {
            return;
        }
        // Our group was deleted: reset state so the next sync() recreates it
        // with setLocked(true) and re-adds all tracked waypoints.
        internalUpdating = true;
        try {
            respawnGroup = null;
            TRACKED_WAYPOINTS.clear();
            LAST_COLOR.clear();
            Constants.LOG.debug("JourneyMap group '{}' deleted, will recreate on next sync", group.getName());
        } finally {
            internalUpdating = false;
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
        WaypointGroup group = respawnGroup;
        if (group == null) {
            return;
        }

        Map<String, RespawnEntry> wanted = new java.util.HashMap<>();
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
                    wp = WaypointFactory.createWaypoint(GROUP_MOD_ID, pos, name, dim, false);
                    wp.setCustomData(CUSTOM_KEY, entryId);
                    wp.setColor(color);
                    wp.setEnabled(true);
                    group.addWaypoint(wp);
                    api.addWaypoint(GROUP_MOD_ID, wp);
                    TRACKED_WAYPOINTS.put(entryId, wp);
                    Constants.LOG.trace("JM sync: created waypoint '{}' in group '{}'",
                            entryId, group.getName());
                } else {
                    wp.setName(name);
                    wp.setBlockPos(pos);
                    wp.setPrimaryDimension(dim);
                    wp.setColor(color);
                    wp.setEnabled(true);
                    // Re-add to group in case group reference changed.
                    group.addWaypoint(wp);
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
        WaypointGroup current = respawnGroup;
        if (current != null) {
            // Verify the group still exists (user may have deleted it).
            IClientAPI api = RespawnJourneyMapPlugin.api();
            if (api != null) {
                boolean stillExists = false;
                try {
                    for (WaypointGroup g : api.getWaypointGroups(GROUP_MOD_ID)) {
                        if (g.getGuid().equals(current.getGuid())) {
                            stillExists = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    Constants.LOG.debug("Could not verify JourneyMap group existence", e);
                }
                if (stillExists) {
                    // Re-apply lock in case the user removed it via UI.
                    try {
                        current.setLocked(true);
                    } catch (Exception e) {
                        Constants.LOG.debug("Could not re-apply lock to waypoint group", e);
                    }
                    return;
                }
            }
            // Group gone: fall through to recreate.
            respawnGroup = null;
        }
        IClientAPI api = RespawnJourneyMapPlugin.api();
        if (api == null) {
            return;
        }
        try {
            // Reuse an existing group with the same name if present (avoids
            // duplicates across reloads/server switches).
            WaypointGroup existing = api.getWaypointGroupByName(GROUP_MOD_ID, GROUP_NAME);
            if (existing != null) {
                respawnGroup = existing;
                try {
                    existing.setLocked(true);
                } catch (Exception e) {
                    Constants.LOG.debug("Could not re-apply lock to waypoint group", e);
                }
                Constants.LOG.trace("JM sync: reused existing waypoint group '{}'", existing.getName());
            } else {
                respawnGroup = WaypointFactory.createWaypointGroup(GROUP_MOD_ID, GROUP_NAME);
                api.addWaypointGroup(respawnGroup);
                respawnGroup.setLocked(true);
                Constants.LOG.trace("JM sync: created locked waypoint group '{}'", respawnGroup.getName());
            }
        } catch (Throwable t) {
            Constants.LOG.warn("Could not create/reuse JourneyMap waypoint group", t);
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
