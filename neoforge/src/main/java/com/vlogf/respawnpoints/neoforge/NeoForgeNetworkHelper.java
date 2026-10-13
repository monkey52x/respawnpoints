package com.vlogf.respawnpoints.neoforge;

import com.vlogf.respawnpoints.client.ClientHandlers;
import com.vlogf.respawnpoints.network.ClientReadyPacket;
import com.vlogf.respawnpoints.network.DeletePointPacket;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.network.RenamePointPacket;
import com.vlogf.respawnpoints.network.RespawnListPacket;
import com.vlogf.respawnpoints.network.SelectRespawnPacket;
import com.vlogf.respawnpoints.network.ServerHelloPacket;
import com.vlogf.respawnpoints.platform.services.INetworkHelper;
import com.vlogf.respawnpoints.server.ServerHandlers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class NeoForgeNetworkHelper implements INetworkHelper {
    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("respawnpoints");
            registrar.playToClient(RespawnListPacket.TYPE, RespawnListPacket.CODEC,
                    (packet, context) -> context.enqueueWork(() -> ClientHandlers.onRespawnList(packet)));
            registrar.playToClient(ServerHelloPacket.TYPE, ServerHelloPacket.CODEC,
                    (packet, context) -> context.enqueueWork(() -> ClientHandlers.onHello(packet)));
            registrar.playToServer(SelectRespawnPacket.TYPE, SelectRespawnPacket.CODEC,
                    NeoForgeNetworkHelper::handleSelect);
            registrar.playToServer(ClientReadyPacket.TYPE, ClientReadyPacket.CODEC,
                    (packet, context) -> context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer player) {
                            ServerHandlers.onReady(player);
                        }
                    }));
            registrar.playToServer(RenamePointPacket.TYPE, RenamePointPacket.CODEC,
                    (packet, context) -> context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer player) {
                            ServerHandlers.onRename(player, packet);
                        }
                    }));
            registrar.playToServer(RecolorPointPacket.TYPE, RecolorPointPacket.CODEC,
                    (packet, context) -> context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer player) {
                            ServerHandlers.onRecolor(player, packet);
                        }
                    }));
            registrar.playToServer(DeletePointPacket.TYPE, DeletePointPacket.CODEC,
                    (packet, context) -> context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer player) {
                            ServerHandlers.onDelete(player, packet);
                        }
                    }));
        });
    }

    private static void handleSelect(SelectRespawnPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerHandlers.onSelect(player, packet);
            }
        });
    }

    @Override
    public void sendToServer(CustomPacketPayload packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
