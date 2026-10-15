package com.vyrriox.justreplay.editor.audio;

import java.util.UUID;

/**
 * A piece of an audio file placed on an audio track. Times on the timeline are replay ticks; the trim points
 * {@link #in} and {@link #out} are positions in the file, also in ticks (1/20 s) but fractional, so a split or a
 * trim never moves the audio by a rounding error.
 * <p>
 * A clip plays its source span once, {@code (out - in) / speed} ticks long, or loops it for {@link #length} ticks,
 * starting {@link #offset} source ticks into the span. Fades are applied over the first and last ticks of the
 * clip on the timeline.
 *
 * @author vyrriox
 */
public final class AudioClip {
    public static final float MIN_SPEED = 0.5F;
    public static final float MAX_SPEED = 2.0F;

    public String id = UUID.randomUUID().toString();
    /** File name in the audio folder. */
    public String file = "";
    /** First timeline tick of the clip. */
    public int start;
    /** Trim: first source tick played. */
    public double in;
    /** Trim: source tick where the clip stops (or wraps when looping). */
    public double out;
    public float volume = 1.0F;
    /** Fade in, in timeline ticks from the start of the clip. */
    public int fadeIn;
    /** Fade out, in timeline ticks before the end of the clip. */
    public int fadeOut;
    /** Repeat the source span for {@link #length} ticks. */
    public boolean loop;
    /** Timeline length of a looping clip, in ticks. */
    public int length;
    /** Looping clips: source position at the start of the clip, in ticks from {@link #in}. */
    public double offset;
    /** Playback rate; the pitch follows. */
    public float speed = 1.0F;

    public AudioClip() {
    }

    public AudioClip(String file, int start, double sourceTicks) {
        this.file = file;
        this.start = start;
        this.in = 0.0;
        this.out = sourceTicks;
    }

    public float speed() {
        return Float.isFinite(speed) ? Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed)) : 1.0F;
    }

    /** Length of the trimmed source, in source ticks. */
    public double span() {
        return Math.max(0.0, out - in);
    }

    /** Length on the timeline, in ticks. */
    public double length() {
        return loop ? Math.max(1, length) : span() / speed();
    }

    /** Timeline tick right after the clip. */
    public double end() {
        return start + length();
    }

    public boolean covers(double tick) {
        return tick >= start && tick < end();
    }

    /** Source tick heard at a timeline tick, or NaN outside the clip. */
    public double sourceAt(double tick) {
        double o = tick - start;
        if (o < 0 || o >= length()) {
            return Double.NaN;
        }
        if (loop) {
            double span = span();
            return span <= 0 ? Double.NaN : in + floorMod(offset + o * speed(), span);
        }
        return in + o * speed();
    }

    /** Gain of the fades at {@code o} ticks from the start of the clip. */
    public float fadeGain(double o) {
        float gain = 1.0F;
        if (fadeIn > 0 && o < fadeIn) {
            gain = (float) Math.max(0.0, o / fadeIn);
        }
        double left = length() - o;
        if (fadeOut > 0 && left < fadeOut) {
            gain = Math.min(gain, (float) Math.max(0.0, left / fadeOut));
        }
        return gain;
    }

    public AudioClip copy() {
        AudioClip c = new AudioClip();
        c.file = file;
        c.start = start;
        c.in = in;
        c.out = out;
        c.volume = volume;
        c.fadeIn = fadeIn;
        c.fadeOut = fadeOut;
        c.loop = loop;
        c.length = length;
        c.offset = offset;
        c.speed = speed;
        return c;
    }

    /**
     * Splits the clip at a timeline tick: this clip keeps the part before it and the returned clip holds the rest.
     * Returns null when the tick is not strictly inside the clip.
     */
    public AudioClip splitAt(int tick) {
        double o = tick - start;
        if (o < 1 || tick >= end() - 0.5) {
            return null;
        }
        AudioClip right = copy();
        right.start = tick;
        right.fadeIn = 0;
        if (loop) {
            int total = Math.max(1, length);
            length = (int) o;
            right.length = total - (int) o;
            double span = span();
            right.offset = span <= 0 ? 0 : floorMod(offset + o * speed(), span);
        } else {
            out = in + o * speed();
            right.in = out;
        }
        fadeOut = 0;
        clampFades();
        right.clampFades();
        return right;
    }

    /** Keeps both fades inside the clip. */
    public void clampFades() {
        int len = (int) Math.floor(length());
        fadeIn = Math.max(0, Math.min(fadeIn, len));
        fadeOut = Math.max(0, Math.min(fadeOut, len - fadeIn));
    }

    /** Repairs a clip read from an older or hand edited project. */
    public void normalize() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (file == null) file = "";
        if (!Double.isFinite(in) || in < 0) in = 0;
        if (!Double.isFinite(out) || out < in) out = in;
        if (!Double.isFinite(offset)) offset = 0;
        if (!(volume >= 0.0F && volume <= 4.0F)) volume = 1.0F;
        speed = speed();
        start = Math.max(0, start);
        length = Math.max(1, length);
        clampFades();
    }

    static double floorMod(double value, double modulus) {
        double r = value % modulus;
        return r < 0 ? r + modulus : r;
    }
}
