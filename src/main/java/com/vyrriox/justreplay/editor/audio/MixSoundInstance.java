package com.vyrriox.justreplay.editor.audio;

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
 * The audio tracks played through the vanilla sound engine: one streaming, non positional source in the master
 * category (only the master volume applies), fed by a {@link MixStream}.
 *
 * @author vyrriox
 */
final class MixSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    static final ResourceLocation LOCATION = JustReplay.id("audio_tracks");

    private final MixStream stream;
    private boolean stopped;

    MixSoundInstance(MixStream stream) {
        super(LOCATION, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.stream = stream;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(LOCATION, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
        return new WeighedSoundEvents(LOCATION, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    void setPitch(float pitch) {
        this.pitch = pitch;
    }

    void stopMix() {
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
