package com.vyrriox.justreplay.voice;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.util.concurrent.CompletableFuture;

/**
 * A recorded voice played through the vanilla sound engine as a streaming source in the voice category, so the
 * voice volume slider, positional attenuation and pause all apply. It has no sound event: it resolves to a
 * streamed {@link Sound} of its own and hands its {@link VoiceStream} to the engine.
 *
 * @author vyrriox
 */
final class VoiceSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final ResourceLocation LOCATION = JustReplay.id("voice");

    private final VoiceStream stream;
    private final int range;
    private boolean stopped;

    VoiceSoundInstance(VoiceStream stream, boolean positional, double x, double y, double z, float distance) {
        super(LOCATION, SoundSource.VOICE, SoundInstance.createUnseededRandom());
        this.stream = stream;
        this.range = Math.max(1, Math.round(distance));
        this.attenuation = positional ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE;
        this.relative = !positional;
        this.x = positional ? x : 0.0;
        this.y = positional ? y : 0.0;
        this.z = positional ? z : 0.0;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(LOCATION, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, range);
        return new WeighedSoundEvents(LOCATION, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    void moveTo(double x, double y, double z) {
        if (!relative) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    void setPitch(float pitch) {
        this.pitch = pitch;
    }

    void stopVoice() {
        stopped = true;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void tick() {
    }
}
