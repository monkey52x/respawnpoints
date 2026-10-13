package com.vlogf.respawnpoints.mixin;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.server.DeathManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Blind warp arrival: while {@link DeathManager#isBlindWarpActive()} the vanilla
 * spot validation (respawnable checks, messages, world-spawn fallback) is skipped
 * and the overridden position is accepted as-is. No blocks are read.
 * Admin warps are placed deliberately, so a walled-in target is the admin's
 * placement issue. {@code require = 0}: if the target drifts, the post-respawn
 * teleport in {@link DeathManager} still lands the player exactly.
 */
@Mixin(ServerPlayer.class)
public class MixinWarpBlindRespawn {
    private static volatile java.lang.reflect.Method respawnPosAngleOf;
    private static volatile boolean reflectionWarned;

    @Inject(method = "findRespawnAndUseSpawnBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void respawnpoints$blindWarp(ServerLevel level, BlockPos pos, float angle,
                                               boolean forced, boolean keepData,
                                               CallbackInfoReturnable<Optional> cir) {
        if (!DeathManager.isBlindWarpActive()) {
            return;
        }
        try {
            java.lang.reflect.Method m = respawnPosAngleOf;
            if (m == null && !reflectionWarned) {
                // RespawnPosAngle is package-private; its factory is public.
                Class<?> rpa = Class.forName("net.minecraft.server.level.ServerPlayer$RespawnPosAngle");
                m = rpa.getMethod("of", Vec3.class, BlockPos.class);
                respawnPosAngleOf = m;
            }
            if (m == null) {
                return;
            }
            Object at = m.invoke(null, pos.getBottomCenter(), pos);
            cir.setReturnValue(Optional.of(at));
        } catch (ReflectiveOperationException e) {
            if (!reflectionWarned) {
                reflectionWarned = true;
                Constants.LOG.warn("Blind warp intercept failed, falling back to vanilla checks", e);
            }
        }
    }
}
