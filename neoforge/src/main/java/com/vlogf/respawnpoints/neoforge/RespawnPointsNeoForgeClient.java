package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.client.ClientPreconditions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only hooks. Referenced from the mod constructor only on the client (lazy linking). */
public final class RespawnPointsNeoForgeClient {
    private RespawnPointsNeoForgeClient() {
    }

    public static void register() {
        ClientPreconditions.checkMapsInstalled();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientReadiness.tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                ClientReadiness.onDisconnect());
    }
}
