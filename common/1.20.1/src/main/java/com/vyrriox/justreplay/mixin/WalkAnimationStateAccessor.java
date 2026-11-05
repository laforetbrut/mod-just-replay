package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Drives the limb swing of actors from their path speed, once per tick, whatever their own tick did.
 *
 * @author vyrriox
 */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
    @Accessor("speedOld")
    void justreplay$setSpeedOld(float value);

    @Accessor("speed")
    void justreplay$setSpeed(float value);

    @Accessor("position")
    void justreplay$setPosition(float value);
}
