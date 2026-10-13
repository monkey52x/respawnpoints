package com.vlogf.respawnpoints.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * Stable server identity, independent of the address the player joined with
 * (raw IP vs domain). The client keys its per-server map-manager choice by it.
 */
public class ServerIdentity extends SavedData {
    private static final String KEY = "respawnpoints_identity";
    private final UUID uuid;

    private ServerIdentity(UUID uuid) {
        this.uuid = uuid;
    }

    public static ServerIdentity get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> new ServerIdentity(UUID.randomUUID()),
                        ServerIdentity::load, null),
                KEY);
    }

    private static ServerIdentity load(CompoundTag tag, HolderLookup.Provider provider) {
        try {
            return new ServerIdentity(tag.getUUID("Uuid"));
        } catch (Exception e) {
            return new ServerIdentity(UUID.randomUUID());
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putUUID("Uuid", uuid);
        return tag;
    }

    public UUID uuid() {
        return uuid;
    }
}
