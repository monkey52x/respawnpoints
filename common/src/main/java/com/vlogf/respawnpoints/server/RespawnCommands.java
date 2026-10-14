package com.vlogf.respawnpoints.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /respawnpoints warp add|del|list}, {@code /respawnpoints bed list|rename|remove},
 * {@code /respawnpoints spawn hide|show}. Registered by each loader.
 */
public final class RespawnCommands {
    private RespawnCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("respawnpoints");

        var warp = Commands.literal("warp").requires(src -> src.hasPermission(2));
        warp.then(Commands.literal("add")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> addWarp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), ""))));
        warp.then(Commands.literal("del")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(warpIdSuggestions())
                        .executes(ctx -> delWarp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));
        warp.then(Commands.literal("list")
                .executes(ctx -> listWarps(ctx.getSource())));

        var bed = Commands.literal("bed");
        bed.then(Commands.literal("list").executes(ctx -> listBeds(ctx.getSource())));
        bed.then(Commands.literal("rename")
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(ownBedIdSuggestions())
                        .then(Commands.literal("default")
                                .executes(ctx -> resetBedName(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"))))
                        .then(Commands.literal("custom")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> renameBed(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "name")))))));
        bed.then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(ownBedIdSuggestions())
                        .executes(ctx -> removeBed(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));
        bed.then(Commands.literal("block").executes(ctx -> armBlock(ctx.getSource())));
        bed.then(Commands.literal("unblock").executes(ctx -> armUnblock(ctx.getSource())));
        bed.then(Commands.literal("blocked").executes(ctx -> listBlocked(ctx.getSource())));
        var admin = Commands.literal("admin").requires(src -> src.hasPermission(2));
        admin.then(Commands.literal("list").executes(ctx -> adminList(ctx.getSource())));
        admin.then(Commands.literal("reload").executes(ctx -> adminReload(ctx.getSource())));
        admin.then(Commands.literal("beds")
                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(ctx -> adminBeds(ctx.getSource(),
                                net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")))));
        admin.then(Commands.literal("bedinfo")
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(allBedIdSuggestions())
                        .executes(ctx -> adminBedInfo(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));
        admin.then(Commands.literal("tp")
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(allPointIdSuggestions())
                        .executes(ctx -> adminTp(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"))))
                .then(Commands.literal("warp")
                        .then(Commands.argument("warpId", StringArgumentType.string())
                                .suggests(warpIdSuggestions())
                                .executes(ctx -> adminTpWarp(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "warpId"))))));
        admin.then(Commands.literal("unbind")
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(allBedIdSuggestions())
                        .then(Commands.argument("player", StringArgumentType.string())
                                .suggests(playerNameSuggestions())
                                .executes(ctx -> adminUnbind(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "player"))))));
        admin.then(Commands.literal("bind")
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(allBedIdSuggestions())
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> adminBind(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player"))))));

        var spawn = Commands.literal("spawn");
        spawn.then(Commands.literal("hide").executes(ctx -> setSpawnHidden(ctx.getSource(), true)));
        spawn.then(Commands.literal("show").executes(ctx -> setSpawnHidden(ctx.getSource(), false)));
        var spawnRename = Commands.literal("rename");
        spawnRename.then(Commands.literal("default")
                .executes(ctx -> resetSpawnName(ctx.getSource())));
        spawnRename.then(Commands.literal("custom")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> renameSpawn(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))));
        spawn.then(spawnRename);

        var debug = Commands.literal("debug").requires(src -> src.hasPermission(2));
        debug.then(Commands.literal("inspect").executes(ctx -> armInspect(ctx.getSource())));

        root.then(warp);
        root.then(bed);
        root.then(admin);
        root.then(spawn);
        root.then(debug);
        dispatcher.register(root);
    }

    private static SuggestionProvider<CommandSourceStack> warpIdSuggestions() {
        return suggestIds(ctx -> {
            var server = ctx.getSource().getServer();
            if (server == null) {
                return java.util.List.of();
            }
            return WarpStorage.get(server.overworld()).all().stream().map(WarpPoint::id).toList();
        });
    }

    /**
     * Server-bound translatable: every non-{@link Component} argument is wrapped
     * into a literal. Raw strings/objects as packet translatable args crash the
     * 1.21.1 strict component codec (kick with {@code system_chat} encode
     * failure) — this helper closes the whole class of bugs at one point.
     */
    static MutableComponent tr(String key, Object... args) {
        Object[] wrapped = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            wrapped[i] = arg instanceof Component component
                    ? component
                    : Component.literal(String.valueOf(arg));
        }
        return Component.translatable(key, wrapped);
    }

    /**
     * Click-to-copy id fragment for chat lists. Appended after a row template
     * that ends with {@code "| id: "}.
     */
    static MutableComponent copyId(String id) {        return Component.literal(id).withStyle(style -> style
                .withColor(ChatFormatting.GRAY)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        tr("respawnpoints.cmd.copy"))));
    }

    private static int addWarp(CommandSourceStack src, String name, String symbol) {
        var server = src.getServer();
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 32) {
            src.sendFailure(tr("respawnpoints.cmd.name_1_32"));
            return 0;
        }
        for (WarpPoint existing : WarpStorage.get(server.overworld()).all()) {
            if (existing.name().trim().equalsIgnoreCase(clean)) {
                src.sendFailure(tr("respawnpoints.cmd.warp_dup", existing.name()));
                return 0;
            }
        }
        BlockPos pos = BlockPos.containing(src.getPosition());
        String dim = src.getLevel().dimension().location().toString();
        WarpPoint warp = WarpStorage.get(server.overworld()).add(clean, symbol, dim, pos);
        src.sendSuccess(() -> tr("respawnpoints.cmd.warp_added",
                warp.name(), warp.id(), dim, pos.getX(), pos.getY(), pos.getZ()), true);
        // Warps are visible to everyone: push the fresh list so markers and
        // open selection screens update immediately (in-place, no rebuild).
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            DeathManager.pushUpdate(online);
        }
        return 1;
    }

    private static int delWarp(CommandSourceStack src, String id) {
        if (WarpStorage.get(src.getServer().overworld()).remove(id)) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.warp_removed", id), true);
            for (ServerPlayer online : src.getServer().getPlayerList().getPlayers()) {
                DeathManager.pushUpdate(online);
            }
            return 1;
        }
        src.sendFailure(tr("respawnpoints.cmd.unknown_warp", id));
        return 0;
    }

    private static int listWarps(CommandSourceStack src) {
        var warps = WarpStorage.get(src.getServer().overworld()).all();
        if (warps.isEmpty()) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.no_warps"), false);
            return 1;
        }
        for (WarpPoint warp : warps) {
            Component row = tr("respawnpoints.cmd.warp_row",
                    warp.name(), warp.dimension(), warp.pos().getX(), warp.pos().getY(),
                    warp.pos().getZ()).append(copyId(warp.id()));
            src.sendSuccess(() -> row, false);
        }
        return warps.size();
    }

    private static ServerPlayer requirePlayer(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(tr("respawnpoints.cmd.only_players"));
            return null;
        }
        return player;
    }

    private static SuggestionProvider<CommandSourceStack> ownBedIdSuggestions() {
        return suggestIds(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                return SpawnRegistry.get(player.server.overworld()).allForPlayer(player.getUUID())
                        .stream().map(r -> r.id().toString()).toList();
            }
            return java.util.List.of();
        });
    }

    private static SuggestionProvider<CommandSourceStack> allBedIdSuggestions() {
        return suggestIds(ctx -> {
            var server = ctx.getSource().getServer();
            if (server == null) {
                return java.util.List.of();
            }
            return SpawnRegistry.get(server.overworld()).all()
                    .stream().map(r -> r.id().toString()).toList();
        });
    }

    private static SuggestionProvider<CommandSourceStack> allPointIdSuggestions() {
        return suggestIds(ctx -> {
            var server = ctx.getSource().getServer();
            if (server == null) {
                return java.util.List.of();
            }
            java.util.List<String> ids = new java.util.ArrayList<>();
            ids.add("spawn");
            for (var record : SpawnRegistry.get(server.overworld()).all()) {
                ids.add(record.id().toString());
            }
            // Warps go through "admin tp warp <id>" (a bare id parses cleanly;
            // the "warp:<id>" form is only accepted quoted).
            return ids;
        });
    }

    /** Single-template id suggester: empty list falls back to no suggestions. */
    private static SuggestionProvider<CommandSourceStack> suggestIds(
            java.util.function.Function<com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
                    java.util.List<String>> ids) {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(ids.apply(ctx).stream(), builder);
    }

    /** Require the player's own bound record, or report unknown_point. Null when rejected. */
    private static SpawnRegistry.SpawnPointRecord requireBoundRecord(CommandSourceStack src,
                                                                     ServerPlayer player, String id) {
        try {
            var record = SpawnRegistry.get(player.server.overworld())
                    .byId(java.util.UUID.fromString(id));
            if (record == null || !record.boundPlayers().contains(player.getUUID())) {
                src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
                return null;
            }
            return record;
        } catch (IllegalArgumentException e) {
            src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
            return null;
        }
    }

    private static int listBeds(CommandSourceStack src) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        var snapshots = PlayerRespawnData.points(player);
        if (snapshots.isEmpty()) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.no_beds"), false);
            return 1;
        }
        SpawnRegistry registry = SpawnRegistry.get(player.server.overworld());
        return sendBedRows(src, registry, snapshots);
    }

    private static int renameBed(CommandSourceStack src, String id, String name) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 32) {
            src.sendFailure(tr("respawnpoints.cmd.name_1_32"));
            return 0;
        }
        if (requireBoundRecord(src, player, id) == null) {
            return 0;
        }
        DeathManager.onRename(player, id, clean);
        src.sendSuccess(() -> tr("respawnpoints.cmd.renamed", clean), false);
        return 1;
    }

    private static int resetBedName(CommandSourceStack src, String id) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        if (requireBoundRecord(src, player, id) == null) {
            return 0;
        }
        DeathManager.onRenameReset(player, id);
        src.sendSuccess(() -> tr("respawnpoints.cmd.name_reset"), false);
        return 1;
    }

    private static int removeBed(CommandSourceStack src, String id) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        if (requireBoundRecord(src, player, id) == null) {
            return 0;
        }
        DeathManager.onDelete(player, id);
        src.sendSuccess(() -> tr("respawnpoints.cmd.unbound"), false);
        return 1;
    }

    private static int armBlock(CommandSourceStack src) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        DeathManager.armBlock(player);
        src.sendSuccess(() -> tr("respawnpoints.cmd.block_hint"), false);
        return 1;
    }

    private static int armUnblock(CommandSourceStack src) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        DeathManager.armUnblock(player);
        src.sendSuccess(() -> tr("respawnpoints.cmd.unblock_hint"), false);
        return 1;
    }

    private static int listBlocked(CommandSourceStack src) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        var blocked = PlayerRespawnData.blocked(player);
        if (blocked.isEmpty()) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.no_blocked"), false);
            return 1;
        }
        for (var spot : blocked) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.blocked_row",
                    spot.kind(), spot.dimension(), spot.pos().getX(), spot.pos().getY(),
                    spot.pos().getZ()), false);
        }
        return blocked.size();
    }

    private static int adminList(CommandSourceStack src) {
        var records = SpawnRegistry.get(src.getServer().overworld()).all();
        if (records.isEmpty()) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.no_records"), false);
            return 1;
        }
        for (var record : records) {
            Component row = tr("respawnpoints.cmd.admin_row",
                    record.kind(), record.pos().dimension().location(),
                    record.pos().pos().getX(), record.pos().pos().getY(),
                    record.pos().pos().getZ(), record.boundPlayers().size(),
                    record.charges()).append(copyId(record.id().toString()));
            src.sendSuccess(() -> row, false);
        }
        return records.size();
    }
    private static int adminBeds(CommandSourceStack src, ServerPlayer target) {
        var snapshots = PlayerRespawnData.points(target);
        if (snapshots.isEmpty()) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.no_beds_of",
                    target.getScoreboardName()), false);
            return 1;
        }
        SpawnRegistry registry = SpawnRegistry.get(src.getServer().overworld());
        return sendBedRows(src, registry, snapshots);
    }

    /** Shared bed/anchor row renderer for bed list + admin beds. */
    private static int sendBedRows(CommandSourceStack src, SpawnRegistry registry,
                                   java.util.List<PlayerRespawnData.PointSnapshot> snapshots) {
        for (var snapshot : snapshots) {
            String name = snapshot.name().isEmpty() ? snapshot.defaultName() : snapshot.name();
            String extra = "";
            try {
                var record = registry.byId(java.util.UUID.fromString(snapshot.id()));
                if (record != null && record.isAnchor()) {
                    extra = " charges=" + record.charges();
                }
            } catch (IllegalArgumentException ignored) {
            }
            Component row = tr("respawnpoints.cmd.bed_row", name,
                    snapshot.kind(), snapshot.dimension(), snapshot.pos().getX(),
                    snapshot.pos().getY(), snapshot.pos().getZ(), extra)
                    .append(copyId(snapshot.id()));
            src.sendSuccess(() -> row, false);
        }
        return snapshots.size();
    }

    private static int adminBedInfo(CommandSourceStack src, String id) {        SpawnRegistry.SpawnPointRecord record = null;
        try {
            record = SpawnRegistry.get(src.getServer().overworld())
                    .byId(java.util.UUID.fromString(id));
        } catch (IllegalArgumentException ignored) {
        }
        if (record == null) {
            src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
            return 0;
        }
        for (Component line : bedInfoCard(src.getServer(), record)) {
            src.sendSuccess(() -> line, false);
        }
        src.sendSuccess(() -> inspectButtons(id), false);
        return 1;
    }

    /**
     * Chat card for one record: title line (id copyable on a second line to
     * keep the layout stable) + bound players, each online one with an
     * instant-unbind button.
     */
    static java.util.List<Component> bedInfoCard(net.minecraft.server.MinecraftServer server,
                                                 SpawnRegistry.SpawnPointRecord record) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        String charges = record.isAnchor() ? " charges=" + record.charges() : "";
        lines.add(tr("respawnpoints.cmd.bedinfo_title",
                record.kind(), record.pos().dimension().location(),
                record.pos().pos().getX(), record.pos().pos().getY(),
                record.pos().pos().getZ(), charges));
        lines.add(copyId(record.id().toString()));
        lines.add(tr("respawnpoints.cmd.bedinfo_bound",
                record.boundPlayers().size()));
        if (record.boundPlayers().isEmpty()) {
            lines.add(tr("respawnpoints.cmd.bedinfo_no_players"));
            return lines;
        }
        for (java.util.UUID uuid : record.boundPlayers()) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                MutableComponent line = Component.literal("- " + online.getScoreboardName() + " ");
                line.append(chatButton(
                        tr("respawnpoints.cmd.bedinfo_unbind_hint"),
                        ClickEvent.Action.RUN_COMMAND,
                        "/respawnpoints admin unbind " + record.id() + " " + online.getScoreboardName(),
                        ChatFormatting.RED));
                lines.add(line);
            } else {
                // Offline players get the same one-click button: the command
                // accepts nicks (profile cache) and raw UUIDs, so no typing.
                MutableComponent line = Component.literal("- " + playerDisplayName(server, uuid) + " ");
                line.append(chatButton(
                        tr("respawnpoints.cmd.bedinfo_unbind_hint"),
                        ClickEvent.Action.RUN_COMMAND,
                        "/respawnpoints admin unbind " + record.id() + " " + rawPlayerName(server, uuid),
                        ChatFormatting.RED));
                lines.add(line);
            }
        }
        return lines;
    }

    /** Raw command-safe identifier for a possibly-offline player: nick if known, else UUID. */
    static String rawPlayerName(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {
        if (server.getProfileCache() != null) {
            var profile = server.getProfileCache().get(uuid);
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        }
        return uuid.toString();
    }

    /** Action row for the inspect card: players list + prefilled add/remove. */
    static MutableComponent inspectButtons(String id) {
        MutableComponent row = Component.empty();
        row.append(chatButton(tr("respawnpoints.cmd.inspect_players"),
                ClickEvent.Action.RUN_COMMAND, "/respawnpoints admin bedinfo " + id,
                ChatFormatting.GREEN));
        row.append(Component.literal(" "));
        row.append(chatButton(tr("respawnpoints.cmd.inspect_add"),
                ClickEvent.Action.SUGGEST_COMMAND, "/respawnpoints admin bind " + id + " ",
                ChatFormatting.YELLOW));
        row.append(Component.literal(" "));
        row.append(chatButton(tr("respawnpoints.cmd.inspect_remove"),
                ClickEvent.Action.SUGGEST_COMMAND, "/respawnpoints admin unbind " + id + " ",
                ChatFormatting.RED));
        return row;
    }

    private static MutableComponent chatButton(Component label, ClickEvent.Action action,
                                               String command, ChatFormatting color) {
        return Component.literal("[").append(label).append("]").withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(action, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, label)));
    }

    static String playerDisplayName(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getScoreboardName();
        }
        if (server.getProfileCache() != null) {
            var profile = server.getProfileCache().get(uuid);
            if (profile.isPresent()) {
                return profile.get().getName() + " (offline)";
            }
        }
        return uuid.toString() + " (offline)";
    }

    /** Online player names for Tab-complete (offline names are typed by hand). */
    private static SuggestionProvider<CommandSourceStack> playerNameSuggestions() {
        return (ctx, builder) -> {
            var server = ctx.getSource().getServer();
            if (server != null) {
                for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                    builder.suggest(online.getGameProfile().getName());
                }
            }
            return builder.buildFuture();
        };
    }

    private static int adminUnbind(CommandSourceStack src, String id, ServerPlayer target) {
        if (DeathManager.adminUnbind(target, id)) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.unbound_admin",
                    target.getScoreboardName(), id), true);
            return 1;
        }
        src.sendFailure(tr("respawnpoints.cmd.unknown_or_not_bound", id));
        return 0;
    }

    /**
     * Admin unbind by name: online players take the live path, offline players
     * are edited on disk (registry + playerdata file, atomic + .bak).
     */
    private static int adminUnbind(CommandSourceStack src, String id, String playerName) {
        var server = src.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(playerName);
        if (online != null) {
            return adminUnbind(src, id, online);
        }
        UUID uuid = null;
        if (server.getProfileCache() != null) {
            var profile = server.getProfileCache().get(playerName);
            if (profile.isPresent()) {
                uuid = profile.get().getId();
            }
        }
        if (uuid == null) {
            UUID derived = OfflinePlayerData.offlineModeUuid(playerName);
            if (OfflinePlayerData.hasDataFile(server, derived)) {
                uuid = derived;
            }
        }
        if (uuid == null) {
            src.sendFailure(tr("respawnpoints.cmd.unknown_or_not_bound", id));
            return 0;
        }
        // Raced login between resolve and edit: take the live path instead of the file.
        ServerPlayer raced = server.getPlayerList().getPlayer(uuid);
        if (raced != null) {
            return adminUnbind(src, id, raced);
        }
        if (DeathManager.adminUnbindOffline(server, uuid, id)) {
            final String name = playerDisplayName(server, uuid);
            src.sendSuccess(() -> tr("respawnpoints.cmd.unbound_admin", name, id), true);
            return 1;
        }
        src.sendFailure(tr("respawnpoints.cmd.unknown_or_not_bound", id));
        return 0;
    }

    private static int adminBind(CommandSourceStack src, String id, ServerPlayer target) {
        if (DeathManager.adminBind(target, id)) {
            src.sendSuccess(() -> tr("respawnpoints.cmd.bound_admin",
                    target.getScoreboardName(), id), true);
            return 1;
        }
        src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
        return 0;
    }

    private static int armInspect(CommandSourceStack src) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        DeathManager.armInspect(player);
        src.sendSuccess(() -> tr("respawnpoints.cmd.inspect_hint"), false);
        return 1;
    }

    /** Teleport to a warp by bare id: `admin tp warp <id>` (no colon to trip the parser). */
    private static int adminTpWarp(CommandSourceStack src, String warpId) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        var server = src.getServer();
        var warp = WarpStorage.get(server.overworld()).find(warpId);
        if (warp == null) {
            src.sendFailure(tr("respawnpoints.cmd.unknown_point", warpId));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level;
        try {
            level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(warp.dimension())));
        } catch (Exception e) {
            level = null;
        }
        if (level == null) {
            src.sendFailure(tr("respawnpoints.cmd.unknown_point", warpId));
            return 0;
        }
        BlockPos pos = warp.pos();
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot());
        return 1;
    }

    private static int adminTp(CommandSourceStack src, String id) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        var server = src.getServer();
        String dim;
        BlockPos pos;
        double yOff;
        if ("spawn".equals(id)) {
            dim = net.minecraft.world.level.Level.OVERWORLD.location().toString();
            pos = server.overworld().getSharedSpawnPos();
            yOff = 1.0;
        } else if ("neardeath".equals(id)) {
            src.sendFailure(tr("respawnpoints.cmd.tp_neardeath"));
            return 0;
        } else if (id.startsWith("warp:")) {
            var warp = WarpStorage.get(server.overworld()).find(id.substring("warp:".length()));
            if (warp == null) {
                src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
                return 0;
            }
            dim = warp.dimension();
            pos = warp.pos();
            yOff = 0.0;
        } else {
            SpawnRegistry.SpawnPointRecord record = null;
            try {
                record = SpawnRegistry.get(server.overworld())
                        .byId(java.util.UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
            }
            if (record == null) {
                src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
                return 0;
            }
            dim = record.pos().dimension().location().toString();
            pos = record.pos().pos();
            yOff = 1.0;
        }
        net.minecraft.server.level.ServerLevel level;
        try {
            level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(dim)));
        } catch (Exception e) {
            level = null;
        }
        if (level == null) {
            src.sendFailure(tr("respawnpoints.cmd.unknown_point", id));
            return 0;
        }
        player.teleportTo(level, pos.getX() + 0.5, pos.getY() + yOff, pos.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot());
        return 1;
    }

    private static int adminReload(CommandSourceStack src) {
        RespawnConfig.load(src.getServer().getServerDirectory());
        // Re-reading is not enough: push so every online player picks the new
        // lists/markers up at once instead of on their next death/login.
        for (ServerPlayer online : src.getServer().getPlayerList().getPlayers()) {
            DeathManager.pushUpdate(online);
        }
        src.sendSuccess(() -> tr("respawnpoints.cmd.config_reloaded",
                RespawnConfig.getBedLimit(), RespawnConfig.getNearDeathRadius(),
                RespawnConfig.getNearDeathMinDistance(), RespawnConfig.isShowSpawn(),
                RespawnConfig.isShowNearDeath()), true);
        return 1;
    }

    private static int resetSpawnName(CommandSourceStack src) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        PlayerRespawnData.setSpawnName(player, "");
        DeathManager.pushUpdate(player);
        src.sendSuccess(() -> tr("respawnpoints.cmd.spawn_reset"), false);
        return 1;
    }

    private static int renameSpawn(CommandSourceStack src, String name) {
        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 32) {
            src.sendFailure(tr("respawnpoints.cmd.name_1_32"));
            return 0;
        }
        PlayerRespawnData.setSpawnName(player, clean);
        DeathManager.pushUpdate(player);
        src.sendSuccess(() -> tr("respawnpoints.cmd.spawn_renamed", clean), false);
        return 1;
    }

    private static int setSpawnHidden(CommandSourceStack src, boolean hidden) {        ServerPlayer player = requirePlayer(src);
        if (player == null) {
            return 0;
        }
        PlayerRespawnData.setSpawnHidden(player, hidden);
        DeathManager.pushUpdate(player);
        src.sendSuccess(() -> tr(hidden
                ? "respawnpoints.cmd.spawn_hidden" : "respawnpoints.cmd.spawn_shown"), false);
        return 1;
    }
}
