package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.client.screen.MapChoiceScreen;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.network.ClientReadyPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Pull-model client state. The server sends nothing marker-related until this
 * client confirms readiness (world loaded, map session up). If the player hits
 * the vanilla Respawn button before that, we request state and wait for it
 * instead of respawning vanilla.
 */
public final class ClientReadiness {
    private static final int MAX_TICKS = 200; // 10 seconds
    private static final long WAIT_TIMEOUT_NS = 5_000_000_000L;
    /** Fresh death offers survive this long on a living client before the stale-arm sweep. */
    private static final long ARM_GRACE_NS = 2_000_000_000L;
    /** READY retry while waiting on the DeathScreen (server may have missed the first). */
    private static final long READY_RETRY_NS = 2_000_000_000L;

    private static boolean helloReceived;
    private static boolean readySent;
    private static int ticks;
    private static boolean waiting;
    private static long waitStartNs;
    private static long lastReadyNs;
    /** Last known server UUID — used to detect world change and reset entry block. */
    private static java.util.UUID lastServerId;

    private ClientReadiness() {
    }

    /**
     * Called when client receives ServerHelloPacket from server.
     * Clears tracking if server changed, then sends ClientReadyPacket.
     */
    public static synchronized void onHello(UUID newServerId) {
        helloReceived = true;
        readySent = false;
        ticks = 0;
        waiting = false;

        // Detect server change and clear tracking
        if (newServerId != null && !newServerId.equals(lastServerId)) {
            lastServerId = newServerId;
            clearXaeroTracking();
            clearJourneyMapTracking();
        }

        // Immediately respond with readiness
        Services.NETWORK.sendToServer(new ClientReadyPacket());
        readySent = true;
        lastReadyNs = System.nanoTime();
    }

    public static synchronized void onDisconnect() {
        helloReceived = false;
        readySent = false;
        ticks = 0;
        waiting = false;
        lastServerId = null;
        PendingRespawn.clear();

        // Clear tracking on disconnect (return to main menu)
        clearXaeroTracking();
        clearJourneyMapTracking();
    }

    public static synchronized boolean hasHello() {
        return helloReceived;
    }

    /** Client tick: send readiness once the world (and the chosen map) is up. */
    public static void tick() {
        var minecraft = Minecraft.getInstance();
        // Block entry into singleplayer world if map not chosen (NONE).
        // DISABLED — valid choice (built-in menu), entry allowed.
        if (minecraft.level != null
                && RespawnClientConfig.activeMap() == RespawnClientConfig.ActiveMap.NONE
                && !singleplayerEntryBlocked) {
            singleplayerEntryBlocked = true;
            minecraft.disconnect();
            if (minecraft.screen == null
                    || !(minecraft.screen instanceof MapChoiceScreen)) {
                minecraft.setScreen(new MapChoiceScreen());
            }
            return;
        }
        // Stale-arm sweep: a death offer that arrived while the client still
        // looked alive (NeoForge early event) is kept for ARM_GRACE so the
        // DeathScreen can still open; only truly stale arms are dropped.
        // Disarm keeps live marker entries intact.
        if (minecraft.player != null && minecraft.player.isAlive() && PendingRespawn.hasPending()) {
            long age = System.nanoTime() - PendingRespawn.setNanos();
            if (age > ARM_GRACE_NS) {
                PendingRespawn.disarm();
            }
        }
        // While waiting on the DeathScreen, retry READY so a lost first offer
        // is resent as a cached pending instead of hanging forever.
        boolean doRetry = false;
        synchronized (ClientReadiness.class) {
            if (waiting && helloReceived && minecraft.screen instanceof net.minecraft.client.gui.screens.DeathScreen
                    && System.nanoTime() - lastReadyNs > READY_RETRY_NS) {
                lastReadyNs = System.nanoTime();
                doRetry = true;
            }
        }
        if (doRetry) {
            Services.NETWORK.sendToServer(new ClientReadyPacket());
        }
        boolean doSend = false;
        synchronized (ClientReadiness.class) {
            if (!helloReceived || readySent) {
                return;
            }
            if (minecraft.level == null) {
                // World not loaded yet: don't burn the tick budget.
                return;
            }
            if (ticks++ >= MAX_TICKS) {
                return;
            }
            readySent = true;
            lastReadyNs = System.nanoTime();
            doSend = true;
        }
        if (doSend) {
            Services.NETWORK.sendToServer(new ClientReadyPacket());
        }
    }

    /** DeathScreen opened: last chance to get ready before the player clicks.
     * Always (re)sends: the first READY may have been lost, and the server
     * answers a dead player with the cached offer. Client-side throttle:
     * at most one resend per READY_RETRY window. */
    public static void onDeathScreenOpened() {
        PendingRespawn.clearEmptyOffer();
        boolean doSend = false;
        synchronized (ClientReadiness.class) {
            if (helloReceived && (!readySent
                    || System.nanoTime() - lastReadyNs > READY_RETRY_NS)) {
                readySent = true;
                lastReadyNs = System.nanoTime();
                doSend = true;
            }
        }
        if (doSend) {
            Services.NETWORK.sendToServer(new ClientReadyPacket());
        }
    }

    /** Start waiting for the offer (vanilla packet already cancelled). */
    public static synchronized void beginWaiting() {
        if (waiting) {
            return;
        }
        waiting = true;
        waitStartNs = System.nanoTime();
        var minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.setOverlayMessage(
                    Component.translatable("respawnpoints.readiness.requesting"), false);
        }
    }

    public static synchronized boolean isWaiting() {
        return waiting;
    }

    /** True when the wait expired: the next click falls through to vanilla. */
    public static synchronized boolean consumeExpiredWait() {
        if (!waiting) {
            return false;
        }
        if (System.nanoTime() - waitStartNs < WAIT_TIMEOUT_NS) {
            return false;
        }
        waiting = false;
        Constants.LOG.warn("Respawn offer wait expired (5s), falling through to vanilla");
        var minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.setOverlayMessage(
                    Component.translatable("respawnpoints.readiness.no_answer"),
                    false);
        }
        return true;
    }

    public static synchronized void clearWaiting() {
        waiting = false;
    }

    /** Extract server identity from integrated server. */
    private static java.util.UUID getServerIdentity(net.minecraft.server.MinecraftServer server) {
        try {
            var field = net.minecraft.server.MinecraftServer.class.getDeclaredField("serverId");
            field.setAccessible(true);
            return (java.util.UUID) field.get(server);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract server identity from remote connection. */
    private static java.util.UUID getRemoteServerIdentity(Minecraft minecraft) {
        try {
            var connection = minecraft.getConnection();
            if (connection == null) return null;
            var field = connection.getClass().getDeclaredField("serverId");
            field.setAccessible(true);
            return (java.util.UUID) field.get(connection);
        } catch (Exception e) {
            return null;
        }
    }

    /** Protection against repeated singleplayer entry block trigger. */
    private static boolean singleplayerEntryBlocked;

    private static void clearXaeroTracking() {
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

    private static void clearJourneyMapTracking() {
        if (MapManagerChoice.resolve() != RespawnClientConfig.ActiveMap.JOURNEYMAP) {
            return;
        }
        if (!Services.PLATFORM.isModLoaded("journeymap")) {
            return;
        }
        MapIntegrations.clearJourneyMapTracking();
    }
}