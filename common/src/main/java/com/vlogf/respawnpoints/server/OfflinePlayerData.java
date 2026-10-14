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
    private static CompoundTag readPlayerFile(Path file) {
        try {
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

    /** Offline-mode (cracked) servers derive UUIDs from the name — fallback when the cache misses. */
    public static UUID offlineModeUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean hasDataFile(MinecraftServer server, UUID uuid) {
        return Files.isRegularFile(playerFile(server, uuid));
    }
}
