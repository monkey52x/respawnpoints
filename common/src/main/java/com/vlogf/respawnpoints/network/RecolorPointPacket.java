package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: recolor one of the player's beds/anchors (map-side edit). */
public record RecolorPointPacket(String pointId, int color) implements CustomPacketPayload {
    public static final Type<RecolorPointPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recolor_point"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecolorPointPacket> CODEC =
            StreamCodec.ofMember(RecolorPointPacket::write, RecolorPointPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(pointId);
        buf.writeInt(color);
    }

    private static RecolorPointPacket read(RegistryFriendlyByteBuf buf) {
        return new RecolorPointPacket(buf.readUtf(), buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
