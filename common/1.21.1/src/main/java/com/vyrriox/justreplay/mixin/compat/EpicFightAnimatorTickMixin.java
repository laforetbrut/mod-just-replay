package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Epic Fight advances an entity's animations from its client animator tick, run by the entity tick, and entities keep
 * ticking while the replay is paused. During a replay the animator follows the replay clock instead: it holds while
 * paused and runs once per recorded tick above x1. Client thread only. Optional: does nothing without Epic Fight.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.api.client.animation.ClientAnimator", remap = false)
public abstract class EpicFightAnimatorTickMixin {
    @Unique
    private static boolean justreplay$extraTicks;
    @Unique
    private static Method justreplay$tick;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$followReplayClock(CallbackInfo ci) {
        if (justreplay$extraTicks || !Minecraft.getInstance().isSameThread()) {
            return;
        }
        int steps = ReplayCompat.animationSteps();
        if (steps < 0) {
            return;
        }
        if (steps == 0) {
            ci.cancel();
            return;
        }
        if (steps > 1) {
            justreplay$extraTicks = true;
            try {
                if (justreplay$tick == null) {
                    justreplay$tick = getClass().getMethod("tick");
                }
                for (int i = 1; i < steps; i++) {
                    justreplay$tick.invoke(this);
                }
            } catch (ReflectiveOperationException ignored) {
                // The normal tick still runs.
            } finally {
                justreplay$extraTicks = false;
            }
        }
    }
}
