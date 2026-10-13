package com.vlogf.respawnpoints.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Direct field access to restore/clear the respawn config after our override. */
@Mixin(ServerPlayer.class)
public interface ServerPlayerRespawnAccessor {
    @Accessor("respawnPosition")
    void respawnpoints$setRespawnPositionDirect(BlockPos pos);

    @Accessor("respawnDimension")
    void respawnpoints$setRespawnDimensionDirect(ResourceKey<Level> dimension);

    @Accessor("respawnAngle")
    void respawnpoints$setRespawnAngleDirect(float angle);

    @Accessor("respawnForced")
    void respawnpoints$setRespawnForcedDirect(boolean forced);
}
