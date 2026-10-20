package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.EntryNames;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.platform.Services;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.common.event.CommonEventRegistry;
import journeymap.api.v2.common.event.common.WaypointEvent;
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
 * locked "Respawn Points" group. Stable linkage via waypoint custom data
 * ({@code respawnpoints:id}).
 *
 * <p>Behaviour mirrors Xaero integration:
 * <ul>
 *   <li>Server-authoritative names and positions — always overwritten from the
 *       server offer, never preserved from user edits.</li>
 *   <li>Two-way colour sync: if the user recolours a marker in JourneyMap, the
 *       change is detected and sent back to the server on the NEXT full sync
 *       (not instantly), exactly like Xaero's LAST_COLOR polling.</li>
 *   <li>{@code LAST_KNOWN_COLOR} tracks what we last wrote to the map; a mismatch
 *       on the next sync means the user edited the colour in JourneyMap UI.</li>
 *   <li>Group is looked up dynamically via {@code getWaypointGroupByName()}
 *       on every access — no static caching (Waystones pattern).</li>
 *   <li>Group created lazily on first waypoint access (Waystones pattern).</li>
 *   <li>Lock toggle on CREATE only (Waystones pattern). No lock toggle on UPDATE.</li>
 *   <li>Group deletion detection via {@code CommonEventRegistry.WAYPOINT_GROUP_EVENT}.</li>
 * </ul>
 *
 * <p>Waypoint identity is stored as entryId -> JourneyMap GUID (not live
 * Waypoint objects), so the mapping survives JourneyMap reloads. On
 * {@link #onMappingEvent(MappingEvent)} the tracking map is restored from the API by
 * reading {@code respawnpoints:id} custom data from each waypoint in our
 * group.
 *
 * <p>ISOLATION: journeymap imports live only in this package. Called reflectively.
 */
public final class JourneyMapWaypointSync {
    private static final String GROUP_NAME = "Respawn Points";
    private static final String MOD_ID = "respawnpoints";

    private static volatile IClientAPI api;
    private static volatile boolean journeyMapReady = false;
    private static volatile boolean groupDeleted = false;

    // Pending recolors detected during sync: entryId -> mapColor (RGB)
    // Sent to server as a batch AFTER all waypoints are updated.
    private static final Map<String, Integer> PENDING_RECOLORS = new ConcurrentHashMap<>();

    // Entries received before JourneyMap is ready; synced on MAPPING_STARTED.
    private static volatile List<RespawnEntry> pendingEntries;

    private JourneyMapWaypointSync() {
    }

    public static void setApi(IClientAPI jmClientApi) {
        api = jmClientApi;
    }

    /**
     * Full sync from server offer. If JourneyMap is ready, syncs immediately.
     * If not ready yet, caches entries for deferred sync on MAPPING_STARTED.
     */
    public static void sync(List<RespawnEntry> entries) {
        if (!journeyMapReady) {
            pendingEntries = List.copyOf(entries);
            return;
        }
        try {
            syncInner(List.copyOf(entries));
        } catch (Throwable t) {
            Constants.LOG.debug("JourneyMap waypoint sync failed, skipping", t);
        }
    }

    public static void onMappingEvent(MappingEvent event) {
        if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
            Constants.LOG.info("JourneyMap MAPPING_STARTED received");
            journeyMapReady = true;
            groupDeleted = false;
            restoreTracking();

            // Sync any entries that arrived before JourneyMap was ready
            List<RespawnEntry> cached = pendingEntries;
            pendingEntries = null;
            if (cached != null) {
                try {
                    syncInner(cached);
                } catch (Throwable t) {
                    Constants.LOG.debug("JourneyMap deferred sync failed", t);
                }
            }
        } else if (event.getStage() == MappingEvent.Stage.MAPPING_STOPPED) {
            journeyMapReady = false;
            groupDeleted = false;
            pendingEntries = null;
            JourneyMapTracking.clear();
            PENDING_RECOLORS.clear();
        }
    }

    /**
     * Waypoint CRUD events from JourneyMap.
     * We only track colour changes locally (deferred to next sync).
     * Name/position edits are ignored — next sync overwrites with server values.
     */
    public static void onWaypointEvent(WaypointEvent event) {
        if (!journeyMapReady) return;

        Waypoint wp = event.getWaypoint();
        if (wp == null || !MOD_ID.equals(wp.getModId())) {
            return;
        }
        String entryId = wp.getCustomData(JourneyMapTracking.CUSTOM_KEY);
        if (entryId == null || !java.util.Objects.equals(wp.getGuid(), JourneyMapTracking.getGuid(entryId))) {
            return;
        }

        switch (event.getContext()) {
            case CREATE, READ -> {
                // New waypoint appeared (user created manually or loaded from disk).
                // Register tracking and seed last-known color.
                JourneyMapTracking.putGuid(entryId, wp.getGuid());
                JourneyMapTracking.putLastKnownColor(entryId, wp.getColor() & 0xFFFFFF);
            }
            case UPDATE -> {
                // User edited something in UI. We only care about colour.
                int mapColor = wp.getColor() & 0xFFFFFF;
                Integer last = JourneyMapTracking.getLastKnownColor(entryId);
                if (last != null && last != mapColor) {
                    // Colour changed since our last write — user recoloured.
                    // Defer actual server packet to next full sync (Xaero-style).
                    PENDING_RECOLORS.put(entryId, mapColor);
                    JourneyMapTracking.putLastKnownColor(entryId, mapColor);
                }
            }
            case DELETED -> {
                // User deleted our waypoint from the map.
                JourneyMapTracking.removeGuid(entryId);
                JourneyMapTracking.removeLastKnownColor(entryId);
                PENDING_RECOLORS.remove(entryId);
            }
        }
    }

    /**
     * Waypoint group events. We only care about DELETE of our group.
     */
    public static void onWaypointGroupEvent(journeymap.api.v2.common.event.common.WaypointGroupEvent event) {
        if (!journeyMapReady) return;

        WaypointGroup group = event.getGroup();
        if (group == null || !MOD_ID.equals(group.getModId()) || !GROUP_NAME.equals(group.getName())) {
            return;
        }

        if (event.getContext() == journeymap.api.v2.common.event.common.WaypointGroupEvent.Context.DELETED) {
            Constants.LOG.info("JourneyMap group '{}' deleted by user, will recreate on next sync", GROUP_NAME);
            groupDeleted = true;
            JourneyMapTracking.clear();
            PENDING_RECOLORS.clear();
        }
    }

    public static void clearAll() {
        JourneyMapTracking.clear();
        PENDING_RECOLORS.clear();
        groupDeleted = false;
    }

    private static void restoreTracking() {
        IClientAPI currentApi = api;
        if (currentApi == null) {
            return;
        }
        try {
            // Only read waypoints from OUR group, not all mod waypoints.
            WaypointGroup group = currentApi.getWaypointGroupByName(MOD_ID, GROUP_NAME);
            if (group == null) {
                return;
            }
            int count = 0;
            for (String guid : group.getWaypointIds()) {
                Waypoint wp = currentApi.getWaypoint(MOD_ID, guid);
                if (wp != null) {
                    String entryId = wp.getCustomData(JourneyMapTracking.CUSTOM_KEY);
                    if (entryId != null) {
                        JourneyMapTracking.putGuid(entryId, wp.getGuid());
                        JourneyMapTracking.putLastKnownColor(entryId, wp.getColor() & 0xFFFFFF);
                        count++;
                    }
                }
            }
            Constants.LOG.info("JourneyMap restored {} tracked waypoints from group '{}'", count, GROUP_NAME);
        } catch (Exception e) {
            Constants.LOG.debug("Could not restore JourneyMap tracking from API", e);
        }
    }

    // Waystones pattern: dynamic group lookup, no caching
    private static WaypointGroup getOrCreateGroup(IClientAPI currentApi) {
        if (groupDeleted) {
            groupDeleted = false;
            return createNewGroup(currentApi);
        }
        WaypointGroup group = currentApi.getWaypointGroupByName(MOD_ID, GROUP_NAME);
        if (group != null && isGroupValid(group)) {
            return group;
        }
        return createNewGroup(currentApi);
    }

    private static WaypointGroup createNewGroup(IClientAPI currentApi) {
        WaypointGroup group = WaypointFactory.createWaypointGroup(MOD_ID, GROUP_NAME);
        group.setLocked(true);
        currentApi.addWaypointGroup(group);
        Constants.LOG.info("JourneyMap created new locked group: {}", group.getName());
        return group;
    }

    private static boolean isGroupValid(WaypointGroup group) {
        try {
            group.getName();
            group.getGuid();
            return true;
        } catch (Exception e) {
            Constants.LOG.info("JourneyMap group reference is stale, will recreate");
            return false;
        }
    }

    private static void syncInner(List<RespawnEntry> entries) {
        IClientAPI currentApi = api;
        if (currentApi == null) {
            return;
        }

        // Build wanted map: only non-concealed entries
        Map<String, RespawnEntry> wanted = new HashMap<>();
        for (RespawnEntry entry : entries) {
            if (!entry.concealed()) {
                wanted.put(entry.id(), entry);
            }
        }

        // Ensure tracking is populated (first sync after mapping start)
        if (JourneyMapTracking.ENTRY_TO_GUID.isEmpty()) {
            restoreTracking();
        }

        // Track which GUIDs we've touched this sync to detect stale ones
        Map<String, String> seenGuids = new HashMap<>();

        // Create/update waypoints
        for (RespawnEntry entry : wanted.values()) {
            String entryId = entry.id();
            BlockPos pos = entry.pos();
            String dim = entry.dimension();
            String name = EntryNames.displayName(entry);
            int serverColor = entry.color();

            String guid = JourneyMapTracking.getGuid(entryId);
            Waypoint wp = guid != null ? currentApi.getWaypoint(MOD_ID, guid) : null;

            if (wp == null) {
                // CREATE
                wp = WaypointFactory.createWaypoint(MOD_ID, pos, name, dim, true);
                wp.setCustomData(JourneyMapTracking.CUSTOM_KEY, entryId);
                wp.setColor(serverColor);
                wp.setEnabled(true);

                currentApi.addWaypoint(MOD_ID, wp);

                WaypointGroup g = getOrCreateGroup(currentApi);
                g.setLocked(false);
                g.addWaypoint(wp);
                g.setLocked(true);

                String newGuid = wp.getGuid();
                JourneyMapTracking.putGuid(entryId, newGuid);
                JourneyMapTracking.putLastKnownColor(entryId, serverColor);
                seenGuids.put(entryId, newGuid);

                Constants.LOG.info("JourneyMap created waypoint '{}' (guid={}) in group '{}'",
                        entryId, newGuid, g.getName());
            } else {
                // UPDATE
                int mapColor = wp.getColor() & 0xFFFFFF;
                Integer last = JourneyMapTracking.getLastKnownColor(entryId);

                // Detect user recolor since last sync
                if (last != null && last != mapColor) {
                    PENDING_RECOLORS.put(entryId, mapColor);
                    JourneyMapTracking.putLastKnownColor(entryId, mapColor);
                } else {
                    // No user edit — ensure our baseline matches server
                    JourneyMapTracking.putLastKnownColor(entryId, serverColor);
                }

                // Server-authoritative fields: ALWAYS overwrite
                wp.setName(name);
                wp.setBlockPos(pos);
                wp.setPrimaryDimension(dim);
                wp.setColor(serverColor); // Write server colour; user's edit will be re-detected next sync
                wp.setCustomData(JourneyMapTracking.CUSTOM_KEY, entryId);
                wp.setEnabled(true);

                currentApi.addWaypoint(MOD_ID, wp);
                // NO lock toggle on UPDATE (Waystones pattern)

                seenGuids.put(entryId, guid);
                Constants.LOG.info("JourneyMap updated waypoint '{}' (guid={})", entryId, guid);
            }
        }

        // Send batched recolors to server (Xaero-style: after all updates)
        for (Map.Entry<String, Integer> e : PENDING_RECOLORS.entrySet()) {
            Services.NETWORK.sendToServer(new RecolorPointPacket(e.getKey(), e.getValue()));
            Constants.LOG.trace("JourneyMap batched recolor '{}' -> 0x{:06X}", e.getKey(), e.getValue());
        }
        PENDING_RECOLORS.clear();

        // Remove stale waypoints (no longer in server offer)
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> e : JourneyMapTracking.ENTRY_TO_GUID.entrySet()) {
            if (!wanted.containsKey(e.getKey())) {
                toRemove.add(e.getKey());
            }
        }
        for (String entryId : toRemove) {
            String guid = JourneyMapTracking.getGuid(entryId);
            JourneyMapTracking.removeGuid(entryId);
            JourneyMapTracking.removeLastKnownColor(entryId);
            if (guid != null) {
                try {
                    currentApi.removeWaypoint(MOD_ID, currentApi.getWaypoint(MOD_ID, guid));
                } catch (Exception ex) {
                    Constants.LOG.warn("Could not remove JourneyMap waypoint '{}'", entryId, ex);
                }
            }
            Constants.LOG.info("JourneyMap removed stale waypoint '{}'", entryId);
        }
    }
}