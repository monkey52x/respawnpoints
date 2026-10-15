package com.vlogf.respawnpoints.server;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/**
 * Random near-death spot with hard rules: only chunks that are loaded now or
 * saved FULL on disk (never generates new ones), lava/void always banned,
 * ocean falls back to a swim spawn. Returns null when banned.
 *
 * <p>Passes run in order, each only if the previous found nothing:
 * P1 dry-safe (heightmap first, then a small volumetric scan for caves),
 * P2 dry-risky (magma/cactus/campfires/powder snow/soul fire allowed),
 * P3 swim (water surface). Server thread only, everything bounded.
 */
public final class NearDeathSearch {
    private NearDeathSearch() {
    }

    private static final int POINT_ATTEMPTS = 48;
    private static final int RISKY_ATTEMPTS = 32;
    private static final int SWIM_ATTEMPTS = 16;
    private static final int FEASIBILITY_SAMPLES = 24;
    /** Disk loads of explored chunks per search (no generation, ever). */
    private static final int MAX_DISK_LOADS = 8;

    /** Banned in the dry-safe pass (feet/head/below). Lava is banned everywhere. */
    private static final Set<Block> DRY_BANNED = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS,
            Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.POWDER_SNOW, Blocks.WITHER_ROSE);
    /** Banned even in the risky pass. */
    private static final Set<Block> RISKY_BANNED = Set.of(
            Blocks.LAVA, Blocks.FIRE);

    /** Cheap offer-time check: any loaded-or-saved column in the band. No loads, no validation. */
    public static boolean isFeasible(ServerPlayer player) {
        ServerLevel level = player.server.getLevel(player.level().dimension());
        if (level == null) {
            return false;
        }
        BlockPos deathPos = player.blockPosition();
        int radius = bandRadius();
        int minDist = bandMin(radius);
        ChunkGate.Scan scan = new ChunkGate.Scan();
        for (int i = 0; i < FEASIBILITY_SAMPLES; i++) {
            int[] xz = polar(level, deathPos, minDist, radius);
            BlockPos probe = new BlockPos(xz[0], deathPos.getY(), xz[1]);
            if (level.hasChunkAt(probe)) {
                return true;
            }
            if (ChunkGate.isSavedFull(level, xz[0] >> 4, xz[1] >> 4, scan)) {
                return true;
            }
        }
        return false;
    }

    /** @return a safe spot, or null when the random teleport is banned. */
    public static BlockPos find(ServerPlayer player, ServerLevel level, BlockPos deathPos) {
        long t0 = System.nanoTime();
        int radius = bandRadius();
        int minDist = bandMin(radius);
        boolean nether = level.dimension() == Level.NETHER;
        // Nether bedrock roof: ceiling layer y=127, walkable top y=128. Whoever died
        // below the roof must never surface on it, and vice versa. Standing inside
        // the ceiling layer (dug-out hole) counts as the top side.
        boolean aboveRoof = nether && deathPos.getY() >= 127;
        boolean belowRoof = nether && !aboveRoof;
        SearchState state = new SearchState();

        // Died above the roof: the roof top right below is the obvious answer.
        if (aboveRoof) {
            BlockPos roof = new BlockPos(deathPos.getX(), 128, deathPos.getZ());
            if (ensureLoaded(level, roof, state)
                    && (isDrySafe(level, roof) || isDryRisky(level, roof))) {
                log(radius, minDist, t0);
                return roof;
            }
        }

        // P1: dry-safe. Heightmap first (O(1), RTP-style), then a small
        // volumetric scan around the death height (caves the heightmap misses).
        for (int i = 0; i < POINT_ATTEMPTS; i++) {
            int[] xz = polar(level, deathPos, minDist, radius);
            if (!ensureLoadedColumn(level, xz[0], xz[1], deathPos.getY(), state)) {
                continue;
            }
            BlockPos surface = heightCandidate(level, xz[0], xz[1], belowRoof, aboveRoof);
            if (surface != null && isDrySafe(level, surface)) {
                log(radius, minDist, t0);
                return surface;
            }
            BlockPos cave = volumetricCandidate(level, deathPos, xz[0], xz[1], belowRoof, aboveRoof, true);
            if (cave != null) {
                log(radius, minDist, t0);
                return cave;
            }
        }

        // P2: dry-risky (dangerous but walkable: magma, cactus, campfires...).
        for (int i = 0; i < RISKY_ATTEMPTS; i++) {
            int[] xz = polar(level, deathPos, minDist, radius);
            if (!ensureLoadedColumn(level, xz[0], xz[1], deathPos.getY(), state)) {
                continue;
            }
            BlockPos surface = heightCandidate(level, xz[0], xz[1], belowRoof, aboveRoof);
            if (surface != null && (isDrySafe(level, surface) || isDryRisky(level, surface))) {
                log(radius, minDist, t0);
                return surface;
            }
            BlockPos cave = volumetricCandidate(level, deathPos, xz[0], xz[1], belowRoof, aboveRoof, false);
            if (cave != null) {
                log(radius, minDist, t0);
                return cave;
            }
        }

        // P3: swim (no water in the Nether, so this pass is naturally empty there).
        if (!nether) {
            for (int i = 0; i < SWIM_ATTEMPTS; i++) {
                int[] xz = polar(level, deathPos, minDist, radius);
                if (!ensureLoadedColumn(level, xz[0], xz[1], deathPos.getY(), state)) {
                    continue;
                }
                int y = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(xz[0], 0, xz[1])).getY();
                BlockPos candidate = new BlockPos(xz[0], y, xz[1]);
                if (isSwimSpot(level, candidate)) {
                    log(radius, minDist, t0);
                    return candidate;
                }
            }
        }

        log(radius, minDist, t0);
        return null;
    }

    private static int bandRadius() {
        return Math.max(16, RespawnConfig.getNearDeathRadius());
    }

    private static int bandMin(int radius) {
        return Math.min(Math.max(0, RespawnConfig.getNearDeathMinDistance()), radius);
    }

    /** Polar pick with sqrt distribution (uniform over the ring area). */
    private static int[] polar(ServerLevel level, BlockPos origin, int minDist, int radius) {
        double angle = level.random.nextDouble() * Math.PI * 2.0;
        double span = Math.max(1, (long) radius * radius - (long) minDist * minDist);
        double dist = Math.sqrt((long) minDist * minDist + level.random.nextDouble() * span);
        int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
        return new int[]{x, z};
    }

    /** True when the column is usable (loaded now, or saved FULL and loaded from disk). Never generates. */
    private static boolean ensureLoadedColumn(ServerLevel level, int x, int z, int y, SearchState state) {
        return ensureLoaded(level, new BlockPos(x, y, z), state);
    }

    private static boolean ensureLoaded(ServerLevel level, BlockPos pos, SearchState state) {
        if (level.hasChunkAt(pos)) {
            return true;
        }
        if (state.loads >= MAX_DISK_LOADS) {
            return false;
        }
        if (!ChunkGate.isSavedFull(level, pos.getX() >> 4, pos.getZ() >> 4, state.scan)) {
            return false;
        }
        // Saved FULL on disk (it was loaded before): read it, never generate.
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        state.loads++;
        return level.hasChunkAt(pos);
    }

    /** Heightmap candidate (surface), or null when the roof rules reject it. */
    private static BlockPos heightCandidate(ServerLevel level, int x, int z, boolean belowRoof, boolean aboveRoof) {
        int y = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
        if (belowRoof && y >= 127) {
            return null;
        }
        if (aboveRoof && y < 128) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    /** Small vertical scan around the death height. Safe-only when strict. */
    private static BlockPos volumetricCandidate(ServerLevel level, BlockPos deathPos, int x, int z,
                                                boolean belowRoof, boolean aboveRoof, boolean strict) {
        int band = Math.max(0, bandRadius() - bandMin(bandRadius()));
        int yHalf = band > 128 ? 8 : 16;
        int y0 = Math.max(level.getMinBuildHeight() + 1,
                Math.min(deathPos.getY() + yHalf, level.getMaxBuildHeight() - 3));
        int y1 = Math.max(level.getMinBuildHeight() + 1, deathPos.getY() - yHalf);
        if (belowRoof) {
            y0 = Math.min(y0, 126);
        } else if (aboveRoof) {
            y1 = Math.max(y1, 128);
        }
        for (int y = y0; y >= y1; y--) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (strict ? isDrySafe(level, candidate) : isDryRisky(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Dry and clean: air x2, sturdy ground, no fluids, blacklist-free. */
    static boolean isDrySafe(ServerLevel level, BlockPos pos) {
        if (!hasChunkAt3(level, pos)) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty()
                || !level.getFluidState(pos.above()).isEmpty()
                || !level.getFluidState(pos.below()).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        var belowState = level.getBlockState(pos.below());
        if (belowState.isAir() || !belowState.isFaceSturdy(level, pos.below(), Direction.UP)) {
            return false;
        }
        if (bannedAt(level, pos, DRY_BANNED)) {
            return false;
        }
        // Nether roof: standing on bedrock below y=128 means inside the ceiling rock.
        // Only the top surface (y=128+) is a valid roof spawn.
        if (level.dimension() == Level.NETHER && pos.getY() < 128
                && belowState.is(Blocks.BEDROCK)) {
            return false;
        }
        return true;
    }

    /** Dry but walkable danger (magma, cactus, campfires...). Lava/fire/water still banned. */
    static boolean isDryRisky(ServerLevel level, BlockPos pos) {
        if (!hasChunkAt3(level, pos)) {
            return false;
        }
        var fluid = level.getFluidState(pos);
        var fluidAbove = level.getFluidState(pos.above());
        var fluidBelow = level.getFluidState(pos.below());
        if (!fluid.isEmpty() || !fluidAbove.isEmpty() || !fluidBelow.isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        var belowState = level.getBlockState(pos.below());
        if (belowState.isAir() || !belowState.isFaceSturdy(level, pos.below(), Direction.UP)) {
            return false;
        }
        if (bannedAt(level, pos, RISKY_BANNED)) {
            return false;
        }
        if (level.dimension() == Level.NETHER && pos.getY() < 128
                && belowState.is(Blocks.BEDROCK)) {
            return false;
        }
        return true;
    }

    /** Swim spawn: water surface, breathable head, no bubble column below. */
    static boolean isSwimSpot(ServerLevel level, BlockPos pos) {
        if (!hasChunkAt3(level, pos)) {
            return false;
        }
        if (!level.getFluidState(pos).is(net.minecraft.world.level.material.Fluids.WATER)) {
            return false;
        }
        if (!level.getBlockState(pos.above()).isAir()
                || !level.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        var belowState = level.getBlockState(pos.below());
        if (belowState.is(Blocks.MAGMA_BLOCK) || belowState.is(Blocks.SOUL_SAND)) {
            return false;
        }
        return true;
    }

    private static boolean hasChunkAt3(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below());
    }

    private static boolean bannedAt(ServerLevel level, BlockPos pos, Set<Block> banned) {
        return banned.contains(level.getBlockState(pos).getBlock())
                || banned.contains(level.getBlockState(pos.above()).getBlock())
                || banned.contains(level.getBlockState(pos.below()).getBlock());
    }

    private static void log(int radius, int minDist, long t0) {
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        if (tookMs > 200) {
            Constants.LOG.warn("nearDeath search slow: radius={} min={} tookMs={}", radius, minDist, tookMs);
        } else {
            Constants.LOG.debug("nearDeath search: radius={} min={} tookMs={}", radius, minDist, tookMs);
        }
    }

    private static final class SearchState {
        int loads;
        final ChunkGate.Scan scan = new ChunkGate.Scan();
    }
}
