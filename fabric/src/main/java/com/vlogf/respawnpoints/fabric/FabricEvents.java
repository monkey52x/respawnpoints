package com.vlogf.respawnpoints.fabric;

import com.vlogf.respawnpoints.network.ServerHelloPacket;
import com.vlogf.respawnpoints.platform.Services;
import com.vlogf.respawnpoints.server.DeathManager;
import com.vlogf.respawnpoints.server.PlayerRespawnData;
import com.vlogf.respawnpoints.server.RespawnCommands;
import com.vlogf.respawnpoints.server.RespawnConfig;
import com.vlogf.respawnpoints.server.ServerIdentity;
import com.vlogf.respawnpoints.server.ServerInteractionLogic;
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
        // Order and conditions live in ServerInteractionLogic (shared with NeoForge).
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()
                    || !(player instanceof ServerPlayer serverPlayer)
                    || !(world instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            var verdict = ServerInteractionLogic.handleUse(serverPlayer, serverLevel,
                    hitResult.getBlockPos(), new ServerInteractionLogic.Call(
                            player.isShiftKeyDown(),
                            player.getMainHandItem().isEmpty(),
                            player.getOffhandItem().isEmpty()));
            if (verdict == ServerInteractionLogic.Verdict.CONSUME) {
                serverPlayer.swing(hand, true);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                RespawnCommands.register(dispatcher));
    }
}
