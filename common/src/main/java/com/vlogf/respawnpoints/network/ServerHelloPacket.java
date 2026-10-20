package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Server -> client on login: stable server identity for the per-server map choice. */
public record ServerHelloPacket(UUID serverId) implements CustomPacketPayload {
    public static final Type<ServerHelloPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_hello"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerHelloPacket> CODEC =
            StreamCodec.ofMember(ServerHelloPacket::write, ServerHelloPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(serverId);
    }

    private static ServerHelloPacket read(RegistryFriendlyByteBuf buf) {
        return new ServerHelloPacket(buf.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}