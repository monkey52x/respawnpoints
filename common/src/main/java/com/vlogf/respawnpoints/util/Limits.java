package com.vlogf.respawnpoints.util;

/** Shared validation limits: single source instead of scattered literals. */
public final class Limits {
    private Limits() {
    }

    public static final int MAX_POINT_ID = 128;
    public static final int MAX_NAME = 32;
    /** Min interval between two packets of the same kind from one player (anti-spam). */
    public static final long RATE_LIMIT_NS = 150_000_000L; // 150ms
}
