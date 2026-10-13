package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.fabric.FabricEvents;
import com.vlogf.respawnpoints.fabric.FabricNetworkHelper;
import net.fabricmc.api.ModInitializer;

public class RespawnPointsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricNetworkHelper.registerPayloads();
        FabricNetworkHelper.registerServerReceivers();
        FabricEvents.register();
        RespawnPointsMod.init();
    }
}
