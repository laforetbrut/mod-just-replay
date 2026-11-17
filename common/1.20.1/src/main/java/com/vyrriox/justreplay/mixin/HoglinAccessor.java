package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.monster.hoglin.Hoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Attack animation counter of hoglin actors, set from the play head because their tick never runs.
 *
 * @author vyrriox
 */
@Mixin(Hoglin.class)
public interface HoglinAccessor {
    @Accessor("attackAnimationRemainingTicks")
    void justreplay$setAttackAnimation(int value);
}
