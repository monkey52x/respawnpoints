package com.vlogf.respawnpoints.mixin;

import com.vlogf.respawnpoints.client.ClientReadiness;
import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the vanilla death screen opens on our server before the client got
 * ready, confirm readiness right away so the offer arrives before the click.
 * Client-only.
 */
@Mixin(DeathScreen.class)
public class MixinDeathScreenOpened {
    @Inject(method = "init", at = @At("TAIL"))
    private void respawnpoints$confirmReady(CallbackInfo ci) {
        ClientReadiness.onDeathScreenOpened();
    }
}
