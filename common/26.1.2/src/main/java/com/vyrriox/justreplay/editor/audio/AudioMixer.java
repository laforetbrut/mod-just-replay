package com.vyrriox.justreplay.editor.audio;

import java.util.List;
import java.util.function.Function;

/**
 * Mixes the audio tracks of a project, sample accurately, for any window of the timeline. Sample {@code s} of the
 * timeline is heard at tick {@code s / 2400}; the preview and the export both render through here, so what is
 * heard while editing is what the video gets. Overlapping clips of a track crossfade with an equal power
 * curve.
 *
 * @author vyrriox
 */
public final class AudioMixer {
    /** Time the ducking takes to lower the game sounds, in samples. */
    public static final int DUCK_ATTACK = AudioPcm.RATE * 15 / 100;
    /** Time the ducking takes to bring them back, in samples. */
    public static final int DUCK_RELEASE = AudioPcm.RATE * 40 / 100;

    private AudioMixer() {
    }

    /** True when at least one audible track holds a clip. */
    public static boolean hasClips(List<AudioTrack> tracks) {
        for (AudioTrack track : tracks) {
            if (!track.clips.isEmpty() && track.audible(tracks)) {
                return true;
            }
        }
        return false;
    }

    /** True when an audible clip plays at a timeline tick. */
    public static boolean activeAt(List<AudioTrack> tracks, double tick) {
        for (AudioTrack track : tracks) {
            if (!track.audible(tracks) || track.volume <= 0.0F) {
                continue;
            }
            for (AudioClip clip : track.clips) {
                if (clip.volume > 0.0F && clip.covers(tick)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the tracks to {@code left} and {@code right} from index {@code at}, for {@code count} samples starting
     * at timeline sample {@code first}. Returns the number of clips heard in that window.
     */
    public static int mix(List<AudioTrack> tracks, Function<String, AudioPcm> source, long first, float[] left, float[] right, int at,
                          int count) {
        int heard = 0;
        long last = first + count;
        for (AudioTrack track : tracks) {
            if (!track.audible(tracks) || track.volume <= 0.0F) {
                continue;
            }
            for (int c = 0; c < track.clips.size(); c++) {
                AudioClip clip = track.clips.get(c);
                long clipStart = (long) clip.start * AudioPcm.PER_TICK;
                long clipEnd = clipStart + (long) Math.ceil(clip.length() * AudioPcm.PER_TICK);
                if (clipEnd <= first || clipStart >= last || clip.volume <= 0.0F || clip.span() <= 0) {
                    continue;
                }
                AudioPcm pcm = source.apply(clip.file);
                if (pcm == null || pcm.frames < 2) {
                    continue;
                }
                if (mixClip(clip, pcm, track.volume * clip.volume, track.crossfadeIn(c), track.crossfadeOut(c), clipStart, clipEnd, first,
                        left, right, at, count)) {
                    heard++;
                }
            }
        }
        return heard;
    }

    private static boolean mixClip(AudioClip clip, AudioPcm pcm, float gain, double xIn, double xOut, long clipStart, long clipEnd,
                                   long first, float[] left, float[] right, int at, int count) {
        long from = Math.max(first, clipStart);
        long to = Math.min(first + count, clipEnd);
        double speed = clip.speed();
        double inFrame = clip.in * AudioPcm.PER_TICK;
        double spanFrames = clip.span() * AudioPcm.PER_TICK;
        double offsetFrames = clip.offset * AudioPcm.PER_TICK;
        double length = clip.length();
        boolean any = false;
        for (long s = from; s < to; s++) {
            long o = s - clipStart;
            double pos = clip.loop ? inFrame + AudioClip.floorMod(offsetFrames + o * speed, spanFrames) : inFrame + o * speed;
            int index = (int) pos;
            if (index < 0 || index >= pcm.frames) {
                continue;
            }
            int nextIndex = Math.min(pcm.frames - 1, index + 1);
            float frac = (float) (pos - index);
            double tick = (double) o / AudioPcm.PER_TICK;
            float fade = clip.fadeGain(tick) * crossfade(tick, length, xIn, xOut);
            if (fade <= 0.0F) {
                continue;
            }
            float g = gain * fade;
            int out = at + (int) (s - first);
            left[out] += (pcm.left[index] + (pcm.left[nextIndex] - pcm.left[index]) * frac) * g;
            right[out] += (pcm.right[index] + (pcm.right[nextIndex] - pcm.right[index]) * frac) * g;
            any = true;
        }
        return any && length > 0;
    }

    /**
     * Equal power crossfade gain at {@code o} ticks into a clip of {@code length} ticks that overlaps the previous
     * clip for {@code xIn} ticks and the next one for {@code xOut} ticks: sine and cosine of the same ramp, so the
     * power of the two clips stays constant through the overlap.
     */
    public static float crossfade(double o, double length, double xIn, double xOut) {
        double gain = 1.0;
        if (xIn > 0.0 && o < xIn) {
            gain = Math.sin(Math.max(0.0, o / xIn) * Math.PI * 0.5);
        }
        double left = length - o;
        if (xOut > 0.0 && left < xOut) {
            gain *= Math.sin(Math.max(0.0, left / xOut) * Math.PI * 0.5);
        }
        return (float) gain;
    }

    /**
     * Gain of the game sounds under the ducking for {@code count} samples from timeline sample {@code first}: 1
     * where no clip plays, {@code 1 - amount} under the clips, with smooth ramps. The ramp state before the window
     * is rebuilt from up to one release time earlier.
     */
    public static float[] duckGains(List<AudioTrack> tracks, float amount, long first, int count) {
        float[] gains = new float[count];
        long warm = Math.min(first, DUCK_RELEASE);
        int total = (int) (count + warm);
        boolean[] active = new boolean[total];
        long start = first - warm;
        for (AudioTrack track : tracks) {
            if (!track.audible(tracks) || track.volume <= 0.0F) {
                continue;
            }
            for (AudioClip clip : track.clips) {
                if (clip.volume <= 0.0F || clip.span() <= 0) {
                    continue;
                }
                long a = Math.max(start, (long) clip.start * AudioPcm.PER_TICK);
                long b = Math.min(start + total, (long) clip.start * AudioPcm.PER_TICK + (long) Math.ceil(clip.length() * AudioPcm.PER_TICK));
                for (long s = a; s < b; s++) {
                    active[(int) (s - start)] = true;
                }
            }
        }
        float env = 0.0F;
        float up = 1.0F / DUCK_ATTACK;
        float down = 1.0F / DUCK_RELEASE;
        for (int i = 0; i < total; i++) {
            env = active[i] ? Math.min(1.0F, env + up) : Math.max(0.0F, env - down);
            if (i >= warm) {
                gains[(int) (i - warm)] = 1.0F - amount * env;
            }
        }
        return gains;
    }

    /** Soft limit shared by the preview and the export, so overlapping clips never clip harshly. */
    public static float limit(float value) {
        return (float) Math.tanh(value);
    }
}
