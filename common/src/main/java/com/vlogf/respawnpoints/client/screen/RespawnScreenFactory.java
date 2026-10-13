package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.MapManagerChoice;
import com.vlogf.respawnpoints.client.PendingRespawn;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.model.RespawnEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Picks which respawn screen to show. The Xaero variant is loaded reflectively. */
public final class RespawnScreenFactory {
    private static final String XAERO_SCREEN = "com.vlogf.respawnpoints.integration.xaero.XaeroRespawnScreen";
    private static final String FALLBACK_SCREEN = "com.vlogf.respawnpoints.client.screen.RespawnSelectScreen";
    private static Component lastDeathMessage = Component.literal("You died!");
    private static boolean lastHardcore;

    private RespawnScreenFactory() {
    }

    public static Screen create(Component deathMessage, boolean hardcore) {
        if (hardcore) {
            return null;
        }
        lastDeathMessage = deathMessage;
        lastHardcore = false;
        return createFresh();
    }

    /** Rebuild the selection screen from the latest pending list (rename/delete refresh). */
    public static Screen createFresh() {
        List<RespawnEntry> entries = PendingRespawn.entries();
        if (entries.isEmpty() || allDepleted(entries)) {
            return null;
        }
        RespawnClientConfig.ActiveMap map = MapManagerChoice.resolve();
        if (map == RespawnClientConfig.ActiveMap.XAERO) {
            Screen xaero = tryXaeroScreen(entries, lastDeathMessage);
            if (xaero != null) {
                return xaero;
            }
        }
        return new RespawnSelectScreen(entries, lastDeathMessage);
    }

    /**
     * If a respawn screen is open, refresh it for a new list. Data-only
     * changes (charges, colors) and structural changes (add/remove/rename)
     * apply in place — no camera/zoom jump; only screen-type switches or
     * failed in-place updates rebuild, preserving the selection by id.
     */
    public static void refreshIfOpen() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current == null || !isRespawnScreen(current)) {
            return;
        }
        // Screens mid-respawn are about to close; never touch them — unless a
        // NEWER death offer arrived (second death while the old screen is still
        // open). Then unfreeze so the new choice can be sent.
        if (current instanceof RespawnListUpdater updater) {
            long seen = updater.respawnpoints$offerId();
            long freshId = PendingRespawn.offerId();
            if (freshId != 0L && seen != 0L && freshId != seen) {
                updater.respawnpoints$resetForNewOffer();
            }
            String keepId = updater.selectedEntryId();
            if (updater.updateIfDataOnly(PendingRespawn.entries())) {
                return;
            }
            if (updater.updateIfStructural(PendingRespawn.entries())) {
                return;
            }
            Screen fresh = createFresh();
            if (fresh != null) {
                minecraft.setScreen(fresh);
                if (keepId != null && fresh instanceof RespawnListUpdater freshUpdater) {
                    freshUpdater.selectEntryId(keepId);
                }
            }
            return;
        }
        Screen fresh = createFresh();
        if (fresh != null) {
            minecraft.setScreen(fresh);
        }
    }

    private static boolean isRespawnScreen(Screen screen) {
        String name = screen.getClass().getName();
        return name.equals(FALLBACK_SCREEN) || name.equals(XAERO_SCREEN);
    }

    /** Every entry is a depleted anchor: nothing choosable, skip our screens (vanilla takes over). */
    private static boolean allDepleted(List<RespawnEntry> entries) {
        for (RespawnEntry entry : entries) {
            if (!ScreenUtils.isDepleted(entry)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Back to the vanilla death screen (e.g. the last point was just deleted
     * or broken): our selection has nothing to show, the vanilla Respawn
     * button path (with the empty-offer instant passthrough) takes over.
     */
    public static void backToVanilla() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current != null && isRespawnScreen(current)) {
            minecraft.setScreen(new net.minecraft.client.gui.screens.DeathScreen(
                    lastDeathMessage, lastHardcore));
        }
    }

    private static Screen tryXaeroScreen(List<RespawnEntry> entries, Component deathMessage) {
        try {
            Class<?> clazz = Class.forName(XAERO_SCREEN);
            var ctor = clazz.getDeclaredConstructor(List.class, Component.class);
            Object screen = ctor.newInstance(entries, deathMessage);
            return (Screen) screen;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // Xaero present but its session not ready (or API drifted) -> fallback screen.
            return null;
        }
    }
}
