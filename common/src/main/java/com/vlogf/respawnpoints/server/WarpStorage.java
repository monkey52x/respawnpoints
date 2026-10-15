package com.vlogf.respawnpoints.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import com.vlogf.respawnpoints.util.Slugs;
import java.util.ArrayList;
import java.util.List;

/** Server warps, persisted per world. Ids are unique slugs; renames keep the id stable. */
public class WarpStorage extends SavedData {
    private static final String KEY = "respawnpoints_warps";
    private static final int NBT_VERSION = 1;
    private final List<WarpPoint> warps = new ArrayList<>();

    public static WarpStorage get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarpStorage::new, WarpStorage::load, null), KEY);
    }

    private static WarpStorage load(CompoundTag tag, HolderLookup.Provider provider) {
        WarpStorage storage = new WarpStorage();
        int version = tag.contains("Version") ? tag.getInt("Version") : 0;
        ListTag list = tag.getList("Warps", Tag.TAG_COMPOUND);
        int before = list.size();
        storage.warps.addAll(WarpPoint.readList(list));
        int dropped = before - storage.warps.size();
        if (dropped > 0 || version != NBT_VERSION) {
            com.vlogf.respawnpoints.Constants.LOG.info(
                    "Loaded warps v{} (expected v{}): {} entries, dropped {} corrupt",
                    version, NBT_VERSION, storage.warps.size(), dropped);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("Version", NBT_VERSION);
        tag.put("Warps", WarpPoint.writeList(warps));
        return tag;
    }

    public List<WarpPoint> all() {
        return List.copyOf(warps);
    }

    public WarpPoint add(String name, String symbol, String dimension, net.minecraft.core.BlockPos pos) {
        String base = Slugs.slugify(name);
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
