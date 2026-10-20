package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.client.ClientPreconditions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.client.MapManagerChoice;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.client.screen.MapChoiceScreen;
import com.vlogf.respawnpoints.fabric.FabricNetworkHelper;
import com.vlogf.respawnpoints.platform.Services;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

public class RespawnPointsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPreconditions.checkMapsInstalled();
        FabricNetworkHelper.registerClientReceivers();
        FabricNetworkHelper.registerDisconnectHandler();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientReadiness.tick();
            // In main menu (level not loaded): if map choice not made
            // (NONE) — show mandatory MapChoiceScreen. DISABLED —
            // valid choice, screen does not show at it.
            if (client.level == null
                    && !(client.screen instanceof MapChoiceScreen)
                    && RespawnClientConfig.activeMap() == RespawnClientConfig.ActiveMap.NONE) {
                client.setScreen(new MapChoiceScreen());
            }
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() && player instanceof net.minecraft.client.player.LocalPlayer local
                    && com.vlogf.respawnpoints.client.ClientBedActions.handleSneakUse(world, local, hitResult.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }
}