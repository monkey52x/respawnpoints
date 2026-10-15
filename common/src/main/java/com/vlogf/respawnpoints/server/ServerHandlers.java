package com.vlogf.respawnpoints.server;

import com.vlogf.respawnpoints.network.DeletePointPacket;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.network.RenamePointPacket;
import com.vlogf.respawnpoints.network.SelectRespawnPacket;
import com.vlogf.respawnpoints.util.Limits;
import net.minecraft.server.level.ServerPlayer;

/** Server-side handling of the client's respawn packets. */
public final class ServerHandlers {
    private ServerHandlers() {
    }

    private static final java.util.Map<java.util.UUID, Long> LAST_SELECT = new java.util.concurrent.ConcurrentHashMap<>();
    // Per-action buckets (not one shared MUTATE map): a single Save click may
    // legitimately send Rename immediately followed by a sync-triggered Recolor,
    // and the second packet must not eat the other's budget.
    private static final java.util.Map<java.util.UUID, Long> LAST_RENAME = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LAST_RECOLOR = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LAST_DELETE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LAST_READY = new java.util.concurrent.ConcurrentHashMap<>();

    public static void onSelect(ServerPlayer player, SelectRespawnPacket packet) {
        if (!validId(packet.pointId()) || !rateLimit(LAST_SELECT, player.getUUID())) {
            return;
        }
        DeathManager.onSelect(player, packet.pointId());
    }

    public static void onRename(ServerPlayer player, RenamePointPacket packet) {
        if (!validId(packet.pointId()) || !rateLimit(LAST_RENAME, player.getUUID())) {
            return;
        }
        DeathManager.onRenameIfChanged(player, packet.pointId(), packet.name());
    }

    public static void onRecolor(ServerPlayer player, RecolorPointPacket packet) {
        if (!validId(packet.pointId()) || !rateLimit(LAST_RECOLOR, player.getUUID())) {
            return;
        }
        DeathManager.onRecolor(player, packet.pointId(), packet.color() & 0xFFFFFF);
    }

    public static void onDelete(ServerPlayer player, DeletePointPacket packet) {
        if (!validId(packet.pointId()) || !rateLimit(LAST_DELETE, player.getUUID())) {
            return;
        }
        DeathManager.onDelete(player, packet.pointId());
    }

    public static void onReady(ServerPlayer player) {
        if (!rateLimit(LAST_READY, player.getUUID())) {
            return;
        }
        DeathManager.onReady(player);
    }

    /** Drop rate-limit state (logout): maps must not grow on unique UUIDs. */
    public static void evict(java.util.UUID uuid) {
        LAST_SELECT.remove(uuid);
        LAST_RENAME.remove(uuid);
        LAST_RECOLOR.remove(uuid);
        LAST_DELETE.remove(uuid);
        LAST_READY.remove(uuid);
    }

    private static boolean validId(String id) {
        return id != null && !id.isEmpty() && id.length() <= Limits.MAX_POINT_ID;
    }

    private static boolean rateLimit(java.util.Map<java.util.UUID, Long> last, java.util.UUID uuid) {
        long now = System.nanoTime();
        Long prev = last.get(uuid);
        if (prev != null && now - prev < Limits.RATE_LIMIT_NS) {
            return false;
        }
        last.put(uuid, now);
        return true;
    }
}
