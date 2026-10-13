package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.screen.RespawnSelectScreen;

import java.util.UUID;

/**
 * Per-server map-manager choice, keyed by the server's stable uuid
 * (same server via IP or domain resolves identically).
 */
public final class MapManagerChoice {
    private static UUID currentServer;

    private MapManagerChoice() {
    }

    public static synchronized void onHello(UUID serverId) {
        currentServer = serverId;
        Constants.LOG.info("Respawn Points server identity: {}", serverId);
        // JourneyMap support is dormant: no prompt, Xaero (or fallback) only.
    }

    public static synchronized UUID currentServer() {
        return currentServer;
    }

    /** Which manager drives markers and the death screen right now. */
    public static RespawnClientConfig.ActiveMap resolve() {
        boolean xaero = RespawnSelectScreen.xaeroPresent();
        boolean jm = RespawnSelectScreen.journeyMapPresent();
        UUID server = currentServer;
        if (server != null) {
            RespawnClientConfig.ActiveMap stored = RespawnClientConfig.choiceFor(server);
            if (stored != null) {
                return sanitize(stored, xaero, jm);
            }
            if (RespawnClientConfig.choiceMade()) {
                // Adopt the legacy global choice for this server.
                RespawnClientConfig.setChoiceFor(server, RespawnClientConfig.activeMap());
                return sanitize(RespawnClientConfig.activeMap(), xaero, jm);
            }
        }
        return RespawnClientConfig.resolve(xaero, jm);
    }

    private static RespawnClientConfig.ActiveMap sanitize(RespawnClientConfig.ActiveMap map,
                                                           boolean xaero, boolean jm) {
        // JourneyMap support is dormant: stored JOURNEYMAP migrates to AUTO.
        if (map == RespawnClientConfig.ActiveMap.JOURNEYMAP) {
            return RespawnClientConfig.ActiveMap.AUTO;
        }
        return switch (map) {
            case XAERO -> xaero ? map : RespawnClientConfig.ActiveMap.NONE;
            case NONE -> RespawnClientConfig.ActiveMap.NONE;
            default -> xaero ? RespawnClientConfig.ActiveMap.XAERO
                    : RespawnClientConfig.ActiveMap.NONE;
        };
    }
}
