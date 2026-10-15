package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.PendingRespawn;
import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.DeletePointPacket;
import com.vlogf.respawnpoints.network.SelectRespawnPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Fallback respawn screen used when no map mod drives the UI. */
public class RespawnSelectScreen extends Screen implements RespawnListUpdater {
    private static final int ENTRY_HEIGHT = UiConstants.ENTRY_HEIGHT;
    private static final int LIST_WIDTH = UiConstants.LIST_WIDTH;

    private final List<RespawnEntry> entries = new java.util.ArrayList<>();
    private final Component deathMessage;
    /** Null = show all dimensions, otherwise only this one. */
    private final String dimFilter;
    private final java.util.List<Button> entryButtons = new java.util.ArrayList<>();
    private final java.util.List<Button> deleteButtons = new java.util.ArrayList<>();
    private RespawnEntry selected;
    private Button respawnButton;
    private boolean sent;
    /** Two-step delete: first click arms, second click confirms. */
    private final RespawnScreenModel.DeleteArm deleteArm = new RespawnScreenModel.DeleteArm();
    /** Death-offer generation this screen was built for (unfreezes on a newer one). */
    private long offerId;

    public RespawnSelectScreen(List<RespawnEntry> entries, Component deathMessage) {
        this(entries, deathMessage, null);
    }

    public RespawnSelectScreen(List<RespawnEntry> entries, Component deathMessage, String dimFilter) {
        super(Component.translatable("respawnpoints.screen.title"));
        var player = Minecraft.getInstance().player;
        String deathDim = player != null ? player.level().dimension().location().toString() : null;
        net.minecraft.core.BlockPos deathPos = player != null ? player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
        this.entries.clear();
        this.entries.addAll(RespawnScreenModel.sorted(entries, deathDim, deathPos));
        this.deathMessage = deathMessage;
        this.dimFilter = dimFilter;
        this.offerId = com.vlogf.respawnpoints.client.PendingRespawn.offerId();
    }

    @Override
    public long respawnpoints$offerId() {
        return offerId;
    }

    @Override
    public void respawnpoints$resetForNewOffer() {
        offerId = com.vlogf.respawnpoints.client.PendingRespawn.offerId();
        sent = false;
        deleteArm.clear();
        refreshButtons();
    }

    private List<RespawnEntry> visibleEntries() {
        if (dimFilter == null) {
            return entries;
        }
        List<RespawnEntry> visible = new java.util.ArrayList<>();
        for (RespawnEntry entry : entries) {
            if (dimFilter.equals(entry.dimension())) {
                visible.add(entry);
            }
        }
        return visible;
    }

    private List<String> dimOptions() {        List<String> dims = new java.util.ArrayList<>();
        for (RespawnEntry entry : entries) {
            if (!dims.contains(entry.dimension())) {
                dims.add(entry.dimension());
            }
        }
        return dims;
    }

    @Override
    protected void init() {
        entryButtons.clear();
        deleteButtons.clear();
        int listLeft = (this.width - LIST_WIDTH) / 2;
        List<String> dims = dimOptions();
        if (!dims.isEmpty()) {
            String label = dimFilter == null
                    ? "Dimension: All ▾"
                    : "Dimension: " + ScreenUtils.shortDimName(dimFilter) + " ▾";
            addRenderableWidget(Button.builder(Component.literal(label), b -> cycleDim())
                    .bounds(listLeft, 38, LIST_WIDTH, 20)
                    .build());
        }
        for (RespawnEntry entry : entries) {
            final String entryId = entry.id();
            Button del = null;
            if (ScreenUtils.isManageable(entry)) {
                del = Button.builder(deleteLabel(entry), b -> deleteById(entryId))
                        .bounds(listLeft + LIST_WIDTH - 26, 64, 26, ENTRY_HEIGHT)
                        .build();
                addRenderableWidget(del);
            }
            deleteButtons.add(del);
            int entryWidth = ScreenUtils.isManageable(entry) ? LIST_WIDTH - 28 : LIST_WIDTH;
            Button button = Button.builder(entryButtonText(entry), b -> selectById(entryId))
                    .bounds(listLeft, 64, entryWidth, ENTRY_HEIGHT)
                    .build();
            addRenderableWidget(button);
            entryButtons.add(button);
        }
        if (selected == null && !visibleEntries().isEmpty()) {
            select(visibleEntries().get(0));
        }

        int bottomY = this.height - 52;
        respawnButton = Button.builder(Component.translatable("respawnpoints.screen.respawn"), b -> respawn())
                .bounds(listLeft, bottomY, LIST_WIDTH / 2 - 2, 20)
                .build();
        addRenderableWidget(respawnButton);
        addRenderableWidget(Button.builder(Component.translatable("respawnpoints.screen.title_screen"), b -> exitToTitle())
                .bounds(listLeft + LIST_WIDTH / 2 + 2, bottomY, LIST_WIDTH / 2 - 2, 20)
                .build());
        refreshLayout();
        refreshButtons();
    }

    /**
     * Show/hide + reposition buttons for the current dimension filter.
     */
    private void refreshLayout() {
        List<RespawnEntry> shown = visibleEntries();
        java.util.Set<String> shownIds = new java.util.HashSet<>();
        for (RespawnEntry entry : shown) {
            shownIds.add(entry.id());
        }
        if (selected != null && !shownIds.contains(selected.id())) {
            selected = shown.isEmpty() ? null : shown.get(0);
            deleteArm.clear();
        }
        int y = 64;
        for (int i = 0; i < entries.size() && i < entryButtons.size(); i++) {
            RespawnEntry entry = entries.get(i);
            Button button = entryButtons.get(i);
            Button del = i < deleteButtons.size() ? deleteButtons.get(i) : null;
            boolean vis = shownIds.contains(entry.id());
            button.visible = vis;
            if (del != null) {
                del.visible = vis && ScreenUtils.isManageable(entry);
            }
            if (vis) {
                button.setY(y);
                button.setMessage(entryButtonText(entry));
                if (del != null) {
                    del.setY(y);
                    del.setMessage(deleteLabel(entry));
                }
                y += ENTRY_HEIGHT + 2;
            }
        }
        refreshButtons();
    }

    private void selectById(String id) {
        for (RespawnEntry entry : entries) {
            if (id.equals(entry.id())) {
                select(entry);
                return;
            }
        }
    }

    private void deleteById(String id) {
        for (RespawnEntry entry : entries) {
            if (id.equals(entry.id())) {
                delete(entry);
                return;
            }
        }
    }

    private Component deleteLabel(RespawnEntry entry) {
        if (entry.id().equals(deleteArm.armedId())) {
            return Component.literal("?");
        }
        return Component.literal("✕");
    }

    private void delete(RespawnEntry entry) {
        if (!deleteArm.click(entry.id())) {
            refreshDeleteLabels();
            return;
        }
        Services.NETWORK.sendToServer(new DeletePointPacket(entry.id()));
        if (entries.size() <= 1) {
            // That was the last point: nothing left to show, back to vanilla
            // at once instead of a stale card (server push confirms after).
            RespawnScreenFactory.backToVanilla();
        }
    }

    private void cycleDim() {
        List<String> dims = dimOptions();
        String next = null;
        if (dimFilter != null) {
            int idx = dims.indexOf(dimFilter);
            if (idx >= 0 && idx + 1 < dims.size()) {
                next = dims.get(idx + 1);
            }
        } else if (!dims.isEmpty()) {
            next = dims.get(0);
        }
        Minecraft.getInstance().setScreen(new RespawnSelectScreen(entries, deathMessage, next));
    }

    private void refreshDeleteLabels() {
        for (int i = 0; i < entries.size() && i < deleteButtons.size(); i++) {
            Button del = deleteButtons.get(i);
            if (del != null) {
                del.setMessage(deleteLabel(entries.get(i)));
            }
        }
    }

    private void select(RespawnEntry entry) {
        this.selected = entry;
        deleteArm.onSelectedOther(entry.id());
        refreshLayout();
    }

    /**
     * Data-only refresh (charges/colors): swap list contents and repaint the
     * frozen button labels in place. Structural changes return false.
     */
    @Override
    public boolean updateIfDataOnly(List<RespawnEntry> freshRaw) {
        // Respawning: this screen is about to close, absorb silently.
        if (sent) {
            return true;
        }
        var player = Minecraft.getInstance().player;
        String deathDim = player != null ? player.level().dimension().location().toString() : null;
        net.minecraft.core.BlockPos deathPos = player != null ? player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
        List<RespawnEntry> scoped = RespawnScreenModel.sorted(freshRaw, deathDim, deathPos);
        if (!EntriesDiff.sameStructure(entries, scoped)) {
            return false;
        }
        String keepId = selected != null ? selected.id() : null;
        entries.clear();
        entries.addAll(scoped);
        if (keepId != null) {
            for (RespawnEntry entry : entries) {
                if (keepId.equals(entry.id())) {
                    selected = entry;
                    break;
                }
            }
        }
        deleteArm.clearIfGone(entries);
        // Selection already remapped onto the fresh objects above (charges/colors
        // changed); repaint + refilter in place.
        refreshLayout();
        return true;
    }

    @Override
    public String selectedEntryId() {
        return selected != null ? selected.id() : null;
    }

    /**
     * Structural in-place update (add/remove/rename/reorder): swap the list
     * and rebuild only the buttons of the same screen — no {@code setScreen},
     * no state loss beyond the removed selection.
     */
    @Override
    public boolean updateIfStructural(List<RespawnEntry> freshRaw) {
        if (sent) {
            return true;
        }
        var player = Minecraft.getInstance().player;
        String deathDim = player != null ? player.level().dimension().location().toString() : null;
        net.minecraft.core.BlockPos deathPos = player != null ? player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
        List<RespawnEntry> scoped = RespawnScreenModel.sorted(freshRaw, deathDim, deathPos);
        String keepId = selected != null ? selected.id() : null;
        entries.clear();
        entries.addAll(scoped);
        selected = null;
        if (keepId != null) {
            for (RespawnEntry entry : entries) {
                if (keepId.equals(entry.id())) {
                    selected = entry;
                    break;
                }
            }
        }
        deleteArm.clearIfGone(entries);
        if (selected == null) {
            List<RespawnEntry> shown = visibleEntries();
            if (!shown.isEmpty()) {
                selected = shown.get(0);
            } else if (!entries.isEmpty()) {
                selected = entries.get(0);
            }
        }
        rebuildWidgets();
        return true;
    }

    @Override
    public void selectEntryId(String id) {
        if (id == null) {
            return;
        }
        for (RespawnEntry entry : entries) {
            if (id.equals(entry.id())) {
                select(entry);
                return;
            }
        }
    }

    private void refreshButtons() {
        if (respawnButton != null) {
            respawnButton.active = selected != null && !sent && !ScreenUtils.isDepleted(selected);
        }
    }

    private Component entryButtonText(RespawnEntry entry) {
        String marker = entry == selected ? "◆ " : "◇ ";
        String dist = ScreenUtils.distanceText(entry);
        String charges = ScreenTexts.chargesText(entry);
        return Component.literal(marker + entry.symbol() + " " + ScreenUtils.displayName(entry) + charges + ScreenTexts.cooldownText(entry) + "  §7" + dist);
    }

    protected void respawn() {
        if (selected == null || sent || ScreenUtils.isDepleted(selected)) {
            return;
        }
        sent = true;
        Services.NETWORK.sendToServer(new SelectRespawnPacket(selected.id()));
        // The screen stays open either way (black overlay in overlay mode,
        // frozen selection otherwise) until vanilla takes over on respawn.
        refreshButtons();
    }

    private void exitToTitle() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.disconnect(new TitleScreen());
    }

    @Override
    public void tick() {
        var player = Minecraft.getInstance().player;
        if (player != null && player.isAlive()) {
            PendingRespawn.clear();
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, deathMessage, width / 2, 20, 0xFF5555);
        graphics.drawCenteredString(font,
                Component.translatable("respawnpoints.screen.subtitle"),
                width / 2, 36, 0xFFFFFF);
        if (sent && com.vlogf.respawnpoints.client.RespawnClientConfig.loadingOverlay()) {
            graphics.drawCenteredString(font,
                    Component.translatable("respawnpoints.screen.respawning"),
                    width / 2, height - 66, 0xFFFF55);
        } else if (selected != null) {
            graphics.drawCenteredString(font, detailText(selected), width / 2, height - 66, 0xAAAAAA);
        }
    }

    private Component detailText(RespawnEntry entry) {
        if (entry.concealed()) {
            return Component.translatable("respawnpoints.screen.hidden_spot");
        }
        BlockPos pos = entry.pos();
        return Component.literal(entry.dimension() + "  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // Stay on the respawn flow; closing is only possible via respawn or title screen.
    }
}
