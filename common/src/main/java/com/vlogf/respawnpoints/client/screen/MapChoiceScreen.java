package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * First-launch map selection screen. Shown in the main menu while the map
 * manager is unset ({@link RespawnClientConfig.ActiveMap#NONE}). The choice is
 * mandatory: there is no "None" option and Escape does not close the screen, so
 * the player cannot enter a world until a manager is picked. Only managers that
 * are actually installed are offered, plus "Disabled" (the built-in list screen
 * without any map integration) which is always available.
 */
public class MapChoiceScreen extends Screen {
    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 6;

    public MapChoiceScreen() {
        super(Component.translatable("respawnpoints.mapchoice.title"));
    }

    @Override
    protected void init() {
        int cx = (width - BUTTON_W) / 2;
        int y = height / 3;

        boolean xaero = MapIntegrations.isXaeroPresent();
        boolean jm = MapIntegrations.isJourneyMapPresent();

        if (xaero) {
            addRenderableWidget(Button.builder(
                            Component.translatable("respawnpoints.mapchoice.xaero"),
                            b -> choose(RespawnClientConfig.ActiveMap.XAERO))
                    .bounds(cx, y, BUTTON_W, BUTTON_H)
                    .build());
            y += BUTTON_H + BUTTON_GAP;
        }
        if (jm) {
            addRenderableWidget(Button.builder(
                            Component.translatable("respawnpoints.mapchoice.journeymap"),
                            b -> choose(RespawnClientConfig.ActiveMap.JOURNEYMAP))
                    .bounds(cx, y, BUTTON_W, BUTTON_H)
                    .build());
            y += BUTTON_H + BUTTON_GAP;
        }

        // "Disabled" button — built-in mod menu without maps, saves DISABLED.
        // Always available so the player can never get stuck on this screen.
        addRenderableWidget(Button.builder(
                        Component.translatable("respawnpoints.mapchoice.disabled"),
                        b -> choose(RespawnClientConfig.ActiveMap.DISABLED))
                .bounds(cx, y, BUTTON_W, BUTTON_H)
                .build());
    }

    private void choose(RespawnClientConfig.ActiveMap map) {
        RespawnClientConfig.setChoice(map);
        minecraft.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Mandatory choice: Escape must not skip the screen.
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 3 - 40, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("respawnpoints.mapchoice.subtitle"),
                width / 2, height / 3 - 20, 0xAAAAAA);
    }
}
