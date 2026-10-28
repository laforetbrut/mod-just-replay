package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.record.Recorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The local player's own entity sounds (footsteps, landing, swimming, eating...) go through LocalPlayer.playSound,
 * which plays them with playLocalSound, not through the level's seeded sound path: the recorder is told here, as the
 * packet every other player received for that sound.
 *
 * @author vyrriox
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSoundsMixin {
    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"))
    private void justreplay$recordOwnSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        if (!(Recorder.isRecording() || Recorder.isBuffering())) {
            return;
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isSilent()) {
            return;
        }
        Recorder.recordLocalEffect(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), self.getSoundSource(),
                self.getX(), self.getY(), self.getZ(), volume, pitch, self.getRandom().nextLong()));
    }
}
