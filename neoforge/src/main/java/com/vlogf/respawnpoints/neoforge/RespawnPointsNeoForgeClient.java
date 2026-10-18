package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.client.ClientPreconditions;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.client.MapManagerChoice;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.client.screen.MapChoiceScreen;
import com.vlogf.respawnpoints.integration.MapIntegrations;
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
        // Auto-detect: если установлен ровно один карто-мод — выбираем его автоматически.
        // Если оба — выбор будет предложен в главном меню через MapChoiceScreen.
        MapManagerChoice.init();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ClientReadiness.tick();
            var minecraft = Minecraft.getInstance();
            // В главном меню (уровень не загружен): если обе карты установлены и
            // игрок ещё не сделал выбор — показываем MapChoiceScreen. После выбора
            // (в т.ч. "Никакая") choiceMade() станет true и экран больше не покажется.
            if (minecraft.level == null && minecraft.screen == null
                    && !RespawnClientConfig.choiceMade()
                    && MapIntegrations.isXaeroPresent() && MapIntegrations.isJourneyMapPresent()) {
                minecraft.setScreen(new MapChoiceScreen());
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                ClientReadiness.onDisconnect());
    }
}
