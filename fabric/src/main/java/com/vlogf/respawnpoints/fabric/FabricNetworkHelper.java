package com.vlogf.respawnpoints.fabric;

import com.vlogf.respawnpoints.client.ClientHandlers;
import com.vlogf.respawnpoints.network.ClientReadyPacket;
import com.vlogf.respawnpoints.network.DeletePointPacket;
import com.vlogf.respawnpoints.network.RecolorPointPacket;
import com.vlogf.respawnpoints.network.RenamePointPacket;
import com.vlogf.respawnpoints.network.RespawnListPacket;
import com.vlogf.respawnpoints.network.SelectRespawnPacket;
import com.vlogf.respawnpoints.platform.services.INetworkHelper;
import com.vlogf.respawnpoints.server.ServerHandlers;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public class FabricNetworkHelper implements INetworkHelper {
    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(RespawnListPacket.TYPE, RespawnListPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectRespawnPacket.TYPE, SelectRespawnPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(ClientReadyPacket.TYPE, ClientReadyPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(RenamePointPacket.TYPE, RenamePointPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(RecolorPointPacket.TYPE, RecolorPointPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(DeletePointPacket.TYPE, DeletePointPacket.CODEC);
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(SelectRespawnPacket.TYPE,
                (packet, context) -> context.server().execute(() ->
                        ServerHandlers.onSelect(context.player(), packet)));
        ServerPlayNetworking.registerGlobalReceiver(RenamePointPacket.TYPE,
                (packet, context) -> context.server().execute(() ->
                        ServerHandlers.onRename(context.player(), packet)));
        ServerPlayNetworking.registerGlobalReceiver(RecolorPointPacket.TYPE,
                (packet, context) -> context.server().execute(() ->
                        ServerHandlers.onRecolor(context.player(), packet)));
        ServerPlayNetworking.registerGlobalReceiver(DeletePointPacket.TYPE,
                (packet, context) -> context.server().execute(() ->
                        ServerHandlers.onDelete(context.player(), packet)));
        ServerPlayNetworking.registerGlobalReceiver(ClientReadyPacket.TYPE,
                (packet, context) -> context.server().execute(() ->
                        ServerHandlers.onReady(context.player())));
    }

    public static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(RespawnListPacket.TYPE,
                (packet, context) -> context.client().execute(() ->
                        ClientHandlers.onRespawnList(packet)));
    }

    @Override
    public void sendToServer(CustomPacketPayload packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        ServerPlayNetworking.send(player, packet);
    }
}
