package com.vlogf.respawnpoints.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Loader-provided networking. Implementations must not be touched from the wrong physical side. */
public interface INetworkHelper {
    /** Client side only. */
    void sendToServer(CustomPacketPayload packet);

    /** Server side only. */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload packet);
}
