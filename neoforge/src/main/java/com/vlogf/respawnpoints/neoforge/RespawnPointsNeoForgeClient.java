package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.client.ClientPreconditions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.client.MapManagerChoice;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.client.screen.MapChoiceScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only hooks. Referenced from the mod constructor only on the client (lazy linking). */
public final class RespawnPointsNeoForgeClient {
    private RespawnPointsNeoForgeClient() {
    }

    public static void register() {
        ClientPreconditions.checkMapsInstalled();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ClientReadiness.tick();
            var minecraft = Minecraft.getInstance();
            // В главном меню (уровень не загружен): если выбор карты не сделан
            // (NONE) — показываем обязательный MapChoiceScreen. DISABLED —
            // валидный выбор, экран при нём не показывается.
            if (minecraft.level == null
                    && !(minecraft.screen instanceof MapChoiceScreen)
                    && RespawnClientConfig.activeMap() == RespawnClientConfig.ActiveMap.NONE) {
                minecraft.setScreen(new MapChoiceScreen());
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                ClientReadiness.onDisconnect());
    }
}
