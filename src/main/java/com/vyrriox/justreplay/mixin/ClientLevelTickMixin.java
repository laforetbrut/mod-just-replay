package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips only the tick of an actor: its old position and its passengers still move on.
 *
 * @author vyrriox
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelTickMixin {
    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void justreplay$tick(Entity entity) {
        if (!ClientHooks.cancelsEntityTick(entity)) {
            entity.tick();
        }
    }
}
