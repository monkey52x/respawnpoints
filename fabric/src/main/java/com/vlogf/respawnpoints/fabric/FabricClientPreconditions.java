package com.vlogf.respawnpoints.fabric;

import com.vlogf.respawnpoints.Constants;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.gui.FabricStatusTree;

/**
 * Fabric client install precondition: both Xaero mods must be present.
 * Missing maps open the native Fabric Loader error window (white GUI, clean
 * exit, no crash report) instead of throwing. Dedicated servers never reach
 * here (client entrypoint only), so Xaero stays out of {@code depends}.
 */
public final class FabricClientPreconditions {
    private FabricClientPreconditions() {
    }

    public static void checkMapsInstalled() {
        boolean minimap = FabricLoader.getInstance().isModLoaded("xaerominimap");
        boolean worldmap = FabricLoader.getInstance().isModLoaded("xaeroworldmap");
        if (minimap && worldmap) {
            return;
        }
        String missing = !minimap && !worldmap ? "xaerominimap and xaeroworldmap"
                : !minimap ? "xaerominimap" : "xaeroworldmap";
        Constants.LOG.error(
                "Respawn Points requires both Xaero's Minimap and Xaero's World Map on the client (missing: {}).",
                missing);
        FabricGuiEntry.displayError(
                "Respawn Points — missing dependencies",
                null,
                tree -> {
                    FabricStatusTree.FabricStatusTab tab = tree.addTab("Missing mods");
                    if (!minimap) {
                        tab.addChild("xaerominimap")
                                .addMessage("Xaero's Minimap is not installed",
                                        FabricStatusTree.FabricTreeWarningLevel.ERROR)
                                .setError();
                    }
                    if (!worldmap) {
                        tab.addChild("xaeroworldmap")
                                .addMessage("Xaero's World Map is not installed",
                                        FabricStatusTree.FabricTreeWarningLevel.ERROR)
                                .setError();
                    }
                },
                true);
    }
}
