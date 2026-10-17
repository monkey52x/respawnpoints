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
    /** Last death offer arrived but carried no entries: nothing to choose. */
    private static boolean emptyOffer = false;

    private PendingRespawn() {
    }

    public static synchronized void set(List<RespawnEntry> list, long id) {
        entries = List.copyOf(list);
        hasPending = true;
        offerId = id;
        setNanos = System.nanoTime();
        emptyOffer = list.isEmpty();
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
        emptyOffer = false;
    }

    /** Drop the death-flow arm but keep live marker entries. */
    public static synchronized void disarm() {
        hasPending = false;
        emptyOffer = false;
    }

    /** Clear the empty-offer flag so a stale one from a previous death
     * can no longer trigger an immediate vanilla fallback. */
    public static synchronized void clearEmptyOffer() {
        emptyOffer = false;
    }

    public static synchronized boolean hasPending() {
        return hasPending && !entries.isEmpty();
    }

    /**
     * The last death offer arrived but was empty: the server answered, there
     * is just nothing to choose. Distinct from "no offer yet" — the client
     * must fall through to vanilla instead of waiting.
     */
    public static synchronized boolean hasEmptyOffer() {
        return emptyOffer && offerId != 0L;
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
}
