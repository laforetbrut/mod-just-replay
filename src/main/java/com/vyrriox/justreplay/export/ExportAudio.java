package com.vyrriox.justreplay.export;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.audio.AudioLibrary;
import com.vyrriox.justreplay.editor.audio.AudioMixer;
import com.vyrriox.justreplay.editor.audio.AudioPcm;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.editor.audio.SoundKinds;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.voice.VoiceBridge;
import com.vyrriox.justreplay.voice.VoiceTrack;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the audio track of an export offline. While frames are captured every sound that would play is muted
 * and logged with its frame, position and volume; when the video is done the sounds are decoded, placed in
 * stereo relative to the camera of that frame and mixed into a 48 kHz WAV that FFmpeg adds to the video. The
 * result is in sync with the frames whatever the export speed.
 * <p>
 * The recorded voice chat (when the replay has one and the project enables voices) is mixed in the same way: frame
 * 0 shows the replay tick the capture started on, so a voice frame of tick {@code t} starts {@code (t - start) / 20}
 * seconds into the track, placed relative to the camera of the frame it starts on.
 * <p>
 * Mods with a sound engine of their own (Drop the Beam) render their audio offline for each captured frame and add it
 * with {@link #addRendered}; it is mixed with the game sounds and follows the same game volume and ducking.
 * <p>
 * The audio tracks of the project are mixed last, sample accurately from the same start tick: the game sounds
 * get the game sound volume of the project, then the ducking lowers them and the voices under the clips, then
 * the clips are added. The tracks are frozen when capture starts, so later edits never change a running export.
 *
 * @author vyrriox
 */
public final class ExportAudio {
    private static final int RATE = 48_000;

    private record Event(int frame, ResourceLocation file, boolean stream, float volume, float pitch, boolean positional,
                         double x, double y, double z, float range) {
    }

    private record CameraState(double x, double y, double z, float yaw) {
    }

    private final int fps;
    private final List<Event> events = new ArrayList<>();
    private final List<CameraState> cameras = new ArrayList<>();
    /** Interleaved stereo audio rendered offline by mods with their own sound engine (see {@link #addRendered}). */
    private final FloatArrayList rendered = new FloatArrayList();
    private final ReplaySession session;
    private final VoiceTrack voices;
    private int voiceStartTick = -1;
    private List<AudioTrack> tracks = List.of();
    private float gameGain = 1.0F;
    private int footsteps;
    private double footstepVolume;
    private boolean ducking;
    private float duckAmount;
    private static volatile int lastMixedClips;

    public ExportAudio(int fps) {
        this.fps = fps;
        this.session = ReplaySession.current();
        VoiceTrack track = session != null && session.project().voices ? session.voices().track() : null;
        this.voices = track != null && !track.isEmpty() ? track : null;
    }

    /** Called for every sound while exporting; returns true when the sound was logged (it is muted either way). */
    public void onSound(SoundInstance instance, int frame, boolean capturing) {
        if (!capturing || instance.getSource() == SoundSource.MUSIC || instance.getSource() == SoundSource.MASTER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        SoundManager manager = mc.getSoundManager();
        try {
            if (instance.resolve(manager) == null) {
                return;
            }
            Sound sound = instance.getSound();
            if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                return;
            }
            // The kind's volume may go above 1: quiet sounds such as footsteps can be turned up; the final mix is soft limited.
            float kind = session != null ? SoundKinds.gain(session.project(), instance) : 1.0F;
            float volume = Math.min(1.0F, instance.getVolume()) * mc.options.getSoundSourceVolume(instance.getSource()) * gameGain() * kind;
            if (instance.getLocation().getPath().endsWith(".step")) {
                footsteps++;
                footstepVolume += volume;
            }
            if (volume <= 0.0F) {
                return;
            }
            float range = Math.max(instance.getVolume(), 1.0F) * sound.getAttenuationDistance();
            boolean positional = !instance.isRelative() && instance.getAttenuation() != SoundInstance.Attenuation.NONE;
            events.add(new Event(frame, sound.getPath(), sound.shouldStream(), volume, instance.getPitch(), positional,
                    instance.getX(), instance.getY(), instance.getZ(), range));
        } catch (Throwable t) {
            JustReplay.LOGGER.debug("Cannot log sound {}", instance.getLocation(), t);
        }
    }

    /** Camera of a captured frame, used to place the sounds that start on it. */
    public void onFrame(double x, double y, double z, float yaw) {
        if (cameras.isEmpty() && session != null) {
            voiceStartTick = session.playback().cursor();
            freezeTracks(session.project());
        }
        cameras.add(new CameraState(x, y, z, yaw));
    }

    public boolean isEmpty() {
        return events.isEmpty() && rendered.isEmpty() && !hasVoices(cameras.size()) && !hasMusic(cameras.size());
    }

    /**
     * Adds audio rendered offline by a mod with its own sound engine (stereo, 48 kHz, already placed relative to the
     * camera), from sample {@code start} of the track (sample 0 is the first captured frame). It is a game sound: the
     * project's game volume, mute and ducking apply to it.
     */
    public void addRendered(long start, float[] left, float[] right, int count) {
        long end = (start + count) * 2;
        if (start < 0 || end > Integer.MAX_VALUE - 8) {
            return;
        }
        if (rendered.size() < end) {
            rendered.size((int) end);
        }
        for (int i = 0; i < count; i++) {
            int at = (int) ((start + i) * 2);
            rendered.set(at, rendered.getFloat(at) + left[i]);
            rendered.set(at + 1, rendered.getFloat(at + 1) + right[i]);
        }
    }

    /** Copies the audio tracks and game sound settings the export mixes. */
    private void freezeTracks(ReplayProject project) {
        List<AudioTrack> copy = new ArrayList<>();
        for (AudioTrack track : project.audioTracks) {
            copy.add(track.copy());
        }
        tracks = copy;
        gameGain = project.muteGame ? 0.0F : Math.max(0.0F, Math.min(1.0F, project.gameVolume));
        ducking = project.ducking && project.duckAmount > 0.0F;
        duckAmount = Math.max(0.0F, Math.min(1.0F, project.duckAmount));
    }

    /** Game sound volume of the project; before capture starts, read live. */
    private float gameGain() {
        if (!cameras.isEmpty() || session == null) {
            return gameGain;
        }
        ReplayProject project = session.project();
        return project.muteGame ? 0.0F : Math.max(0.0F, Math.min(1.0F, project.gameVolume));
    }

    /** True when an audio clip plays inside the captured frames. */
    private boolean hasMusic(int frames) {
        if (voiceStartTick < 0 || frames == 0 || !AudioMixer.hasClips(tracks)) {
            return false;
        }
        int end = voiceEndTick(frames);
        for (int tick = voiceStartTick; tick <= end; tick++) {
            if (AudioMixer.activeAt(tracks, tick)) {
                return true;
            }
        }
        return false;
    }

    /** Clips mixed into the last exported audio track. */
    public static int lastMixedClips() {
        return lastMixedClips;
    }

    /** True when a recorded voice frame starts inside the captured frames. */
    private boolean hasVoices(int frames) {
        if (voices == null || voiceStartTick < 0 || frames == 0) {
            return false;
        }
        int first = voices.firstAfter(voiceStartTick - 1);
        return first < voices.frames().size() && voices.frames().get(first).tick() <= voiceEndTick(frames);
    }

    private int voiceEndTick(int frames) {
        return voiceStartTick + (int) ((long) frames * 20 / fps);
    }

    /** Mixes the logged sounds into a WAV file covering {@code frames} video frames. */
    public void writeWav(Path wav, int frames) throws IOException {
        int samples = (int) ((long) frames * RATE / fps);
        JustReplay.LOGGER.info("Export audio: {} game sounds, {} footsteps (average volume {})", events.size(), footsteps,
                footsteps == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.3f", footstepVolume / footsteps));
        float[] left = new float[samples];
        float[] right = new float[samples];
        Map<ResourceLocation, float[][]> decoded = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        for (Event event : events) {
            float[][] pcm = decoded.computeIfAbsent(event.file(), f -> decode(mc, f));
            if (pcm == null) {
                continue;
            }
            float[] data = pcm[0];
            int channels = (int) pcm[1][0];
            int sourceRate = (int) pcm[1][1];
            float gainL = event.volume();
            float gainR = event.volume();
            if (event.positional() && channels == 1 && !cameras.isEmpty()) {
                CameraState cam = cameras.get(Math.min(event.frame(), cameras.size() - 1));
                double dx = event.x() - cam.x();
                double dy = event.y() - cam.y();
                double dz = event.z() - cam.z();
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                // OpenAL linear distance model as used by Minecraft.
                float attenuation = (float) Math.max(0.0, 1.0 - distance / Math.max(1.0F, event.range()));
                double yaw = Math.toRadians(cam.yaw());
                double rightX = -Math.cos(yaw);
                double rightZ = -Math.sin(yaw);
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                double pan = horizontal < 1e-6 ? 0.0 : (dx * rightX + dz * rightZ) / horizontal;
                gainL = (float) (event.volume() * attenuation * Math.sqrt((1.0 - pan) / 2.0) * Math.sqrt(2.0));
                gainR = (float) (event.volume() * attenuation * Math.sqrt((1.0 + pan) / 2.0) * Math.sqrt(2.0));
            }
            if (gainL <= 0.0001F && gainR <= 0.0001F) {
                continue;
            }
            int start = (int) ((long) event.frame() * RATE / fps);
            int sourceFrames = data.length / channels;
            double step = (double) sourceRate / RATE * Math.max(0.5F, Math.min(2.0F, event.pitch()));
            double position = 0;
            for (int out = start; out < samples; out++) {
                int index = (int) position;
                if (index + 1 >= sourceFrames) {
                    break;
                }
                double frac = position - index;
                float l;
                float r;
                if (channels == 1) {
                    l = r = (float) (data[index] + (data[index + 1] - data[index]) * frac);
                } else {
                    l = (float) (data[index * channels] + (data[(index + 1) * channels] - data[index * channels]) * frac);
                    r = (float) (data[index * channels + 1] + (data[(index + 1) * channels + 1] - data[index * channels + 1]) * frac);
                }
                left[out] += l * gainL;
                right[out] += r * gainR;
                position += step;
            }
        }
        float renderedGain = gameGain();
        for (int i = 0, n = Math.min(samples, rendered.size() / 2); i < n && renderedGain > 0.0F; i++) {
            left[i] += rendered.getFloat(i * 2) * renderedGain;
            right[i] += rendered.getFloat(i * 2 + 1) * renderedGain;
        }
        try {
            mixVoices(mc, left, right, frames);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot mix the recorded voice chat into the export: {}", t.toString());
        }
        try {
            mixTracks(left, right);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot mix the audio tracks into the export: {}", t.toString());
        }
        write(wav, left, right);
    }

    /**
     * Adds the recorded voices to the mix. Frames of one speaker follow each other without gaps when they arrive
     * within a quarter of a second, which absorbs the network jitter of the recording.
     */
    private void mixVoices(Minecraft mc, float[] left, float[] right, int frames) {
        if (!hasVoices(frames)) {
            return;
        }
        float master = mc.options.getSoundSourceVolume(SoundSource.VOICE);
        if (master <= 0.0F) {
            return;
        }
        int samples = left.length;
        int endTick = voiceEndTick(frames);
        Map<Integer, VoiceBridge.Codec> decoders = new HashMap<>();
        Map<Integer, Long> speakerEnd = new HashMap<>();
        boolean missingDecoder = false;
        try {
            for (int i = voices.firstAfter(voiceStartTick - 1); i < voices.frames().size(); i++) {
                VoiceTrack.Frame frame = voices.frames().get(i);
                if (frame.tick() > endTick) {
                    break;
                }
                VoiceBridge.Codec decoder = decoders.computeIfAbsent(frame.speaker(), id -> VoiceBridge.newDecoder());
                short[] pcm = VoiceTrack.decode(frame, decoder);
                if (pcm == null) {
                    missingDecoder |= frame.codec() == VoiceTrack.CODEC_OPUS;
                    continue;
                }
                long start = (long) (frame.tick() - voiceStartTick) * RATE / 20;
                Long end = speakerEnd.get(frame.speaker());
                if (end != null && end > start && end - start < RATE / 4) {
                    start = end;
                }
                speakerEnd.put(frame.speaker(), start + pcm.length);
                if (start >= samples) {
                    continue;
                }
                VoiceTrack.Speaker speaker = voices.speakers().get(frame.speaker());
                float gainL = master;
                float gainR = master;
                if (speaker.positional() && frame.hasPosition()) {
                    CameraState cam = cameras.get((int) Math.min(start * fps / RATE, cameras.size() - 1));
                    double dx = frame.x() - cam.x();
                    double dy = frame.y() - cam.y();
                    double dz = frame.z() - cam.z();
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float range = frame.distance() > 0.0F ? frame.distance() : 48.0F;
                    float attenuation = (float) Math.max(0.0, 1.0 - distance / range);
                    double yaw = Math.toRadians(cam.yaw());
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    double pan = horizontal < 1e-6 ? 0.0 : (dx * -Math.cos(yaw) + dz * -Math.sin(yaw)) / horizontal;
                    gainL = (float) (master * attenuation * Math.sqrt((1.0 - pan) / 2.0) * Math.sqrt(2.0));
                    gainR = (float) (master * attenuation * Math.sqrt((1.0 + pan) / 2.0) * Math.sqrt(2.0));
                }
                if (gainL <= 0.0001F && gainR <= 0.0001F) {
                    continue;
                }
                for (int s = 0; s < pcm.length; s++) {
                    long out = start + s;
                    if (out < 0) {
                        continue;
                    }
                    if (out >= samples) {
                        break;
                    }
                    float value = pcm[s] / 32768.0F;
                    left[(int) out] += value * gainL;
                    right[(int) out] += value * gainR;
                }
            }
        } finally {
            for (VoiceBridge.Codec decoder : decoders.values()) {
                if (decoder != null) {
                    try {
                        decoder.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (missingDecoder) {
            JustReplay.LOGGER.warn("The export skips the recorded voice chat: Simple Voice Chat is needed to decode it");
        }
    }

    /** Ducks the game sounds and voices under the clips, then adds the audio tracks. */
    private void mixTracks(float[] left, float[] right) {
        lastMixedClips = 0;
        if (voiceStartTick < 0 || !AudioMixer.hasClips(tracks)) {
            return;
        }
        long first = (long) voiceStartTick * AudioPcm.PER_TICK;
        int samples = left.length;
        if (ducking) {
            float[] gains = AudioMixer.duckGains(tracks, duckAmount, first, samples);
            for (int i = 0; i < samples; i++) {
                left[i] *= gains[i];
                right[i] *= gains[i];
            }
        }
        // Decoded once each, on this thread: the export already waits for the audio.
        Map<String, AudioPcm> files = new HashMap<>();
        java.util.function.Function<String, AudioPcm> source = file -> files.computeIfAbsent(file, AudioLibrary::getBlocking);
        int clips = AudioMixer.mix(tracks, source, first, left, right, 0, samples);
        lastMixedClips = clips;
        JustReplay.LOGGER.info("Export audio: {} clips mixed from {} tracks{}", clips, tracks.size(),
                ducking ? ", ducking " + Math.round(duckAmount * 100) + "%" : "");
    }

    private static float[][] decode(Minecraft mc, ResourceLocation file) {
        try (InputStream in = mc.getResourceManager().open(file); JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            FloatArrayList samples = new FloatArrayList();
            while (stream.readChunk(samples::add)) {
                if (samples.size() > RATE * 2 * 600) {
                    break; // ten minutes is plenty for one export
                }
            }
            javax.sound.sampled.AudioFormat format = stream.getFormat();
            return new float[][]{samples.toFloatArray(), {format.getChannels(), format.getSampleRate()}};
        } catch (Exception e) {
            JustReplay.LOGGER.debug("Cannot decode sound {}", file, e);
            return null;
        }
    }

    private static void write(Path wav, float[] left, float[] right) throws IOException {
        int samples = left.length;
        try (OutputStream raw = Files.newOutputStream(wav); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            int dataSize = samples * 4;
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + dataSize));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeInt(Integer.reverseBytes(RATE));
            out.writeInt(Integer.reverseBytes(RATE * 4));
            out.writeShort(Short.reverseBytes((short) 4));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataSize));
            for (int i = 0; i < samples; i++) {
                out.writeShort(Short.reverseBytes(toPcm(left[i])));
                out.writeShort(Short.reverseBytes(toPcm(right[i])));
            }
        }
    }

    private static short toPcm(float value) {
        // Soft limit so loud overlapping sounds do not clip harshly.
        float v = AudioMixer.limit(value);
        return (short) Math.round(v * 32767.0F);
    }
}
