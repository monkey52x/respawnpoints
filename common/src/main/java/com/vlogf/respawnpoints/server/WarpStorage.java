package com.vlogf.respawnpoints.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Server warps, persisted per world. Ids are unique slugs; renames keep the id stable. */
public class WarpStorage extends SavedData {
    private static final String KEY = "respawnpoints_warps";
    private final List<WarpPoint> warps = new ArrayList<>();

    public static WarpStorage get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarpStorage::new, WarpStorage::load, null), KEY);
    }

    private static WarpStorage load(CompoundTag tag, HolderLookup.Provider provider) {
        WarpStorage storage = new WarpStorage();
        ListTag list = tag.getList("Warps", Tag.TAG_COMPOUND);
        storage.warps.addAll(WarpPoint.readList(list));
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put("Warps", WarpPoint.writeList(warps));
        return tag;
    }

    public List<WarpPoint> all() {
        return List.copyOf(warps);
    }

    public WarpPoint add(String name, String symbol, String dimension, net.minecraft.core.BlockPos pos) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        if (base.isEmpty()) {
            base = "warp";
        }
        String id = base;
        int n = 2;
        while (find(id) != null) {
            id = base + "-" + (n++);
        }
        WarpPoint warp = new WarpPoint(id, name, symbol, dimension, pos.immutable());
        warps.add(warp);
        setDirty();
        return warp;
    }

    public WarpPoint find(String id) {
        for (WarpPoint warp : warps) {
            if (warp.id().equals(id)) {
                return warp;
            }
        }
        return null;
    }

    public boolean remove(String id) {
        boolean removed = warps.removeIf(w -> w.id().equals(id));
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
