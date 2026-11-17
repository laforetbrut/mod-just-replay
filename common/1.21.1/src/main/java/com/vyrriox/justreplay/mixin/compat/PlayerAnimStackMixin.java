package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * The player animation library (Emotecraft, Better Combat and others) ticks a player's animation stack from the player
 * tick, and players keep ticking while the replay is paused. During a replay the stack follows the replay clock
 * instead: it holds while paused and runs once per recorded tick above x1. Optional: does nothing when the library is
 * absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "dev.kosmx.playerAnim.api.layered.AnimationStack", remap = false)
public abstract class PlayerAnimStackMixin {
    @Unique
    private static boolean justreplay$extraTicks;
    @Unique
    private static Method justreplay$tick;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$followReplayClock(CallbackInfo ci) {
        if (justreplay$extraTicks) {
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
