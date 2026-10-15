package com.vyrriox.justreplay.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Sets the shared entity flags (fall flying) of actors that have no public setter for them.
 *
 * @author vyrriox
 */
@Mixin(Entity.class)
public interface EntityInvoker {
    @Invoker("setSharedFlag")
    void justreplay$setSharedFlag(int flag, boolean set);
}
