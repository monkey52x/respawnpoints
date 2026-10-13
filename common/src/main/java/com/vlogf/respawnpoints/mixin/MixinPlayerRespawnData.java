package com.vlogf.respawnpoints.mixin;

import com.vlogf.respawnpoints.server.PlayerRespawnData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Persists per-player respawn data inside the player's own NBT. Server side only in practice. */
@Mixin(Player.class)
public class MixinPlayerRespawnData {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void respawnpoints$save(CompoundTag tag, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            PlayerRespawnData.writeToNbt(player, tag);
            // Logout-armed one-shot: the write above just persisted the live
            // state, so memory can now be reaped without data loss.
            if (PlayerRespawnData.consumeEvictArm(player.getUUID())) {
                PlayerRespawnData.evict(player.getUUID());
            }
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void respawnpoints$load(CompoundTag tag, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            PlayerRespawnData.readFromNbt(player, tag);
        }
    }
}
