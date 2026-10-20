package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.integration.MapIntegrations;

/** Client preconditions: checks if map mods are installed.
 *  Hard dependency on Xaero removed — mod works with JourneyMap,
 *  Xaero, or no maps at all (standalone mode). */
public final class ClientPreconditions {
    private ClientPreconditions() {
    }

    public static void checkMapsInstalled() {
        boolean xaero = MapIntegrations.isXaeroPresent();
        boolean jm = MapIntegrations.isJourneyMapPresent();
        Constants.LOG.info("Respawn Points client preconditions: Xaero={}, JourneyMap={}", xaero, jm);
    }
}