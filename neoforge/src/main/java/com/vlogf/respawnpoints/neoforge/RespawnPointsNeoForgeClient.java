package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.client.ClientPreconditions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.client.screen.RespawnConfigScreen;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only hooks. Referenced from the mod constructor only on the client (lazy linking). */
public final class RespawnPointsNeoForgeClient {
    private static boolean firstLaunchMapChoiceShown = false;

    private RespawnPointsNeoForgeClient() {
    }

    public static void register() {
        ClientPreconditions.checkMapsInstalled();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ClientReadiness.tick();
            var minecraft = Minecraft.getInstance();
            // At first launch in the main menu, open the YACL config screen so
            // the player can choose which map manager to use. No auto-detection.
            if (!firstLaunchMapChoiceShown
                    && !RespawnClientConfig.choiceMade()
                    && (MapIntegrations.isXaeroPresent() || MapIntegrations.isJourneyMapPresent())
                    && minecraft.level == null) {
                firstLaunchMapChoiceShown = true;
                if (Services.PLATFORM.isModLoaded("yet_another_config_lib_v3")) {
                    minecraft.setScreen(RespawnConfigScreen.create(null));
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                ClientReadiness.onDisconnect());
    }
}
