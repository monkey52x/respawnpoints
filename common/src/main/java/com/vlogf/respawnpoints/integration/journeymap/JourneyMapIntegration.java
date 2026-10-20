package com.vlogf.respawnpoints.integration.journeymap;

import com.vlogf.respawnpoints.Constants;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;
import journeymap.api.v2.common.event.CommonEventRegistry;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * JourneyMap client plugin. Registered via {@code @JourneyMapPlugin} so JourneyMap
 * discovers and initialises it automatically. ISOLATION: journeymap imports live
 * only in this package. Never referenced directly from common code — only via
 * reflection through {@code JourneyMapWaypointSync}.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class JourneyMapIntegration implements IClientPlugin {
    private static volatile JourneyMapIntegration instance;

    public JourneyMapIntegration() {
        instance = this;
    }

    public static JourneyMapIntegration getInstance() {
        return instance;
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        JourneyMapWaypointSync.setApi(jmClientApi);

        ClientEventRegistry.MAPPING_EVENT.subscribe(getModId(), JourneyMapWaypointSync::onMappingEvent);
        CommonEventRegistry.WAYPOINT_EVENT.subscribe(getModId(), JourneyMapWaypointSync::onWaypointEvent);
        CommonEventRegistry.WAYPOINT_GROUP_EVENT.subscribe(getModId(), JourneyMapWaypointSync::onWaypointGroupEvent);

        Constants.LOG.info("JourneyMap plugin '{}' initialised, subscribed to MAPPING_EVENT, WAYPOINT_EVENT, WAYPOINT_GROUP_EVENT", getModId());
    }

    @Override
    public String getModId() {
        return "respawnpoints";
    }
}