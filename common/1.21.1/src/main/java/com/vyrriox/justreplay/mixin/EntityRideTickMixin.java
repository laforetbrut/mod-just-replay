package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips only the tick of an actor riding something: it stays positioned on its vehicle.
 *
 * @author vyrriox
 */
@Mixin(Entity.class)
public abstract class EntityRideTickMixin {
    @Redirect(method = "rideTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void justreplay$tick(Entity entity) {
        if (!ClientHooks.cancelsEntityTick(entity)) {
            entity.tick();
        }
    }
}
