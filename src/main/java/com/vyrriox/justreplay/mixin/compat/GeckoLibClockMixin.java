package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GeckoLib animates block entities, items, armor and animated textures on the wall clock. During a replay they follow
 * the replay clock instead: they hold while paused, follow the speed and are exact per export frame. Client thread
 * only. Optional: does nothing when GeckoLib is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.util.RenderUtils", remap = false)
public abstract class GeckoLibClockMixin {
    @Inject(method = "getCurrentTick()D", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$replayClock(CallbackInfoReturnable<Double> cir) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        double ticks = ReplayCompat.monotonicAnimTicks();
        if (ticks >= 0) {
            cir.setReturnValue(ticks);
        }
    }
}
