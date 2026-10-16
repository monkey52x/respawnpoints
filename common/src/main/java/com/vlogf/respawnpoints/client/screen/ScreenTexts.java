package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.PendingRespawn;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;

/** Single formatting for anchor charges, shared by both selection screens. */
public final class ScreenTexts {
    private ScreenTexts() {
    }

    public static String chargesText(RespawnEntry entry) {
        if (entry.type() != RespawnPointsType.ANCHOR || entry.charges() < 0) {
            return "";
        }
        return entry.charges() == 0 ? "  §c⚓0" : "  §e⚓" + entry.charges();
    }

    /** Plain variant without color codes (Xaero sidebar draws its own colors). */
    public static String plainChargesText(RespawnEntry entry) {
        if (entry.type() != RespawnPointsType.ANCHOR || entry.charges() < 0) {
            return "";
        }
        return "  ⚓" + entry.charges();
    }

    /** Any entry with remaining seconds (> 0). Packet state only. */
    public static boolean isCooldown(RespawnEntry entry) {
        return entry.cooldownSec() > 0;
    }

    private static boolean isAnchorDepleted(RespawnEntry entry) {
        return entry.type() == RespawnPointsType.ANCHOR && entry.charges() == 0;
    }

    /**
     * Live remaining seconds, ticking on the client clock from the offer
     * arrival moment. Display only — the button stays gray until the server
     * push with cooldownSec = 0 arrives. Floors at 0:00. Depleted anchors
     * keep their own text instead.
     */
    public static int cooldownRemainingSec(RespawnEntry entry) {
        if (!isCooldown(entry) || isAnchorDepleted(entry)) {
            return 0;
        }
        long elapsedSec = (System.nanoTime() - PendingRespawn.setNanos()) / 1_000_000_000L;
        return (int) Math.max(0, (long) entry.cooldownSec() - Math.max(0, elapsedSec));
    }

    /** Cooldown timer (m:ss), ticking live. Empty when not on cooldown (depleted anchors keep theirs). */
    public static String cooldownText(RespawnEntry entry) {
        if (!isCooldown(entry) || isAnchorDepleted(entry)) {
            return "";
        }
        int total = cooldownRemainingSec(entry);
        return String.format("  §7%d:%02d", total / 60, total % 60);
    }

    /** True when any entry of the list shows a visible cooldown timer. */
    public static boolean anyCooldown(java.util.List<RespawnEntry> entries) {
        for (RespawnEntry entry : entries) {
            if (isCooldown(entry) && !isAnchorDepleted(entry)) {
                return true;
            }
        }
        return false;
    }
}
