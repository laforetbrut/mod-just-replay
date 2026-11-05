package com.vyrriox.justreplay.export;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.audio.AudioClip;
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
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 * <p>
 * The track is mixed and written in blocks. Looping sounds repeat to the end of the track or their stop, stopped sounds
 * end on their stop frame, and records, streams and loops started during the pre-roll are kept with a negative start.
 *
 * @author vyrriox
 */
public final class ExportAudio {
    static final int RATE = 48_000;
    /** Samples per channel mixed and written at a time, so a long export never holds its whole track in memory. */
    private static final int BLOCK = 1 << 16;
    /** Largest number of stereo samples a WAV file can hold. */
    private static final long MAX_SAMPLES = (0xFFFFFFFFL - 36) / 4;

    /** One logged sound. Samples count from the first captured frame, so a sound of the pre-roll starts below 0. */
    private static final class Event {
        final SoundInstance instance;
        final Identifier location;
        final SoundSource source;
        final long start;
        final boolean loop;
        final Identifier file;
        final float volume;
        final float pitch;
        final boolean positional;
        final double x;
        final double y;
        final double z;
        final float range;
        long end = Long.MAX_VALUE;

        Event(SoundInstance instance, long start, Sound sound, float volume, boolean positional, float range) {
            this.instance = instance;
            this.location = instance.getIdentifier();
            this.source = instance.getSource();
            this.start = start;
            this.loop = instance.isLooping();
            this.file = sound.getPath();
            this.volume = volume;
            this.pitch = instance.getPitch();
            this.positional = positional;
            this.x = instance.getX();
            this.y = instance.getY();
            this.z = instance.getZ();
            this.range = range;
        }

        void stopAt(long sample) {
            end = Math.min(end, sample);
        }
    }

    /** A logged sound being mixed: its decoded file and the gains fixed by the camera of the frame it starts on. */
    private record Playing(Event event, float[] data, int channels, int sourceFrames, double step, float gainL, float gainR, long end) {
    }

    /** A decoded voice frame waiting for its place in the track. */
    private record VoiceChunk(short[] pcm, long start, float gainL, float gainR) {
    }

    private record CameraState(double x, double y, double z, float yaw) {
    }

    private final int fps;
    private final List<Event> events = new ArrayList<>();
    private final List<CameraState> cameras = new ArrayList<>();
    /** Interleaved stereo audio rendered offline by mods with their own sound engine (see {@link #addRendered}). */
    private final FloatArrayList rendered = new FloatArrayList();
    private final ReplaySession session;
    private final com.vyrriox.justreplay.voice.VoicePlayer voicePlayer;
    private VoiceTrack voices;
    /** Logged sounds that update themselves (minecart, elytra...): nothing ticks them while they are muted. */
    private final List<Event> ticking = new ArrayList<>();
    private int lastTickedCursor = -1;
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
        this.voicePlayer = session != null && session.project().voices ? session.voices() : null;
    }

    /** Waits for the voice track, which may still be loading in the background. Called on the finisher thread. */
    public void awaitVoices() {
        VoiceTrack track = voicePlayer != null ? voicePlayer.awaitTrack() : null;
        voices = track != null && !track.isEmpty() ? track : null;
    }

    /**
     * Called for every sound while exporting, which is muted either way. {@code now} is the track sample it starts on.
     * Before capture only records, streams and loops are kept: they are still heard when the video starts.
     */
    public void onSound(SoundInstance instance, long now, boolean capturing) {
        if (instance.getSource() == SoundSource.MUSIC || instance.getSource() == SoundSource.MASTER) {
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
            if (!capturing && !sound.shouldStream() && !instance.isLooping()) {
                return;
            }
            // The kind's volume may go above 1: quiet sounds such as footsteps can be turned up; the final mix is soft limited.
            float kind = session != null ? SoundKinds.gain(session.project(), instance)
                    * com.vyrriox.justreplay.editor.EntityEdits.soundGain(instance) : 1.0F;
            float volume = Math.min(1.0F, instance.getVolume()) * mc.options.getSoundSourceVolume(instance.getSource()) * gameGain() * kind;
            if (instance.getIdentifier().getPath().endsWith(".step")) {
                footsteps++;
                footstepVolume += volume;
            }
            if (volume <= 0.0F) {
                return;
            }
            float range = Math.max(instance.getVolume(), 1.0F) * sound.getAttenuationDistance();
            boolean positional = !instance.isRelative() && instance.getAttenuation() != SoundInstance.Attenuation.NONE;
            Event event = new Event(instance, now, sound, volume, positional, range);
            events.add(event);
            if (instance instanceof TickableSoundInstance) {
                ticking.add(event);
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.debug("Cannot log sound {}", instance.getIdentifier(), t);
        }
    }

    /** One sound was stopped at track sample {@code now}. */
    public void onStop(SoundInstance instance, long now) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Event event = events.get(i);
            if (event.instance == instance) {
                event.stopAt(now);
                return;
            }
        }
    }

    /** The sounds matching a name and source were stopped; null matches any, as in the sound engine. */
    public void onStop(Identifier name, SoundSource source, long now) {
        for (Event event : events) {
            if ((name == null || name.equals(event.location)) && (source == null || source == event.source)) {
                event.stopAt(now);
            }
        }
    }

    /** Runs the game ticks that passed for the self-updating sounds and ends those that stopped or fell silent. */
    private void tickSounds(int cursor, long now) {
        int ticks = lastTickedCursor < 0 ? 0 : Math.max(0, cursor - lastTickedCursor);
        lastTickedCursor = cursor;
        for (int t = 0; t < ticks && !ticking.isEmpty(); t++) {
            ticking.removeIf(event -> {
                TickableSoundInstance sound = (TickableSoundInstance) event.instance;
                try {
                    if (!sound.isStopped()) {
                        sound.tick();
                    }
                    if (sound.isStopped() || sound.getVolume() <= 0.0F) {
                        event.stopAt(now);
                        return true;
                    }
                    return false;
                } catch (Throwable e) {
                    JustReplay.LOGGER.debug("Cannot update sound {}", event.location, e);
                    return true;
                }
            });
        }
    }

    /** Camera of a captured frame, used to place the sounds that start on it. */
    public void onFrame(double x, double y, double z, float yaw) {
        if (cameras.isEmpty() && session != null) {
            voiceStartTick = session.playback().cursor();
            freezeTracks(session.project());
        }
        if (session != null) {
            tickSounds(session.playback().cursor(), (long) cameras.size() * RATE / fps);
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
        for (int tick = (int) (clipBaseSample() / AudioPcm.PER_TICK); tick <= end; tick++) {
            if (AudioMixer.activeAt(tracks, tick)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Timeline sample of track sample 0. The clock of frame 0 shows the tick before the cursor (see
     * {@code CameraController.frameTime}), so clips line up with the picture one tick before {@link #voiceStartTick}.
     */
    private long clipBaseSample() {
        return Math.max(0L, voiceStartTick - 1L) * AudioPcm.PER_TICK;
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

    /** Mixes the logged sounds, block by block, into a WAV file covering {@code frames} video frames. */
    public void writeWav(Path wav, int frames) throws IOException {
        long samples = (long) frames * RATE / fps;
        if (samples > MAX_SAMPLES) {
            JustReplay.LOGGER.warn("The export is too long for a WAV file, its sound stops after {} samples", MAX_SAMPLES);
            samples = MAX_SAMPLES;
        }
        JustReplay.LOGGER.info("Export audio: {} game sounds, {} footsteps (average volume {})", events.size(), footsteps,
                footsteps == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.3f", footstepVolume / footsteps));
        Minecraft mc = Minecraft.getInstance();
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(e -> e.start));
        Map<Identifier, float[][]> decoded = new HashMap<>();
        List<Playing> playing = new ArrayList<>();
        int nextEvent = 0;
        float[] left = new float[BLOCK];
        float[] right = new float[BLOCK];
        byte[] bytes = new byte[BLOCK * 4];
        float renderedGain = gameGain();
        long renderedSamples = rendered.size() / 2;
        lastMixedClips = 0;
        boolean mixClips = voiceStartTick >= 0 && AudioMixer.hasClips(tracks);
        // Decoded once each, on this thread: the export already waits for the audio.
        Map<String, AudioPcm> files = new HashMap<>();
        Function<String, AudioPcm> source = file -> files.computeIfAbsent(file, AudioLibrary::getBlocking);
        VoiceMix voiceMix = hasVoices(frames) ? new VoiceMix(mc, frames) : null;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(wav), 1 << 16)) {
            writeHeader(out, samples);
            for (long from = 0; from < samples; from += BLOCK) {
                int count = (int) Math.min(BLOCK, samples - from);
                Arrays.fill(left, 0, count, 0.0F);
                Arrays.fill(right, 0, count, 0.0F);
                while (nextEvent < sorted.size() && sorted.get(nextEvent).start < from + count) {
                    Playing started = start(mc, sorted.get(nextEvent++), decoded, from);
                    if (started != null) {
                        playing.add(started);
                    }
                }
                long blockStart = from;
                playing.removeIf(sound -> mixSound(sound, left, right, blockStart, count));
                for (int i = 0, n = (int) Math.min(count, renderedSamples - from); i < n && renderedGain > 0.0F; i++) {
                    int at = (int) (from + i) * 2;
                    left[i] += rendered.getFloat(at) * renderedGain;
                    right[i] += rendered.getFloat(at + 1) * renderedGain;
                }
                if (voiceMix != null) {
                    try {
                        voiceMix.mix(left, right, from, count);
                    } catch (Throwable t) {
                        JustReplay.LOGGER.warn("Cannot mix the recorded voice chat into the export: {}", t.toString());
                        voiceMix.close();
                        voiceMix = null;
                    }
                }
                if (mixClips) {
                    try {
                        mixTracks(left, right, from, count, source);
                    } catch (Throwable t) {
                        JustReplay.LOGGER.warn("Cannot mix the audio tracks into the export: {}", t.toString());
                        mixClips = false;
                    }
                }
                writeBlock(out, left, right, count, bytes);
            }
        } finally {
            if (voiceMix != null) {
                voiceMix.close();
            }
        }
        if (voiceStartTick >= 0 && AudioMixer.hasClips(tracks)) {
            int clips = countClips(files, samples);
            lastMixedClips = clips;
            JustReplay.LOGGER.info("Export audio: {} clips mixed from {} tracks{}", clips, tracks.size(),
                    ducking ? ", ducking " + Math.round(duckAmount * 100) + "%" : "");
        }
    }

    /** Decodes and places a sound reaching the block at {@code from}; null when it cannot be heard there. */
    private Playing start(Minecraft mc, Event event, Map<Identifier, float[][]> decoded, long from) {
        float[][] pcm = decoded.computeIfAbsent(event.file, f -> decode(mc, f));
        if (pcm == null) {
            return null;
        }
        float[] data = pcm[0];
        int channels = (int) pcm[1][0];
        int sourceRate = (int) pcm[1][1];
        int sourceFrames = data.length / channels;
        if (sourceFrames < 2) {
            return null;
        }
        float gainL = event.volume;
        float gainR = event.volume;
        if (event.positional && channels == 1 && !cameras.isEmpty()) {
            long frame = Math.max(0L, (event.start * fps + RATE - 1) / RATE);
            CameraState cam = cameras.get((int) Math.min(frame, cameras.size() - 1));
            double dx = event.x - cam.x();
            double dy = event.y - cam.y();
            double dz = event.z - cam.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // OpenAL linear distance model as used by Minecraft.
            float attenuation = (float) Math.max(0.0, 1.0 - distance / Math.max(1.0F, event.range));
            double yaw = Math.toRadians(cam.yaw());
            double rightX = -Math.cos(yaw);
            double rightZ = -Math.sin(yaw);
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double pan = horizontal < 1e-6 ? 0.0 : (dx * rightX + dz * rightZ) / horizontal;
            gainL = (float) (event.volume * attenuation * Math.sqrt((1.0 - pan) / 2.0) * Math.sqrt(2.0));
            gainR = (float) (event.volume * attenuation * Math.sqrt((1.0 + pan) / 2.0) * Math.sqrt(2.0));
        }
        if (gainL <= 0.0001F && gainR <= 0.0001F) {
            return null;
        }
        double step = (double) sourceRate / RATE * Math.max(0.5F, Math.min(2.0F, event.pitch));
        long end = event.end;
        if (!event.loop) {
            end = Math.min(end, event.start + (long) Math.ceil((sourceFrames - 1) / step));
        }
        return end <= from ? null : new Playing(event, data, channels, sourceFrames, step, gainL, gainR, end);
    }

    /** Adds the part of a sound inside a block; returns true when the sound is over. */
    private static boolean mixSound(Playing sound, float[] left, float[] right, long from, int count) {
        long start = sound.event().start;
        long to = Math.min(from + count, sound.end());
        boolean loop = sound.event().loop;
        double span = sound.sourceFrames() - 1;
        float[] data = sound.data();
        int channels = sound.channels();
        for (long out = Math.max(from, start); out < to; out++) {
            double position = (out - start) * sound.step();
            if (loop) {
                position %= span;
            }
            int index = Math.min((int) position, sound.sourceFrames() - 2);
            double frac = position - index;
            float l;
            float r;
            if (channels == 1) {
                l = r = (float) (data[index] + (data[index + 1] - data[index]) * frac);
            } else {
                l = (float) (data[index * channels] + (data[(index + 1) * channels] - data[index * channels]) * frac);
                r = (float) (data[index * channels + 1] + (data[(index + 1) * channels + 1] - data[index * channels + 1]) * frac);
            }
            int at = (int) (out - from);
            left[at] += l * sound.gainL();
            right[at] += r * sound.gainR();
        }
        return sound.end() <= from + count;
    }

    /**
     * Adds the recorded voices to the mix a block at a time. Frames of one speaker follow each other without gaps when
     * they arrive within a quarter of a second, which absorbs the network jitter of the recording.
     */
    private final class VoiceMix {
        private final float master;
        private final int endTick;
        private final Map<Integer, VoiceBridge.Codec> decoders = new HashMap<>();
        private final Map<Integer, Long> speakerEnd = new HashMap<>();
        private final ArrayDeque<VoiceChunk> queued = new ArrayDeque<>();
        private final List<VoiceChunk> active = new ArrayList<>();
        private int next;
        private boolean missingDecoder;

        VoiceMix(Minecraft mc, int frames) {
            this.master = mc.options.getSoundSourceVolume(SoundSource.VOICE);
            this.endTick = voiceEndTick(frames);
            this.next = voices.firstAfter(voiceStartTick - 1);
        }

        void mix(float[] left, float[] right, long from, int count) {
            if (master <= 0.0F) {
                return;
            }
            long blockEnd = from + count;
            List<VoiceTrack.Frame> frames = voices.frames();
            while (next < frames.size()) {
                VoiceTrack.Frame frame = frames.get(next);
                // A frame can only start later than its tick, so none past this block can reach it yet.
                if (frame.tick() > endTick || (long) (frame.tick() - voiceStartTick) * RATE / 20 >= blockEnd) {
                    break;
                }
                next++;
                decodeFrame(frame);
            }
            for (Iterator<VoiceChunk> it = queued.iterator(); it.hasNext(); ) {
                VoiceChunk chunk = it.next();
                if (chunk.start() < blockEnd) {
                    active.add(chunk);
                    it.remove();
                }
            }
            active.removeIf(chunk -> {
                long to = Math.min(blockEnd, chunk.start() + chunk.pcm().length);
                for (long out = Math.max(from, chunk.start()); out < to; out++) {
                    float value = chunk.pcm()[(int) (out - chunk.start())] / 32768.0F;
                    int at = (int) (out - from);
                    left[at] += value * chunk.gainL();
                    right[at] += value * chunk.gainR();
                }
                return chunk.start() + chunk.pcm().length <= blockEnd;
            });
        }

        private void decodeFrame(VoiceTrack.Frame frame) {
            VoiceBridge.Codec decoder = decoders.computeIfAbsent(frame.speaker(), id -> VoiceBridge.newDecoder());
            short[] pcm = VoiceTrack.decode(frame, decoder);
            if (pcm == null) {
                missingDecoder |= frame.codec() == VoiceTrack.CODEC_OPUS;
                return;
            }
            long start = (long) (frame.tick() - voiceStartTick) * RATE / 20;
            Long end = speakerEnd.get(frame.speaker());
            if (end != null && end > start && end - start < RATE / 4) {
                start = end;
            }
            speakerEnd.put(frame.speaker(), start + pcm.length);
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
            if (gainL > 0.0001F || gainR > 0.0001F) {
                queued.add(new VoiceChunk(pcm, start, gainL, gainR));
            }
        }

        void close() {
            for (VoiceBridge.Codec decoder : decoders.values()) {
                if (decoder != null) {
                    try {
                        decoder.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (missingDecoder) {
                JustReplay.LOGGER.warn("The export skips the recorded voice chat: Simple Voice Chat is needed to decode it");
            }
        }
    }

    /** Ducks the game sounds and voices under the clips, then adds the audio tracks, for one block. */
    private void mixTracks(float[] left, float[] right, long from, int count, Function<String, AudioPcm> source) {
        long first = clipBaseSample() + from;
        if (ducking) {
            // Started one attack earlier, the rebuilt ramp reaches its true level before the block whatever came before.
            int lead = (int) Math.min(first, AudioMixer.DUCK_ATTACK);
            float[] gains = AudioMixer.duckGains(tracks, duckAmount, first - lead, lead + count);
            for (int i = 0; i < count; i++) {
                left[i] *= gains[lead + i];
                right[i] *= gains[lead + i];
            }
        }
        AudioMixer.mix(tracks, source, first, left, right, 0, count);
    }

    /** Clips heard in the whole track; a clip spanning several blocks counts once. */
    private int countClips(Map<String, AudioPcm> files, long samples) {
        long first = clipBaseSample();
        int clips = 0;
        for (AudioTrack track : tracks) {
            if (!track.audible(tracks) || track.volume <= 0.0F) {
                continue;
            }
            for (AudioClip clip : track.clips) {
                long clipStart = (long) clip.start * AudioPcm.PER_TICK;
                long clipEnd = clipStart + (long) Math.ceil(clip.length() * AudioPcm.PER_TICK);
                AudioPcm pcm = files.get(clip.file);
                if (clipEnd > first && clipStart < first + samples && clip.volume > 0.0F && clip.span() > 0 && pcm != null && pcm.frames >= 2) {
                    clips++;
                }
            }
        }
        return clips;
    }

    private static float[][] decode(Minecraft mc, Identifier file) {
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

    private static void writeHeader(OutputStream raw, long samples) throws IOException {
        DataOutputStream out = new DataOutputStream(raw);
        int dataSize = (int) (samples * 4); // unsigned in the file
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
        out.flush();
    }

    private static void writeBlock(OutputStream out, float[] left, float[] right, int count, byte[] bytes) throws IOException {
        for (int i = 0; i < count; i++) {
            short l = toPcm(left[i]);
            short r = toPcm(right[i]);
            bytes[i * 4] = (byte) l;
            bytes[i * 4 + 1] = (byte) (l >> 8);
            bytes[i * 4 + 2] = (byte) r;
            bytes[i * 4 + 3] = (byte) (r >> 8);
        }
        out.write(bytes, 0, count * 4);
    }

    private static short toPcm(float value) {
        // Soft limit so loud overlapping sounds do not clip harshly.
        float v = AudioMixer.limit(value);
        return (short) Math.round(v * 32767.0F);
    }
}
