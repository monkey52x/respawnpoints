package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.integration.MapIntegrations;

/**
 * Global client-side map-manager choice. Not tied to a specific server.
 * Choice is made once (auto-detect or via MapChoiceScreen in the main menu)
 * and saved in {@link RespawnClientConfig}.
 */
public final class MapManagerChoice {
    private MapManagerChoice() {
    }

    /** Called once at client init: auto-detects if exactly one map mod is installed. */
    public static synchronized void init() {
        if (RespawnClientConfig.choiceMade()) {
            return; // choice already exists, do not override
        }
        boolean xaero = MapIntegrations.isXaeroPresent();
        boolean jm = MapIntegrations.isJourneyMapPresent();
        if (xaero && !jm) {
            RespawnClientConfig.setChoice(RespawnClientConfig.ActiveMap.XAERO);
            Constants.LOG.info("Map manager auto-detected: Xaero's Map (only one installed)");
        } else if (jm && !xaero) {
            RespawnClientConfig.setChoice(RespawnClientConfig.ActiveMap.JOURNEYMAP);
            Constants.LOG.info("Map manager auto-detected: JourneyMap (only one installed)");
        } else {
            // Both or none installed: leave as NONE. User chooses in main menu if both.
            RespawnClientConfig.setChoice(RespawnClientConfig.ActiveMap.NONE);
        }
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
            case AUTO -> RespawnClientConfig.resolve(xaero, jm);
        };
    }
}
