package com.vlogf.respawnpoints.client;

import com.vlogf.respawnpoints.client.screen.RenamePointScreen;
import com.vlogf.respawnpoints.model.RespawnEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-only: shift + empty-hand right-click on a tracked bed/anchor opens rename.
 * The entry (with its server-side UUID) is matched by coordinates in the live list.
 * Called from loader interaction hooks; never from server code.
 */
public final class ClientBedActions {
    private ClientBedActions() {
    }

    /** @return true if the interaction was consumed by the rename flow. */
    public static boolean handleSneakUse(Level level, LocalPlayer player, BlockPos pos) {
        if (level == null || player == null || !player.isShiftKeyDown()
                || !player.getMainHandItem().isEmpty()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        BlockPos keyPos;
        if (state.getBlock() instanceof BedBlock) {
            keyPos = com.vlogf.respawnpoints.server.SpawnRegistry.headPos(level, pos);
            if (keyPos == null) {
                return false;
            }
        } else if (state.getBlock() instanceof RespawnAnchorBlock) {
            keyPos = pos;
        } else {
            return false;
        }
        String dim = level.dimension().location().toString();
        RespawnEntry match = null;
        for (RespawnEntry known : PendingRespawn.entries()) {
            if (known.dimension().equals(dim) && known.pos().equals(keyPos)
                    && (known.type() == com.vlogf.respawnpoints.model.RespawnPointsType.BED
                    || known.type() == com.vlogf.respawnpoints.model.RespawnPointsType.ANCHOR)) {
                match = known;
                break;
            }
        }
        if (match == null) {
            return false;
        }
        Minecraft.getInstance().setScreen(new RenamePointScreen(match));
        return true;
    }
}
