package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.network.ServerHelloPacket;
import com.vlogf.respawnpoints.platform.Services;
import com.vlogf.respawnpoints.server.DeathManager;
import com.vlogf.respawnpoints.server.PlayerRespawnData;
import com.vlogf.respawnpoints.server.RespawnCommands;
import com.vlogf.respawnpoints.server.RespawnConfig;
import com.vlogf.respawnpoints.server.ServerIdentity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Server-side NeoForge hooks: death, join, commands. */
public final class NeoForgeEvents {
    private NeoForgeEvents() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                RespawnConfig.load(event.getServer().getServerDirectory()));
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
                Services.NETWORK.sendToPlayer(player,
                        new ServerHelloPacket(ServerIdentity.get(player.server).uuid()));
                // Clean snapshots broken while offline before the client is ready;
                // the first push after ClientReady is already correct.
                DeathManager.pruneOnJoin(player);
                // Full push on login: rebuilds markers after a map-save wipe.
                DeathManager.pushUpdate(player);
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
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            // Block/unblock/inspect capture click: armed via bed block|unblock or debug inspect.
            if (!event.getLevel().isClientSide()
                    && event.getEntity() instanceof ServerPlayer capturePlayer
                    && event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && (DeathManager.tryCaptureBlockClick(capturePlayer, serverLevel, event.getPos())
                            || DeathManager.tryCaptureInspect(capturePlayer, serverLevel,
                                    event.getPos()))) {
                capturePlayer.swing(event.getHand(), true);
                event.setCanceled(true);
                return;
            }
            // Plain right-click on a charged anchor is never blocked (server side).
            // Both hands must be empty so an off-hand glowstone charge is never eaten.
            if (!event.getLevel().isClientSide()
                    && event.getEntity() instanceof ServerPlayer serverPlayer
                    && !event.getEntity().isShiftKeyDown()
                    && event.getEntity().getMainHandItem().isEmpty()
                    && event.getEntity().getOffhandItem().isEmpty()
                    && event.getLevel().getBlockState(event.getPos()).getBlock()
                            instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                    && DeathManager.onAnchorTouch(serverPlayer,
                            event.getLevel().dimension(), event.getPos())) {
                // Explicit swing: the consumed click has no client-side prediction.
                serverPlayer.swing(event.getHand(), true);
                event.setCanceled(true);
                return;
            }
            // Anchor charging (anything held, either hand): re-read charges once vanilla
            // applied the change. Independent of shift - charging is done without it.
            if (!event.getLevel().isClientSide()
                    && event.getEntity() instanceof ServerPlayer serverCharger
                    && event.getLevel().getBlockState(event.getPos()).getBlock()
                            instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                    && (!event.getEntity().getMainHandItem().isEmpty()
                            || !event.getEntity().getOffhandItem().isEmpty())) {
                var dim = event.getLevel().dimension();
                var pos = event.getPos();
                serverCharger.server.execute(() ->
                        DeathManager.refreshAnchor(serverCharger, dim, pos));
            }
            if (!(event.getEntity() instanceof net.minecraft.world.entity.player.Player player)
                    || !player.isShiftKeyDown()) {
                return;
            }
            if (event.getLevel().isClientSide()
                    && player instanceof net.minecraft.client.player.LocalPlayer local
                    && com.vlogf.respawnpoints.client.ClientBedActions.handleSneakUse(
                            event.getLevel(), local, event.getPos())) {
                event.setCanceled(true);
                return;
            }
            if (!event.getLevel().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                var state = event.getLevel().getBlockState(event.getPos());
                boolean emptyHand = player.getMainHandItem().isEmpty();
                if (!emptyHand) {
                    return;
                }
                net.minecraft.core.BlockPos keyPos = event.getPos();
                if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                    keyPos = com.vlogf.respawnpoints.server.SpawnRegistry.headPos(
                            event.getLevel(), event.getPos());
                } else if (!(state.getBlock()
                        instanceof net.minecraft.world.level.block.RespawnAnchorBlock)) {
                    return;
                }
                var record = com.vlogf.respawnpoints.server.SpawnRegistry
                        .get(serverPlayer.server.overworld())
                        .byPos(net.minecraft.core.GlobalPos.of(event.getLevel().dimension(), keyPos));
                if (record != null && record.boundPlayers().contains(serverPlayer.getUUID())) {
                    event.setCanceled(true);
                }
            }
        });
    }
}
