package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.monster.Zoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Attack animation counter of zoglin actors, set from the play head because their tick never runs.
 *
 * @author vyrriox
 */
@Mixin(Zoglin.class)
public interface ZoglinAccessor {
    @Accessor("attackAnimationRemainingTicks")
    void justreplay$setAttackAnimation(int value);
}
