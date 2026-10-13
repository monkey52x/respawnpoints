package com.vlogf.respawnpoints.fabric;

import com.vlogf.respawnpoints.network.ServerHelloPacket;
import com.vlogf.respawnpoints.platform.Services;
import com.vlogf.respawnpoints.server.DeathManager;
import com.vlogf.respawnpoints.server.PlayerRespawnData;
import com.vlogf.respawnpoints.server.RespawnCommands;
import com.vlogf.respawnpoints.server.RespawnConfig;
import com.vlogf.respawnpoints.server.ServerIdentity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/** Server-side Fabric hooks: death, join, commands. */
public final class FabricEvents {
    private FabricEvents() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(
                server -> RespawnConfig.load(server.getServerDirectory()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                DeathManager.onDeath(player);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            Services.NETWORK.sendToPlayer(player,
                    new ServerHelloPacket(ServerIdentity.get(server).uuid()));
            // Clean snapshots broken while offline before the client is ready;
            // the first push after ClientReady is already correct.
            DeathManager.pruneOnJoin(player);
            // Full push on login: rebuilds markers after a map-save wipe.
            DeathManager.pushUpdate(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                DeathManager.clearReady(handler.getPlayer().getUUID()));
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                DeathManager.onBlockBroken(serverPlayer,
                        world.dimension().location().toString(), pos);
            }
            return true;
        });
        // Shift + empty-hand right-click on a TRACKED bed/anchor is the rename
        // gesture: consume so vanilla does not sleep/set-spawn.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Block/unblock/inspect capture click: armed via bed block|unblock or debug inspect.
            if (!world.isClientSide() && player instanceof ServerPlayer capturePlayer
                    && world instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && (DeathManager.tryCaptureBlockClick(capturePlayer, serverLevel,
                            hitResult.getBlockPos())
                            || DeathManager.tryCaptureInspect(capturePlayer, serverLevel,
                                    hitResult.getBlockPos()))) {
                capturePlayer.swing(hand, true);
                return InteractionResult.SUCCESS;
            }
            // Plain right-click on a charged anchor is never blocked: the first click
            // binds in vanilla, repeat clicks are consumed by the mod (action-bar note).
            // Both hands must be empty so an off-hand glowstone charge is never eaten.
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer
                    && !player.isShiftKeyDown() && player.getMainHandItem().isEmpty()
                    && player.getOffhandItem().isEmpty()) {
                var touched = hitResult.getBlockPos();
                if (world.getBlockState(touched).getBlock()
                        instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                        && DeathManager.onAnchorTouch(serverPlayer, world.dimension(), touched)) {
                    // Explicit swing: the consumed click has no client-side prediction.
                    serverPlayer.swing(hand, true);
                    return InteractionResult.SUCCESS;
                }
            }
            // Anchor charging (anything held, either hand): re-read charges once vanilla
            // applied the change. Independent of shift - charging is done without it.
            if (!world.isClientSide() && player instanceof ServerPlayer serverCharger) {
                var touched = hitResult.getBlockPos();
                if (world.getBlockState(touched).getBlock()
                        instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                        && (!player.getMainHandItem().isEmpty()
                                || !player.getOffhandItem().isEmpty())) {
                    var dim = world.dimension();
                    serverCharger.server.execute(
                            () -> DeathManager.refreshAnchor(serverCharger, dim, touched));
                }
            }
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                    || !player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            var pos = hitResult.getBlockPos();
            var state = world.getBlockState(pos);
            boolean emptyHand = player.getMainHandItem().isEmpty();
            if (!emptyHand) {
                return InteractionResult.PASS;
            }
            net.minecraft.core.BlockPos keyPos = pos;
            if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                keyPos = com.vlogf.respawnpoints.server.SpawnRegistry.headPos(world, pos);
            } else if (!(state.getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock)) {
                return InteractionResult.PASS;
            }
            var record = com.vlogf.respawnpoints.server.SpawnRegistry
                    .get(serverPlayer.server.overworld())
                    .byPos(net.minecraft.core.GlobalPos.of(world.dimension(), keyPos));
            if (record != null && record.boundPlayers().contains(serverPlayer.getUUID())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                RespawnCommands.register(dispatcher));
    }
}
