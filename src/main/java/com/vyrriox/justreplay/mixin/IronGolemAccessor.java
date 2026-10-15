package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.animal.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Attack animation counter of iron golem actors, set from the play head because their tick never runs.
 *
 * @author vyrriox
 */
@Mixin(IronGolem.class)
public interface IronGolemAccessor {
    @Accessor("attackAnimationTick")
    void justreplay$setAttackAnimation(int value);
}
