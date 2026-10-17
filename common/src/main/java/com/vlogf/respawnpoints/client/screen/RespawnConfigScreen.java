package com.vlogf.respawnpoints.client.screen;

import com.vlogf.respawnpoints.client.RespawnClientConfig;
import com.vlogf.respawnpoints.server.DeathManager;
import com.vlogf.respawnpoints.server.RespawnConfig;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * In-game settings screen (YACL, optional dependency). Built fresh on every
 * open; Save writes the files, Cancel discards the draft without touching the
 * game (save-only semantics, no instant apply).
 */
public final class RespawnConfigScreen {
    private RespawnConfigScreen() {
    }

    public static Screen create(Screen parent) {
        var minecraft = Minecraft.getInstance();
        var builder = YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("respawnpoints.config.title"))
                .category(lookCategory())
                .category(mapCategory());
        // Map-manager choice is parked with JourneyMap support (Xaero-only for now).
        // Host settings live in the client's config dir and never touch a
        // foreign server: show them in singleplayer and in the main menu
        // (for future hosted worlds), hide only on a remote server.
        boolean foreign = minecraft.getSingleplayerServer() == null
                && minecraft.getCurrentServer() != null;
        if (!foreign) {
            builder.category(serverCategory());
        }
        builder.save(() -> {
            RespawnClientConfig.save();
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                RespawnConfig.save(server.getServerDirectory());
                server.execute(() -> {
                    for (var online : server.getPlayerList().getPlayers()) {
                        DeathManager.pushUpdate(online);
                    }
                });
            } else {
                // Main menu: persist host defaults where the integrated
                // server will read them on the next world start.
                RespawnConfig.save(Minecraft.getInstance().gameDirectory.toPath());
            }
        });
        return builder.build().generateScreen(parent);
    }

    /** Map-manager choice UI (Xaero and JourneyMap). */
    private static ConfigCategory mapCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable("respawnpoints.config.map"))
                .option(Option.<RespawnClientConfig.ActiveMap>createBuilder()
                        .name(Component.translatable("respawnpoints.config.map_choice"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.map_choice_desc")))
                        .binding(RespawnClientConfig.ActiveMap.AUTO,
                                RespawnClientConfig::activeMap,
                                RespawnClientConfig::setChoice)
                        .controller(opt -> CyclingListControllerBuilder.create(opt)
                                .values(List.of(RespawnClientConfig.ActiveMap.values()))
                                .formatValue(value -> Component.translatable(
                                        "respawnpoints.config.map_"
                                                + value.name().toLowerCase())))
                        .build())
                .build();
    }

    private static ConfigCategory lookCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable("respawnpoints.config.look"))
                .option(Option.<Boolean>createBuilder()
                        .name(Component.translatable("respawnpoints.config.show_name"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.show_name_desc")))
                        .binding(false,
                                RespawnClientConfig::showSelectedName,
                                RespawnClientConfig::setShowSelectedName)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(Component.translatable("respawnpoints.config.overlay"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.overlay_desc")))
                        .binding(false,
                                RespawnClientConfig::loadingOverlay,
                                RespawnClientConfig::setLoadingOverlay)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                .build();
    }

    private static ConfigCategory serverCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable("respawnpoints.config.server"))
                .tooltip(Component.translatable("respawnpoints.config.server_tooltip"))
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable("respawnpoints.config.bed_limit"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.bed_limit_desc")))
                        .binding(10,
                                RespawnConfig::getBedLimit,
                                RespawnConfig::setBedLimit)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(1, 1024).step(1))
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(Component.translatable("respawnpoints.config.enable_neardeath"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.enable_neardeath_desc")))
                        .binding(false,
                                RespawnConfig::isEnableNearDeath,
                                RespawnConfig::setEnableNearDeath)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable("respawnpoints.config.neardeath_radius"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.neardeath_radius_desc")))
                        .binding(1024,
                                RespawnConfig::getNearDeathRadius,
                                RespawnConfig::setNearDeathRadius)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(16, 2048).step(16))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable("respawnpoints.config.neardeath_min"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.neardeath_min_desc")))
                        .binding(512,
                                RespawnConfig::getNearDeathMinDistance,
                                RespawnConfig::setNearDeathMinDistance)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(0, 1024).step(8))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(Component.translatable("respawnpoints.config.neardeath_cooldown"))
                        .description(OptionDescription.of(Component.translatable(
                                "respawnpoints.config.neardeath_cooldown_desc")))
                        .binding(300,
                                RespawnConfig::getNearDeathCooldownSec,
                                RespawnConfig::setNearDeathCooldownSec)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(0, 3600).step(60))
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(Component.translatable("respawnpoints.config.show_spawn"))
                        .description(OptionDescription.createBuilder()
                                .text(Component.translatable(
                                        "respawnpoints.config.show_spawn_desc"))
                                .image(net.minecraft.resources.ResourceLocation
                                        .fromNamespaceAndPath("respawnpoints", "yacl/show_spawn.png"), 480, 270)
                                .build())
                        .binding(true,
                                RespawnConfig::isShowSpawn,
                                RespawnConfig::setShowSpawn)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(Component.translatable("respawnpoints.config.show_neardeath"))
                        .description(OptionDescription.createBuilder()
                                .text(Component.translatable(
                                        "respawnpoints.config.show_neardeath_desc"))
                                .image(net.minecraft.resources.ResourceLocation
                                        .fromNamespaceAndPath("respawnpoints", "yacl/show_neardeath.png"), 480, 270)
                                .build())
                        .binding(false,
                                RespawnConfig::isShowNearDeath,
                                RespawnConfig::setShowNearDeath)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                .build();
    }
}
