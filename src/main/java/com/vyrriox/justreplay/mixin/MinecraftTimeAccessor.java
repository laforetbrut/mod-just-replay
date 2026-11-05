package com.vyrriox.justreplay.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the partial tick the game holds while it is paused.
 *
 * @author vyrriox
 */
@Mixin(Minecraft.class)
public interface MinecraftTimeAccessor {
    @Accessor("pausePartialTick")
    float justreplay$pausePartialTick();
}
