package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.integration.MapIntegrations;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** First-join prompt (dormant while JourneyMap support is parked: never prompted). */
public class MapChoiceScreen extends Screen {
    private final java.util.UUID serverId;

    public MapChoiceScreen(java.util.UUID serverId) {
        super(Component.translatable("respawnpoints.mapchoice.title"));
        this.serverId = serverId;
    }

    @Override
    protected void init() {
        int w = 200;
        int x = (width - w) / 2;
        int y = height / 2 - 30;
        boolean xaero = MapIntegrations.isXaeroPresent();

        if (xaero) {
            addRenderableWidget(Button.builder(Component.literal("Xaero's Map"),
                    b -> choose(RespawnClientConfig.ActiveMap.XAERO))
                    .bounds(x, y, w, 20).build());
            y += 24;
        }
        // JourneyMap button parked with the integration (dormant).
        addRenderableWidget(Button.builder(Component.translatable("respawnpoints.mapchoice.auto"),
                b -> choose(RespawnClientConfig.ActiveMap.AUTO))
                .bounds(x, y, w, 20).build());
    }

    private void choose(RespawnClientConfig.ActiveMap map) {
        RespawnClientConfig.setChoiceFor(serverId, map);
        minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("respawnpoints.mapchoice.subtitle"),
                width / 2, height / 2 - 46, 0xAAAAAA);
    }

    /** Open the prompt for a server the player never chose a manager for. */
    public static void promptFor(java.util.UUID serverId) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen == null) {
                minecraft.setScreen(new MapChoiceScreen(serverId));
            }
        });
    }
}
