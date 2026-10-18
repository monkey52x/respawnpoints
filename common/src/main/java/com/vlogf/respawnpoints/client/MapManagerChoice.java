package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.integration.MapIntegrations;

/**
 * Global client-side map-manager choice. Not tied to a specific server.
 * Choice is made once (via MapChoiceScreen in the main menu) and saved in
 * {@link RespawnClientConfig}.
 */
public final class MapManagerChoice {
    private MapManagerChoice() {
    }

    /** Which manager drives markers and the death screen right now. */
    public static RespawnClientConfig.ActiveMap resolve() {
        RespawnClientConfig.ActiveMap map = RespawnClientConfig.activeMap();
        boolean xaero = MapIntegrations.isXaeroPresent();
        boolean jm = MapIntegrations.isJourneyMapPresent();
        return switch (map) {
            case XAERO -> xaero ? map : RespawnClientConfig.ActiveMap.NONE;
            case JOURNEYMAP -> jm ? map : RespawnClientConfig.ActiveMap.NONE;
            case NONE -> RespawnClientConfig.ActiveMap.NONE;
            case DISABLED -> RespawnClientConfig.ActiveMap.DISABLED;
        };
    }
}
