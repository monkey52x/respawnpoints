package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.model.RespawnEntry;

import java.util.List;

/** Client-side state: the respawn options offered by the server for the current death. */
public final class PendingRespawn {
    private static List<RespawnEntry> entries = List.of();
    private static boolean hasPending = false;
    /** Death-offer generation from the server (0 = none/live). */
    private static long offerId = 0L;
    /** When the current offer was armed (nanos): guards the alive-tick disarm. */
    private static long setNanos = 0L;

    private PendingRespawn() {
    }

    public static synchronized void set(List<RespawnEntry> list, long id) {
        entries = List.copyOf(list);
        hasPending = true;
        offerId = id;
        setNanos = System.nanoTime();
    }

    /** Live marker sync while alive: refresh the list without arming the death flow. */
    public static synchronized void updateLive(List<RespawnEntry> list) {
        entries = List.copyOf(list);
    }

    public static synchronized void clear() {
        entries = List.of();
        hasPending = false;
        offerId = 0L;
        setNanos = 0L;
    }

    /** Drop the death-flow arm but keep live marker entries. */
    public static synchronized void disarm() {
        hasPending = false;
    }

    public static synchronized boolean hasPending() {
        return hasPending && !entries.isEmpty();
    }

    /** True when the last death offer arrived and was empty (server answered, nothing to choose). */
    public static synchronized boolean hasEmptyOffer() {
        return offerId != 0L && entries.isEmpty();
    }

    public static synchronized long offerId() {
        return offerId;
    }

    public static synchronized long setNanos() {
        return setNanos;
    }

    public static synchronized List<RespawnEntry> entries() {
        return entries;
    }

    /** Clear the empty-offer state so a stale one from a previous death can no longer trigger vanilla fallback. */
    public static synchronized void clearEmptyOffer() {
        if (offerId != 0L && entries.isEmpty()) {
            offerId = 0L;
        }
    }
}