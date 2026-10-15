package com.vlogf.respawnpoints.client.screen;

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

    /** Near-death on cooldown: cooldownSec carries the remaining seconds (> 0). Never 0 while active. */
    public static boolean isCooldown(RespawnEntry entry) {
        return entry.type() == RespawnPointsType.NEAR_DEATH && entry.cooldownSec() > 0;
    }

    /** Cooldown timer snapshot (m:ss) taken when the offer was built. */
    public static String cooldownText(RespawnEntry entry) {
        if (!isCooldown(entry)) {
            return "";
        }
        int total = entry.cooldownSec();
        return String.format("  §7%d:%02d", total / 60, total % 60);
    }
}
