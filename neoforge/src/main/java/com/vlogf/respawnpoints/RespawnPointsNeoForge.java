package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.neoforge.NeoForgeEvents;
import com.vlogf.respawnpoints.neoforge.NeoForgeNetworkHelper;
import com.vlogf.respawnpoints.neoforge.RespawnPointsNeoForgeClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(Constants.MOD_ID)
public class RespawnPointsNeoForge {
    public RespawnPointsNeoForge(IEventBus eventBus) {
        NeoForgeNetworkHelper.register(eventBus);
        NeoForgeEvents.register(eventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RespawnPointsNeoForgeClient.register();
            // Config button only when YACL is installed; otherwise no button.
            if (net.neoforged.fml.ModList.get().isLoaded("yet_another_config_lib_v3")) {
                net.neoforged.fml.ModLoadingContext.get().registerExtensionPoint(
                        net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                        () -> (client, parent) -> com.vlogf.respawnpoints.client.screen
                                .RespawnConfigScreen.create(parent));
            }
        }
        RespawnPointsMod.init();
    }
}
