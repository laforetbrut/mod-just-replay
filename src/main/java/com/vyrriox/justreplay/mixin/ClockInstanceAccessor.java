package com.vyrriox.justreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The partial tick of one clock of the client clock manager, see {@link com.vyrriox.justreplay.replay.ClockRates}.
 *
 * @author vyrriox
 */
@Mixin(targets = "net.minecraft.client.ClientClockManager$ClockInstance")
public interface ClockInstanceAccessor {
    @Accessor("partialTick")
    float justreplay$getPartialTick();
}
