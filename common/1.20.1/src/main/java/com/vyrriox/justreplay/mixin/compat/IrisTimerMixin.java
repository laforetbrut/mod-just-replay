package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.ReplayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris feeds shader time from the wall clock, so shader water and wind keep moving while a replay is paused and race in
 * an export. During a replay the timer is set from the replay clock, which makes a given tick look the same in every
 * export. The frame time stays above zero for the packs that divide by it. The frame counter is left alone: temporal
 * anti-aliasing needs it to advance. Optional: does nothing when Iris is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class IrisTimerMixin {
    @Unique
    private static final float justreplay$MIN_FRAME_TIME = 0.0001F;

    @Shadow
    private float frameTimeCounter;
    @Shadow
    private float lastFrameTime;
    @Unique
    private double justreplay$lastClock = -1;

    @Inject(method = "beginFrame(J)V", at = @At("TAIL"), require = 0, remap = false)
    private void justreplay$replayClock(long frameStart, CallbackInfo ci) {
        double clock = ReplayCompat.frameClockTicks();
        if (clock >= 0) {
            double delta = justreplay$lastClock < 0 ? 0 : Math.max(0, clock - justreplay$lastClock);
            lastFrameTime = Math.max((float) (delta / 20.0), justreplay$MIN_FRAME_TIME);
            frameTimeCounter = (float) ((clock / 20.0) % 3600.0);
        }
        justreplay$lastClock = clock;
    }
}
