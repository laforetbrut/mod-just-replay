package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A frozen level stops its entities (players excepted), its clock and its ambient block effects.
 *
 * @author vyrriox
 */
@Mixin(ClientLevel.class)
public abstract class FreezeClientLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void justreplay$holdEntity(Entity entity, CallbackInfo ci) {
        if (TimeControl.isEntityFrozen(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void justreplay$holdTime(CallbackInfo ci) {
        if (TimeControl.isFrozen((ClientLevel) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
    private void justreplay$holdAmbient(int x, int y, int z, CallbackInfo ci) {
        if (TimeControl.isFrozen((ClientLevel) (Object) this)) {
            ci.cancel();
        }
    }
}
