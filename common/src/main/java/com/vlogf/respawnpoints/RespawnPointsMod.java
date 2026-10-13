package com.vlogf.respawnpoints;

import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

// Shared entry point, called by Fabric and NeoForge bootstraps.
// Loader-specific code must stay out of here; use Services / interfaces instead.
public class RespawnPointsMod {
    public static void init() {
        Constants.LOG.info("Respawn Points build {}", Constants.buildTime());
        Constants.LOG.info("Respawn Points init on {} ({} environment)",
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
        Constants.LOG.info("The ID for diamonds is {}", BuiltInRegistries.ITEM.getKey(Items.DIAMOND));

        if (Services.PLATFORM.isModLoaded(Constants.MOD_ID)) {
            Constants.LOG.info("Respawn Points mod present");
        }

        // Probe optional map integrations without hard-linking them.
        MapIntegrations.init();
    }
}
