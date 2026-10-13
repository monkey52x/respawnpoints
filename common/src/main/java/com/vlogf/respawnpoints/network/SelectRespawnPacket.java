package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: the player chose a respawn point. */
public record SelectRespawnPacket(String pointId) implements CustomPacketPayload {
    public static final Type<SelectRespawnPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "select_respawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelectRespawnPacket> CODEC =
            StreamCodec.ofMember(SelectRespawnPacket::write, SelectRespawnPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(pointId);
    }

    private static SelectRespawnPacket read(RegistryFriendlyByteBuf buf) {
        return new SelectRespawnPacket(buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
