package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.model.RespawnEntry;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs respawn options as client-local JourneyMap waypoints.
 * Stable linkage via waypoint custom data ("respawnpoints:id").
 * ISOLATION: journeymap imports live only in this package. Called reflectively.
 */
public final class JourneyMapWaypointSync {
    private static final String CUSTOM_KEY = "respawnpoints:id";

    private JourneyMapWaypointSync() {
    }

    public static void sync(List<RespawnEntry> entries) {
        try {
            syncInner(entries);
        } catch (Throwable t) {
            Constants.LOG.warn("JourneyMap waypoint sync failed, skipping", t);
        }
    }

    private static void syncInner(List<RespawnEntry> entries) {
        IClientAPI api = RespawnJourneyMapPlugin.api();
        if (api == null) {
            return;
        }
        Map<String, RespawnEntry> wanted = new HashMap<>();
        for (RespawnEntry entry : entries) {
            if (!entry.concealed()) {
                wanted.put(entry.id(), entry);
            }
        }

        java.util.List<Waypoint> existingWaypoints;
        try {
            existingWaypoints = new java.util.ArrayList<>(api.getWaypoints("respawnpoints"));
        } catch (Exception e) {
            Constants.LOG.warn("Could not list JourneyMap waypoints", e);
            return;
        }
        Map<String, Waypoint> byId = new HashMap<>();
        for (Waypoint existing : existingWaypoints) {
            String id = existing.getCustomData(CUSTOM_KEY);
            if (id == null || !wanted.containsKey(id)) {
                try {
                    api.removeWaypoint("respawnpoints", existing);
                } catch (Exception e) {
                    Constants.LOG.warn("Could not remove JourneyMap waypoint", e);
                }
            } else {
                byId.put(id, existing);
            }
        }

        for (RespawnEntry entry : wanted.values()) {
            Waypoint match = byId.get(entry.id());
            try {
                if (match == null) {
                    BlockPos pos = entry.pos();
                    Waypoint wp = WaypointFactory.createWaypoint("respawnpoints", pos,
                            com.vlogf.respawnpoints.client.EntryNames.displayName(entry),
                            entry.dimension(), false);
                    wp.setCustomData(CUSTOM_KEY, entry.id());
                    wp.setColor(entry.color());
                    wp.setEnabled(true);
                    api.addWaypoint("respawnpoints", wp);
                } else {
                    match.setName(com.vlogf.respawnpoints.client.EntryNames.displayName(entry));
                    match.setBlockPos(entry.pos());
                    match.setColor(entry.color());
                    match.setPrimaryDimension(entry.dimension());
                    match.setEnabled(true);
                }
            } catch (Exception e) {
                Constants.LOG.warn("Could not sync JourneyMap waypoint '{}'", entry.id(), e);
            }
        }
    }
}
