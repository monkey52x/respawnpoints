package com.vlogf.respawnpoints.model;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One respawn option, as transferred to the client.
 * For NEAR_DEATH the exact spot is concealed: pos carries the death position
 * (for distance display) and the server picks the real spot on selection.
 * Charges: anchor charge count, -1 when not applicable.
 * Cooldown seconds: remaining near-death cooldown, -1 when none, 0 when ready.
 */
public record RespawnEntry(
        String id,
        RespawnPointsType type,
        String name,
        String symbol,
        int color,
        String dimension,
        BlockPos pos,
        boolean concealed,
        int charges,
        int cooldownSec) {

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeByte(type.ordinal());
        buf.writeUtf(name);
        buf.writeUtf(symbol);
        buf.writeInt(color);
        buf.writeUtf(dimension);
        buf.writeBlockPos(pos);
        buf.writeBoolean(concealed);
        buf.writeVarInt(charges);
        buf.writeVarInt(cooldownSec);
    }

    public static RespawnEntry read(RegistryFriendlyByteBuf buf) {
        String id = buf.readUtf();
        int ordinal = buf.readByte();
        RespawnPointsType[] values = RespawnPointsType.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new io.netty.handler.codec.DecoderException("Unknown RespawnPointsType ordinal: " + ordinal);
        }
        RespawnPointsType type = values[ordinal];
        String name = buf.readUtf();
        String symbol = buf.readUtf();
        int color = buf.readInt();
        String dimension = buf.readUtf();
        BlockPos pos = buf.readBlockPos();
        boolean concealed = buf.readBoolean();
        int charges = buf.readVarInt();
        int cooldownSec = buf.readVarInt();
        return new RespawnEntry(id, type, name, symbol, color, dimension, pos, concealed, charges, cooldownSec);
    }
}
