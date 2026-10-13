package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: the client finished loading (world + map session ready)
 * and asks for the full marker/offer state. The server sends nothing
 * marker-related before this (pull model).
 */
public record ClientReadyPacket() implements CustomPacketPayload {
    public static final Type<ClientReadyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "client_ready"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientReadyPacket> CODEC =
            StreamCodec.ofMember(ClientReadyPacket::write, ClientReadyPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
    }

    private static ClientReadyPacket read(RegistryFriendlyByteBuf buf) {
        return new ClientReadyPacket();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
