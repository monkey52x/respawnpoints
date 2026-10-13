package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.client.screen.RespawnScreenFactory;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.mixin.DeathScreenAccessor;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.RespawnListPacket;
import com.vlogf.respawnpoints.network.ServerHelloPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;

/** Runs on the client thread; called by both loader receivers. */
public final class ClientHandlers {
    private ClientHandlers() {
    }

    private static java.util.UUID lastClearedServer;

    public static void onHello(ServerHelloPacket packet) {
        com.vlogf.respawnpoints.client.PendingRespawn.clear();
        MapManagerChoice.onHello(packet.serverId());
        ClientReadiness.onHello();
        clearXaeroTracking(packet.serverId());
    }

    /**
     * New server (possibly same address, new world): drop Xaero baselines, ids may
     * repeat. Reconnects to the SAME server keep baselines (no repeat adopt packets).
     */
    private static void clearXaeroTracking(java.util.UUID serverId) {
        if (serverId != null && serverId.equals(lastClearedServer)) {
            return;
        }
        lastClearedServer = serverId;
        if (!Services.PLATFORM.isModLoaded("xaerominimap")) {
            return;
        }
        try {
            Class<?> sync = Class.forName("com.vlogf.respawnpoints.integration.xaero.XaeroWaypointSync");
            sync.getMethod("clearTracking").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            // Best effort only.
        }
    }

    public static void onRespawnList(RespawnListPacket packet) {
        var minecraft = Minecraft.getInstance();
        // Death offers (liveSync=false) must always arm, even if the client
        // player still looks alive: on NeoForge the server fires LivingDeathEvent
        // before the client processes the death, so the guard used to eat the
        // offer and hang on "requesting points". A stale arm on a truly living
        // player is disarmed next tick by ClientReadiness.tick().
        boolean liveSync = packet.liveSync();
        if (!liveSync) {
            PendingRespawn.set(packet.entries(), packet.offerId());
        } else {
            PendingRespawn.updateLive(packet.entries());
        }
        MapIntegrations.syncClientWaypoints(packet.entries());
        if (!liveSync && packet.entries().isEmpty()) {
            // Explicit "nothing to choose": drop any waiting state at once so
            // the player falls through to vanilla instead of hanging on
            // "requesting points" until the timeout. Nothing to open/refresh.
            ClientReadiness.clearWaiting();
            var mc = Minecraft.getInstance();
            if (mc.gui != null) {
                mc.gui.setOverlayMessage(
                        net.minecraft.network.chat.Component.translatable("respawnpoints.screen.no_points"), false);
            }
            // The last point vanished by any path (delete, break, admin):
            // an open selection would go stale, back to vanilla instead.
            if (mc.player != null && !mc.player.isAlive()) {
                RespawnScreenFactory.backToVanilla();
            }
            return;
        }
        if (!liveSync) {
            // Stale rename target deleted meanwhile: drop the rename screen
            // back to the selection (or close it if nothing is left).
            closeStaleRenameScreen();
            // A fresh list while selecting (rename/delete) updates the open screen.
            RespawnScreenFactory.refreshIfOpen();
            // Offer arrived while waiting on DeathScreen: open selection at once.
            // Same when the player confirmed and we closed the screen early: a
            // fresh offer means the choice was rejected, reopen the selection.
            synchronized (ClientReadiness.class) {
                boolean waiting = ClientReadiness.isWaiting()
                        && (minecraft.screen instanceof DeathScreen
                                || minecraft.screen instanceof DeathScreen.TitleConfirmScreen);
                boolean rejected = minecraft.screen == null && minecraft.player != null
                        && !minecraft.player.isAlive();
                if (waiting || rejected) {
                    ClientReadiness.clearWaiting();
                    Screen replacement;
                    if (minecraft.screen instanceof DeathScreen) {
                        DeathScreenAccessor access = (DeathScreenAccessor) minecraft.screen;
                        replacement = RespawnScreenFactory.create(
                                access.respawnpoints$getCauseOfDeath(), access.respawnpoints$isHardcore());
                    } else {
                        replacement = RespawnScreenFactory.createFresh();
                    }
                    if (replacement != null) {
                        minecraft.setScreen(replacement);
                    }
                }
            }
        }
    }

    /** Rename screen points at a stale entry that the server just removed. */
    private static void closeStaleRenameScreen() {
        var minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (!(screen instanceof com.vlogf.respawnpoints.client.screen.RenamePointScreen rename)) {
            return;
        }
        String id = rename.entryId();
        for (RespawnEntry entry : PendingRespawn.entries()) {
            if (entry.id().equals(id)) {
                return;
            }
        }
        Screen fresh = RespawnScreenFactory.createFresh();
        // Nothing left: keep the rename open (save will reject, cancel works);
        // never leave a dead player on a null screen from here.
        if (fresh != null) {
            minecraft.setScreen(fresh);
        }
    }
}
