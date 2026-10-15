package com.vlogf.respawnpoints.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;

/**
 * Loader-agnostic right-click logic for beds/anchors.
 * Fabric and NeoForge adapters only translate their event into {@link Call} —
 * the order and conditions live here once, so both loaders behave identically.
 */
public final class ServerInteractionLogic {
    private ServerInteractionLogic() {
    }

    public enum Verdict {
        PASS,
        CONSUME
    }

    public record Call(boolean shift, boolean mainEmpty, boolean offEmpty) {
    }

    public static Verdict handleUse(ServerPlayer player, ServerLevel level, BlockPos pos, Call call) {
        // 1. Block/unblock/inspect capture click (armed via commands).
        if (DeathManager.tryCaptureBlockClick(player, level, pos)
                || DeathManager.tryCaptureInspect(player, level, pos)) {
            return Verdict.CONSUME;
        }

        var state = level.getBlockState(pos);
        boolean isAnchor = state.getBlock() instanceof RespawnAnchorBlock;

        // 2. Plain right-click on a charged anchor (both hands empty, no shift).
        // Never blocks the first vanilla bind; consumes repeat clicks.
        if (!call.shift() && call.mainEmpty() && call.offEmpty() && isAnchor
                && DeathManager.onAnchorTouch(player, level.dimension(), pos)) {
            return Verdict.CONSUME;
        }

        // 3. Anchor charging (anything held): re-read charges once vanilla applied it.
        // Never consumes — only schedules a deferred refresh.
        if (isAnchor && (!call.mainEmpty() || !call.offEmpty())) {
            var dim = level.dimension();
            player.server.execute(() -> DeathManager.refreshAnchor(player, dim, pos));
        }

        // 4. Shift + empty-hand on a TRACKED bed/anchor = rename gesture, consume
        // so vanilla does not sleep/set-spawn.
        if (!call.shift() || !call.mainEmpty()) {
            return Verdict.PASS;
        }
        BlockPos keyPos = pos;
        if (state.getBlock() instanceof BedBlock) {
            keyPos = SpawnRegistry.headPos(level, pos);
            if (keyPos == null) {
                return Verdict.PASS;
            }
        } else if (!isAnchor) {
            return Verdict.PASS;
        }
        var record = SpawnRegistry.get(player.server.overworld())
                .byPos(GlobalPos.of(level.dimension(), keyPos));
        if (record != null && record.boundPlayers().contains(player.getUUID())) {
            return Verdict.CONSUME;
        }
        return Verdict.PASS;
    }
}
