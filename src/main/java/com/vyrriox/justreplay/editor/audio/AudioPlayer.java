package com.vyrriox.justreplay.editor.audio;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

import java.util.List;

/**
 * Plays the audio tracks of the open replay live, in step with the replay clock. Every client tick renders the
 * mix of the ticks just reached (plus a short lead that hides the latency of the sound engine) into one streaming
 * source. Pausing starves the source, a seek drops what is queued and restarts at the sample of the new tick, and
 * speeds between x0.5 and x2 change its pitch; outside that range, and during video exports (which mix the tracks
 * offline), the tracks are silent.
 * <p>
 * It also applies the game sound settings of the project to the sounds the replay starts: muted, or played at
 * the game sound volume lowered by the ducking while a clip plays.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class AudioPlayer {
    /** Ticks rendered ahead of the cursor. */
    private static final int LEAD_TICKS = 2;
    /** Ducking ramp per client tick. */
    private static final float DUCK_STEP = 0.25F;

    private static ReplaySession session;
    private static MixStream stream;
    private static MixSoundInstance instance;
    /** Next timeline sample to render, or -1 when out of sync. */
    private static long rendered = -1;
    private static int lastSeek = Integer.MIN_VALUE;
    private static int lastCursor = Integer.MIN_VALUE;
    private static float duck;

    private AudioPlayer() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        try {
            tick(mc);
        } catch (Throwable t) {
            JustReplay.LOGGER.debug("Audio track playback failed", t);
            stop(mc);
        }
    }

    private static void tick(Minecraft mc) {
        ReplaySession s = ReplaySession.current();
        if (s != session) {
            stop(mc);
            session = s;
            lastSeek = Integer.MIN_VALUE;
            duck = 0.0F;
        }
        if (s == null || !ReplaySession.isRunning() || mc.level == null) {
            stop(mc);
            return;
        }
        ReplayProject project = s.project();
        Playback playback = s.playback();
        int cursor = playback.cursor();
        List<AudioTrack> tracks = project.audioTracks;
        boolean active = project.ducking && AudioMixer.activeAt(tracks, cursor);
        duck = active ? Math.min(1.0F, duck + DUCK_STEP) : Math.max(0.0F, duck - DUCK_STEP);

        if (playback.seekCount() != lastSeek || cursor < lastCursor) {
            lastSeek = playback.seekCount();
            rendered = -1;
            if (stream != null) {
                stream.clear();
            }
        }
        lastCursor = cursor;
        float speed = playback.speed();
        boolean audible = !VideoExporter.isActive() && AudioMixer.hasClips(tracks) && speed >= AudioClip.MIN_SPEED
                && speed <= AudioClip.MAX_SPEED && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        if (!audible) {
            stop(mc);
            return;
        }
        preload(tracks);
        if (rendered < 0) {
            rendered = (long) cursor * AudioPcm.PER_TICK;
        }
        long target = (long) (Math.min(playback.duration(), cursor) + LEAD_TICKS) * AudioPcm.PER_TICK;
        if (playback.isPaused()) {
            // Nothing new while paused; the lead already queued plays out.
            target = Math.min(target, rendered);
        }
        if (target - rendered > AudioPcm.RATE) {
            // Far behind (stall, long frame): skip ahead rather than play stale audio.
            rendered = target - AudioPcm.PER_TICK * (long) LEAD_TICKS;
        }
        short[] block = null;
        if (target > rendered) {
            int count = (int) (target - rendered);
            float[] left = new float[count];
            float[] right = new float[count];
            AudioMixer.mix(tracks, AudioLibrary::get, rendered, left, right, 0, count);
            block = new short[count * 2];
            for (int i = 0; i < count; i++) {
                block[i * 2] = toPcm(left[i]);
                block[i * 2 + 1] = toPcm(right[i]);
            }
            rendered = target;
        }
        if (instance == null || instance.isStopped() || !mc.getSoundManager().isActive(instance)) {
            start(mc);
        }
        if (block != null) {
            stream.push(block);
        }
        instance.setPitch(speed);
    }

    private static void start(Minecraft mc) {
        stopSource(mc);
        stream = new MixStream();
        instance = new MixSoundInstance(stream);
        mc.getSoundManager().play(instance);
    }

    /** Starts decoding the files of the clips near the play head so they are ready when reached. */
    private static void preload(List<AudioTrack> tracks) {
        for (AudioTrack track : tracks) {
            for (AudioClip clip : track.clips) {
                AudioLibrary.get(clip.file);
            }
        }
    }

    private static short toPcm(float value) {
        return (short) Math.round(AudioMixer.limit(value) * 32767.0F);
    }

    private static void stopSource(Minecraft mc) {
        if (instance != null) {
            instance.stopMix();
            mc.getSoundManager().stop(instance);
            instance = null;
        }
        if (stream != null) {
            stream.clear();
            stream = null;
        }
    }

    private static void stop(Minecraft mc) {
        stopSource(mc);
        rendered = -1;
    }

    /** Gain applied to the game sounds starting now: game sound volume and ducking of the project. */
    public static float gameGain(ReplayProject project) {
        if (project.muteGame) {
            return 0.0F;
        }
        float gain = Math.max(0.0F, Math.min(1.0F, project.gameVolume));
        if (project.ducking) {
            gain *= 1.0F - Math.max(0.0F, Math.min(1.0F, project.duckAmount)) * duck;
        }
        return gain;
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        ReplaySession s = ReplaySession.current();
        if (sound == null || s == null || VideoExporter.isActive() || !ReplaySession.isRunning()) {
            return; // exports apply these settings to their own mix
        }
        SoundSource source = sound.getSource();
        if (source == SoundSource.MASTER || source == SoundSource.MUSIC || source == SoundSource.VOICE) {
            return;
        }
        float gain = gameGain(s.project()) * SoundKinds.gain(s.project(), sound);
        if (gain <= 0.001F) {
            event.setSound(null);
        } else if (Math.abs(gain - 1.0F) > 0.001F && sound.getClass() == SimpleSoundInstance.class && source != SoundSource.RECORDS) {
            // Only plain one shot sounds: nobody keeps them to stop them later, so wrapping them is safe.
            event.setSound(new ScaledSoundInstance(sound, gain));
        }
    }
}
