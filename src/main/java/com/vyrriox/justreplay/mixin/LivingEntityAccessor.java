package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Animation state of living actors, whose own tick never runs: the actor animator sets it directly.
 *
 * @author vyrriox
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("swimAmount")
    void justreplay$setSwimAmount(float value);

    @Accessor("swimAmountO")
    void justreplay$setSwimAmountO(float value);

    @Accessor("useItemRemaining")
    void justreplay$setUseItemRemaining(int value);

    @Accessor("fallFlyTicks")
    void justreplay$setFallFlyTicks(int value);

    @Invoker("setLivingEntityFlag")
    void justreplay$setLivingEntityFlag(int key, boolean value);
}
