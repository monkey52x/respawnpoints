package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.client.event.WaypointEvent;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;
import journeymap.api.v2.common.event.CommonEventRegistry;

/**
 * JourneyMap client plugin. Registered via {@code @JourneyMapPlugin} so JourneyMap
 * discovers and initialises it automatically. ISOLATION: journeymap imports live
 * only in this package. Never referenced directly from common code — only via
 * reflection through {@code JourneyMapWaypointSync}.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class RespawnJourneyMapPlugin implements IClientPlugin {
    public RespawnJourneyMapPlugin() {
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        JourneyMapWaypointSync.setApi(jmClientApi);
        // Subscribe to waypoint events (colour changes, deletions) and mapping
        // lifecycle events (MAPPING_STARTED / MAPPING_STOPPED).
        ClientEventRegistry.WAYPOINT_EVENT.subscribe(getModId(), event ->
                JourneyMapWaypointSync.onWaypointEvent(event));
        // Waypoint-group events are only exposed on the common registry, which
        // fires on both sides and is the documented bus for client plugins
        // (see the JourneyMap API testmod). Without this subscription a group
        // deleted by the user is never recreated.
        CommonEventRegistry.WAYPOINT_GROUP_EVENT.subscribe(getModId(), event ->
                JourneyMapWaypointSync.onWaypointGroupEvent(event));
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
}
