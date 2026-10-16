package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.integration.MapIntegrations;

/** Client install precondition: both Xaero mods must be present.
 * Normally enforced by the loader metadata before this ever fires.
 * NeoForge path (its mods.toml already blocks); Fabric uses its own native
 * GUI check instead ({@code FabricClientPreconditions}). */
public final class ClientPreconditions {
    private ClientPreconditions() {
    }

    public static void checkMapsInstalled() {
        if (!MapIntegrations.isXaeroPresent()) {
            throw new IllegalStateException(
                    "Respawn Points requires both Xaero's Minimap and Xaero's World Map on the client.");
        }
    }
}
