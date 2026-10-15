package com.vlogf.respawnpoints.server;

import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.UUID;

/**
 * Single place for "is this point still real" checks.
 * Lenient {@link #isLivePoint} (unloaded chunk = assumed live) is used for
 * display-time filtering; strict {@link #isSelectable} is used on select.
 * Same block predicates back both, so the rules can never diverge.
 */
public final class PointValidator {
    private PointValidator() {
    }

    /** Lenient dimension parse: null on corrupt NBT instead of throwing. */
    public static ResourceKey<Level> tryDimKey(String dim) {
        if (dim == null || dim.isEmpty()) {
            return null;
        }
        try {
            ResourceLocation location = ResourceLocation.tryParse(dim);
            if (location == null) {
                return null;
            }
            return ResourceKey.create(Registries.DIMENSION, location);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isBedHead(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    public static boolean isBedFree(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD
                && !state.getValue(BedBlock.OCCUPIED);
    }

    public static boolean isAnchor(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof RespawnAnchorBlock;
    }

    public static boolean isChargedAnchor(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof RespawnAnchorBlock
                && state.getValue(RespawnAnchorBlock.CHARGE) > 0;
    }

    public static int anchorCharges(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof RespawnAnchorBlock) {
            return state.getValue(RespawnAnchorBlock.CHARGE);
        }
        return 0;
    }

    /** The record must still exist in the world (loaded chunk) or be assumed live (unloaded). */
    public static boolean isLivePoint(ServerPlayer player, SpawnRegistry.SpawnPointRecord record) {
        ServerLevel level = player.server.getLevel(record.pos().dimension());
        if (level == null || !level.hasChunkAt(record.pos().pos())) {
            return true;
        }
        if (record.isAnchor()) {
            return isAnchor(level, record.pos().pos());
        }
        return isBedHead(level, record.pos().pos());
    }

    /** Strict select-time check: mirrors the old DeathManager.validate 1:1. */
    public static boolean isSelectable(ServerPlayer player, RespawnEntry entry) {
        return switch (entry.type()) {
            case SPAWN -> !PlayerRespawnData.isSpawnHidden(player);
            case BED, ANCHOR -> {
                UUID uuid = parseUuid(entry.id());
                SpawnRegistry.SpawnPointRecord record = uuid == null ? null
                        : SpawnRegistry.get(player.server.overworld()).byId(uuid);
                if (record == null || !record.boundPlayers().contains(player.getUUID())) {
                    yield false;
                }
                ServerLevel level = player.server.getLevel(record.pos().dimension());
                if (level == null) {
                    yield false;
                }
                // Unloaded chunk = assumed valid (checked strictly once loaded).
                if (!level.hasChunkAt(record.pos().pos())) {
                    yield true;
                }
                if (entry.type() == RespawnPointsType.BED) {
                    yield isBedFree(level, record.pos().pos());
                }
                yield isChargedAnchor(level, record.pos().pos());
            }
            case WARP -> {
                String id = entry.id();
                if (!id.startsWith("warp:") || id.length() <= "warp:".length()) {
                    yield false;
                }
                WarpPoint warp = WarpStorage.get(player.server.overworld()).find(id.substring("warp:".length()));
                yield warp != null && warp.pos().equals(entry.pos())
                        && warp.dimension().equals(entry.dimension());
            }
            case NEAR_DEATH -> RespawnConfig.isEnableNearDeath()
                    && PlayerRespawnData.nearDeathCooldownRemainingMs(player) <= 0;
        };
    }

    private static UUID parseUuid(String pointId) {
        try {
            return UUID.fromString(pointId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
