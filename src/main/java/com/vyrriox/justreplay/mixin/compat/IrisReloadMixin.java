package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.Integrations;
import com.vyrriox.justreplay.compat.ShaderSettle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A shader pack reload (new pack, changed options) starts the settling window of {@link ShaderSettle}: in a paused
 * replay the light of the pack would otherwise take ages to come back. Optional: does nothing when Iris is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisReloadMixin {
    @Inject(method = "reload()V", at = @At("TAIL"), require = 0, remap = false)
    private static void justreplay$settle(CallbackInfo ci) {
        if (!Integrations.IRIS.on()) {
            return;
        }
        try {
            ShaderSettle.reloaded(System.nanoTime());
        } catch (Throwable t) {
            Integrations.IRIS.fail("Iris.reload", t);
        }
    }
}
