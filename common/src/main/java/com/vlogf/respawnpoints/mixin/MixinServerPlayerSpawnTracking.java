package com.vlogf.respawnpoints.mixin;

import com.vlogf.respawnpoints.server.DeathManager;
import com.vlogf.respawnpoints.server.PlayerRespawnData;
import com.vlogf.respawnpoints.server.SpawnRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds players to beds/anchors in the server registry. Forced sets (our respawn
 * override, admin commands) are skipped. Bed halves always resolve to the exact
 * HEAD position — no proximity scans.
 */
@Mixin(ServerPlayer.class)
public class MixinServerPlayerSpawnTracking {
    @Inject(method = "setRespawnPosition", at = @At("TAIL"))
    private void respawnpoints$trackBedOrAnchor(ResourceKey<Level> dimension, BlockPos pos, float angle,
                                               boolean forced, boolean sendMessage, CallbackInfo ci) {
        if (forced || pos == null) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.server == null) {
            return;
        }
        var level = self.server.getLevel(dimension);
        if (level == null) {
            return;
        }
        var state = level.getBlockState(pos);
        SpawnRegistry registry = SpawnRegistry.get(level);
        String dimName = dimension.location().toString();
        if (state.getBlock() instanceof BedBlock bedBlock) {
            BlockPos headPos = pos;
            if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
                headPos = pos.relative(BedBlock.getConnectedDirection(state));
            }
            // Blocklisted by the player (anti-misclick): vanilla sleep goes
            // through, but no marker, snapshot or list push is created.
            if (PlayerRespawnData.isBlocked(self, dimName, headPos)) {
                return;
            }
            int color = bedBlock.getColor().getTextColor();
            String defaultName = prettifyDye(bedBlock.getColor().getName()) + " Bed";
            SpawnRegistry.SpawnPointRecord record = registry.bind(dimension, headPos, "BED",
                    color, defaultName, -1, self.getUUID());
            DeathManager.ensureSnapshot(self, record);
            DeathManager.pushUpdate(self);
        } else if (state.getBlock() instanceof RespawnAnchorBlock) {
            if (PlayerRespawnData.isBlocked(self, dimName, pos)) {
                return;
            }
            int charges = state.getValue(RespawnAnchorBlock.CHARGE);
            DeathManager.bindAnchor(level, dimension, pos, charges, self);
            DeathManager.pushUpdate(self);
        }
    }

    private static String prettifyDye(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1));
        }
        return sb.toString();
    }
}
