package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: delete one of the player's beds/anchors from tracking. */
public record DeletePointPacket(String pointId) implements CustomPacketPayload {
    public static final Type<DeletePointPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "delete_point"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePointPacket> CODEC =
            StreamCodec.ofMember(DeletePointPacket::write, DeletePointPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(pointId);
    }

    private static DeletePointPacket read(RegistryFriendlyByteBuf buf) {
        return new DeletePointPacket(buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
