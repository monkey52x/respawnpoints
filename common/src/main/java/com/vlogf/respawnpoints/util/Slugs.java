package com.vlogf.respawnpoints.util;

import java.util.Locale;

/** Single place for warp slugs and case-insensitive name checks. */
public final class Slugs {
    private Slugs() {
    }

    public static String slugify(String name) {
        if (name == null) {
            return "warp";
        }
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        if (base.isEmpty()) {
            return "warp";
        }
        return base;
    }

    public static boolean equalsTrimmedIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }
}
