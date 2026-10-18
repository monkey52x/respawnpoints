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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Syncs respawn options as client-local JourneyMap waypoints in a dedicated,
 * locked "Respawn Points" group so they never mix with the user's "Default"
 * group. Stable linkage via waypoint custom data ({@code respawnpoints:id}).
 * <p>
 * Follows the Xaero third-party waypoint pattern:
 * <ul>
 *   <li>Server-authoritative names and positions — always overwritten from the
 *       server offer, never preserved from user edits.</li>
 *   <li>Two-way colour sync: if the user recolours a marker in JourneyMap, the
 *       change is sent back to the server via {@link RecolorPointPacket}.</li>
 *   <li>{@code LAST_COLOR} tracks what we last wrote to the map; a mismatch
 *       means the user edited the colour in JourneyMap UI.</li>
 *   <li>{@code internalUpdating} blocks our own programmatic writes from
 *       being interpreted as user edits and bouncing packets back to the
 *       server.</li>
 * </ul>
 * <p>
 * Waypoint identity is stored as entryId &rarr; JourneyMap GUID (not live
 * Waypoint objects), so the mapping survives JourneyMap reloads. On
 * {@link #onMappingStarted()} the tracking map is restored from the API by
 * reading {@code respawnpoints:id} custom data from each waypoint in our
 * group.
 * <p>
 * ISOLATION: journeymap imports live only in this package. Called reflectively.
 */
public final class JourneyMapWaypointSync {
    private static final String CUSTOM_KEY = "respawnpoints:id";
    private static final String GROUP_MOD_ID = "respawnpoints";
    private static final String GROUP_NAME = "Respawn Points";

    private static volatile IClientAPI api;
    private static volatile WaypointGroup respawnGroup;
    private static volatile boolean mappingStarted = false;
    /** Set while we are mutating waypoints internally, so our own
     *  api.addWaypoint / api.removeWaypoint calls do not trigger recolour
     *  packets to the server. */
    private static volatile boolean internalUpdating = false;
    /** entry.id -> JourneyMap waypoint GUID. Survives JourneyMap reloads. */
    private static final Map<String, String> TRACKED_WAYPOINT_GUIDS = new ConcurrentHashMap<>();
    /** entry.id -> last colour we wrote to the map (RGB). Detects user edits. */
    private static final Map<String, Integer> LAST_COLOR = new ConcurrentHashMap<>();
    /** Death offer entries received before MAPPING_STARTED; synced once ready. */
    private static volatile List<RespawnEntry> pendingSync = null;

    private JourneyMapWaypointSync() {
    }

    /** Set the current API instance (called by {@link RespawnJourneyMapPlugin}). */
    public static void setApi(IClientAPI jmClientApi) {
        api = jmClientApi;
    }

    /** Called reflectively from {@code MapIntegrations.syncClientWaypoints}. */
    public static void sync(List<RespawnEntry> entries) {
        if (internalUpdating) {
            return;
        }
        pendingSync = List.copyOf(entries);
        if (!mappingStarted || api == null) {
            // JourneyMap not ready yet: sync will run on MAPPING_STARTED.
            return;
        }
        syncInner(entries);
    }

    /** Called by {@link RespawnJourneyMapPlugin} on MAPPING_STARTED. */
    public static void onMappingStarted() {
        mappingStarted = true;
        ensureGroup();
        restoreTrackingFromApi();
        List<RespawnEntry> pending = pendingSync;
        if (pending != null) {
            pendingSync = null;
            syncInner(pending);
        }
    }

    /** Called by {@link RespawnJourneyMapPlugin} on MAPPING_STOPPED. */
    public static void onMappingStopped() {
        mappingStarted = false;
        clearTracking();
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
        if (entryId == null || !TRACKED_WAYPOINT_GUIDS.containsKey(entryId)) {
            return;
        }
        switch (event.getContext()) {
            case UPDATE -> {
                int mapColor = wp.getColor() & 0xFFFFFF;
                Integer last = LAST_COLOR.get(entryId);
                if (last != null && last != mapColor) {
                    // User recoloured the marker in JourneyMap UI: echo back
                    // to the server. LAST_COLOR is updated so we don't bounce
                    // our own programmatic writes.
                    LAST_COLOR.put(entryId, mapColor);
                    sendRecolourToServer(entryId, mapColor);
                } else {
                    // No user change: keep our server-authoritative colour.
                    LAST_COLOR.put(entryId, mapColor);
                }
            }
            case DELETED -> {
                // Player deleted our system waypoint in JourneyMap UI: restore it
                // inside the Respawn Points group so it never falls into Default.
                String guid = TRACKED_WAYPOINT_GUIDS.get(entryId);
                if (guid != null) {
                    internalUpdating = true;
                    try {
                        IClientAPI currentApi = api;
                        if (currentApi != null && respawnGroup != null) {
                            Waypoint restored = currentApi.getWaypoint(GROUP_MOD_ID, guid);
                            if (restored != null) {
                                respawnGroup.addWaypoint(restored);
                                currentApi.addWaypoint(GROUP_MOD_ID, restored);
                            }
                        }
                    } finally {
                        internalUpdating = false;
                    }
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
            TRACKED_WAYPOINT_GUIDS.clear();
            LAST_COLOR.clear();
            Constants.LOG.debug("JourneyMap group '{}' deleted, will recreate on next sync", group.getName());
        } finally {
            internalUpdating = false;
        }
    }

    /** Remove all our waypoints from JourneyMap and reset state. */
    public static void clearTracking() {
        internalUpdating = true;
        try {
            IClientAPI currentApi = api;
            if (currentApi != null) {
                try {
                    currentApi.removeAllWaypoints(GROUP_MOD_ID);
                } catch (Exception e) {
                    Constants.LOG.warn("Could not remove all JourneyMap waypoints for respawnpoints", e);
                }
            }
            TRACKED_WAYPOINT_GUIDS.clear();
            LAST_COLOR.clear();
            respawnGroup = null;
        } finally {
            internalUpdating = false;
        }
    }

    /* ------------------------------------------------------------------ */
    private static void syncInner(List<RespawnEntry> entries) {
        IClientAPI currentApi = api;
        if (currentApi == null) {
            return;
        }
        ensureGroup();
        WaypointGroup group = respawnGroup;
        if (group == null) {
            return;
        }

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
                BlockPos pos = entry.pos();
                String dim = entry.dimension();
                String name = EntryNames.displayName(entry);
                int color = entry.color();

                String guid = TRACKED_WAYPOINT_GUIDS.get(entryId);
                Waypoint wp = guid != null ? currentApi.getWaypoint(GROUP_MOD_ID, guid) : null;

                if (wp == null) {
                    // Create a new client-side waypoint.
                    wp = WaypointFactory.createClientWaypoint(GROUP_MOD_ID, pos, name, dim, false);
                    wp.setCustomData(CUSTOM_KEY, entryId);
                    wp.setColor(color);
                    wp.setEnabled(true);
                    group.addWaypoint(wp);
                    currentApi.addWaypoint(GROUP_MOD_ID, wp);
                    String newGuid = wp.getGuid();
                    TRACKED_WAYPOINT_GUIDS.put(entryId, newGuid);
                    LAST_COLOR.put(entryId, color);
                    Constants.LOG.trace("JM sync: created waypoint '{}' (guid={}) in group '{}'",
                            entryId, newGuid, group.getName());
                } else {
                    // Existing waypoint: check if the user recoloured it in JourneyMap.
                    int mapColor = wp.getColor() & 0xFFFFFF;
                    Integer last = LAST_COLOR.get(entryId);
                    if (last != null && last != mapColor) {
                        // User edit detected: send back to server.
                        LAST_COLOR.put(entryId, mapColor);
                        sendRecolourToServer(entryId, mapColor);
                    } else {
                        // No user change: keep server-authoritative colour.
                        LAST_COLOR.put(entryId, color);
                    }
                    // Server-authoritative name, position, dimension, enabled.
                    wp.setName(name);
                    wp.setBlockPos(pos);
                    wp.setPrimaryDimension(dim);
                    wp.setColor(color);
                    wp.setEnabled(true);
                    // Re-add to group in case group reference changed.
                    group.addWaypoint(wp);
                    currentApi.addWaypoint(GROUP_MOD_ID, wp);
                    Constants.LOG.trace("JM sync: updated waypoint '{}' (guid={})", entryId, guid);
                }
            }

            // Sweep: remove tracked waypoints no longer in the offer.
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, String> e : TRACKED_WAYPOINT_GUIDS.entrySet()) {
                if (!wanted.containsKey(e.getKey())) {
                    toRemove.add(e.getKey());
                }
            }
            for (String entryId : toRemove) {
                String guid = TRACKED_WAYPOINT_GUIDS.remove(entryId);
                LAST_COLOR.remove(entryId);
                if (guid != null) {
                    try {
                        currentApi.removeWaypoint(GROUP_MOD_ID,
                                currentApi.getWaypoint(GROUP_MOD_ID, guid));
                    } catch (Exception ex) {
                        Constants.LOG.warn("Could not remove JourneyMap waypoint '{}'", entryId, ex);
                    }
                }
                Constants.LOG.trace("JM sync: removed stale waypoint '{}'", entryId);
            }
        } finally {
            internalUpdating = false;
        }
    }

    private static void ensureGroup() {
        if (respawnGroup != null) {
            return;
        }
        IClientAPI currentApi = api;
        if (currentApi == null) {
            return;
        }
        try {
            // Reuse an existing group with the same name if present (avoids
            // duplicates across reloads/server switches).
            WaypointGroup existing = currentApi.getWaypointGroupByName(GROUP_MOD_ID, GROUP_NAME);
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
                currentApi.addWaypointGroup(respawnGroup);
                respawnGroup.setLocked(true);
                Constants.LOG.trace("JM sync: created locked waypoint group '{}'", respawnGroup.getName());
            }
        } catch (Throwable t) {
            Constants.LOG.warn("Could not create/reuse JourneyMap waypoint group", t);
        }
    }

    /** Restore entryId &rarr; GUID mapping from JourneyMap API on MAPPING_STARTED. */
    private static void restoreTrackingFromApi() {
        IClientAPI currentApi = api;
        if (currentApi == null) {
            return;
        }
        try {
            var waypoints = currentApi.getWaypoints(GROUP_MOD_ID);
            if (waypoints == null) {
                return;
            }
            for (Waypoint wp : waypoints) {
                String entryId = wp.getCustomData(CUSTOM_KEY);
                if (entryId != null) {
                    TRACKED_WAYPOINT_GUIDS.put(entryId, wp.getGuid());
                    LAST_COLOR.put(entryId, wp.getColor() & 0xFFFFFF);
                }
            }
            Constants.LOG.trace("JM sync: restored {} tracked waypoints from API",
                    TRACKED_WAYPOINT_GUIDS.size());
        } catch (Exception e) {
            Constants.LOG.debug("Could not restore JourneyMap tracking from API", e);
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
