package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.DropTheBeamCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop the Beam's own audio output thread, feeding each voice's OpenAL source. During a replay a voice is fed silence
 * (as when the game is paused) while the replay is paused, away from x1 or its game sounds are muted, and its volume
 * follows the project's game sound volume. During an export the thread renders nothing, so the export can render the
 * voices offline without ever running a voice's renderer on two threads. Optional: does nothing when Drop the Beam is
 * absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "thefricadelle.dropthebeam.client.audio.AudioOutput", remap = false)
public abstract class DropTheBeamAudioMixin {
    @Shadow(remap = false)
    private static boolean pausedPass;

    @Inject(method = "feed", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void justreplay$enterFeed(CallbackInfo ci) {
        if (!DropTheBeamCompat.enterFeed()) {
            ci.cancel();
            return;
        }
        if (DropTheBeamCompat.audioMuted()) {
            pausedPass = true;
        }
    }

    @Inject(method = "feed", at = @At("RETURN"), require = 0, remap = false)
    private static void justreplay$exitFeed(CallbackInfo ci) {
        DropTheBeamCompat.exitFeed();
    }

    @ModifyVariable(method = "feed", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
    private static float justreplay$gameVolume(float category) {
        return DropTheBeamCompat.liveCategory(category);
    }
}
