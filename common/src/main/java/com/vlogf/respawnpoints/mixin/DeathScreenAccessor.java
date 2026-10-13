package com.vlogf.respawnpoints.mixin;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Client-only. Registered in the "client" section of the mixins config. */
@Mixin(DeathScreen.class)
public interface DeathScreenAccessor {
    @Accessor("causeOfDeath")
    Component respawnpoints$getCauseOfDeath();

    @Accessor("hardcore")
    boolean respawnpoints$isHardcore();
}
