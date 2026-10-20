package com.vlogf.respawnpoints.integration.xaero;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.EntryNames;
import com.vlogf.respawnpoints.client.screen.DeleteArm;
import com.vlogf.respawnpoints.client.screen.EntriesDiff;
import com.vlogf.respawnpoints.client.PendingRespawn;
import com.vlogf.respawnpoints.client.screen.ScreenTexts;
import com.vlogf.respawnpoints.client.screen.ScreenUtils;
import com.vlogf.respawnpoints.client.screen.RespawnScreenModel;
import com.vlogf.respawnpoints.client.screen.UiConstants;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.model.RespawnPointsType;
import com.vlogf.respawnpoints.network.DeletePointPacket;
import com.vlogf.respawnpoints.network.SelectRespawnPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointWorld;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;
import xaero.lib.client.gui.widget.dropdown.IDropDownContainer;
import xaero.lib.client.gui.widget.dropdown.IDropDownWidgetCallback;
import xaero.map.WorldMapSession;
import xaero.map.element.HoveredMapElementHolder;
import xaero.map.gui.GuiMap;
import xaero.map.mods.SupportMods;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Death screen with Xaero's fullscreen map and a hand-drawn side list.
 * While open, each viewed dimension switches to its default waypoint set:
 * that is the only set under which Xaero renders third-party markers, so our
 * points stay visible. Previously selected sets are restored on close.
 * A dropdown above the list switches the viewed dimension.
 * Instantiated reflectively by RespawnScreenFactory only when Xaero is present.
 */
public class XaeroRespawnScreen extends GuiMap
        implements IDropDownWidgetCallback, com.vlogf.respawnpoints.client.screen.RespawnListUpdater {
    private static final int SIDEBAR_W = UiConstants.SIDEBAR_W;
    private static final int CARD_H = UiConstants.CARD_H;
    private static final int LIST_TOP = UiConstants.LIST_TOP;

    private final List<RespawnEntry> sortedEntries = new ArrayList<>();
    private final Component deathMessage;
    private final List<String> dimOptions = new ArrayList<>();
    /** Xaero renders third-party markers only under the default set (or all-sets mode). */
    private static final String DEFAULT_SET = "gui.xaero_default";

    /** Waypoint world (per dimension) -> set name selected before we switched to default. */
    private final Map<String, String> originalSetByDim = new HashMap<>();
    private String viewedDim;
    private DropDownWidget dimDropdown;
    private int selectedIdx = -1;
    private double scrollAmount;
    private boolean respawning;
    /** Two-step delete: first click arms, second click confirms. */
    private final DeleteArm deleteArm = DeleteArm.create();
    /** Death-offer generation this screen was built for (unfreezes on a newer one). */
    private long offerId;
    /** Forced while open: the map must render the VIEWED dimension's markers. */
    private Boolean originalOnlyCurrentMapWaypoints;
    /** Cave mode per viewed dimension before we forced it off (type id). */
    private final Map<String, Integer> originalCaveByDim = new HashMap<>();
    /** Xaero cave mode types: 0 = OFF, 2 = FULL (see XaeroPlus/FAQ). */
    private static final int CAVE_OFF = 0;
    private static final int CAVE_FULL = 2;
    /** Death dimension for scope filtering (fixed for the screen's lifetime). */
    private final String deathDimContext;
    /** Auto-focus runs only on first open, never on re-init (avoids camera jumps). */
    private boolean focusedOnce;
    private long lastSwitchMillis;
    /** Last requested dimension (heavy switch in flight until the map confirms it). */
    private String switchingTo;
    /** Camera focus deferred until the map confirms the dimension. */
    private BlockPos pendingFocus;
    private int pendingFocusTicks;
    /** Throttle for tick-driven switch retries (see tick()). */
    private int switchRetryCooldown;

    public XaeroRespawnScreen(List<RespawnEntry> entries, Component deathMessage) {
        super(null, null,
                WorldMapSession.getCurrentSession().getMapProcessor(),
                Minecraft.getInstance().getCameraEntity());
        this.deathMessage = deathMessage;
        var player = Minecraft.getInstance().player;
        String deathDim = player != null ? player.level().dimension().location().toString() : null;
        this.deathDimContext = deathDim;
        BlockPos deathPos = player != null ? player.blockPosition() : BlockPos.ZERO;
        sortedEntries.addAll(RespawnScreenModel.sorted(entries, deathDim, deathPos));
        Set<String> dims = new LinkedHashSet<>();
        for (RespawnEntry entry : sortedEntries) {
            dims.add(entry.dimension());
        }
        dimOptions.addAll(dims);
        String playerDim = deathDim;
        viewedDim = playerDim != null && dims.contains(playerDim) ? playerDim : dimOptions.get(0);
        offerId = com.vlogf.respawnpoints.client.PendingRespawn.offerId();
    }

    @Override
    public long respawnpoints$offerId() {
        return offerId;
    }

    @Override
    public void respawnpoints$resetForNewOffer() {
        offerId = com.vlogf.respawnpoints.client.PendingRespawn.offerId();
        respawning = false;
        deleteArm.clear();
    }

    private static int sortKey(RespawnEntry entry, BlockPos deathPos) {
        return RespawnScreenModel.sortKey(entry, deathPos);
    }

    @Override
    public void init() {
        Constants.LOG.trace("XaeroRespawnScreen init (re-init={})", focusedOnce);
        scrollAmount = 0.0;
        forceCurrentMapWaypoints();
        super.init();
        hideXaeroButtons();
        for (GuiEventListener listener : new ArrayList<>(children())) {
            if (!(listener instanceof DropDownWidget)) {
                removeWidget(listener);
            }
        }
        buildDimDropdown();
        ensureRespawnSetCurrent();
        if (selectedIdx == -1 && !sortedEntries.isEmpty()) {
            selectedIdx = 0;
            // Default: near-death first, then a bed in the viewed dimension.
            for (int i = 0; i < sortedEntries.size(); i++) {
                if (sortedEntries.get(i).type() == RespawnPointsType.NEAR_DEATH) {
                    selectedIdx = i;
                    break;
                }
            }
            if (sortedEntries.get(selectedIdx).type() != RespawnPointsType.NEAR_DEATH) {
                for (int i = 0; i < sortedEntries.size(); i++) {
                    if (sortedEntries.get(i).type() == RespawnPointsType.BED
                            && sortedEntries.get(i).dimension().equals(viewedDim)) {
                        selectedIdx = i;
                        break;
                    }
                }
            }
            if (!sortedEntries.get(selectedIdx).dimension().equals(viewedDim)
                    && sortedEntries.get(selectedIdx).type() != RespawnPointsType.NEAR_DEATH) {
                for (int i = 0; i < sortedEntries.size(); i++) {
                    if (sortedEntries.get(i).type() == RespawnPointsType.BED) {
                        selectedIdx = i;
                        break;
                    }
                }
            }
        }
        // First open only: dimension switch, cave mode, camera focus.
        // Re-inits must NOT re-request the switch: every request triggers a
        // map world update that re-inits the screen, which re-requested the
        // switch — a self-sustaining loop that hangs the client. User-driven
        // switches go through switchViewedDimension; unapplied ones are
        // retried from tick() while pendingFocus is active.
        if (!focusedOnce) {
            focusedOnce = true;
            // Open on the death dimension; switch only on explicit user action.
            switchMapDimension(viewedDim);
            applyCaveMode(viewedDim);
            focusSelected();
        }
    }

    /** Initial camera: the selected entry itself (concealed = player position). */
    private void focusSelected() {
        if (selectedIdx >= 0 && selectedIdx < sortedEntries.size()) {
            RespawnEntry selected = sortedEntries.get(selectedIdx);
            var player = Minecraft.getInstance().player;
            if (selected.concealed() && player != null) {
                BlockPos p = player.blockPosition();
                focusMapOn(p.getX(), p.getZ());
                return;
            }
            if (selected.dimension().equals(viewedDim)) {
                focusMapOn(selected.pos().getX(), selected.pos().getZ());
                return;
            }
        }
        focusFirstOfDimension(viewedDim);
    }

    private void focusFirstOfDimension(String dim) {
        for (RespawnEntry entry : sortedEntries) {
            if (!entry.concealed() && entry.dimension().equals(dim)) {
                focusMapOn(entry.pos().getX(), entry.pos().getZ());
                return;
            }
        }
    }

    /**
     * Data-only refresh (charges/colors): swap list contents, keep camera,
     * zoom, scroll, dimension and selection. Structural changes return false
     * for a full rebuild.
     */
    @Override
    public boolean updateIfDataOnly(List<RespawnEntry> freshRaw) {
        // Respawning: this screen is about to close, absorb silently.
        if (respawning) {
            return true;
        }
        var player = Minecraft.getInstance().player;
        BlockPos deathPos = player != null ? player.blockPosition() : BlockPos.ZERO;
        List<RespawnEntry> scoped = RespawnScreenModel.sorted(freshRaw, deathDimContext, deathPos);
        if (!EntriesDiff.sameStructure(sortedEntries, scoped)) {
            return false;
        }
        String keepId = selectedEntryId();
        sortedEntries.clear();
        sortedEntries.addAll(scoped);
        int idx = EntriesDiff.indexOfId(sortedEntries, keepId);
        selectedIdx = idx >= 0 ? idx
                : Math.max(0, Math.min(selectedIdx, sortedEntries.size() - 1));
        deleteArm.clearIfGone(sortedEntries);
        return true;
    }

    @Override
    public String selectedEntryId() {
        if (selectedIdx >= 0 && selectedIdx < sortedEntries.size()) {
            return sortedEntries.get(selectedIdx).id();
        }
        return null;
    }

    /**
     * Structural in-place update (add/remove/rename/reorder) without
     * recreating the screen: the map, camera, zoom, waypoint set and cave
     * mode stay untouched. Only a vanished viewed dimension triggers a
     * single user-equivalent dimension switch.
     */
    @Override
    public boolean updateIfStructural(java.util.List<RespawnEntry> freshRaw) {
        if (respawning) {
            return true;
        }
        var player = Minecraft.getInstance().player;
        BlockPos deathPos = player != null ? player.blockPosition() : BlockPos.ZERO;
        List<RespawnEntry> scoped = RespawnScreenModel.sorted(freshRaw, deathDimContext, deathPos);

        String keepId = selectedEntryId();
        Set<String> newDims = new LinkedHashSet<>();
        for (RespawnEntry entry : scoped) {
            newDims.add(entry.dimension());
        }

        // Empty offer: show an empty list, never crash, never switch the map.
        if (scoped.isEmpty()) {
            sortedEntries.clear();
            dimOptions.clear();
            selectedIdx = -1;
            deleteArm.clear();
            pendingFocus = null;
            switchingTo = null;
            switchRetryCooldown = 0;
            pendingFocusTicks = 0;
            scrollAmount = 0.0;
            rebuildDimDropdown();
            return true;
        }

        // Cancel a switch retry aimed at a dimension that no longer exists.
        if (switchingTo != null && !newDims.contains(switchingTo)) {
            switchingTo = null;
            pendingFocus = null;
            switchRetryCooldown = 0;
            pendingFocusTicks = 0;
        }

        sortedEntries.clear();
        sortedEntries.addAll(scoped);
        boolean dimsChanged = !new LinkedHashSet<>(dimOptions).equals(newDims);
        dimOptions.clear();
        dimOptions.addAll(newDims);

        // Viewed dimension gone: fall back like the constructor does, with
        // exactly one map switch (no re-init, no camera jump otherwise).
        if (!newDims.contains(viewedDim)) {
            String fallback = deathDimContext != null && newDims.contains(deathDimContext)
                    ? deathDimContext : dimOptions.get(0);
            viewedDim = fallback;
            switchingTo = fallback;
            switchMapDimension(fallback);
            ensureRespawnSetCurrent();
            applyCaveMode(fallback);
            RespawnEntry first = firstOfDimension(fallback);
            if (first != null) {
                pendingFocus = first.pos();
                pendingFocusTicks = 100;
            } else {
                pendingFocus = null;
                pendingFocusTicks = 0;
            }
        } else if (dimsChanged) {
            ensureRespawnSetCurrent();
        }

        int idx = EntriesDiff.indexOfId(sortedEntries, keepId);
        if (idx >= 0) {
            selectedIdx = idx;
        } else if (!sortedEntries.isEmpty()) {
            selectedIdx = Math.max(0, Math.min(selectedIdx, sortedEntries.size() - 1));
        } else {
            selectedIdx = -1;
        }
        deleteArm.clearIfGone(sortedEntries);
        int maxScroll = Math.max(0, sortedEntries.size() * CARD_H - (height - 80));
        scrollAmount = Math.max(0.0, Math.min(scrollAmount, maxScroll));
        if (dimsChanged) {
            rebuildDimDropdown();
        } else {
            syncDropdownSelection();
        }
        return true;
    }

    /** Drop the stale dropdown widget so new dimensions appear. Safe outside render. */
    private void rebuildDimDropdown() {
        if (dimDropdown != null) {
            try {
                removeWidget(dimDropdown);
            } catch (Exception e) {
                Constants.LOG.warn("Could not remove dimension dropdown", e);
            }
            dimDropdown = null;
        }
        buildDimDropdown();
    }

    @Override
    public void selectEntryId(String id) {
        int idx = EntriesDiff.indexOfId(sortedEntries, id);
        if (idx >= 0) {
            selectedIdx = idx;
        }
    }

    private void buildDimDropdown() {
        try {
            Component[] options = new Component[dimOptions.size()];
            for (int i = 0; i < dimOptions.size(); i++) {
                options[i] = Component.literal(ScreenUtils.shortDimName(dimOptions.get(i)));
            }
            if (dimDropdown != null) {
                // Re-add after resize (vanilla clears widgets) and refresh position.
                dimDropdown.setX(8);
                dimDropdown.setY(8);
                dimDropdown.setWidth(SIDEBAR_W - 16);
                addRenderableWidget(dimDropdown);
                return;
            }
            var builder = DropDownWidget.Builder.begin()
                    .setOptions(options)
                    .setX(8)
                    .setY(8)
                    .setW(SIDEBAR_W - 16)
                    .setSelected(Math.max(0, dimOptions.indexOf(viewedDim)))
                    .setNarrationTitle(Component.literal("Dimension"))
                    .setCallback(this);
            if (this instanceof IDropDownContainer container) {
                builder.setContainer(container);
            }
            dimDropdown = builder.build();
            addRenderableWidget(dimDropdown);
        } catch (Throwable t) {
            Constants.LOG.warn("Could not build dimension dropdown", t);
            dimDropdown = null;
        }
    }
    @Override
    public boolean onSelected(DropDownWidget widget, int id) {
        if (widget == dimDropdown && id >= 0 && id < dimOptions.size()) {
            switchViewedDimension(dimOptions.get(id));
            return true;
        }
        return false;
    }

    private void switchViewedDimension(String dim) {
        switchViewedDimension(dim, null);
    }

    /**
     * @param focus explicit camera target once the map confirms the dimension;
     *              null = first visible entry of the dimension (dropdown path).
     *              Concealed entries resolve to the player's position by callers.
     */
    private void switchViewedDimension(String dim, BlockPos focus) {
        if (dim.equals(viewedDim)) {
            return;
        }
        // Storm guard: drop accidental double switches, but let a switch through
        // once the previous one visibly applied (map confirms the dimension).
        long now = System.currentTimeMillis();
        if (now - lastSwitchMillis < 500 && !switchApplied(switchingTo)) {
            syncDropdownSelection();
            return;
        }
        lastSwitchMillis = now;
        switchingTo = dim;
        viewedDim = dim;
        switchMapDimension(dim);
        ensureRespawnSetCurrent();
        applyCaveMode(viewedDim);
        // Keep the previous selection; focus follows once the map confirms the dim.
        RespawnEntry first = firstOfDimension(dim);
        BlockPos target = focus != null ? focus : (first != null ? first.pos() : null);
        if (target != null) {
            pendingFocus = target;
            pendingFocusTicks = 100;
        }
    }

    /** True when the map already renders the requested dimension (or none requested). */
    private boolean switchApplied(String dim) {
        if (dim == null) {
            return true;
        }
        try {
            if (getMapProcessor() == null || getMapProcessor().getMapWorld() == null) {
                return false;
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
            return key.equals(getMapProcessor().getMapWorld().getCurrentDimensionId());
        } catch (Exception e) {
            return false;
        }
    }

    private RespawnEntry firstOfDimension(String dim) {
        for (RespawnEntry entry : sortedEntries) {
            if (!entry.concealed() && entry.dimension().equals(dim)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Mirror of Xaero's own dimension toggle (MapWorld.toggleDimension):
     * custom dimension id + world update. No field poking — GuiMap manages
     * its own init dimensions and fights external writes.
     */
    private void switchMapDimension(String dim) {
        long t0 = System.nanoTime();
        try {
            if (getMapProcessor() == null || getMapProcessor().getMapWorld() == null) {
                return;
            }
            var mapWorld = getMapProcessor().getMapWorld();
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
            Constants.LOG.trace("Map dim switch: current={} usingCustom={} -> {}",
                    mapWorld.getCurrentDimensionId(), mapWorld.isUsingCustomDimension(), key.location());
            if (key.equals(mapWorld.getCurrentDimensionId())) {
                return;
            }
            mapWorld.setCustomDimensionId(key);
            getMapProcessor().checkForWorldUpdate();
            Constants.LOG.trace("Map dim switched: current={} usingCustom={}",
                    mapWorld.getCurrentDimensionId(), mapWorld.isUsingCustomDimension());
        } catch (Exception e) {
            Constants.LOG.warn("Could not switch Xaero map dimension", e);
        } finally {
            if (Constants.LOG.isTraceEnabled()) {
                Constants.LOG.trace("Cave timing: switchMapDimension({}) took {}ms",
                        dim, (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    /**
     * While selecting, the map must render the VIEWED dimension's markers.
     * Without this, Xaero falls back to the player's dimension markers always
     * (see SupportXaeroMinimap.checkWaypoints). Restored on close.
     */
    private void forceCurrentMapWaypoints() {
        if (originalOnlyCurrentMapWaypoints != null) {
            return;
        }
        try {
            var config = xaero.map.WorldMap.INSTANCE.getConfigs().getClientConfigManager()
                    .getPrimaryConfigManager().getConfig();
            if (config != null) {
                originalOnlyCurrentMapWaypoints = config.get(
                        xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions.ONLY_CURRENT_MAP_WAYPOINTS);
                config.set(
                        xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions.ONLY_CURRENT_MAP_WAYPOINTS,
                        true);
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not force Xaero waypoint visibility", e);
        }
    }

    private void restoreMapWaypointVisibility() {        try {
            if (originalOnlyCurrentMapWaypoints != null) {
                var config = xaero.map.WorldMap.INSTANCE.getConfigs().getClientConfigManager()
                        .getPrimaryConfigManager().getConfig();
                if (config != null) {
                    config.set(
                            xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions.ONLY_CURRENT_MAP_WAYPOINTS,
                            originalOnlyCurrentMapWaypoints);
                }
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not restore Xaero waypoint visibility", e);
        } finally {
            originalOnlyCurrentMapWaypoints = null;
        }
    }

    /**
     * Smart cave mode: the Nether renders FULL (otherwise the bedrock roof
     * hides every tunnel, anchor and marker), everything else renders OFF
     * (surface map; markers are a layer-independent overlay, so houses,
     * basements and caves stay legible without a cut slice). The previous
     * per-dimension type is restored on close. Toggle semantics vary by Xaero
     * version, so cycle boundedly until the wanted type is reached.
     */
    private void applyCaveMode(String dim) {
        long t0 = System.nanoTime();
        try {
            if (getMapProcessor() == null || getMapProcessor().getMapWorld() == null) {
                return;
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
            var mapDim = getMapProcessor().getMapWorld().getDimension(key);
            if (mapDim == null) {
                return;
            }
            int target = targetCaveType(dim);
            if (mapDim.getCaveModeType() == target) {
                return;
            }
            if (target == CAVE_FULL && caveMapsBanned()) {
                Constants.LOG.debug("Fair-play server bans cave maps, leaving cave mode as-is for {}", dim);
                return;
            }
            originalCaveByDim.putIfAbsent(dim, mapDim.getCaveModeType());
            for (int i = 0; i < 6 && mapDim.getCaveModeType() != target; i++) {
                mapDim.toggleCaveModeType(target > mapDim.getCaveModeType());
            }
            if (mapDim.getCaveModeType() != target) {
                Constants.LOG.debug("Could not force cave mode to {} for {}", target, dim);
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not force Xaero cave mode", e);
        } finally {
            if (Constants.LOG.isTraceEnabled()) {
                Constants.LOG.trace("Cave timing: applyCaveMode({}) took {}ms",
                        dim, (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    /** Nether = FULL (roof hides everything otherwise); all else = OFF. */
    private static int targetCaveType(String dim) {
        return "minecraft:the_nether".equals(dim) ? CAVE_FULL : CAVE_OFF;
    }

    /**
     * Fair-play servers can ban cave maps via effect. Fail-closed: when the
     * holders are unavailable we assume the ban and never force FULL.
     */
    private static boolean caveMapsBanned() {
        try {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                return true;
            }
            var noCave = xaero.map.effects.Effects.NO_CAVE_MAPS;
            var noCaveHarmful = xaero.map.effects.Effects.NO_CAVE_MAPS_HARMFUL;
            if (noCave == null && noCaveHarmful == null) {
                return true;
            }
            return (noCave != null && player.hasEffect(noCave))
                    || (noCaveHarmful != null && player.hasEffect(noCaveHarmful));
        } catch (Throwable t) {
            Constants.LOG.debug("Could not check cave-map ban, assuming banned", t);
            return true;
        }
    }

    private void restoreCaveModes() {
        try {
            if (getMapProcessor() == null || getMapProcessor().getMapWorld() == null) {
                return;
            }
            var mapWorld = getMapProcessor().getMapWorld();
            for (var saved : originalCaveByDim.entrySet()) {
                try {
                    ResourceKey<Level> key = ResourceKey.create(
                            Registries.DIMENSION, ResourceLocation.parse(saved.getKey()));
                    var mapDim = mapWorld.getDimension(key);
                    if (mapDim == null) {
                        continue;
                    }
                    for (int i = 0; i < 6 && mapDim.getCaveModeType() != saved.getValue(); i++) {
                        mapDim.toggleCaveModeType(saved.getValue() > mapDim.getCaveModeType());
                    }
                } catch (Exception e) {
                    Constants.LOG.warn("Could not restore Xaero cave mode for {}", saved.getKey(), e);
                }
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not restore Xaero cave modes", e);
        } finally {
            originalCaveByDim.clear();
        }
    }

    private void hideXaeroButtons() {
        for (String fieldName : new String[]{
                "settingsButton", "caveModeButton", "dimensionToggleButton", "keybindingsButton",
                "waypointsButton", "playersButton", "claimsButton", "exportButton",
                "radarButton", "zoomInButton", "zoomOutButton"}) {
            try {
                Field field = GuiMap.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object btn = field.get(this);
                if (btn instanceof AbstractWidget widget) {
                    widget.visible = false;
                    widget.active = false;
                }
            } catch (Exception e) {
                Constants.LOG.warn("Could not hide Xaero button '{}'", fieldName, e);
            }
        }
    }

    /**
     * While selecting, the viewed dimension renders the default set: the only set
     * under which Xaero draws third-party markers (besides all-sets mode).
     * The previously selected set is remembered per dimension and restored
     * on close — the user's setup is never permanently changed.
     */
    private void ensureRespawnSetCurrent() {
        WaypointWorld world = XaeroWaypointSync.worldForDimension(viewedDim);
        if (world == null) {
            return;
        }
        if (!world.getSets().containsKey(DEFAULT_SET)) {
            Constants.LOG.debug("No default waypoint set for {}, leaving current", viewedDim);
            return;
        }
        originalSetByDim.putIfAbsent(viewedDim, world.getCurrent());
        if (!DEFAULT_SET.equals(world.getCurrent())) {
            world.setCurrent(DEFAULT_SET);
            try {
                if (SupportMods.minimap()) {
                    SupportMods.xaeroMinimap.requestWaypointsRefresh();
                }
            } catch (Throwable t) {
                // Optional refresh only.
            }
            Constants.LOG.trace("Waypoint set for {}: switched to '{}'",
                    viewedDim, DEFAULT_SET);
        }
    }

    private void restoreRespawnSetCurrents() {
        try {
            for (var saved : originalSetByDim.entrySet()) {
                WaypointWorld world = XaeroWaypointSync.worldForDimension(saved.getKey());
                if (world == null) {
                    continue;
                }
                if (DEFAULT_SET.equals(world.getCurrent())
                        && saved.getValue() != null
                        && world.getSets().containsKey(saved.getValue())) {
                    world.setCurrent(saved.getValue());
                }
            }
            try {
                if (SupportMods.minimap()) {
                    SupportMods.xaeroMinimap.requestWaypointsRefresh();
                }
            } catch (Throwable t) {
                // Optional refresh only.
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not restore Xaero waypoint sets", e);
        } finally {
            originalSetByDim.clear();
        }
        restoreCaveModes();
        restoreMapWaypointVisibility();
    }

    private void focusMapOn(int targetX, int targetZ) {
        try {
            Field destField = GuiMap.class.getDeclaredField("cameraDestination");
            destField.setAccessible(true);
            destField.set(this, new int[]{targetX, targetZ});
            Field animXField = GuiMap.class.getDeclaredField("cameraDestinationAnimX");
            animXField.setAccessible(true);
            animXField.set(this, null);
            Field animZField = GuiMap.class.getDeclaredField("cameraDestinationAnimZ");
            animZField.setAccessible(true);
            animZField.set(this, null);
        } catch (Exception e) {
            Constants.LOG.warn("Could not focus Xaero map", e);
        }
    }

    @Override
    public void tick() {
        super.tick();
        var player = Minecraft.getInstance().player;
        if (player != null && player.isAlive()) {
            PendingRespawn.clear();
            restoreRespawnSetCurrents();
            Minecraft.getInstance().setScreen(null);
            return;
        }
        // Set enforcement only matters while a dimension switch is in flight;
        // polling the container worlds 20x/sec is pure waste otherwise.
        // switchingTo clears once the map confirms it (never null-checked
        // elsewhere as "in flight", so clearing here is safe).
        if (switchingTo != null) {
            if (switchApplied(switchingTo)) {
                switchingTo = null;
            } else {
                ensureRespawnSetCurrent();
            }
        }
        if (pendingFocus != null) {
            if (switchApplied(viewedDim)) {
                focusMapOn(pendingFocus.getX(), pendingFocus.getZ());
                pendingFocus = null;
                switchRetryCooldown = 0;
            } else {
                // The map can revert an unapplied switch (it tracks the corpse
                // dimension): re-request throttled, never from init() (that
                // re-request loop hung the client). Retry lives in tick only.
                if (--switchRetryCooldown <= 0) {
                    switchRetryCooldown = 10;
                    switchMapDimension(viewedDim);
                    applyCaveMode(viewedDim);
                }
                if (--pendingFocusTicks <= 0) {
                    pendingFocus = null;
                    switchRetryCooldown = 0;
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (respawning && com.vlogf.respawnpoints.client.RespawnClientConfig.loadingOverlay()) {
            graphics.fill(0, 0, width, height, 0xFF000000);
            graphics.drawCenteredString(font,
                    Component.translatable("respawnpoints.screen.respawning"),
                    width / 2, height / 2, 0xFFFFFF);
            return;
        }
        if (isWorldStateUnusable()) {
            graphics.fill(0, 0, width, height, 0xFF000000);
            graphics.drawCenteredString(font,
                    Component.translatable("respawnpoints.screen.loading"),
                    width / 2, height / 2, 0xFFFFFF);
            return;
        }
        // Remove widgets Xaero adds dynamically, but keep our dropdown.
        // (Wiping unconditionally every frame causes GuiMap to rebuild constantly.)
        boolean hasForeign = false;
        for (GuiEventListener listener : children()) {
            if (!(listener instanceof DropDownWidget)) {
                hasForeign = true;
                break;
            }
        }
        if (hasForeign) {
            for (GuiEventListener listener : new ArrayList<>(children())) {
                if (!(listener instanceof DropDownWidget)) {
                    removeWidget(listener);
                }
            }
        }
        // Steady selection highlight: re-asserted every frame before the map
        // renders (see RespawnSelector). Xaero's renderer overwrites destAlpha
        // from hover state each frame, so a one-shot highlight fades in 0.8s.
        // Gated by client config: OFF means no flashes and zero marker scans.
        if (com.vlogf.respawnpoints.client.RespawnClientConfig.showSelectedName()) {
            highlightSelectedWaypoint();
        }
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * Xaero draws the player arrow inside {@code GuiMap.render} above normal
     * 2D draws. This hook runs after that (but before the dimension dropdown),
     * so the sidebar lands strictly above the arrow: arrow &lt; sidebar &lt; dropdown.
     */
    @Override
    protected void renderPreDropdown(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderPreDropdown(graphics, mouseX, mouseY, partialTicks);
        if ((respawning && com.vlogf.respawnpoints.client.RespawnClientConfig.loadingOverlay())
                || isWorldStateUnusable()) {
            return;
        }
        graphics.fill(0, 0, SIDEBAR_W, height, 0xD8000000);
        graphics.fill(SIDEBAR_W - 1, 0, SIDEBAR_W, height, 0x20FFFFFF);
        graphics.enableScissor(0, LIST_TOP, SIDEBAR_W, height - 40);
        for (int i = 0; i < sortedEntries.size(); i++) {
            RespawnEntry entry = sortedEntries.get(i);
            int itemX = 8;
            int itemY = LIST_TOP + 4 + i * CARD_H - (int) scrollAmount;
            int itemW = SIDEBAR_W - 24;
            int itemH = CARD_H - 8;
            if (itemY + itemH < LIST_TOP || itemY > height - 40) {
                continue;
            }
            boolean isSelected = selectedIdx == i;
            boolean isHovered = mouseX >= itemX && mouseX <= itemX + itemW
                    && mouseY >= itemY && mouseY <= itemY + itemH;
            int bg = isSelected ? 0x30FFFFFF : (isHovered ? 0x28FFFFFF : 0x12FFFFFF);
            int border = isSelected ? 0xA0B0B0B0 : (isHovered ? 0x50FFFFFF : 0x22FFFFFF);
            graphics.fill(itemX, itemY, itemX + itemW, itemY + itemH, bg);
            graphics.fill(itemX, itemY, itemX + itemW, itemY + 1, border);
            graphics.fill(itemX, itemY + itemH - 1, itemX + itemW, itemY + itemH, border);
            graphics.fill(itemX, itemY, itemX + 1, itemY + itemH, border);
            graphics.fill(itemX + itemW - 1, itemY, itemX + itemW, itemY + itemH, border);
            if (isSelected) {
                graphics.fill(itemX, itemY, itemX + 4, itemY + itemH, 0xFFFFFFFF);
            }
            String nameToDraw = entry.symbol() + " " + EntryNames.displayName(entry);
            if (nameToDraw.length() > 22) {
                nameToDraw = nameToDraw.substring(0, 20) + "...";
            }
            int nameColor = ScreenUtils.isDepleted(entry) ? 0x777777 : 0xFFFFFF;
            graphics.drawString(font, nameToDraw, itemX + 10, itemY + 6, nameColor);
            String subLine = ScreenUtils.distanceText(entry) + ScreenTexts.plainChargesText(entry)
                    + ScreenTexts.cooldownText(entry);
            graphics.drawString(font, subLine, itemX + 10, itemY + 19,
                    ScreenUtils.isDepleted(entry) ? 0xFF5555 : 0x9FC5FF);
            if (ScreenUtils.isManageable(entry)) {
                boolean armed = entry.id().equals(deleteArm.armedId());
                graphics.drawString(font, armed ? "?" : "✕", itemX + itemW - 16, itemY + 8,
                        armed ? 0xFFFF55 : 0xFF6666);
            }
        }
        graphics.disableScissor();

        int totalHeight = sortedEntries.size() * CARD_H;
        int visibleHeight = height - 70;
        if (totalHeight > visibleHeight) {
            int scrollbarX = SIDEBAR_W - 6;
            int scrollbarH = Math.max(12, visibleHeight * visibleHeight / totalHeight);
            int scrollbarY = LIST_TOP + 4 + (int) (scrollAmount * (visibleHeight - 4) / (double) totalHeight);
            graphics.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarH, 0x60FFFFFF);
        }

        int btnW = SIDEBAR_W - 16;
        int btnH = 22;
        int btnX = 8;
        int btnY = height - 30;
        RespawnEntry current = selectedIdx >= 0 && selectedIdx < sortedEntries.size()
                ? sortedEntries.get(selectedIdx) : null;
        boolean depleted = current != null && ScreenUtils.isDepleted(current);
        boolean onCooldown = current != null && ScreenTexts.isCooldown(current);
        boolean anchorDepleted = current != null
                && current.type() == RespawnPointsType.ANCHOR && current.charges() == 0;
        boolean frozen = respawning && !com.vlogf.respawnpoints.client.RespawnClientConfig.loadingOverlay();
        boolean enabled = current != null && !depleted && !respawning;
        boolean hovered = enabled && mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + btnH;
        drawPanelButton(graphics, btnX, btnY, btnW, btnH,
                anchorDepleted ? Component.translatable("respawnpoints.screen.anchor_depleted")
                        : (onCooldown ? Component.translatable("respawnpoints.screen.on_cooldown")
                                : ((enabled || frozen) ? Component.translatable("respawnpoints.screen.confirm")
                                        : Component.translatable("respawnpoints.screen.select_point"))),
                enabled, hovered, frozen);
    }

    private void drawPanelButton(GuiGraphics graphics, int x, int y, int w, int h,
                                 Component text, boolean enabled, boolean hovered, boolean frozen) {
        int border = 0xFF000000;
        // Frozen (choice sent, waiting on vanilla): dark green, clearly distinct
        // from both the active green and the disabled gray.
        int bg = frozen ? 0xFF23402A
                : (enabled ? (hovered ? 0xFF3E7A34 : 0xFF2F6128) : 0xFF4B4B4B);
        int highlight = frozen ? 0xFF2E5237
                : (enabled ? 0xFF55A047 : 0xFF4B4B4B);
        int shadow = frozen ? 0xFF14261A
                : (enabled ? 0xFF1E4A1A : 0xFF282828);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        graphics.fill(x, y, x + w, y + 1, border);
        graphics.fill(x, y + h - 1, x + w, y + h, border);
        graphics.fill(x, y, x + 1, y + h, border);
        graphics.fill(x + w - 1, y, x + w, y + h, border);
        if (enabled) {
            graphics.fill(x + 1, y + 1, x + w - 1, y + 2, highlight);
            graphics.fill(x + 1, y + 1, x + 2, y + h - 1, highlight);
            graphics.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, shadow);
            graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, shadow);
        }
        int textColor = frozen ? 0xE0E0E0 : (enabled ? (hovered ? 0xFFFFFF : 0xE0E0E0) : 0xA0A0A0);
        int textW = font.width(text);
        graphics.drawString(font, text, x + (w - textW) / 2, y + (h - 8) / 2, textColor);
    }

    private void highlightSelectedWaypoint() {
        long t0 = System.nanoTime();
        try {
            if (selectedIdx < 0 || selectedIdx >= sortedEntries.size()) {
                return;
            }
            RespawnEntry selected = sortedEntries.get(selectedIdx);
            if (SupportMods.xaeroMinimap == null) {
                return;
            }
            var mapWaypoints = SupportMods.xaeroMinimap.getWaypoints();
            if (mapWaypoints == null) {
                return;
            }
            for (Object obj : mapWaypoints) {
                if (!(obj instanceof xaero.map.mods.gui.Waypoint mapW)) {
                    continue;
                }
                Object orig = mapW.getOriginal();
                if (!(orig instanceof Waypoint minimapW)) {
                    continue;
                }
                if (minimapW.getX() == selected.pos().getX()
                        && minimapW.getY() == selected.pos().getY()
                        && minimapW.getZ() == selected.pos().getZ()) {
                    mapW.setDestAlpha(255.0f);
                    mapW.setAlpha(255.0f);
                }
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not highlight selected waypoint", e);
        } finally {
            if (Constants.LOG.isTraceEnabled()) {
                Constants.LOG.trace("Cave timing: highlightSelectedWaypoint took {}ms",
                        (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (respawning) {
            return true;
        }
        if (mouseX <= SIDEBAR_W) {
            int maxScroll = Math.max(0, sortedEntries.size() * CARD_H - (height - 80));
            scrollAmount = Math.max(0.0, Math.min(maxScroll, scrollAmount - scrollY * 15.0));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Frozen after Confirm: swallow everything (button, cards, dropdown,
        // camera) until vanilla takes over or a fresh offer unfreezes.
        if (respawning) {
            clickSound();
            return true;
        }
        for (GuiEventListener listener : children()) {
            if (listener.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        int btnW = SIDEBAR_W - 16;
        int btnH = 22;
        int btnX = 8;
        int btnY = height - 30;
        if (button == 0 && selectedIdx != -1
                && mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + btnH) {
            RespawnEntry chosen = sortedEntries.get(selectedIdx);
            if (ScreenUtils.isDepleted(chosen)) {
                clickSound();
                return true;
            }
            Services.NETWORK.sendToServer(new SelectRespawnPacket(chosen.id()));
            clickSound();
            respawning = true;
            // The screen stays open either way (black overlay in overlay mode,
            // frozen selection otherwise) until vanilla takes over on respawn.
            return true;
        }
        if (mouseX <= SIDEBAR_W) {
            if (button == 0 && mouseY >= LIST_TOP && mouseY <= height - 40) {
                for (int i = 0; i < sortedEntries.size(); i++) {
                    RespawnEntry entry = sortedEntries.get(i);
                    int itemX = 8;
                    int itemY = LIST_TOP + 4 + i * CARD_H - (int) scrollAmount;
                    int itemW = SIDEBAR_W - 24;
                    int itemH = CARD_H - 8;
                    if (itemY + itemH < LIST_TOP || itemY > height - 40) {
                        continue;
                    }
                    if (!(mouseX >= itemX) || !(mouseX <= itemX + itemW)
                            || !(mouseY >= itemY) || !(mouseY <= itemY + itemH)) {
                        continue;
                    }
                    if (ScreenUtils.isManageable(entry)
                            && mouseX >= itemX + itemW - 18 && mouseX <= itemX + itemW - 1) {
                        if (deleteArm.click(entry.id())) {
                            Services.NETWORK.sendToServer(new DeletePointPacket(entry.id()));
                            if (sortedEntries.size() <= 1) {
                                // That was the last point: back to vanilla at
                                // once instead of a stale card (server confirms).
                                com.vlogf.respawnpoints.client.screen.RespawnScreenFactory.backToVanilla();
                                return true;
                            }
                        } else {
                            // Armed - will show "?" on next render
                        }
                        clickSound();
                        return true;
                    }
                    deleteArm.onSelectedOther(entry.id());
                    selectedIdx = i;
                    // Camera target is the clicked entry itself; concealed entries
                    // (near-death) resolve to the player's position.
                    BlockPos focus = entry.concealed() && Minecraft.getInstance().player != null
                            ? Minecraft.getInstance().player.blockPosition()
                            : entry.pos();
                    if (!entry.dimension().equals(viewedDim)) {
                        switchViewedDimension(entry.dimension(), focus);
                        syncDropdownSelection();
                    } else {
                        focusMapOn(focus.getX(), focus.getZ());
                    }
                    clickSound();
                    return true;
                }
            }
            return true;
        }
        if (button == 1) {
            return true;
        }
        boolean result = super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
            selectWaypointUnderCursor();
        }
        return result;
    }

    private void syncDropdownSelection() {
        try {
            if (dimDropdown != null) {
                dimDropdown.selectId(Math.max(0, dimOptions.indexOf(viewedDim)), false);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("Could not sync dimension dropdown", t);
        }
    }

    private void selectWaypointUnderCursor() {
        try {
            Field viewedField = GuiMap.class.getDeclaredField("viewed");
            viewedField.setAccessible(true);
            Object hovered = viewedField.get(this);
            if (!(hovered instanceof HoveredMapElementHolder<?, ?> holder)) {
                return;
            }
            Object element = holder.getElement();
            if (!(element instanceof xaero.map.mods.gui.Waypoint mapW)) {
                return;
            }
            Object orig = mapW.getOriginal();
            if (!(orig instanceof Waypoint minimapW)) {
                return;
            }
            // Prefer identity: our tracked marker object wins over coordinate lookalikes
            // (e.g. the user's own marker stacked on the same block).
            for (int i = 0; i < sortedEntries.size(); i++) {
                RespawnEntry p = sortedEntries.get(i);
                if (p.concealed() || !p.dimension().equals(viewedDim)) {
                    continue;
                }
                if (minimapW == XaeroWaypointSync.findMarker(p.dimension(), p.id())) {
                    selectedIdx = i;
                    focusMapOn(p.pos().getX(), p.pos().getZ());
                    return;
                }
            }
            // Fallback: match by coordinates.
            for (int i = 0; i < sortedEntries.size(); i++) {
                RespawnEntry p = sortedEntries.get(i);
                if (p.concealed() || !p.dimension().equals(viewedDim)) {
                    continue;
                }
                if (minimapW.getX() == p.pos().getX()
                        && minimapW.getY() == p.pos().getY()
                        && minimapW.getZ() == p.pos().getZ()) {
                    selectedIdx = i;
                    focusMapOn(p.pos().getX(), p.pos().getZ());
                    return;
                }
            }
        } catch (Exception e) {
            Constants.LOG.warn("Could not pick waypoint from map", e);
        }
    }

    private void clickSound() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private boolean isWorldStateUnusable() {
        try {
            XaeroMinimapSession minimapSession = XaeroMinimapSession.getCurrentSession();
            if (minimapSession != null && minimapSession.getWaypointsManager() != null
                    && minimapSession.getWaypointsManager().getCurrentWorld() != null
                    && minimapSession.getWaypointsManager().getCurrentWorld().getContainer() != null) {
                String key = minimapSession.getWaypointsManager().getCurrentWorld().getContainer().getKey();
                return key == null || key.isEmpty() || key.length() < 4;
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    /** Anchor with no charges left: shown greyed out, respawn blocked (server rejects too). */
    private static String chargesText(RespawnEntry entry) {
        return ScreenTexts.plainChargesText(entry);
    }

    @Override
    public void onClose() {
        restoreRespawnSetCurrents();
        super.onClose();
    }

    @Override
    public void removed() {
        restoreRespawnSetCurrents();
        super.removed();
    }
}
