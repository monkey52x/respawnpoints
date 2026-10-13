package com.vlogf.respawnpoints.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.vlogf.respawnpoints.platform.Services;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ModMenu config button (Fabric). The entrypoint is inert without ModMenu;
 * without YACL a small vanilla notice is shown instead of crashing.
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (!Services.PLATFORM.isModLoaded("yet_another_config_lib_v3")) {
                return new NoYaclScreen(parent);
            }
            return com.vlogf.respawnpoints.client.screen.RespawnConfigScreen.create(parent);
        };
    }

    private static final class NoYaclScreen extends Screen {
        private final Screen parent;

        NoYaclScreen(Screen parent) {
            super(Component.translatable("respawnpoints.config.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int w = 200;
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                            Component.translatable("respawnpoints.config.no_yacl"),
                            b -> onClose())
                    .bounds((width - w) / 2, height / 2 + 10, w, 20)
                    .build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics,
                           int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font,
                    Component.translatable("respawnpoints.config.no_yacl_hint"),
                    width / 2, height / 2 - 20, 0xFFFFFF);
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }
    }
}
