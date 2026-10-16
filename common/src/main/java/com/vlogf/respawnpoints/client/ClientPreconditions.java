package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.integration.MapIntegrations;

/** Client install precondition: both Xaero mods must be present.
 * Normally enforced by the loader metadata before this ever fires. */
public final class ClientPreconditions {
    private ClientPreconditions() {
    }

    public static void checkMapsInstalled() {
        if (!MapIntegrations.isXaeroPresent()) {
            Constants.LOG.error(
                    "Respawn Points requires both Xaero's Minimap and Xaero's World Map on the client.");
            throw new IllegalStateException(
                    "Respawn Points requires both Xaero's Minimap and Xaero's World Map on the client.");
        }
    }
}
