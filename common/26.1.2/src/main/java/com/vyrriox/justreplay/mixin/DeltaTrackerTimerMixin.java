package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.export.VideoExporter;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During a video export the game clock follows the export instead of the wall clock: whole ticks during the
 * pre-roll, then exactly one frame of video time per rendered frame, with the partial tick set from the frame
 * number so frame n always shows the same replay time.
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
        VideoExporter.TimerStep step = VideoExporter.nextTimerStep();
        if (step == null) {
            return;
        }
        this.lastMs = time;
        this.deltaTicks = step.deltaTicks();
        this.deltaTickResidual = step.partialTick();
        cir.setReturnValue(step.ticks());
    }
}
