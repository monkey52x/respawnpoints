package com.vlogf.respawnpoints.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-local respawn data, persisted in the player's own NBT.
 * Per owned point (keyed by the registry UUID):
 * <ul>
 * <li>immutable snapshot taken at bind time: dimension, position, kind,
 * default name/color;</li>
 * <li>player edits: custom name/color (sent to the client);</li>
 * <li>flags: world-spawn hidden.</li>
 * </ul>
 * Plus a per-player blocklist of beds/anchors that must never auto-bind
 * (anti-misclick on a neighbor's bed): dimension + head position + kind.
 * Charges are never stored here — always read fresh from {@link SpawnRegistry}.
 */
public final class PlayerRespawnData {
    private static final String ROOT = "RespawnPoints";
    private static final String POINTS = "Points";
    private static final String SPAWN_HIDDEN = "SpawnHidden";
    private static final String SPAWN_NAME = "SpawnName";
    private static final String BLOCKED = "Blocked";

    private static final Map<UUID, Data> STORE = new ConcurrentHashMap<>();

    private PlayerRespawnData() {
    }

    public record PointSnapshot(
            String id,
            String dimension,
            BlockPos pos,
            String kind,
            String defaultName,
            int defaultColor,
            String name,
            int color) {
    }

    /** A bed/anchor head position the player refuses to bind to. */
    public record BlockedSpot(String dimension, BlockPos pos, String kind) {
    }

    private static final class Data {
        final Map<String, PointSnapshot> points = new LinkedHashMap<>();
        final List<BlockedSpot> blocked = new ArrayList<>();
        boolean spawnHidden;
        String spawnName = "";
    }

    private static Data data(ServerPlayer player) {
        return STORE.computeIfAbsent(player.getUUID(), k -> new Data());
    }

    /** Drop in-memory state (logout): NBT stays on disk, memory does not leak. */
    public static void evict(UUID uuid) {
        STORE.remove(uuid);
        EVICT_ARMED.remove(uuid);
    }

    /**
     * One-shot evict arming: set on logout, consumed by the first save after
     * that. The save hook writes from the live STORE first and evicts right
     * after, in one call stack — the save can never observe wiped memory.
     * A second save finds nothing armed and is a no-op write-skip.
     */
    private static final java.util.Set<UUID> EVICT_ARMED = ConcurrentHashMap.newKeySet();

    /** Arm post-save eviction (logout). Safe even if no save follows: just leaks one entry. */
    public static void armEvictOnSave(UUID uuid) {
        if (STORE.containsKey(uuid)) {
            EVICT_ARMED.add(uuid);
        }
    }

    /** One-shot: evict only if armed by logout, then disarm. Called from the save hook. */
    public static boolean consumeEvictArm(UUID uuid) {
        return EVICT_ARMED.remove(uuid);
    }

    public static void writeToNbt(ServerPlayer player, CompoundTag playerTag) {
        Data data = STORE.get(player.getUUID());
        if (data == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (PointSnapshot snapshot : data.points.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("Id", snapshot.id());
            e.putString("Dim", snapshot.dimension());
            e.putInt("X", snapshot.pos().getX());
            e.putInt("Y", snapshot.pos().getY());
            e.putInt("Z", snapshot.pos().getZ());
            e.putString("Kind", snapshot.kind());
            e.putString("DefaultName", snapshot.defaultName());
            e.putInt("DefaultColor", snapshot.defaultColor());
            e.putString("Name", snapshot.name());
            e.putInt("Color", snapshot.color());
            list.add(e);
        }
        tag.put(POINTS, list);
        tag.putBoolean(SPAWN_HIDDEN, data.spawnHidden);
        tag.putString(SPAWN_NAME, data.spawnName);
        ListTag blocked = new ListTag();
        for (BlockedSpot spot : data.blocked) {
            CompoundTag e = new CompoundTag();
            e.putString("Dim", spot.dimension());
            e.putInt("X", spot.pos().getX());
            e.putInt("Y", spot.pos().getY());
            e.putInt("Z", spot.pos().getZ());
            e.putString("Kind", spot.kind());
            blocked.add(e);
        }
        tag.put(BLOCKED, blocked);
        playerTag.put(ROOT, tag);
    }

    public static void readFromNbt(ServerPlayer player, CompoundTag playerTag) {
        // Fresh login invalidates any pending post-save evict: the in-memory
        // state below belongs to the live session and must not be reaped.
        EVICT_ARMED.remove(player.getUUID());
        Data data = new Data();
        if (playerTag.contains(ROOT, Tag.TAG_COMPOUND)) {
            CompoundTag tag = playerTag.getCompound(ROOT);
            // Legacy positional records are intentionally ignored (fresh registry model).
            ListTag list = tag.getList(POINTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                String id = e.getString("Id");
                if (id.isEmpty()) {
                    continue;
                }
                data.points.put(id, new PointSnapshot(
                        id,
                        e.getString("Dim"),
                        new BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z")),
                        e.getString("Kind"),
                        e.getString("DefaultName"),
                        e.getInt("DefaultColor"),
                        e.getString("Name"),
                        e.contains("Color") ? e.getInt("Color") : e.getInt("DefaultColor")));
            }
            data.spawnHidden = tag.getBoolean(SPAWN_HIDDEN);
            data.spawnName = tag.contains(SPAWN_NAME, Tag.TAG_STRING) ? tag.getString(SPAWN_NAME) : "";
            ListTag blocked = tag.getList(BLOCKED, Tag.TAG_COMPOUND);
            for (int i = 0; i < blocked.size(); i++) {
                CompoundTag e = blocked.getCompound(i);
                String dim = e.getString("Dim");
                if (dim.isEmpty()) {
                    continue;
                }
                data.blocked.add(new BlockedSpot(dim,
                        new BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z")),
                        e.getString("Kind")));
            }
        }
        STORE.put(player.getUUID(), data);
    }

    public static List<PointSnapshot> points(ServerPlayer player) {
        return List.copyOf(data(player).points.values());
    }

    public static PointSnapshot point(ServerPlayer player, String id) {
        return data(player).points.get(id);
    }

    public static void putSnapshot(ServerPlayer player, PointSnapshot snapshot) {
        data(player).points.put(snapshot.id(), snapshot);
    }

    public static void dropPoint(ServerPlayer player, String id) {
        data(player).points.remove(id);
    }

    public static String customName(ServerPlayer player, String pointId) {
        PointSnapshot snapshot = data(player).points.get(pointId);
        return snapshot == null ? "" : snapshot.name();
    }

    public static void setCustomName(ServerPlayer player, String pointId, String name) {
        Data data = data(player);
        PointSnapshot snapshot = data.points.get(pointId);
        if (snapshot == null) {
            return;
        }
        data.points.put(pointId, new PointSnapshot(snapshot.id(), snapshot.dimension(),
                snapshot.pos(), snapshot.kind(), snapshot.defaultName(), snapshot.defaultColor(),
                name, snapshot.color()));
    }

    public static void setCustomColor(ServerPlayer player, String pointId, int color) {
        Data data = data(player);
        PointSnapshot snapshot = data.points.get(pointId);
        if (snapshot == null) {
            return;
        }
        data.points.put(pointId, new PointSnapshot(snapshot.id(), snapshot.dimension(),
                snapshot.pos(), snapshot.kind(), snapshot.defaultName(), snapshot.defaultColor(),
                snapshot.name(), color));
    }

    public static boolean isSpawnHidden(ServerPlayer player) {
        return data(player).spawnHidden;
    }

    public static void setSpawnHidden(ServerPlayer player, boolean hidden) {
        data(player).spawnHidden = hidden;
    }

    /** Custom world-spawn name, "" = localized default. */
    public static String spawnName(ServerPlayer player) {
        String name = data(player).spawnName;
        return name == null ? "" : name;
    }

    public static void setSpawnName(ServerPlayer player, String name) {
        data(player).spawnName = name == null ? "" : name;
    }

    public static List<BlockedSpot> blocked(ServerPlayer player) {
        return List.copyOf(data(player).blocked);
    }

    public static boolean isBlocked(ServerPlayer player, String dimension, BlockPos pos) {
        for (BlockedSpot spot : data(player).blocked) {
            if (spot.dimension().equals(dimension) && spot.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if newly added, false if already present. */
    public static boolean addBlocked(ServerPlayer player, String dimension, BlockPos pos, String kind) {
        Data data = data(player);
        for (BlockedSpot spot : data.blocked) {
            if (spot.dimension().equals(dimension) && spot.pos().equals(pos)) {
                return false;
            }
        }
        data.blocked.add(new BlockedSpot(dimension, pos, kind));
        return true;
    }

    /** @return true if an entry was removed. */
    public static boolean removeBlocked(ServerPlayer player, String dimension, BlockPos pos) {
        return data(player).blocked.removeIf(
                spot -> spot.dimension().equals(dimension) && spot.pos().equals(pos));
    }
}
