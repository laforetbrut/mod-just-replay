package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.Integrations;
import com.vyrriox.justreplay.compat.DropTheBeamCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drop the Beam's estimate of the server clock, which its music, light shows and screens follow. During a replay it
 * answers with the recorded server instant of the replay time (see {@code DropTheBeamCompat}), and it neither pings
 * the replay host nor takes its answers. Optional: does nothing when Drop the Beam is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "thefricadelle.dropthebeam.client.audio.ServerClock", remap = false)
public abstract class DropTheBeamClockMixin {
    @Inject(method = "serverNanos", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$replayClock(long clientNanos, CallbackInfoReturnable<Long> cir) {
        if (!Integrations.DROP_THE_BEAM.on()) {
            return;
        }
        try {
            long replay = DropTheBeamCompat.serverNanos(clientNanos);
            if (replay != Long.MIN_VALUE) {
                cir.setReturnValue(replay);
            }
        } catch (Throwable t) {
            Integrations.DROP_THE_BEAM.fail("ServerClock.serverNanos", t);
        }
    }

    @Inject(method = "offset", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$replayOffset(CallbackInfoReturnable<Long> cir) {
        if (!Integrations.DROP_THE_BEAM.on()) {
            return;
        }
        try {
            long now = System.nanoTime();
            long replay = DropTheBeamCompat.serverNanos(now);
            if (replay != Long.MIN_VALUE) {
                cir.setReturnValue(replay - now);
            }
        } catch (Throwable t) {
            Integrations.DROP_THE_BEAM.fail("ServerClock.offset", t);
        }
    }

    @Inject(method = "calibrated", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$replayCalibrated(CallbackInfoReturnable<Boolean> cir) {
        if (!Integrations.DROP_THE_BEAM.on()) {
            return;
        }
        try {
            Boolean replay = DropTheBeamCompat.calibrated();
            if (replay != null) {
                cir.setReturnValue(replay);
            }
        } catch (Throwable t) {
            Integrations.DROP_THE_BEAM.fail("ServerClock.calibrated", t);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$noPingInReplay(CallbackInfo ci) {
        if (!Integrations.DROP_THE_BEAM.on()) {
            return;
        }
        try {
            if (DropTheBeamCompat.isReplaying()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Integrations.DROP_THE_BEAM.fail("ServerClock.tick", t);
        }
    }

    @Inject(method = "pong", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$noPongInReplay(CallbackInfo ci) {
        if (!Integrations.DROP_THE_BEAM.on()) {
            return;
        }
        try {
            if (DropTheBeamCompat.isReplaying()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Integrations.DROP_THE_BEAM.fail("ServerClock.pong", t);
        }
    }
}
