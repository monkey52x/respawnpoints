package com.vlogf.respawnpoints.network;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.model.RespawnEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: respawn options. {@code liveSync} = marker-only update while alive.
 * {@code offerId} = death-offer generation (0 for live syncs); lets the client
 * tell death #1 from death #2 when a new offer lands on a frozen screen. */
public record RespawnListPacket(List<RespawnEntry> entries, boolean liveSync, long offerId) implements CustomPacketPayload {
    public RespawnListPacket(List<RespawnEntry> entries, boolean liveSync) {
        this(entries, liveSync, 0L);
    }
    public static final Type<RespawnListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "respawn_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RespawnListPacket> CODEC =
            StreamCodec.ofMember(RespawnListPacket::write, RespawnListPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (RespawnEntry entry : entries) {
            entry.write(buf);
        }
        buf.writeBoolean(liveSync);
        buf.writeVarLong(offerId);
    }

    private static RespawnListPacket read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<RespawnEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(RespawnEntry.read(buf));
        }
        boolean live = buf.readBoolean();
        long id = buf.readVarLong(); // Always present in protocol; 0 = live sync / no offer
        return new RespawnListPacket(List.copyOf(entries), live, id);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}