package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.integration.MapIntegrations;

/** Клиентские предусловия: проверяет наличие карто-модов.
 * Жёсткой зависимости от Xaero больше нет — мод работает с JourneyMap,
 * с Xaero или совсем без карт (standalone-режим). */
public final class ClientPreconditions {
    private ClientPreconditions() {
    }

    public static void checkMapsInstalled() {
        boolean xaero = MapIntegrations.isXaeroPresent();
        boolean jm = MapIntegrations.isJourneyMapPresent();
        Constants.LOG.info("Respawn Points client preconditions: Xaero={}, JourneyMap={}", xaero, jm);
    }
}
