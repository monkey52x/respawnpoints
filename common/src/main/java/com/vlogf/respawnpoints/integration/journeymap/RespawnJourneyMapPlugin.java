package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.client.event.WaypointEvent;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;

/**
 * JourneyMap client plugin. Registered via {@code @JourneyMapPlugin} so JourneyMap
 * discovers and initialises it automatically. ISOLATION: journeymap imports live
 * only in this package. Never referenced directly from common code — only via
 * reflection through {@code JourneyMapWaypointSync}.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class RespawnJourneyMapPlugin implements IClientPlugin {
    private static volatile IClientAPI api;

    public RespawnJourneyMapPlugin() {
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        api = jmClientApi;
        // Subscribe to waypoint events (colour changes, deletions) and mapping
        // lifecycle events (MAPPING_STARTED / MAPPING_STOPPED).
        ClientEventRegistry.WAYPOINT_EVENT.subscribe(getModId(), event ->
                JourneyMapWaypointSync.onWaypointEvent(event));
        ClientEventRegistry.MAPPING_EVENT.subscribe(getModId(), event -> {
            if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
                JourneyMapWaypointSync.onMappingStarted();
            } else if (event.getStage() == MappingEvent.Stage.MAPPING_STOPPED) {
                JourneyMapWaypointSync.onMappingStopped();
            }
        });
        Constants.LOG.debug("JourneyMap plugin '{}' initialised", getModId());
    }

    @Override
    public String getModId() {
        return "respawnpoints";
    }

    static IClientAPI api() {
        return api;
    }
}
