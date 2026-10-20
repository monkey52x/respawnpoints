package com.vlogf.respawnpoints.integration.journeymap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime tracking for JourneyMap waypoints.
 * Survives JourneyMap reloads via custom data on waypoints.
 * Not persisted to disk — rebuilt on each mapping session start.
 */
final class JourneyMapTracking {
    static final String CUSTOM_KEY = "respawnpoints:id";

    // entryId -> JourneyMap GUID
    static final Map<String, String> ENTRY_TO_GUID = new ConcurrentHashMap<>();
    // entryId -> last known color (RGB 0xRRGGBB) from the map, for Xaero-style deferred recolor detection
    static final Map<String, Integer> LAST_KNOWN_COLOR = new ConcurrentHashMap<>();

    static String getGuid(String entryId) {
        return ENTRY_TO_GUID.get(entryId);
    }

    static void putGuid(String entryId, String guid) {
        ENTRY_TO_GUID.put(entryId, guid);
    }

    static void removeGuid(String entryId) {
        ENTRY_TO_GUID.remove(entryId);
    }

    static Integer getLastKnownColor(String entryId) {
        return LAST_KNOWN_COLOR.get(entryId);
    }

    static void putLastKnownColor(String entryId, int color) {
        LAST_KNOWN_COLOR.put(entryId, color);
    }

    static void removeLastKnownColor(String entryId) {
        LAST_KNOWN_COLOR.remove(entryId);
    }

    static void clear() {
        ENTRY_TO_GUID.clear();
        LAST_KNOWN_COLOR.clear();
    }
}