package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.integration.MapIntegrations;
import com.vlogf.respawnpoints.model.RespawnEntry;
import com.vlogf.respawnpoints.network.DeletePointPacket;
import com.vlogf.respawnpoints.network.RenamePointPacket;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small text-input screen to rename a bed/anchor from the respawn flow. */
public class RenamePointScreen extends Screen {
    private final RespawnEntry entry;
    private EditBox nameField;
    private Button unbindButton;
    /** Two-step unbind: first click arms, second click confirms. */
    private final DeleteArm deleteArm = DeleteArm.create();

    public RenamePointScreen(RespawnEntry entry) {
        super(Component.translatable("respawnpoints.rename.title"));
        this.entry = entry;
    }

    /** Id of the renamed point, for stale-target detection. */
    public String entryId() {
        return entry.id();
    }

    @Override
    protected void init() {
        int w = 200;
        int x = (width - w) / 2;
        int y = height / 2 - 30;
        nameField = new EditBox(font, x, y, w, 20,
                Component.translatable("respawnpoints.rename.prompt"));
        nameField.setMaxLength(32);
        nameField.setValue(entry.name());
        addRenderableWidget(nameField);
        setInitialFocus(nameField);

        addRenderableWidget(Button.builder(Component.translatable("respawnpoints.rename.save"), b -> save())
                .bounds(x, y + 28, w / 2 - 2, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("respawnpoints.rename.cancel"), b -> back())
                .bounds(x + w / 2 + 2, y + 28, w / 2 - 2, 20)
                .build());
        unbindButton = Button.builder(Component.translatable("respawnpoints.rename.unbind"), b -> unbind())
                .bounds(x, y + 52, w, 20)
                .build();
        addRenderableWidget(unbindButton);
    }

    private void save() {
        String name = nameField.getValue().trim();
        if (!name.isEmpty() && !name.equals(entry.name())) {
            Services.NETWORK.sendToServer(new RenamePointPacket(entry.id(), name));
        }
        // Map-side recolors ride the same full sync the death flow uses:
        // reconcile detects the diff and sends it, so this works with no
        // death, no timing luck, and no extra lookup paths.
        MapIntegrations.syncClientWaypoints(
                com.vlogf.respawnpoints.client.PendingRespawn.entries());
        back();
    }

    private void unbind() {
        if (!deleteArm.click(entry.id())) {
            unbindButton.setMessage(Component.translatable("respawnpoints.rename.confirm_unbind"));
            return;
        }
        Services.NETWORK.sendToServer(new DeletePointPacket(entry.id()));
        back();
    }

    private void back() {
        var player = Minecraft.getInstance().player;
        if (player != null && player.isAlive()) {
            // Alive (shift+RMB flow): just return to the game, no selection screen flash.
            Minecraft.getInstance().setScreen(null);
            return;
        }
        Screen fresh = RespawnScreenFactory.createFresh();
        Minecraft.getInstance().setScreen(fresh);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 56, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}