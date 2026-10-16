package com.vlogf.respawnpoints.server;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * File-level surgery on offline players' data. Used by admin commands when the
 * target is not online: the in-memory paths ({@link PlayerRespawnData},
 * {@link DeathManager#pushUpdate}) cannot run, so the {@code playerdata} file
 * is edited directly and atomically. Everything runs on the server thread;
 * callers must re-check that the player is still offline right before writing.
 * Vanilla spawn tags are deliberately left alone: unbind removes our mark only.
 */
public final class OfflinePlayerData {
    private OfflinePlayerData() {
    }

    /**
     * Drop one snapshot from an offline player's file.
     *
     * @return true if the file was rewritten (or there was nothing to remove).
     */
    public static boolean dropPoint(MinecraftServer server, UUID uuid, String pointId) {
        Path file = playerFile(server, uuid);
        if (!Files.isRegularFile(file)) {
            return true;
        }
        CompoundTag root = readPlayerFile(file);
        if (root == null) {
            Constants.LOG.warn("Could not read player data for {}", uuid);
            return false;
        }
        if (!root.contains("RespawnPoints", Tag.TAG_COMPOUND)) {
            return true;
        }
        CompoundTag tag = root.getCompound("RespawnPoints");
        ListTag points = tag.getList("Points", Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        boolean removed = false;
        for (int i = 0; i < points.size(); i++) {
            Tag t = points.get(i);
            if (t instanceof CompoundTag e && pointId.equals(e.getString("Id"))) {
                removed = true;
                continue;
            }
            kept.add(t);
        }
        if (!removed) {
            return true;
        }
        tag.put("Points", kept);
        try {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            net.minecraft.nbt.NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            Constants.LOG.warn("Could not write player data for {}", uuid, e);
            return false;
        }
    }

    /** Vanilla idiom: .dat first, .dat_old fallback. */
    private static CompoundTag readPlayerFile(Path file) {        try {
            return net.minecraft.nbt.NbtIo.readCompressed(file,
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        } catch (Exception ignored) {
        }
        try {
            Path old = file.resolveSibling(file.getFileName() + "_old");
            if (Files.isRegularFile(old)) {
                return net.minecraft.nbt.NbtIo.readCompressed(old,
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path playerFile(MinecraftServer server, UUID uuid) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
    }

    /** One vanilla usercache.json entry that is still valid. */
    public record KnownPlayer(UUID uuid, String name) {
    }

    /**
     * Nick directory from vanilla's own <code>usercache.json</code> (server dir,
     * game-dir fallback), keyed by lower-cased nick. Expired or unparseable
     * entries are skipped like vanilla skips them — never resolved, never
     * suggested by name (raw UUID still works). Read-only: we never write it.
     */
    public static java.util.Map<String, KnownPlayer> readUsernameCache(MinecraftServer server) {
        java.util.Map<String, KnownPlayer> out = new java.util.LinkedHashMap<>();
        Path file = server.getServerDirectory().resolve("usercache.json");
        if (!Files.isRegularFile(file)) {
            file = java.nio.file.Paths.get(System.getProperty("user.dir"), "usercache.json");
            if (!Files.isRegularFile(file)) {
                return out;
            }
        }
        long now = System.currentTimeMillis();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            com.google.gson.JsonArray array =
                    new com.google.gson.Gson().fromJson(json, com.google.gson.JsonArray.class);
            if (array == null) {
                return out;
            }
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT);
            for (com.google.gson.JsonElement el : array) {
                if (!el.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject e = el.getAsJsonObject();
                if (!e.has("name") || !e.has("uuid")) {
                    continue;
                }
                String name = e.get("name").getAsString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(e.get("uuid").getAsString());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                long expiresAt = Long.MIN_VALUE;
                if (e.has("expiresOn")) {
                    try {
                        expiresAt = java.time.OffsetDateTime.parse(
                                e.get("expiresOn").getAsString(), fmt).toInstant().toEpochMilli();
                    } catch (Exception ignored) {
                        continue;
                    }
                }
                if (expiresAt <= now) {
                    continue;
                }
                String key = name.toLowerCase(java.util.Locale.ROOT);
                // First valid entry wins; vanilla keeps nicks unique anyway.
                out.putIfAbsent(key, new KnownPlayer(uuid, name));
            }
        } catch (Exception e) {
            Constants.LOG.debug("Could not read usernamecache.json", e);
        }
        return out;
    }

    /** Snapshots from an offline player's file (custom names included). Empty when none. */
    public static java.util.List<PlayerRespawnData.PointSnapshot> readSnapshots(
            MinecraftServer server, UUID uuid) {
        java.util.List<PlayerRespawnData.PointSnapshot> out = new java.util.ArrayList<>();
        CompoundTag root = readPlayerFile(playerFile(server, uuid));
        if (root == null || !root.contains("RespawnPoints", Tag.TAG_COMPOUND)) {
            return out;
        }
        ListTag points = root.getCompound("RespawnPoints").getList("Points", Tag.TAG_COMPOUND);
        for (int i = 0; i < points.size(); i++) {
            if (!(points.get(i) instanceof CompoundTag e)) {
                continue;
            }
            String id = e.getString("Id");
            if (id.isEmpty()) {
                continue;
            }
            out.add(new PlayerRespawnData.PointSnapshot(
                    id,
                    e.getString("Dim"),
                    new net.minecraft.core.BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z")),
                    e.getString("Kind"),
                    e.getString("DefaultName"),
                    e.getInt("DefaultColor"),
                    e.getString("Name"),
                    e.contains("Color") ? e.getInt("Color") : e.getInt("DefaultColor")));
        }
        return out;
    }

    /**
     * Insert or replace one snapshot in an offline player's file (defaults for
     * name/color — customs have nowhere to come from). Creates the section
     * when missing. Vanilla spawn tags are deliberately left alone.
     *
     * @return true if the file now contains the snapshot.
     */
    public static boolean upsertSnapshot(MinecraftServer server, UUID uuid,
                                         SpawnRegistry.SpawnPointRecord record) {
        Path file = playerFile(server, uuid);
        CompoundTag root = Files.isRegularFile(file) ? readPlayerFile(file) : new CompoundTag();
        if (root == null) {
            Constants.LOG.warn("Could not read player data for {}", uuid);
            return false;
        }
        CompoundTag tag = new CompoundTag();
        if (root.contains("RespawnPoints", Tag.TAG_COMPOUND)) {
            tag = root.getCompound("RespawnPoints");
        }
        ListTag kept = new ListTag();
        String id = record.id().toString();
        ListTag points = tag.getList("Points", Tag.TAG_COMPOUND);
        for (int i = 0; i < points.size(); i++) {
            Tag t = points.get(i);
            if (t instanceof CompoundTag e && id.equals(e.getString("Id"))) {
                continue;
            }
            kept.add(t);
        }
        CompoundTag e = new CompoundTag();
        e.putString("Id", id);
        e.putString("Dim", record.pos().dimension().location().toString());
        e.putInt("X", record.pos().pos().getX());
        e.putInt("Y", record.pos().pos().getY());
        e.putInt("Z", record.pos().pos().getZ());
        e.putString("Kind", record.kind());
        e.putString("DefaultName", record.defaultName());
        e.putInt("DefaultColor", record.color());
        e.putString("Name", "");
        e.putInt("Color", record.color());
        kept.add(e);
        tag.put("Points", kept);
        root.put("RespawnPoints", tag);
        try {
            if (Files.isRegularFile(file)) {
                Path bak = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            net.minecraft.nbt.NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ex) {
            Constants.LOG.warn("Could not write player data for {}", uuid, ex);
            return false;
        }
    }

    /**
     * Active cooldowns from an offline player's file (id -&gt; end millis).
     * Expired entries are skipped (never resurrected, never reported).
     */
    public static java.util.Map<String, Long> readCooldowns(MinecraftServer server, UUID uuid) {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        Path file = playerFile(server, uuid);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        CompoundTag root = readPlayerFile(file);
        if (root == null || !root.contains("RespawnPoints", Tag.TAG_COMPOUND)) {
            return out;
        }
        CompoundTag tag = root.getCompound("RespawnPoints");
        if (!tag.contains("Cooldowns", Tag.TAG_COMPOUND)) {
            return out;
        }
        CompoundTag cooldowns = tag.getCompound("Cooldowns");
        long now = System.currentTimeMillis();
        for (String key : cooldowns.getAllKeys()) {
            try {
                long untilMs = cooldowns.getLong(key);
                if (untilMs > now) {
                    out.put(key, untilMs);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Set ({@code untilMs} non-null) or remove (null) one cooldown in an
     * offline player's file. Creates the section when missing, drops expired
     * and removed keys so NBT never litters. Atomic tmp + move, .bak,
     * vanilla tags untouched.
     *
     * @return true if the file now reflects the change.
     */
    public static boolean writeCooldown(MinecraftServer server, UUID uuid, String id, Long untilMs) {
        Path file = playerFile(server, uuid);
        CompoundTag root = Files.isRegularFile(file) ? readPlayerFile(file) : new CompoundTag();
        if (root == null) {
            Constants.LOG.warn("Could not read player data for {}", uuid);
            return false;
        }
        CompoundTag tag = new CompoundTag();
        if (root.contains("RespawnPoints", Tag.TAG_COMPOUND)) {
            tag = root.getCompound("RespawnPoints");
        }
        CompoundTag cooldowns = new CompoundTag();
        if (tag.contains("Cooldowns", Tag.TAG_COMPOUND)) {
            cooldowns = tag.getCompound("Cooldowns");
        }
        long now = System.currentTimeMillis();
        for (String key : new java.util.ArrayList<>(cooldowns.getAllKeys())) {
            try {
                if (cooldowns.getLong(key) <= now) {
                    cooldowns.remove(key);
                }
            } catch (Exception ignored) {
                cooldowns.remove(key);
            }
        }
        if (untilMs == null) {
            cooldowns.remove(id);
        } else if (untilMs > now) {
            cooldowns.putLong(id, untilMs);
        } else {
            cooldowns.remove(id);
        }
        tag.put("Cooldowns", cooldowns);
        root.put("RespawnPoints", tag);
        try {
            if (Files.isRegularFile(file)) {
                Path bak = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            net.minecraft.nbt.NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ex) {
            Constants.LOG.warn("Could not write player data for {}", uuid, ex);
            return false;
        }
    }
}
