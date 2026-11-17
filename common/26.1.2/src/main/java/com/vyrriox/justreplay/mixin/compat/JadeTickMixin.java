package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.JadeCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jade draws its tooltip from a render event that the clean HUD setting does not reach, and its client tick ray traces
 * from the replay camera and asks the replay host about what it sees. While a replay hides the HUD (clean HUD on, or the
 * editor open), the tick clears the tooltip and stops there. Optional: does nothing when Jade is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.WailaTickHandler", remap = false)
public abstract class JadeTickMixin {
    @Inject(method = "tickClient()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$hideInReplay(CallbackInfo ci) {
        if (JadeCompat.tooltipHidden()) {
            JadeCompat.clearTooltip(this);
            ci.cancel();
        }
    }
}
