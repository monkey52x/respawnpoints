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
}
