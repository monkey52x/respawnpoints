package com.vlogf.respawnpoints.server;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.mixin.ServerPlayerRespawnAccessor;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;
import com.vlogf.respawnpoints.network.RespawnListPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative death flow: builds the offered list, validates the
 * player's choice and performs the respawn via the vanilla path.
 */
public final class DeathManager {
    /** Default marker color for freshly bound anchors (Xaero DARK_PURPLE, index 5). */
    public static final int ANCHOR_DEFAULT_COLOR = 0xAA00AA;

    /**
     * Blind-warp flag, live only around the vanilla respawn call for a warp.
     * Read by {@code MixinWarpBlindRespawn} to skip vanilla spot validation.
     */
    private static final ThreadLocal<Boolean> BLIND_WARP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** @return true while a blind warp respawn is in flight (server thread). */
    public static boolean isBlindWarpActive() {
        return Boolean.TRUE.equals(BLIND_WARP.get());
    }

    private static void setBlindWarp(boolean active) {
        if (active) {
            BLIND_WARP.set(Boolean.TRUE);
        } else {
            BLIND_WARP.remove();
        }
    }

    private static final Map<UUID, PendingDeath> PENDING = new ConcurrentHashMap<>();
    /** Players whose client confirmed readiness (pull model): only they get pushes. */
    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();
    /** Block-capture mode: next bed/anchor click is stored, not bound. Value = deadline ms. */
    private static final Map<UUID, Long> BLOCK_ARM = new ConcurrentHashMap<>();
    /** Unblock-capture mode: next bed/anchor click removes the block. Value = deadline ms. */
    private static final Map<UUID, Long> UNBLOCK_ARM = new ConcurrentHashMap<>();
    /** Inspect-capture mode: next bed/anchor click prints a chat card. Value = deadline ms. */
    private static final Map<UUID, Long> INSPECT_ARM = new ConcurrentHashMap<>();
    private static final long ARM_TIMEOUT_MS = 30_000;

    /** Arm block-capture for the next bed/anchor click (30s). */
    public static void armBlock(ServerPlayer player) {
        UNBLOCK_ARM.remove(player.getUUID());
        INSPECT_ARM.remove(player.getUUID());
        BLOCK_ARM.put(player.getUUID(), System.currentTimeMillis() + ARM_TIMEOUT_MS);
    }

    /** Arm unblock-capture for the next bed/anchor click (30s). */
    public static void armUnblock(ServerPlayer player) {
        BLOCK_ARM.remove(player.getUUID());
        INSPECT_ARM.remove(player.getUUID());
        UNBLOCK_ARM.put(player.getUUID(), System.currentTimeMillis() + ARM_TIMEOUT_MS);
    }

    /** Arm inspect-capture for the next bed/anchor click (30s). */
    public static void armInspect(ServerPlayer player) {
        BLOCK_ARM.remove(player.getUUID());
        UNBLOCK_ARM.remove(player.getUUID());
        INSPECT_ARM.put(player.getUUID(), System.currentTimeMillis() + ARM_TIMEOUT_MS);
    }

    /** @return true if block-capture was armed and still valid (consumes it). */
    public static boolean consumeBlockArm(ServerPlayer player) {
        Long until = BLOCK_ARM.remove(player.getUUID());
        if (until == null) {
            return false;
        }
        return System.currentTimeMillis() <= until;
    }

    /** @return true if unblock-capture was armed and still valid (consumes it). */
    public static boolean consumeUnblockArm(ServerPlayer player) {
        Long until = UNBLOCK_ARM.remove(player.getUUID());
        if (until == null) {
            return false;
        }
        return System.currentTimeMillis() <= until;
    }

    /** @return true if inspect-capture was armed and still valid (consumes it). */
    public static boolean consumeInspectArm(ServerPlayer player) {
        Long until = INSPECT_ARM.remove(player.getUUID());
        if (until == null) {
            return false;
        }
        return System.currentTimeMillis() <= until;
    }

    private DeathManager() {
    }

    private record PendingDeath(List<RespawnEntry> entries, String deathDimension, BlockPos deathPos, long offerId) {
    }

    /** Death-offer generation: every new offer bumps it so the client can tell death #2 from death #1. */
    private static final java.util.concurrent.atomic.AtomicLong OFFER_SEQ = new java.util.concurrent.atomic.AtomicLong(0L);

    /** Client said it is ready (world + map session loaded): answer with full state. */
    public static void onReady(ServerPlayer player) {
        if (READY.add(player.getUUID())) {
            Constants.LOG.debug("Client ready: {}", player.getScoreboardName());
        }
        // Dead with a cached offer (relog, waiting retry): resend it as-is to
        // preserve the original death position instead of rebuilding.
        if (!player.isAlive() && resendIfDead(player)) {
            Constants.LOG.debug("Resent cached offer to {}", player.getScoreboardName());
            return;
        }
        pushUpdate(player);
    }

    /** Drop the ready flag (logout). */
    public static void clearReady(UUID uuid) {
        onLogout(uuid);
    }

    /** Full per-player cleanup on logout: ready flag, pending offer, capture arms. */
    public static void onLogout(UUID uuid) {
        READY.remove(uuid);
        PENDING.remove(uuid);
        BLOCK_ARM.remove(uuid);
        UNBLOCK_ARM.remove(uuid);
        INSPECT_ARM.remove(uuid);
        // Armed, not immediate: the logout event precedes the player's NBT
        // save, so evicting here would wipe the disk copy. The save hook
        // persists first and reaps right after, in one call stack.
        PlayerRespawnData.armEvictOnSave(uuid);
        ServerHandlers.evict(uuid);
    }

    /**
     * Clean snapshots right on login, before the client is ready. No packets
     * are sent (the client can't accept them yet) — this only drops NBT
     * entries whose record is gone (e.g. bed broken while the player was
     * offline), so the first push after {@link #onReady} is already clean.
     * It also heals the reverse desync: records still bound to the player
     * but missing a snapshot (e.g. wiped by the pre-fix logout evict) get a
     * fresh snapshot with default name/color. Intentional unbinds remove both
     * sides, so this never resurrects a removed binding.
     */
    public static void pruneOnJoin(ServerPlayer player) {
        pruneSnapshots(player);
        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        for (SpawnRegistry.SpawnPointRecord record : registry.allForPlayer(player.getUUID())) {
            if (PlayerRespawnData.point(player, record.id().toString()) == null) {
                ensureSnapshot(player, record);
            }
        }
    }

    /** Called when a non-hardcore player dies. Always builds a death offer,
     * even if the platform fires the event while the player is still alive
     * (NeoForge LivingDeathEvent vs Fabric AFTER_DEATH). */
    public static void onDeath(ServerPlayer player) {
        if (player.server.isHardcore()) {
            return;
        }
        if (!READY.contains(player.getUUID())) {
            return;
        }
        pruneSnapshots(player);
        List<SpawnRegistry.SpawnPointRecord> chargesChanged = new ArrayList<>();
        List<RespawnEntry> entries = buildEntries(player, chargesChanged);
        BlockPos deathPos = player.blockPosition();
        String deathDim = player.level().dimension().location().toString();
        long offerId = OFFER_SEQ.incrementAndGet();
        PENDING.put(player.getUUID(), new PendingDeath(entries, deathDim, deathPos, offerId));
        Services.NETWORK.sendToPlayer(player, new RespawnListPacket(entries, false, offerId));
        fanOutCharges(player, chargesChanged);
    }

    /**
     * Rebuild and push the player's list. Dead players get a selectable offer
     * (cached pending); alive players get a marker-only live sync.
     * Nothing is sent before the client confirmed readiness (pull model).
     */
    public static void pushUpdate(ServerPlayer player) {
        if (!READY.contains(player.getUUID())) {
            return;
        }
        pruneSnapshots(player);
        List<SpawnRegistry.SpawnPointRecord> chargesChanged = new ArrayList<>();
        List<RespawnEntry> entries = buildEntries(player, chargesChanged);
        if (entries.isEmpty()) {
            // Empty state must still reach the client so markers clear (alive)
            // and the death flow leaves waiting (dead, vanilla fallback).
            if (player.isAlive()) {
                Services.NETWORK.sendToPlayer(player, new RespawnListPacket(entries, true));
            } else {
                BlockPos deathPos = player.blockPosition();
                String deathDim = player.level().dimension().location().toString();
                long offerId = OFFER_SEQ.incrementAndGet();
                PENDING.put(player.getUUID(), new PendingDeath(entries, deathDim, deathPos, offerId));
                Services.NETWORK.sendToPlayer(player, new RespawnListPacket(entries, false, offerId));
            }
            fanOutCharges(player, chargesChanged);
            return;
        }
        if (player.isAlive()) {
            Services.NETWORK.sendToPlayer(player, new RespawnListPacket(entries, true));
        } else {
            BlockPos deathPos = player.blockPosition();
            String deathDim = player.level().dimension().location().toString();
            long offerId = OFFER_SEQ.incrementAndGet();
            PENDING.put(player.getUUID(), new PendingDeath(entries, deathDim, deathPos, offerId));
            Services.NETWORK.sendToPlayer(player, new RespawnListPacket(entries, false, offerId));
        }
        // Anchor charges were refreshed from live blocks as a side effect above:
        // fan the update out to the other bound players (their screens/markers
        // would otherwise stay stale until their own next push). The second pass
        // sees live == stored, so this terminates after one level.
        fanOutCharges(player, chargesChanged);
    }

    /** Push charge updates to other bound players once, skipping the dead
     * (their PENDING offer must keep the death position, not be rebuilt). */
    private static void fanOutCharges(ServerPlayer player,
                                      List<SpawnRegistry.SpawnPointRecord> chargesChanged) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        seen.add(player.getUUID());
        for (SpawnRegistry.SpawnPointRecord changed : chargesChanged) {
            for (UUID uuid : changed.boundPlayers()) {
                if (!seen.add(uuid)) {
                    continue;
                }
                ServerPlayer online = player.server.getPlayerList().getPlayer(uuid);
                if (online != null && online.isAlive()) {
                    pushUpdate(online);
                }
            }
        }
    }

    /**
     * Pull-model pruning: drop player snapshots whose record is gone or no
     * longer bound to them (breaks while offline, future admin unbinds).
     */
    private static void pruneSnapshots(ServerPlayer player) {        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        for (PlayerRespawnData.PointSnapshot snapshot : PlayerRespawnData.points(player)) {
            UUID uuid = parseUuid(snapshot.id());
            SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
            if (record == null || !record.boundPlayers().contains(player.getUUID())) {
                PlayerRespawnData.dropPoint(player, snapshot.id());
            }
        }
    }

    /** Re-send the list when a dead player (re)joins with a pending choice.
     * @return true if a cached offer was resent. */
    public static boolean resendIfDead(ServerPlayer player) {
        if (!READY.contains(player.getUUID())) {
            return false;
        }
        if (!player.isAlive()) {
            PendingDeath pending = PENDING.get(player.getUUID());
            if (pending != null) {
                Services.NETWORK.sendToPlayer(player,
                        new RespawnListPacket(pending.entries(), false, pending.offerId()));
                return true;
            }
        }
        return false;
    }

    /** Called from the SelectRespawn packet handler, always on the server thread. */
    public static void onSelect(ServerPlayer player, String pointId) {
        if (pointId == null || pointId.length() > 128) {
            return;
        }
        PendingDeath pending = PENDING.get(player.getUUID());
        if (pending == null) {
            // Stale client state (e.g. server rebooted) — rebuild the offer if still dead.
            if (!player.isAlive()) {
                refresh(player);
            }
            return;
        }
        if (player.isAlive()) {
            PENDING.remove(player.getUUID());
            return;
        }
        RespawnEntry chosen = null;
        for (RespawnEntry entry : pending.entries()) {
            if (entry.id().equals(pointId)) {
                chosen = entry;
                break;
            }
        }
        if (chosen == null) {
            Constants.LOG.warn("Player {} chose unknown respawn point '{}' (offer {})",
                    player.getScoreboardName(), pointId, pending.offerId());
            refresh(player);
            return;
        }
        if (!validate(player, chosen)) {
            Constants.LOG.info("Respawn point '{}' no longer valid for {} (offer {})",
                    pointId, player.getScoreboardName(), pending.offerId());
            refresh(player);
            return;
        }
        try {
            executeRespawn(player, chosen, pending);
        } catch (RuntimeException e) {
            Constants.LOG.warn("Respawn via '{}' failed for {}, rebuilding offer", pointId,
                    player.getScoreboardName(), e);
            refresh(player);
            return;
        }
        PENDING.remove(player.getUUID(), pending);
    }

    private static void refresh(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        pushUpdate(player);
    }

    /**
     * Block/unblock capture click (after {@code /respawnpoints bed block} or
     * {@code unblock}): stores or drops the bed/anchor in the player's
     * blocklist instead of binding. Consumes the click when armed on a
     * bed/anchor, so vanilla sleep never fires for the capture click.
     *
     * @return true if the click was consumed.
     */
    public static boolean tryCaptureBlockClick(ServerPlayer player, ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        String kind;
        BlockPos keyPos;
        if (state.getBlock() instanceof BedBlock) {
            keyPos = SpawnRegistry.headPos(level, pos);
            if (keyPos == null) {
                return false;
            }
            kind = "BED";
        } else if (state.getBlock() instanceof RespawnAnchorBlock) {
            keyPos = pos;
            kind = "ANCHOR";
        } else {
            return false;
        }
        String dim = level.dimension().location().toString();
        if (consumeBlockArm(player)) {
            if (PlayerRespawnData.addBlocked(player, dim, keyPos, kind)) {
                player.sendSystemMessage(RespawnCommands.tr("respawnpoints.cmd.blocked",
                        kind, dim, keyPos.getX(), keyPos.getY(), keyPos.getZ()));
            } else {
                player.sendSystemMessage(RespawnCommands.tr("respawnpoints.cmd.already_blocked"));
            }
            return true;
        }
        if (consumeUnblockArm(player)) {
            if (PlayerRespawnData.removeBlocked(player, dim, keyPos)) {
                player.sendSystemMessage(RespawnCommands.tr("respawnpoints.cmd.unblocked"));
            } else {
                player.sendSystemMessage(RespawnCommands.tr("respawnpoints.cmd.not_blocked"));
            }
            return true;
        }
        return false;
    }

    /**
     * Inspect-capture click (after {@code /respawnpoints debug inspect}): prints
     * a chat card for the clicked bed/anchor with action buttons. Consumes the
     * click when armed on a bed/anchor.
     *
     * @return true if the click was consumed.
     */
    public static boolean tryCaptureInspect(ServerPlayer player, ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        BlockPos keyPos;
        if (state.getBlock() instanceof BedBlock) {
            keyPos = SpawnRegistry.headPos(level, pos);
            if (keyPos == null) {
                return false;
            }
        } else if (state.getBlock() instanceof RespawnAnchorBlock) {
            keyPos = pos;
        } else {
            return false;
        }
        if (!consumeInspectArm(player)) {
            return false;
        }
        String dim = level.dimension().location().toString();
        SpawnRegistry.SpawnPointRecord record = SpawnRegistry.get(level)
                .byPos(GlobalPos.of(level.dimension(), keyPos));
        if (record == null) {
            player.sendSystemMessage(RespawnCommands.tr("respawnpoints.cmd.inspect_no_record",
                    dim, keyPos.getX(), keyPos.getY(), keyPos.getZ()));
            return true;
        }
        for (Component line : RespawnCommands.bedInfoCard(player.server, record)) {
            player.sendSystemMessage(line);
        }
        player.sendSystemMessage(RespawnCommands.inspectButtons(record.id().toString()));
        return true;
    }

    /**
     * A tracked bed/anchor block was broken: delete the registry record (unbinds
     * everyone) and push fresh state to all previously bound players online.
     */
    public static void onBlockBroken(ServerPlayer breaker, String dimension, BlockPos brokenPos) {
        onBlockRemoved(breaker.server, dimension, brokenPos);
    }

    /**
     * Same as {@link #onBlockBroken} but without a breaking player: used by
     * explosions and any other non-player removal path.
     */
    public static void onBlockRemoved(net.minecraft.server.MinecraftServer server,
                                      String dimension, BlockPos brokenPos) {
        ResourceKey<Level> brokenDim = tryDimKey(dimension);
        if (brokenDim == null) {
            return;
        }
        var level = server.getLevel(brokenDim);
        if (level == null) {
            return;
        }
        BlockPos headPos = SpawnRegistry.headPos(level, brokenPos);
        if (headPos == null) {
            // Not a bed: maybe an anchor half... anchors are single blocks.
            var state = level.getBlockState(brokenPos);
            if (!(state.getBlock() instanceof RespawnAnchorBlock)) {
                return;
            }
            headPos = brokenPos;
        }
        SpawnRegistry.SpawnPointRecord removed = SpawnRegistry.get(level)
                .remove(GlobalPos.of(brokenDim, headPos));
        if (removed == null) {
            // Untacked block, but it may sit in someone's blocklist: drop stale spots.
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                PlayerRespawnData.removeBlocked(online, dimension, headPos);
            }
            return;
        }
        Constants.LOG.info("Spawn point {} broken, unbinding {} player(s)",
                removed.id(), removed.boundPlayers().size());
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            boolean touched = false;
            if (removed.boundPlayers().contains(online.getUUID())) {
                PlayerRespawnData.dropPoint(online, removed.id().toString());
                touched = true;
            }
            if (PlayerRespawnData.removeBlocked(online, dimension, headPos)) {
                touched = true;
            }
            if (touched) {
                pushUpdate(online);
            }
        }
    }

    /** Push fresh state to every bound player currently online (no unbind). */
    public static void pushToBound(net.minecraft.server.MinecraftServer server,
                                   SpawnRegistry.SpawnPointRecord record) {
        for (UUID uuid : record.boundPlayers()) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                pushUpdate(online);
            }
        }
    }

    private static List<RespawnEntry> buildEntries(ServerPlayer player,
                                                     List<SpawnRegistry.SpawnPointRecord> chargesChanged) {
        List<RespawnEntry> entries = new ArrayList<>();
        var server = player.server;

        // Order: near-death first, spawn last. Both screens follow packet order.
        BlockPos deathPos = player.blockPosition();
        if (RespawnConfig.isShowNearDeath()) {
            entries.add(new RespawnEntry("neardeath", RespawnPointsType.NEAR_DEATH,
                    "Near death (random)", "?", 0xAAAAAA,
                    player.level().dimension().location().toString(), deathPos, true, -1));
        }

        for (PlayerRespawnData.PointSnapshot snapshot : PlayerRespawnData.points(player)) {
            SpawnRegistry.SpawnPointRecord record = SpawnRegistry.get(server.overworld())
                    .byId(parseUuid(snapshot.id()));
            if (record == null || !record.boundPlayers().contains(player.getUUID())) {
                continue;
            }
            String display = snapshot.name().isEmpty() ? snapshot.defaultName() : snapshot.name();
            String dimName = snapshot.dimension();
            if (record.isAnchor()) {
                int charges = record.charges();
                ServerLevel anchorLevel = server.getLevel(record.pos().dimension());
                if (anchorLevel != null && anchorLevel.hasChunkAt(record.pos().pos())) {
                    // Refresh stored charges from the live block (no validation here).
                    var state = anchorLevel.getBlockState(record.pos().pos());
                    int live = state.getBlock() instanceof RespawnAnchorBlock
                            ? state.getValue(RespawnAnchorBlock.CHARGE) : charges;
                    if (live != charges) {
                        SpawnRegistry.get(anchorLevel).setCharges(record.id(), live);
                        charges = live;
                        SpawnRegistry.SpawnPointRecord updated =
                                SpawnRegistry.get(anchorLevel).byId(record.id());
                        if (updated != null) {
                            chargesChanged.add(updated);
                        }
                    }
                }
                entries.add(new RespawnEntry(record.id().toString(), RespawnPointsType.ANCHOR,
                        display, "⚓", snapshot.color(), dimName, snapshot.pos(), false, charges));
            } else {
                entries.add(new RespawnEntry(record.id().toString(), RespawnPointsType.BED,
                        display, "🛏", snapshot.color(), dimName, snapshot.pos(), false, -1));
            }
        }

        WarpStorage warps = WarpStorage.get(server.overworld());
        for (WarpPoint warp : warps.all()) {
            ResourceKey<Level> warpDim = tryDimKey(warp.dimension());
            if (warpDim == null || server.getLevel(warpDim) == null) {
                continue;
            }
            entries.add(new RespawnEntry("warp:" + warp.id(), RespawnPointsType.WARP,
                    warp.name(), warp.symbol().isEmpty() ? "★" : warp.symbol(),
                    0xFFFF55, warp.dimension(), warp.pos(), false, -1));
        }

        if (RespawnConfig.isShowSpawn() && !PlayerRespawnData.isSpawnHidden(player)) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            // Custom name goes raw (may be empty): the client resolves "" to
            // the localized default, so it follows the game language.
            entries.add(new RespawnEntry("spawn", RespawnPointsType.SPAWN,
                    PlayerRespawnData.spawnName(player),
                    "⌘",
                    0xFFFF00, Level.OVERWORLD.location().toString(), spawn, false, -1));
        }

        return entries;
    }

    /** Lenient dimension parse: null on corrupt NBT instead of throwing. */
    private static ResourceKey<Level> tryDimKey(String dim) {
        if (dim == null || dim.isEmpty()) {
            return null;
        }
        try {
            ResourceLocation location = ResourceLocation.tryParse(dim);
            if (location == null) {
                return null;
            }
            return ResourceKey.create(Registries.DIMENSION, location);
        } catch (Exception e) {
            return null;
        }
    }

    /** Rename one of the player's bound points (name is player-local). */
    public static void onRename(ServerPlayer player, String pointId, String name) {
        if (pointId == null || pointId.length() > 128 || name == null) {
            return;
        }
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 32) {
            return;
        }
        UUID uuid = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
        if (record == null || !record.boundPlayers().contains(player.getUUID())
                || !isLivePoint(player, record)) {
            Constants.LOG.info("Rename '{}' rejected for {} (unknown/gone point)",
                    pointId, player.getScoreboardName());
            return;
        }
        PlayerRespawnData.setCustomName(player, record.id().toString(), clean);
        Constants.LOG.info("Player {} renamed '{}' to '{}'",
                player.getScoreboardName(), pointId, clean);
        pushUpdate(player);
    }

    /** Rename one of the player's bound points, skipping the push when unchanged. */
    public static void onRenameIfChanged(ServerPlayer player, String pointId, String name) {
        if (pointId == null || pointId.length() > 128 || name == null) {
            return;
        }
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 32) {
            return;
        }
        if (clean.equals(PlayerRespawnData.customName(player, pointId))) {
            return;
        }
        onRename(player, pointId, clean);
    }

    /** Reset a bound point's name to the default (clears the custom name). */
    public static void onRenameReset(ServerPlayer player, String pointId) {
        if (pointId == null || pointId.length() > 128) {
            return;
        }
        UUID uuid = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
        if (record == null || !record.boundPlayers().contains(player.getUUID())
                || !isLivePoint(player, record)) {
            Constants.LOG.info("Rename-reset '{}' rejected for {} (unknown/gone point)",
                    pointId, player.getScoreboardName());
            return;
        }
        PlayerRespawnData.setCustomName(player, record.id().toString(), "");
        Constants.LOG.info("Player {} reset name of '{}'", player.getScoreboardName(), pointId);
        pushUpdate(player);
    }

    /** Recolor one of the player's bound points (color is player-local). */
    public static void onRecolor(ServerPlayer player, String pointId, int color) {
        if (pointId == null || pointId.length() > 128) {
            return;
        }
        color &= 0xFFFFFF;
        UUID uuid = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
        if (record == null || !record.boundPlayers().contains(player.getUUID())
                || !isLivePoint(player, record)) {
            Constants.LOG.info("Recolor '{}' rejected for {} (unknown/gone point)",
                    pointId, player.getScoreboardName());
            return;
        }
        PlayerRespawnData.setCustomColor(player, record.id().toString(), color);
        Constants.LOG.info("Player {} recolored '{}' to #{}",
                player.getScoreboardName(), pointId, Integer.toHexString(color));
        pushUpdate(player);
    }

    /**
     * Unbind the player from one of their points. The record survives while
     * others stay bound. Spawn/warps cannot be deleted this way.
     */
    public static void onDelete(ServerPlayer player, String pointId) {
        if (pointId == null || pointId.length() > 128) {
            return;
        }
        UUID uuid = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
        if (record == null || !record.boundPlayers().contains(player.getUUID())) {
            Constants.LOG.info("Delete '{}' rejected for {} (unknown point)",
                    pointId, player.getScoreboardName());
            return;
        }
        registry.unbind(record.id(), player.getUUID());
        PlayerRespawnData.dropPoint(player, record.id().toString());
        Constants.LOG.info("Player {} unbound '{}'", player.getScoreboardName(), pointId);
        pushUpdate(player);
    }

    /**
     * Admin: unbind any (online) player from a point. Record survives while
     * others stay bound.
     *
     * @return true when a binding was removed.
     */
    public static boolean adminUnbind(ServerPlayer target, String pointId) {
        UUID uuid = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(target.server.overworld());
        SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
        if (record == null || !record.boundPlayers().contains(target.getUUID())) {
            return false;
        }
        registry.unbind(record.id(), target.getUUID());
        PlayerRespawnData.dropPoint(target, record.id().toString());
        pushUpdate(target);
        return true;
    }

    /**
     * Admin: unbind an offline player. Registry is canonical and always
     * available; the player's file is edited directly (atomic + .bak) since
     * no live state exists. Vanilla spawn tags are left alone: unbind removes
     * our mark only.
     *
     * @return true when the binding was removed.
     */
    public static boolean adminUnbindOffline(net.minecraft.server.MinecraftServer server,
                                             UUID uuid, String pointId) {
        UUID id = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(server.overworld());
        SpawnRegistry.SpawnPointRecord record = id == null ? null : registry.byId(id);
        if (record == null || !record.boundPlayers().contains(uuid)) {
            return false;
        }
        if (!OfflinePlayerData.dropPoint(server, uuid, record.id().toString())) {
            return false;
        }
        registry.unbind(record.id(), uuid);
        return true;
    }

    /**
     * Admin: bind any (online) player to an existing record (reuses
     * {@link SpawnRegistry#bind}, so the per-player limit still applies).
     *
     * @return true when the player is now bound.
     */
    public static boolean adminBind(ServerPlayer target, String pointId) {
        UUID uuid = parseUuid(pointId);
        SpawnRegistry registry = SpawnRegistry.get(target.server.overworld());
        SpawnRegistry.SpawnPointRecord record = uuid == null ? null : registry.byId(uuid);
        if (record == null) {
            return false;
        }
        SpawnRegistry.SpawnPointRecord bound = registry.bind(record.pos().dimension(),
                record.pos().pos(), record.kind(), record.color(), record.defaultName(),
                record.charges(), target.getUUID());
        ensureSnapshot(target, bound);
        pushUpdate(target);
        return true;
    }

    /**
     * Plain right-click (no shift, empty hand) on an anchor. Vanilla blocks
     * repeat clicks once the spawn is set there; we unblock them when the
     * anchor is charged: the first click still goes to vanilla (bind + its
     * message), repeat clicks are consumed here with an action-bar note and
     * re-register our point if the player had deleted it.
     *
     * @return true when the click was consumed (vanilla must do nothing).
     */
    public static boolean onAnchorTouch(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        ServerLevel level = player.server.getLevel(dim);
        if (level == null || !level.hasChunkAt(pos)) {
            return false;
        }
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RespawnAnchorBlock)
                || state.getValue(RespawnAnchorBlock.CHARGE) <= 0) {
            return false;
        }
        BlockPos vanillaPos = player.getRespawnPosition();
        ResourceKey<Level> vanillaDim = player.getRespawnDimension();
        if (vanillaPos == null || vanillaDim == null
                || !vanillaDim.equals(dim) || !vanillaPos.equals(pos)) {
            return false;
        }
        player.displayClientMessage(
                RespawnCommands.tr("respawnpoints.anchor.already_bound"), true);
        SpawnRegistry.SpawnPointRecord record = SpawnRegistry.get(level)
                .byPos(GlobalPos.of(dim, pos));
        if (record == null) {
            bindAnchor(level, dim, pos, state.getValue(RespawnAnchorBlock.CHARGE), player);
        } else if (!record.boundPlayers().contains(player.getUUID())) {
            SpawnRegistry.SpawnPointRecord bound = SpawnRegistry.get(level).bind(dim, pos,
                    "ANCHOR", record.color(), record.defaultName(),
                    state.getValue(RespawnAnchorBlock.CHARGE), player.getUUID());
            ensureSnapshot(player, bound);
        }
        pushUpdate(player);
        return true;
    }

    /** Re-create the player snapshot for a record, keeping prior custom name/color. */
    public static void ensureSnapshot(ServerPlayer player, SpawnRegistry.SpawnPointRecord record) {
        String id = record.id().toString();
        String name = "";
        int color = record.color();
        for (PlayerRespawnData.PointSnapshot existing : PlayerRespawnData.points(player)) {
            if (existing.id().equals(id)) {
                name = existing.name();
                color = existing.color();
                break;
            }
        }
        PlayerRespawnData.putSnapshot(player, new PlayerRespawnData.PointSnapshot(
                id, record.pos().dimension().location().toString(), record.pos().pos(),
                record.kind(), record.defaultName(), record.color(), name, color));
    }

    /**
     * Fresh anchor bind: single source of truth for new anchors (vanilla tracking
     * mixin and manual touch share it, so the default color can never diverge).
     */
    public static SpawnRegistry.SpawnPointRecord bindAnchor(ServerLevel level, ResourceKey<Level> dim,
                                                            BlockPos pos, int charges, ServerPlayer player) {
        SpawnRegistry.SpawnPointRecord record = SpawnRegistry.get(level).bind(dim, pos,
                "ANCHOR", ANCHOR_DEFAULT_COLOR, "Respawn Anchor", charges, player.getUUID());
        ensureSnapshot(player, record);
        return record;
    }

    private static UUID parseUuid(String pointId) {
        try {
            return UUID.fromString(pointId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** The record must still exist in the world (loaded chunk) or be assumed live (unloaded). */
    private static boolean isLivePoint(ServerPlayer player, SpawnRegistry.SpawnPointRecord record) {
        ServerLevel level = player.server.getLevel(record.pos().dimension());
        if (level == null || !level.hasChunkAt(record.pos().pos())) {
            return true;
        }
        var state = level.getBlockState(record.pos().pos());
        if (record.isAnchor()) {
            return state.getBlock() instanceof RespawnAnchorBlock;
        }
        return state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /**
     * Re-read an anchor's charge after an interaction (e.g. glowstone charging).
     * Called deferred, once vanilla applied the change.
     */
    public static void refreshAnchor(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        ServerLevel level = player.server.getLevel(dim);
        if (level == null || !level.hasChunkAt(pos)) {
            return;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof RespawnAnchorBlock)) {
            return;
        }
        SpawnRegistry.SpawnPointRecord record = SpawnRegistry.get(level)
                .byPos(GlobalPos.of(dim, pos));
        if (record == null) {
            return;
        }
        int live = level.getBlockState(pos).getValue(RespawnAnchorBlock.CHARGE);
        if (live != record.charges()) {
            SpawnRegistry.get(level).setCharges(record.id(), live);
            pushToBound(player.server, record);
        }
    }

    private static boolean validate(ServerPlayer player, RespawnEntry entry) {
        return switch (entry.type()) {
            case SPAWN -> !PlayerRespawnData.isSpawnHidden(player);
            case BED, ANCHOR -> {
                UUID uuid = parseUuid(entry.id());
                SpawnRegistry.SpawnPointRecord record = uuid == null ? null
                        : SpawnRegistry.get(player.server.overworld()).byId(uuid);
                if (record == null || !record.boundPlayers().contains(player.getUUID())) {
                    yield false;
                }
                ServerLevel level = player.server.getLevel(record.pos().dimension());
                if (level == null) {
                    yield false;
                }
                // Unloaded chunk = assumed valid (checked strictly once loaded).
                if (!level.hasChunkAt(record.pos().pos())) {
                    yield true;
                }
                var state = level.getBlockState(record.pos().pos());
                if (entry.type() == RespawnPointsType.BED) {
                    yield state.getBlock() instanceof BedBlock
                            && state.getValue(BedBlock.PART) == BedPart.HEAD
                            && !state.getValue(BedBlock.OCCUPIED);
                }
                yield state.getBlock() instanceof RespawnAnchorBlock
                        && state.getValue(RespawnAnchorBlock.CHARGE) > 0;
            }
            case WARP -> {
                String id = entry.id();
                if (!id.startsWith("warp:") || id.length() <= "warp:".length()) {
                    yield false;
                }
                WarpPoint warp = WarpStorage.get(player.server.overworld()).find(id.substring("warp:".length()));
                yield warp != null && warp.pos().equals(entry.pos())
                        && warp.dimension().equals(entry.dimension());
            }
            case NEAR_DEATH -> true;
        };
    }

    private static void executeRespawn(ServerPlayer player, RespawnEntry entry, PendingDeath pending) {
        Target target = resolveTarget(player, entry, pending);
        if (target == null) {
            Constants.LOG.warn("Could not resolve respawn target for {}", entry.id());
            refresh(player);
            return;
        }
        BlockPos oldPos = player.getRespawnPosition();
        ResourceKey<Level> oldDim = player.getRespawnDimension();
        float oldAngle = player.getRespawnAngle();
        boolean oldForced = player.isRespawnForced();

        // Forced set: skipped by our bed/anchor tracking. Beds/anchors use forced=false
        // exactly like vanilla sleep does, so anchor charges deplete and obstruction
        // rules apply. Warps/spawn/near-death use forced=true (exact spots).
        boolean exact = entry.type() != RespawnPointsType.BED && entry.type() != RespawnPointsType.ANCHOR;
        player.setRespawnPosition(target.dim(), target.pos(), 0.0F, exact, false);
        boolean blind = entry.type() == RespawnPointsType.WARP;
        if (blind) {
            setBlindWarp(true);
        }
        try {
            player.connection.handleClientCommand(
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        } finally {
            if (blind) {
                setBlindWarp(false);
            }
        }

        // Restore the player's original spawn config (accessor bypasses tracking).
        ServerPlayer current = player.server.getPlayerList().getPlayer(player.getUUID());
        if (current != null) {
            ServerPlayerRespawnAccessor access = (ServerPlayerRespawnAccessor) current;
            access.respawnpoints$setRespawnPositionDirect(oldPos);
            access.respawnpoints$setRespawnDimensionDirect(oldDim);
            access.respawnpoints$setRespawnAngleDirect(oldAngle);
            access.respawnpoints$setRespawnForcedDirect(oldForced);
        }

        if (current != null && entry.type() == RespawnPointsType.WARP) {
            // Admin warps arrive blindly on exact coordinates: vanilla safety roulette
            // does not apply (a walled-in warp is the admin's placement issue, not ours).
            ServerLevel targetLevel = player.server.getLevel(target.dim());
            ServerPlayer arrived = player.server.getPlayerList().getPlayer(player.getUUID());
            if (targetLevel != null && arrived != null) {
                arrived.teleportTo(targetLevel, target.pos().getX() + 0.5, target.pos().getY(),
                        target.pos().getZ() + 0.5, java.util.Set.of(), 0.0F, 0.0F);
                current = player.server.getPlayerList().getPlayer(player.getUUID());
                if (current != null) {
                    Constants.LOG.info("Player {} respawned at {} {} via {}",
                            player.getScoreboardName(), target.dim().location(), target.pos(), entry.id());
                }
            }
        } else if (current != null && fellBack(current, target, entry.type())) {
            Constants.LOG.warn("Vanilla respawn fell back for {} (target {} {} unavailable), player is at {} {}",
                    player.getScoreboardName(), target.dim().location(), target.pos(),
                    current.level().dimension().location(), current.blockPosition());
            player.sendSystemMessage(RespawnCommands.tr("respawnpoints.msg.fell_back"));
        } else {
            Constants.LOG.info("Player {} respawned at {} {} via {}",
                    player.getScoreboardName(), target.dim().location(), target.pos(), entry.id());
        }

        // Anchor respawns deplete a charge: refresh the registry record (chunk is loaded).
        if (entry.type() == RespawnPointsType.ANCHOR) {
            UUID uuid = parseUuid(entry.id());
            ServerLevel targetLevel = player.server.getLevel(target.dim());
            if (uuid != null && targetLevel != null && targetLevel.hasChunkAt(target.pos())) {
                var state = targetLevel.getBlockState(target.pos());
                if (state.getBlock() instanceof RespawnAnchorBlock) {
                    SpawnRegistry.get(targetLevel).setCharges(uuid,
                            state.getValue(RespawnAnchorBlock.CHARGE));
                }
            }
            SpawnRegistry.SpawnPointRecord record = uuid == null ? null
                    : SpawnRegistry.get(player.server.overworld()).byId(uuid);
            if (record != null) {
                pushToBound(player.server, record);
            } else {
                ServerPlayer after = player.server.getPlayerList().getPlayer(player.getUUID());
                if (after != null) {
                    pushUpdate(after);
                }
            }
        }
    }

    /** True when vanilla did not honor the overridden target (bed gone/obstructed).
     * Beds legitimately land on adjacent spots (8 blocks); spawn/near-death go
     * through adjustSpawnLocation which may legally shift further (32 blocks).
     * Vertical drops (pit/roof) also count as fallback. */
    private static boolean fellBack(ServerPlayer current, Target target, RespawnPointsType type) {
        if (!current.level().dimension().equals(target.dim())) {
            return true;
        }
        BlockPos actual = current.blockPosition();
        BlockPos wanted = target.pos();
        if (Math.abs(actual.getY() - wanted.getY()) > 8) {
            return true;
        }
        double dd = Math.sqrt(Math.pow(actual.getX() - wanted.getX(), 2)
                + Math.pow(actual.getZ() - wanted.getZ(), 2));
        double threshold = (type == RespawnPointsType.SPAWN || type == RespawnPointsType.NEAR_DEATH)
                ? 32.0 : 8.0;
        return dd > threshold;
    }

    private record Target(ResourceKey<Level> dim, BlockPos pos) {
    }

    private static Target resolveTarget(ServerPlayer player, RespawnEntry entry, PendingDeath pending) {
        return switch (entry.type()) {
            case SPAWN, BED, ANCHOR, WARP -> {
                ResourceKey<Level> dim = tryDimKey(entry.dimension());
                if (dim == null) {
                    yield null;
                }
                ServerLevel level = player.server.getLevel(dim);
                if (level == null) {
                    yield null;
                }
                // For beds/anchors the vanilla respawn logic finds the safe spot itself.
                if (entry.type() == RespawnPointsType.BED || entry.type() == RespawnPointsType.ANCHOR) {
                    yield new Target(dim, entry.pos());
                }
                // Warps arrive blindly on exact admin-set coordinates (see executeRespawn).
                if (entry.type() == RespawnPointsType.WARP) {
                    yield new Target(dim, entry.pos());
                }
                yield new Target(dim, player.adjustSpawnLocation(level, entry.pos()));
            }
            case NEAR_DEATH -> {
                ResourceKey<Level> dim = tryDimKey(pending.deathDimension());
                if (dim == null) {
                    yield null;
                }
                ServerLevel level = player.server.getLevel(dim);
                if (level == null) {
                    yield null;
                }
                yield new Target(dim, randomNearDeath(player, level, pending.deathPos()));
            }
        };
    }

    /**
     * Random safe spot near the death position. Volumetric search around the death
     * point first (works in caves and the Nether — no roof teleporting unless the
     * player died up there), then a heightmap column, then adjusted death pos.
     * Never leaves the death dimension.
     */
    private static BlockPos randomNearDeath(ServerPlayer player, ServerLevel level, BlockPos deathPos) {
        long t0 = System.nanoTime();
        int radius = RespawnConfig.getNearDeathRadius();
        int minDist = RespawnConfig.getNearDeathMinDistance();
        if (minDist > radius) {
            minDist = radius;
        }
        // Scale attempts with the search band instead of always paying the max:
        // a 512/256 band no longer burns 64 full-height scans.
        int band = Math.max(0, radius - minDist);
        int attempts = Math.min(64, Math.max(16, band / 8));
        int yHalf = band > 128 ? 8 : 16;
        // Nether bedrock roof: ceiling layer y=127, walkable top y=128. Whoever died
        // below the roof must never surface on it, and vice versa. Standing inside
        // the ceiling layer (dug-out hole) counts as the top side.
        boolean aboveRoof = level.dimension() == Level.NETHER && deathPos.getY() >= 127;
        boolean belowRoof = level.dimension() == Level.NETHER && !aboveRoof;
        for (int i = 0; i < attempts; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double dist = minDist + level.random.nextDouble() * Math.max(1, radius - minDist);
            int x = deathPos.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = deathPos.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, deathPos.getY(), z))) {
                continue;
            }
            int y0 = Math.max(level.getMinBuildHeight() + 1,
                    Math.min(deathPos.getY() + yHalf, level.getMaxBuildHeight() - 3));
            int y1 = Math.max(level.getMinBuildHeight() + 1, deathPos.getY() - yHalf);
            if (belowRoof) {
                y0 = Math.min(y0, 126);
            } else if (aboveRoof) {
                y1 = Math.max(y1, 128);
            }
            for (int y = y0; y >= y1; y--) {
                BlockPos candidate = new BlockPos(x, y, z);
                if (isSafe(level, candidate)) {
                    logNearDeath(radius, minDist, t0);
                    return candidate;
                }
            }
        }
        int heightAttempts = Math.min(16, attempts / 2);
        for (int i = 0; i < heightAttempts; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double dist = minDist + level.random.nextDouble() * Math.max(1, radius - minDist);
            int x = deathPos.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = deathPos.getZ() + (int) Math.round(Math.sin(angle) * dist);
            BlockPos column = new BlockPos(x, deathPos.getY(), z);
            if (!level.hasChunkAt(column)) {
                continue;
            }
            int y = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
            if (belowRoof && y >= 127) {
                continue;
            }
            if (aboveRoof && y < 128) {
                continue;
            }
            BlockPos candidate = new BlockPos(x, y, z);
            if (isSafe(level, candidate)) {
                logNearDeath(radius, minDist, t0);
                return candidate;
            }
        }
        BlockPos fallback = player.adjustSpawnLocation(level, deathPos);
        logNearDeath(radius, minDist, t0);
        return fallback;
    }

    private static void logNearDeath(int radius, int minDist, long t0) {
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        if (tookMs > 200) {
            Constants.LOG.warn("nearDeath search slow: radius={} min={} tookMs={}", radius, minDist, tookMs);
        } else {
            Constants.LOG.debug("nearDeath search: radius={} min={} tookMs={}", radius, minDist, tookMs);
        }
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        // Unloaded chunks first: never force chunk loads from the death search.
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty()
                || !level.getFluidState(pos.above()).isEmpty()
                || !level.getFluidState(pos.below()).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        var belowState = level.getBlockState(pos.below());
        if (belowState.isAir() || !belowState.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) {
            return false;
        }
        // Nether roof: standing on bedrock below y=128 means inside the ceiling rock.
        // Only the top surface (y=128+) is a valid roof spawn.
        if (level.dimension() == Level.NETHER && pos.getY() < 128
                && belowState.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
            return false;
        }
        return true;
    }
}
