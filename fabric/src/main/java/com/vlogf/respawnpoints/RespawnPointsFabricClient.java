package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.client.ClientBedActions;
import com.vlogf.respawnpoints.client.ClientPreconditions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.client.screen.RespawnConfigScreen;
import com.vlogf.respawnpoints.fabric.FabricNetworkHelper;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.platform.Services;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

public class RespawnPointsFabricClient implements ClientModInitializer {
    private static boolean firstLaunchMapChoiceShown = false;

    @Override
    public void onInitializeClient() {
        ClientPreconditions.checkMapsInstalled();
        FabricNetworkHelper.registerClientReceivers();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientReadiness.tick();
            // At first launch in the main menu, open the YACL config screen so
            // the player can choose which map manager to use. No auto-detection.
            if (!firstLaunchMapChoiceShown
                    && !RespawnClientConfig.choiceMade()
                    && (MapIntegrations.isXaeroPresent() || MapIntegrations.isJourneyMapPresent())
                    && client.level == null) {
                firstLaunchMapChoiceShown = true;
                if (Services.PLATFORM.isModLoaded("yet_another_config_lib_v3")) {
                    client.setScreen(RespawnConfigScreen.create(null));
                }
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                ClientReadiness.onDisconnect());
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() && player instanceof net.minecraft.client.player.LocalPlayer local
                    && ClientBedActions.handleSneakUse(world, local, hitResult.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }
}
