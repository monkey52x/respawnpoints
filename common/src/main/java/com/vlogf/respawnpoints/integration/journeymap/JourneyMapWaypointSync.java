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
import net.minecraft.client.Minecraft;
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
    /**
     * Last server-authoritative entry list we synced. Survives a stop/start
     * cycle (e.g. a dimension switch, which fires MAPPING_STOPPED followed by
     * MAPPING_STARTED without a fresh server packet), so markers can be
     * restored and the group recreated. Cleared only on a real server/world
     * change (see {@link #clearTracking()}).
     */
    private static volatile List<RespawnEntry> lastEntries = List.of();

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
        List<RespawnEntry> copy = List.copyOf(entries);
        lastEntries = copy;
        if (!mappingStarted || api == null) {
            // JourneyMap not ready yet: sync will run on MAPPING_STARTED.
            pendingSync = copy;
            return;
        }
        pendingSync = null;
        syncInner(copy);
    }

    /** Called by {@link RespawnJourneyMapPlugin} on MAPPING_STARTED. */
    public static void onMappingStarted() {
        mappingStarted = true;
        ensureGroup();
        restoreTrackingFromApi();
        // Members may have been moved to Default while the group was gone
        // (e.g. deleted by the user, or a stop/start cycle): reclaim them.
        reconcileTrackedIntoGroup();
        List<RespawnEntry> pending = pendingSync;
        if (pending != null) {
            pendingSync = null;
            syncInner(pending);
        }
    }

    /**
     * Called by {@link RespawnJourneyMapPlugin} on MAPPING_STOPPED. This also
     * fires for a simple dimension switch (stop then start), so only the
     * runtime map bindings are dropped here: {@code lastEntries} is preserved
     * so markers can be restored on the next MAPPING_STARTED without waiting
     * for a new server packet.
     */
    public static void onMappingStopped() {
        mappingStarted = false;
        clearRuntimeBindings();
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
                                // Follow Waystones pattern: api.addWaypoint first,
                                // then add to group with lock toggle.
                                currentApi.addWaypoint(GROUP_MOD_ID, restored);
                                respawnGroup.setLocked(false);
                                respawnGroup.addWaypoint(restored);
                                respawnGroup.setLocked(true);
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
        // Our group was deleted. JourneyMap is mid-deletion here, so do NOT
        // touch its API synchronously: drop the stale group reference and
        // recreate/reclaim on the next client task to avoid a
        // ConcurrentModificationException. Tracking maps are kept so existing
        // waypoints are re-added (not duplicated).
        respawnGroup = null;
        Constants.LOG.debug("JourneyMap group '{}' deleted, recreating", group.getName());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(JourneyMapWaypointSync::recreateGroupAfterDeletion);
        } else {
            recreateGroupAfterDeletion();
        }
    }

    /**
     * Recreates the group after a user deletion and reclaims tracked markers,
     * even when the entry list is empty (the group must exist regardless).
     */
    private static void recreateGroupAfterDeletion() {
        if (!mappingStarted || api == null) {
            return;
        }
        ensureGroup();
        List<RespawnEntry> snapshot = lastEntries;
        if (snapshot.isEmpty()) {
            return;
        }
        syncInner(snapshot);
    }

    /**
     * Re-adds every tracked waypoint into {@link #respawnGroup}. Used on
     * MAPPING_STARTED to recover from a group that was deleted (its waypoints
     * fall back to JourneyMap's "Default" group) while mapping was stopped.
     */
    private static void reconcileTrackedIntoGroup() {
        IClientAPI currentApi = api;
        WaypointGroup group = respawnGroup;
        if (currentApi == null || group == null) {
            return;
        }
        internalUpdating = true;
        try {
            for (Map.Entry<String, String> tracked : TRACKED_WAYPOINT_GUIDS.entrySet()) {
                String guid = tracked.getValue();
                if (guid == null) {
                    continue;
                }
                Waypoint wp = currentApi.getWaypoint(GROUP_MOD_ID, guid);
                if (wp == null) {
                    continue;
                }
                currentApi.addWaypoint(GROUP_MOD_ID, wp);
                group.setLocked(false);
                group.addWaypoint(wp);
                group.setLocked(true);
            }
        } catch (Exception e) {
            Constants.LOG.debug("Could not reconcile JourneyMap waypoints into group", e);
        } finally {
            internalUpdating = false;
        }
    }

    /**
     * Remove all our waypoints from JourneyMap and reset state. Called on a
     * real server/world change: this is the only place that also drops
     * {@link #lastEntries}, so points from the previous world can never leak
     * into the new one on the next mapping start.
     */
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
            lastEntries = List.of();
            // Не сбрасываем respawnGroup: группа должна остаться, чтобы при
            // повторном входе в мир не требовалось перезаходить для её воссоздания.
        } finally {
            internalUpdating = false;
        }
    }

    /**
     * Drops only the runtime map bindings (entryId &rarr; GUID and last colour).
     * Used on MAPPING_STOPPED, which also happens for a dimension switch:
     * {@link #lastEntries} and the group object are intentionally kept.
     */
    private static void clearRuntimeBindings() {
        TRACKED_WAYPOINT_GUIDS.clear();
        LAST_COLOR.clear();
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
                    // Follow Waystones pattern: api.addWaypoint first, then add to
                    // group with lock toggle so the marker lands in our group,
                    // not in JourneyMap's "Default" group.
                    currentApi.addWaypoint(GROUP_MOD_ID, wp);
                    group.setLocked(false);
                    group.addWaypoint(wp);
                    group.setLocked(true);
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
                    // Re-add to group with lock toggle to ensure it stays in our
                    // group and not in JourneyMap's "Default" group.
                    currentApi.addWaypoint(GROUP_MOD_ID, wp);
                    group.setLocked(false);
                    group.addWaypoint(wp);
                    group.setLocked(true);
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
