package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.network.ServerHelloPacket;
import com.vlogf.respawnpoints.platform.Services;
import com.vlogf.respawnpoints.server.DeathManager;
import com.vlogf.respawnpoints.server.RespawnCommands;
import com.vlogf.respawnpoints.server.RespawnConfig;
import com.vlogf.respawnpoints.server.ServerIdentity;
import com.vlogf.respawnpoints.server.ServerHandlers;
import com.vlogf.respawnpoints.server.ServerInteractionLogic;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class NeoForgeEvents {
    private NeoForgeEvents() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                RespawnConfig.load(event.getServer().getServerDirectory()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) ->
                DeathManager.onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                RespawnCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            if (event.isCanceled()) {
                return;
            }
            if (event.getEntity() instanceof ServerPlayer player) {
                DeathManager.onDeath(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                // Clear rate limit so initial ClientReadyPacket is never throttled
                ServerHandlers.clearReadyRateLimit(player.getUUID());
                // Send ServerHelloPacket (client will respond with ClientReadyPacket)
                Services.NETWORK.sendToPlayer(player,
                        new ServerHelloPacket(ServerIdentity.get(player.server).uuid()));
                // Clean snapshots broken while offline before the client is ready;
                // the first push after ClientReady is already correct.
                DeathManager.pruneOnJoin(player);
                // pushUpdate() will be called in ServerHandlers.onReady() after ClientReadyPacket
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) ->
                DeathManager.clearReady(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (event.getPlayer() instanceof ServerPlayer player && !player.level().isClientSide()) {
                DeathManager.onBlockBroken(player,
                        player.level().dimension().location().toString(), event.getPos());
            }
        });
        // Shift + empty-hand right-click on a TRACKED bed/anchor is the rename
        // gesture: consume so vanilla does not sleep/set-spawn.
        // Order and conditions live in ServerInteractionLogic (shared with Fabric).
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            if (event.getEntity() instanceof net.minecraft.world.entity.player.Player player
                    && player.isShiftKeyDown()
                    && event.getLevel().isClientSide()
                    && player instanceof net.minecraft.client.player.LocalPlayer local
                    && com.vlogf.respawnpoints.client.ClientBedActions.handleSneakUse(
                            event.getLevel(), local, event.getPos())) {
                event.setCanceled(true);
                return;
            }
            if (event.getLevel().isClientSide()
                    || !(event.getEntity() instanceof ServerPlayer serverPlayer)
                    || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
                return;
            }
            var verdict = ServerInteractionLogic.handleUse(serverPlayer, serverLevel,
                    event.getPos(), new ServerInteractionLogic.Call(
                            event.getEntity().isShiftKeyDown(),
                            event.getEntity().getMainHandItem().isEmpty(),
                            event.getEntity().getOffhandItem().isEmpty()));
            if (verdict == ServerInteractionLogic.Verdict.CONSUME) {
                serverPlayer.swing(event.getHand(), true);
                event.setCanceled(true);
            }
        });
    }
}