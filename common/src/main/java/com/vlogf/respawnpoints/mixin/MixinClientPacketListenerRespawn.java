package com.vlogf.respawnpoints.mixin;

import com.vlogf.respawnpoints.Constants;
import com.vlogf.respawnpoints.client.ClientReadiness;
import com.vlogf.respawnpoints.client.PendingRespawn;
import com.vlogf.respawnpoints.client.screen.RespawnScreenFactory;
import com.vlogf.respawnpoints.mixin.DeathScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the vanilla "Respawn" click (PERFORM_RESPAWN packet):
 * <ul>
 * <li>offer present — open our map screen instead of respawning;</li>
 * <li>no offer yet on our server — request state, wait for it, open on arrival;</li>
 * <li>otherwise (hardcore, foreign server, wait expired) — vanilla passthrough.</li>
 * </ul>
 * Both the main death screen and its title-confirm screen
 * ({@code DeathScreen$TitleConfirmScreen}, same packet) are covered; the confirm
 * screen exposes no hardcore flag, so it is intercepted only with an offer.
 * Client-only.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientPacketListenerRespawn {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void respawnpoints$interceptRespawn(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundClientCommandPacket cmd)
                || cmd.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;
        boolean isConfirm = !(current instanceof DeathScreen)
                && current instanceof DeathScreen.TitleConfirmScreen;
        if (!(current instanceof DeathScreen) && !isConfirm) {
            return;
        }
        if (isConfirm) {
            // The confirm screen exposes no hardcore flag or death message.
            if (PendingRespawn.hasPending()) {
                Screen replacement = RespawnScreenFactory.createFresh();
                if (replacement != null) {
                    minecraft.setScreen(replacement);
                    ci.cancel();
                }
                return;
            }
            if (PendingRespawn.hasEmptyOffer()) {
                // Server answered: nothing to choose — vanilla at once, silently.
                return;
            }
            // Same waiting flow as the main screen: our server, offer not here yet.
            if (!ClientReadiness.hasHello()) {
                return;
            }
            if (ClientReadiness.consumeExpiredWait()) {
                return;
            }
            if (!ClientReadiness.isWaiting()) {
                ClientReadiness.onDeathScreenOpened();
                ClientReadiness.beginWaiting();
            }
            ci.cancel();
            return;
        }
        DeathScreenAccessor access = (DeathScreenAccessor) current;
        if (access.respawnpoints$isHardcore()) {
            return;
        }
        if (PendingRespawn.hasPending()) {
            Screen replacement = RespawnScreenFactory.create(
                    access.respawnpoints$getCauseOfDeath(), false);
            if (replacement != null) {
                minecraft.setScreen(replacement);
                ci.cancel();
            }
            return;
        }
        if (PendingRespawn.hasEmptyOffer()) {
            // Server answered: nothing to choose — vanilla at once, silently.
            return;
        }
        if (!ClientReadiness.hasHello()) {
            return;
        }
        if (ClientReadiness.consumeExpiredWait()) {
            // Server never answered: explicit second click falls through to vanilla.
            Constants.LOG.warn("Respawn offer wait expired, falling through to vanilla respawn");
            return;
        }
        if (!ClientReadiness.isWaiting()) {
            ClientReadiness.onDeathScreenOpened();
            ClientReadiness.beginWaiting();
        }
        ci.cancel();
    }
}
