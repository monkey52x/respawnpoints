package com.vlogf.respawnpoints.integration.xaero;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointWorld;
import xaero.common.minimap.waypoints.WaypointsManager;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syncs respawn options as client-local Xaero waypoints in the official
 * third-party namespace ({@code respawnpoints:point}), one collection
 * per dimension sub-container.
 *
 * <p>This is the pattern Xaero itself blesses (cf. its Waystones integration):
 * stable string ids instead of coordinate adoption, no writes into the user's
 * waypoint sets or files, per-dimension isolation by construction. Markers are
 * persisted by Xaero itself, so they survive restarts with stable identity.
 *
 * <p>Names are server-authoritative (Xaero locks name cells for third-party
 * markers); map-side color edits are sent back to the server. Positions stay
 * server-authoritative. The death screen switches viewed dimensions to their
 * default set, the only set under which Xaero renders third-party markers.
 *
 * <p>ISOLATION: xaero imports live only in this package. Called reflectively.
 */
public final class XaeroWaypointSync {
    /** Our origin inside Xaero's third-party namespace. */
    private static final ResourceLocation ORIGIN =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "point");

    /** Session tag: in-memory refs die with the Xaero session. */
    private static Object cachedSession;
    /** Key: dimension + entry id. Value: palette color last synced (detects map-side recolors). */
    private static final Map<String, WaypointColor> LAST_COLOR = new HashMap<>();

    private XaeroWaypointSync() {
    }

    public static void sync(List<RespawnEntry> entries) {
        try {
            syncInner(entries);
        } catch (Throwable t) {
            Constants.LOG.warn("Xaero waypoint sync failed, skipping", t);
        }
    }

    /** Drop in-memory baselines (e.g. on server switch): ids may repeat elsewhere. */
    public static void clearTracking() {
        LAST_COLOR.clear();
        cachedSession = null;
    }

    private static void syncInner(List<RespawnEntry> entries) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        dropStaleSessionRefs();

        Map<String, List<RespawnEntry>> byDim = new HashMap<>();
        Set<String> offerIds = new HashSet<>();
        for (RespawnEntry entry : entries) {
            if (entry.concealed()) {
                continue;
            }
            byDim.computeIfAbsent(entry.dimension(), k -> new ArrayList<>()).add(entry);
            offerIds.add(entry.id());
        }

        for (var offer : byDim.entrySet()) {
            ThirdPartyWaypoints thirdParty = thirdPartyForDimension(offer.getKey());
            if (thirdParty == null) {
                continue;
            }
            reconcileThirdParty(thirdParty, offer.getValue(), offer.getKey());
        }

        // Sweep every known sub-container: ids gone from the offer (deleted points,
        // other servers' leftovers, post-restart orphans) are removed. No dimension
        // mapping needed — entry ids are unique per server.
        sweepThirdParty(offerIds);

        try {
            if (xaero.map.mods.SupportMods.minimap()) {
                xaero.map.mods.SupportMods.xaeroMinimap.requestWaypointsRefresh();
            }
        } catch (Throwable t) {
            // Optional refresh only.
        }
    }

    /**
     * Diff our third-party collection of one dimension against the offer.
     * Identity is by stable entry id — no coordinate adoption heuristics.
     */
    private static void reconcileThirdParty(ThirdPartyWaypoints thirdParty,
                                            List<RespawnEntry> wanted, String dim) {
        Map<String, RespawnEntry> wantedById = new HashMap<>();
        for (RespawnEntry entry : wanted) {
            wantedById.put(entry.id(), entry);
        }

        for (RespawnEntry entry : wanted) {
            String key = key(dim, entry.id());
            WaypointColor wantColor = wantPalette(entry.color());
            Waypoint wp = thirdParty.get(entry.id());
            String display = com.vlogf.respawnpoints.client.EntryNames.displayName(entry);
            if (wp == null) {
                wp = new Waypoint(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(),
                        display, entry.symbol(), wantColor, WaypointPurpose.NORMAL);
                wp.setColor(wantColor.ordinal());
                // Symbol and initials are separate fields; the map renders initials.
                wp.setSymbol(entry.symbol());
                wp.setInitials(entry.symbol());
                wp.setTemporary(false);
                wp.setDisabled(false);
                thirdParty.add(entry.id(), wp);
                clearDeletedFlag(thirdParty, entry.id(), wp);
                LAST_COLOR.put(key, wantColor);
                Constants.LOG.trace("Xaero third-party waypoint '{}': name='{}' symbol='{}'",
                        key, wp.getName(), wp.getSymbol());
                continue;
            }
            // Names are server-authoritative: Xaero locks name cells for third-party
            // markers, so a differing map value can only be stale state, never an
            // edit. Overwrite silently, never send rename packets from sync
            // (renames go through the Shift+RMB screen with its own packet).
            // Colors converge on exact palette hexes (all 21 reachable, no flapping):
            // the menu is the only color writer besides us, so a differing map
            // color is the user's edit.
            clearDeletedFlag(thirdParty, entry.id(), wp);
            // Our markers are never temporary: the menu sets this flag on delete,
            // so a restored marker would keep the "temporary" badge without this.
            wp.setTemporary(false);
            if (!wp.getName().equals(display)) {
                wp.setName(display);
            }
            WaypointColor mapColor = wp.getWaypointColor();
            WaypointColor lastColor = LAST_COLOR.get(key);
            if (lastColor != null && !mapColor.equals(lastColor)) {
                if (isPointId(entry.id())) {
                    int rgb = paletteRgb(mapColor, entry.color());
                    Constants.LOG.trace("Map-side recolor '{}' -> {}, sending to server", key, mapColor);
                    Services.NETWORK.sendToServer(new RecolorPointPacket(entry.id(), rgb));
                }
                LAST_COLOR.put(key, mapColor);
            } else {
                wp.setColor(wantColor.ordinal());
                LAST_COLOR.put(key, wantColor);
            }
            wp.setSymbol(entry.symbol());
            wp.setInitials(entry.symbol());
            wp.setX(entry.pos().getX());
            wp.setY(entry.pos().getY());
            wp.setZ(entry.pos().getZ());
            // NOTE: disabled/3D flags are the user's own (Xaero menu) — never reset here.
        }

        for (String id : new ArrayList<>(thirdPartyIds(thirdParty))) {
            if (wantedById.containsKey(id)) {
                continue;
            }
            thirdParty.remove(id);
            LAST_COLOR.keySet().removeIf(k -> k.endsWith("|" + id));
            Constants.LOG.trace("Removed stale Xaero third-party waypoint '{}' in {}", id, dim);
        }
    }

    /**
     * Restore decision: markers deleted in the Xaero menu come back on next sync.
     * Clears the menu's deleted flag and drops its persisted override entry, so a
     * re-added marker actually renders instead of staying suppressed.
     */
    private static void clearDeletedFlag(ThirdPartyWaypoints thirdParty, String id, Waypoint wp) {
        try {
            wp.setThirdPartyDeleted(false);
        } catch (Throwable ignored) {
            // Best effort only.
        }
        try {
            thirdParty.getRenderInfoOverrides().remove(id);
        } catch (Throwable ignored) {
            // Map may be unmodifiable or absent; the flag above is what matters live.
        }
    }

    /** Live marker object for an entry, or null (for identity matching on click). */
    public static Waypoint findMarker(String dim, String entryId) {
        try {
            ThirdPartyWaypoints thirdParty = thirdPartyForDimension(dim);
            return thirdParty == null ? null : thirdParty.get(entryId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String> thirdPartyIds(ThirdPartyWaypoints thirdParty) {
        List<String> ids = new ArrayList<>();
        try {
            for (String id : thirdParty.getIds()) {
                ids.add(id);
            }
        } catch (Throwable t) {
            Constants.LOG.debug("Could not list third-party waypoints", t);
        }
        return ids;
    }

    /** Remove offer-foreign ids from our origin in every known sub-container. */
    private static void sweepThirdParty(Set<String> offerIds) {
        try {
            MinimapWorldRootContainer root = autoRoot();
            if (root == null) {
                return;
            }
            for (MinimapWorldContainer container : root.getSubContainers()) {
                ThirdPartyWaypointManager manager;
                try {
                    manager = container.getThirdPartyWaypointManager();
                } catch (Throwable t) {
                    continue;
                }
                if (manager == null) {
                    continue;
                }
                ThirdPartyWaypoints thirdParty;
                try {
                    thirdParty = manager.get(ORIGIN);
                } catch (Throwable t) {
                    continue;
                }
                if (thirdParty == null) {
                    continue;
                }
                for (String id : new ArrayList<>(thirdPartyIds(thirdParty))) {
                    if (!offerIds.contains(id)) {
                        thirdParty.remove(id);
                        LAST_COLOR.keySet().removeIf(k -> k.endsWith("|" + id));
                        Constants.LOG.trace("Swept foreign Xaero third-party waypoint '{}'", id);
                    }
                }
            }
        } catch (Throwable t) {
            Constants.LOG.debug("Third-party sweep failed, skipping", t);
        }
    }

    /** Our third-party collection for a dimension, creating the sub-container on demand. */
    private static ThirdPartyWaypoints thirdPartyForDimension(String dim) {
        try {
            MinimapWorldRootContainer root = autoRoot();
            if (root == null) {
                return null;
            }
            ResourceKey<Level> dimKey =
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
            String dirName = root.getSession().getDimensionHelper().getDimensionDirectoryName(dimKey);
            if (dirName == null) {
                return null;
            }
            MinimapWorldContainer container =
                    root.addSubContainer(root.getPath().resolve(dirName));
            if (container == null) {
                return null;
            }
            ThirdPartyWaypointManager manager = container.getThirdPartyWaypointManager();
            if (manager == null) {
                return null;
            }
            return manager.get(ORIGIN);
        } catch (Throwable t) {
            Constants.LOG.debug("Could not resolve third-party waypoints for {}", dim, t);
            return null;
        }
    }

    private static MinimapWorldRootContainer autoRoot() {
        try {
            MinimapSession session =
                    (MinimapSession) BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null || session.getWorldManager() == null) {
                return null;
            }
            return session.getWorldManager().getAutoRootContainer();
        } catch (Throwable t) {
            Constants.LOG.debug("Could not resolve Xaero auto root container", t);
            return null;
        }
    }

    /**
     * Resolve the waypoint world backing the given dimension id, strictly inside
     * the current container. Used by the death screen's set switching only;
     * marker storage lives in the third-party namespace above.
     */
    public static WaypointWorld worldForDimension(String dim) {
        try {
            var container = currentContainer();
            if (container == null) {
                return null;
            }
            for (WaypointWorld world : container.getAllWorlds()) {
                if (matchesDim(world, dim)) {
                    return world;
                }
            }
            Constants.LOG.trace("Xaero sync: no waypoint world for {} in current container", dim);
        } catch (Exception e) {
            Constants.LOG.warn("Could not resolve waypoint world for {}", dim, e);
        }
        return null;
    }

    private static boolean matchesDim(WaypointWorld world, String dim) {
        return world.getDimId() != null && world.getDimId().location().toString().equals(dim);
    }

    /**
     * The container Xaero itself treats as current (same one manual waypoints
     * land in). Object-based, never a string-key lookup: key formats drifted
     * before and silently resolved foreign saves' worlds.
     */
    private static xaero.common.minimap.waypoints.WaypointWorldContainer currentContainer() {
        try {
            XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
            if (session == null || session.getWaypointsManager() == null) {
                return null;
            }
            WaypointsManager manager = session.getWaypointsManager();
            WaypointWorld currentWorld = manager.getCurrentWorld();
            if (currentWorld == null || currentWorld.getContainer() == null) {
                Constants.LOG.trace("Xaero sync: no current waypoint world yet");
                return null;
            }
            var container = currentWorld.getContainer();
            return container;
        } catch (Exception e) {
            Constants.LOG.warn("Could not resolve current waypoint container", e);
            return null;
        }
    }

    private static void dropStaleSessionRefs() {
        try {
            Object session = XaeroMinimapSession.getCurrentSession();
            if (session == null) {
                return;
            }
            if (cachedSession != session) {
                cachedSession = session;
                LAST_COLOR.clear();
            }
        } catch (Throwable t) {
            // Best effort only.
        }
    }

    private static String key(String dim, String entryId) {
        return dim + "|" + entryId;
    }

    /** Player points use registry UUIDs; spawn/warps/near-death have fixed ids. */
    private static boolean isPointId(String id) {
        try {
            java.util.UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Exact palette hex -> entry, built once. Covers all 21 colors incl. dark 1-5. */
    private static final Map<Integer, WaypointColor> EXACT_HEX = buildExactHex();

    private static Map<Integer, WaypointColor> buildExactHex() {
        Map<Integer, WaypointColor> map = new HashMap<>();
        try {
            for (WaypointColor color : WaypointColor.values()) {
                try {
                    // getHex() carries alpha bits; our model is 24-bit RGB.
                    map.putIfAbsent(color.getHex() & 0xFFFFFF, color);
                } catch (Exception ignored) {
                    // Skip unreadable entries.
                }
            }
        } catch (Exception ignored) {
            // Fall back to dye matching below.
        }
        return map;
    }

    /**
     * Server RGB -> wanted palette entry. Exact palette hexes pin directly
     * (this is what makes dark colors 1-5 and recolored markers stable);
     * anything else falls back to nearest-dye matching.
     */
    private static WaypointColor wantPalette(int rgb) {
        WaypointColor exact = EXACT_HEX.get(rgb & 0xFFFFFF);
        if (exact != null) {
            return exact;
        }
        return nearestWaypointColor(rgb);
    }

    /** Palette entry -> RGB to send back: its own hex (round-trips exactly). */
    private static int paletteRgb(WaypointColor mapColor, int fallbackRgb) {
        try {
            return mapColor.getHex() & 0xFFFFFF;
        } catch (Exception ignored) {
            return fallbackRgb;
        }
    }

    /** Dye -> palette, resolved once (nearest search below only handles arbitrary RGB). */
    private static final Map<net.minecraft.world.item.DyeColor, WaypointColor> DYE_TO_PALETTE =
            buildDyeTable();

    private static Map<net.minecraft.world.item.DyeColor, WaypointColor> buildDyeTable() {
        Map<net.minecraft.world.item.DyeColor, WaypointColor> map = new HashMap<>();
        for (net.minecraft.world.item.DyeColor dye : net.minecraft.world.item.DyeColor.values()) {
            try {
                map.put(dye, WaypointColor.fromDye(dye));
            } catch (Exception ignored) {
                // Skip unmappable dyes.
            }
        }
        return map;
    }

    /** Our packet carries RGB; Xaero uses a palette — pick the nearest via dye colors. */
    private static WaypointColor nearestWaypointColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        net.minecraft.world.item.DyeColor best = net.minecraft.world.item.DyeColor.WHITE;
        long bestDist = Long.MAX_VALUE;
        for (net.minecraft.world.item.DyeColor dye : net.minecraft.world.item.DyeColor.values()) {
            int hex = dye.getTextColor();
            int dr = r - ((hex >> 16) & 0xFF);
            int dg = g - ((hex >> 8) & 0xFF);
            int db = b - (hex & 0xFF);
            long dist = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = dye;
            }
        }
        WaypointColor mapped = DYE_TO_PALETTE.get(best);
        return mapped != null ? mapped : WaypointColor.WHITE;
    }
}
