package com.vyrriox.justreplay.editor.audio;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.CompletableFuture;

/**
 * A one shot game sound played quieter: everything comes from the wrapped sound except the volume, scaled by the
 * game sound volume and the ducking of the project when it starts.
 *
 * @author vyrriox
 */
final class ScaledSoundInstance implements SoundInstance {
    private final SoundInstance wrapped;
    private final float gain;

    ScaledSoundInstance(SoundInstance wrapped, float gain) {
        this.wrapped = wrapped;
        this.gain = gain;
    }

    @Override
    public ResourceLocation getLocation() {
        return wrapped.getLocation();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return wrapped.resolve(manager);
    }

    @Override
    public Sound getSound() {
        return wrapped.getSound();
    }

    @Override
    public SoundSource getSource() {
        return wrapped.getSource();
    }

    @Override
    public boolean isLooping() {
        return wrapped.isLooping();
    }

    @Override
    public boolean isRelative() {
        return wrapped.isRelative();
    }

    @Override
    public int getDelay() {
        return wrapped.getDelay();
    }

    @Override
    public float getVolume() {
        return wrapped.getVolume() * gain;
    }

    @Override
    public float getPitch() {
        return wrapped.getPitch();
    }

    @Override
    public double getX() {
        return wrapped.getX();
    }

    @Override
    public double getY() {
        return wrapped.getY();
    }

    @Override
    public double getZ() {
        return wrapped.getZ();
    }

    @Override
    public Attenuation getAttenuation() {
        return wrapped.getAttenuation();
    }

    @Override
    public boolean canStartSilent() {
        return wrapped.canStartSilent();
    }

    @Override
    public boolean canPlaySound() {
        return wrapped.canPlaySound();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return wrapped.getStream(soundBuffers, sound, looping);
    }
}
