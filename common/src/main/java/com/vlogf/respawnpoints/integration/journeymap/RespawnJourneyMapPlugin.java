package com.vlogf.respawnpoints.integration.journeymap;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;

/**
 * JourneyMap client plugin (dormant while the integration is parked).
 * The {@code @JourneyMapPlugin} annotation is intentionally absent: with it,
 * JourneyMap itself demands a fixed API version at startup and blocks loading.
 * The sync class stays reachable only via reflection for a future return.
 * ISOLATION: journeymap imports live only in this package. Never referenced directly
 * from common code — only via reflection through {@code JourneyMapWaypointSync}.
 */
public class RespawnJourneyMapPlugin implements IClientPlugin {
    private static volatile IClientAPI api;

    public RespawnJourneyMapPlugin() {
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        api = jmClientApi;
    }

    @Override
    public String getModId() {
        return "respawnpoints";
    }

    static IClientAPI api() {
        return api;
    }
}
