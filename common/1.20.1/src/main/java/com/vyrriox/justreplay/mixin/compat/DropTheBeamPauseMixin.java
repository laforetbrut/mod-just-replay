package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.DropTheBeamCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop the Beam stops its songs and videos with a paused single-player game and, on resume, moves every video by the
 * length of the pause and asks the integrated server to do the same. A replay runs on an integrated host, but its
 * clock is the replay's (paused and resumed by the replay itself): during a replay none of this happens. Optional:
 * does nothing when Drop the Beam is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = {"thefricadelle.dropthebeam.client.display.video.VideoPlayers", "thefricadelle.dropthebeam.client.audio.MusicClient"},
        remap = false)
public abstract class DropTheBeamPauseMixin {
    @Inject(method = "followGamePause", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$replayOwnsThePause(CallbackInfo ci) {
        if (DropTheBeamCompat.isReplaying()) {
            ci.cancel();
        }
    }
}
