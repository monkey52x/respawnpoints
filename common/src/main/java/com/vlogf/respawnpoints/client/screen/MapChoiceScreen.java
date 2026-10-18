package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * First-launch map selection screen. Shown once in the main menu when no map
 * manager has been chosen yet and at least one map mod (Xaero or JourneyMap)
 * is installed. All buttons are centered horizontally; the "None" button sits
 * at the bottom centre of the screen. Can be closed via Escape — choosing
 * "None" is a valid choice and saves NONE to the config.
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

        // "None" button at the bottom centre.
        int noneY = height - 50;
        addRenderableWidget(Button.builder(
                        Component.translatable("respawnpoints.mapchoice.none"),
                        b -> choose(RespawnClientConfig.ActiveMap.NONE))
                .bounds(cx, noneY, BUTTON_W, BUTTON_H)
                .build());
    }

    private void choose(RespawnClientConfig.ActiveMap map) {
        RespawnClientConfig.setChoice(map);
        minecraft.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
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

    /** Open the map choice screen if no choice has been made yet. */
    public static void promptIfNeeded() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (!RespawnClientConfig.choiceMade() && minecraft.screen == null) {
            minecraft.setScreen(new MapChoiceScreen());
        }
    }
}
