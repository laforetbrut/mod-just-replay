package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.monster.Ravager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Attack animation counter of ravager actors, set from the play head because their tick never runs.
 *
 * @author vyrriox
 */
@Mixin(Ravager.class)
public interface RavagerAccessor {
    @Accessor("attackTick")
    void justreplay$setAttackAnimation(int value);
}
