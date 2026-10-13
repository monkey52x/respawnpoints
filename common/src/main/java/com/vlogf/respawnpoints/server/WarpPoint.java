package com.vlogf.respawnpoints.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Admin warp point stored in level SavedData. Id is stable: rename keeps waypoints linked. */
public record WarpPoint(String id, String name, String symbol, String dimension, BlockPos pos) {
    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Name", name);
        tag.putString("Symbol", symbol);
        tag.putString("Dim", dimension);
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    public static WarpPoint read(CompoundTag tag) {
        return new WarpPoint(
                tag.getString("Id"),
                tag.getString("Name"),
                tag.getString("Symbol"),
                tag.getString("Dim"),
                new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")));
    }

    static ListTag writeList(java.util.List<WarpPoint> warps) {
        ListTag list = new ListTag();
        for (WarpPoint warp : warps) {
            list.add(warp.write());
        }
        return list;
    }

    static java.util.List<WarpPoint> readList(ListTag list) {
        java.util.List<WarpPoint> result = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag tag)) {
                continue;
            }
            try {
                WarpPoint warp = read(tag);
                if (warp.id() == null || warp.id().isEmpty()
                        || warp.dimension() == null || warp.dimension().isEmpty()) {
                    continue;
                }
                result.add(warp);
            } catch (Exception ignored) {
                // Skip corrupt entries.
            }
        }
        return result;
    }
}
