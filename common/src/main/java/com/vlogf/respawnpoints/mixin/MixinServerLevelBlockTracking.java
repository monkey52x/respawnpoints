package com.vlogf.respawnpoints.mixin;

import com.vlogf.respawnpoints.server.DeathManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches bed/anchor removal by any means (explosions, fire, pistons,
 * {@code /fill}, creative break): player-break events only see manual breaks.
 * Fires only on bed/anchor -&gt; non-bed/anchor transitions; the registry call
 * is idempotent, so the double-fire with the manual-break hooks is harmless.
 * Target is Level (setBlock is defined there): the ServerLevel guard keeps
 * client setBlock calls cheap (early return before any block lookup).
 */
@Mixin(Level.class)
public class MixinServerLevelBlockTracking {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void respawnpoints$trackRemoval(BlockPos pos, BlockState newState, int flags,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        // Skip client-side prediction paths that share this method id.
        if (level.getServer() == null) {
            return;
        }
        var oldState = level.getBlockState(pos);
        boolean wasBed = oldState.getBlock() instanceof BedBlock;
        boolean wasAnchor = oldState.getBlock() instanceof RespawnAnchorBlock;
        if (!wasBed && !wasAnchor) {
            return;
        }
        boolean stillSame = wasBed
                ? newState.getBlock() instanceof BedBlock
                : newState.getBlock() instanceof RespawnAnchorBlock;
        if (stillSame) {
            return;
        }
        var server = level.getServer();
        if (server == null) {
            return;
        }
        DeathManager.onBlockRemoved(server, level.dimension().location().toString(), pos);
    }
}
