package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.client.ClientBedActions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.fabric.FabricClientPreconditions;
import com.vlogf.respawnpoints.fabric.FabricNetworkHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;

public class RespawnPointsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricClientPreconditions.checkMapsInstalled();
        FabricNetworkHelper.registerClientReceivers();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientReadiness.tick());
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
