package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.client.hook.ClientHooks;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the replay drop a sound or play a scaled copy of it. The copy goes through this method again, past the hook.
 *
 * @author vyrriox
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineHooksMixin {
    @Shadow
    private boolean loaded;
    @Unique
    private boolean justreplay$replaying;

    @Shadow
    public abstract void play(SoundInstance sound);

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void justreplay$play(SoundInstance sound, CallbackInfo ci) {
        if (!this.loaded || this.justreplay$replaying) {
            return;
        }
        SoundInstance result = ClientHooks.filterSound(sound);
        if (result == sound) {
            return;
        }
        ci.cancel();
        if (result != null) {
            this.justreplay$replaying = true;
            try {
                this.play(result);
            } finally {
                this.justreplay$replaying = false;
            }
        }
    }
}
