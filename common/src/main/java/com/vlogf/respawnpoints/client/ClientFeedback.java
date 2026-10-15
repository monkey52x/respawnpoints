package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.client.screen.RespawnScreenFactory;
import net.minecraft.client.Minecraft;

/**
 * Single policy for vanilla fallbacks.
 * Empty offers fall through silently (no toast, no stuck screen);
 * rejected choices reopen the selection. Callers must not branch this logic.
 */
public final class ClientFeedback {
    private ClientFeedback() {
    }

    /** Empty offer: clear waiting state and return a dead player to vanilla. */
    public static void fallBackToVanillaSilent() {
        ClientReadiness.clearWaiting();
        var mc = Minecraft.getInstance();
        if (mc.player != null && !mc.player.isAlive()) {
            RespawnScreenFactory.backToVanilla();
        }
    }
}
