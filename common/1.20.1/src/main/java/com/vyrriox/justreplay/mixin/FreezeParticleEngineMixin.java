package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles hold in a frozen level.
 *
 * @author vyrriox
 */
@Mixin(ParticleEngine.class)
public abstract class FreezeParticleEngineMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void justreplay$hold(CallbackInfo ci) {
        if (TimeControl.isFrozen()) {
            ci.cancel();
        }
    }
}
