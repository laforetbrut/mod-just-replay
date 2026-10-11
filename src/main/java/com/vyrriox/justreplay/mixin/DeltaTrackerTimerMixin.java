package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.export.VideoExporter;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During a video export, every rendered frame advances the game by exactly one frame of video time instead of
 * the elapsed wall clock time.
 *
 * @author vyrriox
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
    @Shadow
    private float deltaTicks;
    @Shadow
    private float deltaTickResidual;
    @Shadow
    private long lastMs;

    @Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
    private void justreplay$fixedStep(long time, CallbackInfoReturnable<Integer> cir) {
        if (!VideoExporter.isFixedStep()) {
            return;
        }
        this.lastMs = time;
        this.deltaTicks = VideoExporter.ticksPerFrame();
        this.deltaTickResidual += this.deltaTicks;
        int ticks = (int) this.deltaTickResidual;
        this.deltaTickResidual -= ticks;
        cir.setReturnValue(ticks);
    }
}
