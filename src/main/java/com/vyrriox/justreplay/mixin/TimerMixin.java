package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.TimeControl;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drives the game clock. During a video export it follows the export instead of the wall clock: whole ticks during
 * the pre-roll, then exactly one frame of video time per rendered frame, with the partial tick set from the frame
 * number so frame n always shows the same replay time. Otherwise a slowed replay stretches the tick.
 *
 * @author vyrriox
 */
@Mixin(Timer.class)
public abstract class TimerMixin {
    @Shadow
    public float partialTick;
    @Shadow
    public float tickDelta;
    @Shadow
    private long lastMs;

    @Inject(method = "advanceTime", at = @At("HEAD"), cancellable = true)
    private void justreplay$advance(long time, CallbackInfoReturnable<Integer> cir) {
        VideoExporter.TimerStep step = VideoExporter.nextTimerStep();
        if (step != null) {
            this.lastMs = time;
            this.tickDelta = step.deltaTicks();
            this.partialTick = step.partialTick();
            cir.setReturnValue(step.ticks());
            return;
        }
        float msPerTick = TimeControl.millisPerTick();
        if (msPerTick != TimeControl.DEFAULT_MS_PER_TICK) {
            this.tickDelta = (float) (time - this.lastMs) / msPerTick;
            this.lastMs = time;
            this.partialTick += this.tickDelta;
            int ticks = (int) this.partialTick;
            this.partialTick -= (float) ticks;
            cir.setReturnValue(ticks);
        }
    }
}
