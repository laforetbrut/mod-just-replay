package com.vyrriox.justreplay.editor.audio;

/**
 * A decoded audio file: 48 kHz stereo float samples, with peak tables precomputed for the waveform previews.
 *
 * @author vyrriox
 */
public final class AudioPcm {
    public static final int RATE = 48_000;
    /** Samples per replay tick. */
    public static final int PER_TICK = RATE / 20;
    private static final int FINE = 256;
    private static final int COARSE = 4096;

    public final float[] left;
    public final float[] right;
    public final int frames;
    private final byte[] fine;
    private final byte[] coarse;

    public AudioPcm(float[] left, float[] right) {
        this.left = left;
        this.right = right;
        this.frames = Math.min(left.length, right.length);
        this.fine = peaks(FINE);
        this.coarse = peaks(COARSE);
    }

    /** Length of the file in ticks. */
    public double lengthTicks() {
        return frames / (double) PER_TICK;
    }

    /** Memory held by the samples. */
    public long bytes() {
        return (long) frames * 8L;
    }

    private byte[] peaks(int bucket) {
        byte[] peaks = new byte[(frames + bucket - 1) / bucket];
        for (int b = 0; b < peaks.length; b++) {
            float max = 0.0F;
            int end = Math.min(frames, (b + 1) * bucket);
            for (int i = b * bucket; i < end; i++) {
                max = Math.max(max, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            }
            peaks[b] = (byte) Math.round(Math.min(1.0F, max) * 255.0F);
        }
        return peaks;
    }

    /** Highest level (0..1) between two frames, from the peak tables. */
    public float peak(long from, long to) {
        from = Math.max(0, from);
        to = Math.min(frames, to);
        if (to <= from) {
            return 0.0F;
        }
        boolean wide = to - from >= COARSE * 4L;
        byte[] table = wide ? coarse : fine;
        int bucket = wide ? COARSE : FINE;
        int a = (int) (from / bucket);
        int b = (int) Math.min(table.length - 1, (to - 1) / bucket);
        int max = 0;
        for (int i = a; i <= b; i++) {
            max = Math.max(max, table[i] & 0xFF);
        }
        return max / 255.0F;
    }
}
