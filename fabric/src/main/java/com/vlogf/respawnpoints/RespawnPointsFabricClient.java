package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.client.ClientBedActions;
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
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientReadiness.tick();
            // В главном меню (уровень не загружен): если выбор карты не сделан
            // (NONE) — показываем обязательный MapChoiceScreen. DISABLED —
            // валидный выбор, экран при нём не показывается.
            if (client.level == null
                    && !(client.screen instanceof MapChoiceScreen)
                    && RespawnClientConfig.activeMap() == RespawnClientConfig.ActiveMap.NONE) {
                client.setScreen(new MapChoiceScreen());
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
