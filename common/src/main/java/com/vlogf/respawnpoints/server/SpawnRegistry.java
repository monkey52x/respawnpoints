package com.vlogf.respawnpoints.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-canonical spawn points (beds + respawn anchors), persisted in level data.
 * Lookup is exact by {@link GlobalPos} (beds always normalized to HEAD) — no
 * proximity scans, so a neighbouring bed can never be caught accidentally.
 * Each record has a stable random UUID shared with all bound players.
 * Threading: server thread only. Returned records expose the live
 * boundPlayers set — do not mutate it outside bind/unbind/remove.
 */
public class SpawnRegistry extends net.minecraft.world.level.saveddata.SavedData {
    private static final String KEY = "respawnpoints_spawns";

    public record SpawnPointRecord(
            UUID id,
            GlobalPos pos,
            String kind,
            int color,
            String defaultName,
            int charges,
            Set<UUID> boundPlayers) {
        public boolean isAnchor() {
            return "ANCHOR".equals(kind);
        }
    }

    private final Map<GlobalPos, SpawnPointRecord> byPos = new LinkedHashMap<>();

    public static SpawnRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(SpawnRegistry::new, SpawnRegistry::load, null), KEY);
    }

    private static SpawnRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
        SpawnRegistry registry = new SpawnRegistry();
        ListTag list = tag.getList("Spawns", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                UUID id = e.getUUID("Id");
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(e.getString("Dim")));
                BlockPos pos = new BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z"));
                Set<UUID> bound = new LinkedHashSet<>();
                ListTag players = e.getList("Players", Tag.TAG_COMPOUND);
                for (int j = 0; j < players.size(); j++) {
                    try {
                        bound.add(players.getCompound(j).getUUID("Uuid"));
                    } catch (Exception ignored) {
                    }
                }
                registry.byPos.put(GlobalPos.of(dim, pos), new SpawnPointRecord(
                        id, GlobalPos.of(dim, pos), e.getString("Kind"), e.getInt("Color"),
                        e.getString("Default"), e.getInt("Charges"), bound));
            } catch (Exception ignored) {
                // Skip corrupt entries.
            }
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (SpawnPointRecord record : byPos.values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("Id", record.id());
            e.putString("Dim", record.pos().dimension().location().toString());
            e.putInt("X", record.pos().pos().getX());
            e.putInt("Y", record.pos().pos().getY());
            e.putInt("Z", record.pos().pos().getZ());
            e.putString("Kind", record.kind());
            e.putInt("Color", record.color());
            e.putString("Default", record.defaultName());
            e.putInt("Charges", record.charges());
            ListTag players = new ListTag();
            for (UUID uuid : record.boundPlayers()) {
                CompoundTag p = new CompoundTag();
                p.putUUID("Uuid", uuid);
                players.add(p);
            }
            e.put("Players", players);
            list.add(e);
        }
        tag.put("Spawns", list);
        return tag;
    }

    public SpawnPointRecord byId(UUID id) {
        for (SpawnPointRecord record : byPos.values()) {
            if (record.id().equals(id)) {
                return record;
            }
        }
        return null;
    }

    public SpawnPointRecord byPos(GlobalPos pos) {
        return byPos.get(pos);
    }

    /** True when the player is already bound at this exact position (no re-bind needed). */
    public boolean isBound(ResourceKey<Level> dim, BlockPos pos, UUID player) {
        SpawnPointRecord record = byPos.get(GlobalPos.of(dim, pos.immutable()));
        return record != null && record.boundPlayers().contains(player);
    }

    public List<SpawnPointRecord> allForPlayer(UUID player) {
        List<SpawnPointRecord> result = new ArrayList<>();
        for (SpawnPointRecord record : byPos.values()) {
            if (record.boundPlayers().contains(player)) {
                result.add(record);
            }
        }
        return result;
    }

    public List<SpawnPointRecord> all() {
        return List.copyOf(byPos.values());
    }

    /**
     * Bind a player to the point at the given position, creating the record
     * (with a fresh random UUID) when absent. Past the per-player limit the
     * player's oldest binding drops.
     */
    public SpawnPointRecord bind(ResourceKey<Level> dim, BlockPos pos, String kind,
                                 int color, String defaultName, int charges, UUID player) {
        GlobalPos key = GlobalPos.of(dim, pos.immutable());
        SpawnPointRecord existing = byPos.get(key);
        if (existing != null) {
            if (existing.boundPlayers().add(player)) {
                setDirty();
            }
            return existing;
        }
        int owned = 0;
        GlobalPos oldest = null;
        for (var entry : byPos.entrySet()) {
            if (entry.getValue().boundPlayers().contains(player)) {
                owned++;
                if (oldest == null) {
                    oldest = entry.getKey();
                }
            }
        }
        if (owned >= RespawnConfig.getBedLimit() && oldest != null) {
            var victim = byPos.get(oldest);
            if (victim != null) {
                victim.boundPlayers().remove(player);
                if (victim.boundPlayers().isEmpty()) {
                    byPos.remove(oldest);
                }
            }
            setDirty();
        }
        SpawnPointRecord record = new SpawnPointRecord(UUID.randomUUID(), key, kind,
                color, defaultName, charges, new LinkedHashSet<>(Set.of(player)));
        byPos.put(key, record);
        setDirty();
        return record;
    }

    /** Refresh the stored charge count (anchors). No-op for unknown records. */
    public void setCharges(UUID id, int charges) {
        for (var entry : byPos.entrySet()) {
            if (entry.getValue().id().equals(id)) {
                SpawnPointRecord old = entry.getValue();
                byPos.put(entry.getKey(), new SpawnPointRecord(old.id(), old.pos(), old.kind(),
                        old.color(), old.defaultName(), charges, new LinkedHashSet<>(old.boundPlayers())));
                setDirty();
                return;
            }
        }
    }

    /**
     * Unbind one player. The record survives while others stay bound.
     *
     * @return true when the binding existed.
     */
    public boolean unbind(UUID id, UUID player) {
        SpawnPointRecord record = byId(id);
        if (record == null) {
            return false;
        }
        boolean removed = record.boundPlayers().remove(player);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /**
     * Delete the whole record (block broken): unbinds everyone.
     *
     * @return the deleted record with its bound players, or null.
     */
    public SpawnPointRecord remove(GlobalPos pos) {
        SpawnPointRecord removed = byPos.remove(pos);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    /** Normalize any bed half to its HEAD position (anchors pass through). */
    public static BlockPos headPos(net.minecraft.world.level.Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return null;
        }
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.relative(BedBlock.getConnectedDirection(state));
        }
        return pos;
    }
}
