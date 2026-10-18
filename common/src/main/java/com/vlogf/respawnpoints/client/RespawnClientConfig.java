package com.vlogf.respawnpoints.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.vlogf.respawnpoints.Constants;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/** Client config: which map drives the respawn screen. */
public final class RespawnClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum ActiveMap {
        NONE,
        XAERO,
        JOURNEYMAP,
        DISABLED
    }

    private static ActiveMap activeMap = ActiveMap.NONE;
    private static boolean choiceMade = false;
    /** Show the selected point's name on the map (per-frame highlight). */
    private static boolean showSelectedName = false;
    /** Keep a black "respawning" overlay after Confirm instead of closing at once. */
    private static boolean loadingOverlay = false;
    private static boolean loaded = false;

    private RespawnClientConfig() {
    }

    private static File file() {
        return new File(new File(Minecraft.getInstance().gameDirectory, "config"), "respawnpoints-client.json");
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = file();
        if (!f.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(f)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json != null) {
                if (json.has("activeMap")) {
                    try {
                        activeMap = ActiveMap.valueOf(json.get("activeMap").getAsString().toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                        activeMap = ActiveMap.NONE;
                    }
                }
                if (json.has("choiceMade")) {
                    choiceMade = json.get("choiceMade").getAsBoolean();
                }
                if (json.has("showSelectedName")) {
                    showSelectedName = json.get("showSelectedName").getAsBoolean();
                }
                if (json.has("loadingOverlay")) {
                    loadingOverlay = json.get("loadingOverlay").getAsBoolean();
                }

            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not read respawnpoints-client.json, using defaults", e);
        }
    }

    public static synchronized void save() {
        JsonObject json = new JsonObject();
        json.addProperty("activeMap", activeMap.name().toLowerCase());
        json.addProperty("choiceMade", choiceMade);
        json.addProperty("showSelectedName", showSelectedName);
        json.addProperty("loadingOverlay", loadingOverlay);

        try {
            file().getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file())) {
                GSON.toJson(json, writer);
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not save respawnpoints-client.json", e);
        }
    }

    public static synchronized ActiveMap activeMap() {
        load();
        return activeMap;
    }

    public static synchronized boolean choiceMade() {
        load();
        return choiceMade;
    }

    /** Show the selected point's name on the map. Applies on save, no restart. */
    public static synchronized boolean showSelectedName() {
        load();
        return showSelectedName;
    }

    public static synchronized void setShowSelectedName(boolean value) {
        showSelectedName = value;
    }

    /** Black "respawning" overlay after Confirm instead of closing at once. Applies on save. */
    public static synchronized boolean loadingOverlay() {
        load();
        return loadingOverlay;
    }

    public static synchronized void setLoadingOverlay(boolean value) {
        loadingOverlay = value;
    }

    public static synchronized void setChoice(ActiveMap map) {
        activeMap = map;
        choiceMade = true;
        save();
    }

}
