package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.network.ClientReadyPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

    private ClientReadiness() {
    }

    public static synchronized void onHello() {
        helloReceived = true;
        readySent = false;
        ticks = 0;
        waiting = false;
    }

    public static synchronized void onDisconnect() {
        helloReceived = false;
        readySent = false;
        ticks = 0;
        waiting = false;
        PendingRespawn.clear();
    }

    public static synchronized boolean hasHello() {
        return helloReceived;
    }

    /** Client tick: send readiness once the world (and the chosen map) is up. */
    public static void tick() {
        // Stale-arm sweep: a death offer that arrived while the client still
        // looked alive (NeoForge early event) is kept for ARM_GRACE so the
        // DeathScreen can still open; only truly stale arms are dropped.
        // Disarm keeps live marker entries intact.
        var early = Minecraft.getInstance();
        if (early.player != null && early.player.isAlive() && PendingRespawn.hasPending()) {
            long age = System.nanoTime() - PendingRespawn.setNanos();
            if (age > ARM_GRACE_NS) {
                PendingRespawn.disarm();
            }
        }
        // While waiting on the DeathScreen, retry READY so a lost first offer
        // is resent as a cached pending instead of hanging forever.
        boolean doRetry = false;
        synchronized (ClientReadiness.class) {
            if (waiting && helloReceived && early.screen instanceof net.minecraft.client.gui.screens.DeathScreen
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
            var minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                // World not loaded yet: don't burn the tick budget.
                return;
            }
            // If at least one map mod is installed but the player has not made
            // a choice yet, wait: do not send ClientReady with NONE before the
            // MapChoiceScreen selection is made.
            boolean anyMap = MapIntegrations.isXaeroPresent() || MapIntegrations.isJourneyMapPresent();
            if (anyMap && !RespawnClientConfig.choiceMade()) {
                return;
            }
            if (ticks++ >= MAX_TICKS) {
                return;
            }
            if (MapManagerChoice.resolve() == RespawnClientConfig.ActiveMap.XAERO
                    && !MapIntegrations.isXaeroSessionReady()) {
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
}
