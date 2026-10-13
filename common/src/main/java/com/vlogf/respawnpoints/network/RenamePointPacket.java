package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: rename one of the player's beds/anchors. */
public record RenamePointPacket(String pointId, String name) implements CustomPacketPayload {
    public static final Type<RenamePointPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "rename_point"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RenamePointPacket> CODEC =
            StreamCodec.ofMember(RenamePointPacket::write, RenamePointPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(pointId);
        buf.writeUtf(name);
    }

    private static RenamePointPacket read(RegistryFriendlyByteBuf buf) {
        return new RenamePointPacket(buf.readUtf(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
