package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.export.VideoExporter;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a running export that a sound was stopped (record taken out, stop-sound packet): sounds are muted during an
 * export, so the sound engine never sees them, but the offline track must end them on that frame.
 *
 * @author vyrriox
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerStopMixin {
    @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
    private void justreplay$stopInstance(SoundInstance sound, CallbackInfo ci) {
        VideoExporter.onSoundStopped(sound);
    }

    @Inject(method = "stop(Lnet/minecraft/resources/Identifier;Lnet/minecraft/sounds/SoundSource;)V", at = @At("HEAD"))
    private void justreplay$stopNamed(Identifier name, SoundSource source, CallbackInfo ci) {
        VideoExporter.onSoundsStopped(name, source);
    }
}
