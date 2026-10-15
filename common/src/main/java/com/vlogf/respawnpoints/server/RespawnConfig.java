package com.vlogf.respawnpoints.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.vlogf.respawnpoints.Constants;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Server-side tuning, loaded from {@code config/respawnpoints-server.json}.
 * Missing file or unreadable values fall back to defaults (and rewrite them).
 */
public final class RespawnConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "respawnpoints-server.json";

    /** Max tracked beds+anchors per player. Oldest entry drops when exceeded. */
    private static volatile int bedLimit = 10;
    /** Max distance for the random NEAR_DEATH spot (explored chunks only, never generates). */
    private static volatile int nearDeathRadius = 1024;
    /** Min distance for the random NEAR_DEATH spot (so it is not the exact death pos). */
    private static volatile int nearDeathMinDistance = 512;
    /** Cooldown after using the near-death spot, seconds (0 = disabled). */
    private static volatile int nearDeathCooldownSec = 300;
    /** Show the world-spawn entry. */
    private static volatile boolean showSpawn = true;
    /** Show the random near-death entry (off by default until the system rework). */
    private static volatile boolean showNearDeath = false;

    private RespawnConfig() {
    }

    public static int getBedLimit() {
        return bedLimit;
    }

    public static int getNearDeathRadius() {
        return nearDeathRadius;
    }

    public static int getNearDeathMinDistance() {
        return nearDeathMinDistance;
    }

    public static int getNearDeathCooldownSec() {
        return nearDeathCooldownSec;
    }

    public static boolean isShowSpawn() {
        return showSpawn;
    }

    public static boolean isShowNearDeath() {
        return showNearDeath;
    }

    /** Live setters for the in-game config screen (same clamps as load). */
    public static synchronized void setBedLimit(int value) {
        bedLimit = Math.min(1024, Math.max(1, value));
    }

    public static synchronized void setNearDeathRadius(int value) {
        nearDeathRadius = Math.min(2048, Math.max(16, value));
        if (nearDeathMinDistance > nearDeathRadius) {
            nearDeathMinDistance = nearDeathRadius;
        }
    }

    public static synchronized void setNearDeathMinDistance(int value) {
        nearDeathMinDistance = Math.min(1024, Math.max(0, value));
        if (nearDeathMinDistance > nearDeathRadius) {
            nearDeathMinDistance = nearDeathRadius;
        }
    }

    public static synchronized void setNearDeathCooldownSec(int value) {
        nearDeathCooldownSec = Math.min(3600, Math.max(0, value));
    }

    public static synchronized void setShowSpawn(boolean value) {
        showSpawn = value;
    }

    public static synchronized void setShowNearDeath(boolean value) {
        showNearDeath = value;
    }

    /** Load (or create with defaults) from the server game directory. */
    public static synchronized void load(Path gameDir) {
        java.io.File file = gameDir.resolve("config").resolve(FILE_NAME).toFile();
        if (file.exists()) {
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null) {
                    bedLimit = clampInt(json, "bedLimit", bedLimit, 1, 1024);
                    nearDeathRadius = clampInt(json, "nearDeathRadius", nearDeathRadius, 16, 2048);
                    nearDeathMinDistance =
                            clampInt(json, "nearDeathMinDistance", nearDeathMinDistance, 0, 1024);
                    nearDeathCooldownSec =
                            clampInt(json, "nearDeathCooldownSec", nearDeathCooldownSec, 0, 3600);
                    if (nearDeathMinDistance > nearDeathRadius) {
                        nearDeathMinDistance = nearDeathRadius;
                    }
                    showSpawn = clampBool(json, "showSpawn", showSpawn);
                    showNearDeath = clampBool(json, "showNearDeath", showNearDeath);
                    Constants.LOG.info("RespawnPoints config loaded: bedLimit={} nearDeathRadius={} nearDeathMinDistance={} nearDeathCooldownSec={} showSpawn={} showNearDeath={}",
                            bedLimit, nearDeathRadius, nearDeathMinDistance, nearDeathCooldownSec, showSpawn, showNearDeath);
                    // Backfill newly added keys into old files.
                    save(gameDir);
                    return;
                }
            } catch (Exception e) {
                Constants.LOG.warn("Could not read respawnpoints-server.json, using defaults", e);
            }
        }
        save(gameDir);
    }

    /** Write current values (called by load-on-missing and the in-game screen). */
    public static synchronized void save(Path gameDir) {
        JsonObject json = new JsonObject();
        json.addProperty("bedLimit", bedLimit);
        json.addProperty("nearDeathRadius", nearDeathRadius);
        json.addProperty("nearDeathMinDistance", nearDeathMinDistance);
        json.addProperty("nearDeathCooldownSec", nearDeathCooldownSec);
        json.addProperty("showSpawn", showSpawn);
        json.addProperty("showNearDeath", showNearDeath);
        try {
            java.io.File file = gameDir.resolve("config").resolve(FILE_NAME).toFile();
            file.getParentFile().mkdirs();
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            Constants.LOG.info("RespawnPoints config written with defaults: {}", file);
        } catch (Exception e) {
            Constants.LOG.warn("Could not save respawnpoints-server.json", e);
        }
    }

    private static int clampInt(JsonObject json, String key, int current, int min, int max) {
        if (!json.has(key)) {
            return current;
        }
        try {
            return Math.min(max, Math.max(min, json.get(key).getAsInt()));
        } catch (Exception e) {
            return current;
        }
    }

    private static boolean clampBool(JsonObject json, String key, boolean current) {
        if (!json.has(key)) {
            return current;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception e) {
            return current;
        }
    }
}
