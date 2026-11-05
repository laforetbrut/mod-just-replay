package com.vyrriox.justreplay.editor.audio;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Decodes the audio files of the audio folder to 48 kHz stereo floats: Ogg Vorbis through the decoder of the game,
 * WAV (integer PCM 8, 16, 24 or 32 bits, float 32 or 64 bits, any rate, any channel count) with a small parser.
 * Mono files are played on both sides, files with more than two channels keep their first two.
 *
 * @author vyrriox
 */
public final class AudioDecoder {
    /** Longest file accepted, in seconds. */
    public static final int MAX_SECONDS = 10 * 60;
    private static final int WAVE_PCM = 1;
    private static final int WAVE_FLOAT = 3;
    private static final int WAVE_EXTENSIBLE = 0xFFFE;

    private AudioDecoder() {
    }

    /** Thrown with a message meant for the user. */
    public static final class DecodeException extends IOException {
        public DecodeException(String message) {
            super(message);
        }
    }

    public static boolean isSupported(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        return name.endsWith(".ogg") || name.endsWith(".wav");
    }

    /** Audio files the editor recognises but cannot read: they must be converted. */
    public static boolean isConvertible(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".opus");
    }

    public static AudioPcm decode(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ogg")) {
            return decodeOgg(file);
        }
        if (name.endsWith(".wav")) {
            return decodeWav(file);
        }
        throw new DecodeException("unsupported format, convert it to OGG or WAV");
    }

    // ---------------------------------------------------------------- ogg

    private static AudioPcm decodeOgg(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            int channels = Math.max(1, stream.getFormat().getChannels());
            float rate = stream.getFormat().getSampleRate();
            long limit = (long) (rate * MAX_SECONDS) * channels;
            FloatArrayList samples = new FloatArrayList(1 << 20);
            while (stream.readChunk(samples::add)) {
                if (samples.size() > limit) {
                    throw new DecodeException("longer than " + MAX_SECONDS / 60 + " minutes");
                }
            }
            float[] data = samples.elements();
            int frames = samples.size() / channels;
            float[] left = new float[frames];
            float[] right = new float[frames];
            for (int i = 0; i < frames; i++) {
                left[i] = data[i * channels];
                right[i] = channels > 1 ? data[i * channels + 1] : left[i];
            }
            return resample(left, right, rate);
        }
    }

    // ---------------------------------------------------------------- wav

    private static AudioPcm decodeWav(Path file) throws IOException {
        long fileSize = Files.size(file);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (!"RIFF".equals(fourCc(in))) {
                throw new DecodeException("not a WAV file");
            }
            readIntLe(in);
            if (!"WAVE".equals(fourCc(in))) {
                throw new DecodeException("not a WAV file");
            }
            int format = -1;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            int blockAlign = 0;
            while (true) {
                String id;
                try {
                    id = fourCc(in);
                } catch (EOFException end) {
                    throw new DecodeException("no audio data in the WAV file");
                }
                long size = readIntLe(in) & 0xFFFFFFFFL;
                if ("fmt ".equals(id)) {
                    format = readShortLe(in);
                    channels = readShortLe(in);
                    rate = readIntLe(in);
                    readIntLe(in); // byte rate
                    blockAlign = readShortLe(in);
                    bits = readShortLe(in);
                    long read = 16;
                    if (format == WAVE_EXTENSIBLE && size >= 40) {
                        readShortLe(in); // extension size
                        readShortLe(in); // valid bits
                        readIntLe(in); // channel mask
                        format = readShortLe(in); // first bytes of the sub format GUID
                        read += 10;
                    }
                    skip(in, size - read + (size & 1));
                } else if ("data".equals(id)) {
                    if (format < 0) {
                        throw new DecodeException("WAV data before its format");
                    }
                    if (size == 0xFFFFFFFFL || size == 0) {
                        size = fileSize; // streamed WAV: read up to the end
                    }
                    return readSamples(in, size, format, channels, rate, bits, blockAlign);
                } else {
                    skip(in, size + (size & 1));
                }
            }
        }
    }

    private static AudioPcm readSamples(DataInputStream in, long size, int format, int channels, int rate, int bits, int blockAlign)
            throws IOException {
        boolean isFloat = format == WAVE_FLOAT;
        if (format != WAVE_PCM && !isFloat) {
            throw new DecodeException("compressed WAV (format " + format + "), export it as PCM");
        }
        if (channels < 1 || rate < 1000 || rate > 768_000) {
            throw new DecodeException("invalid WAV format");
        }
        int bytes = (bits + 7) / 8;
        if (isFloat ? bytes != 4 && bytes != 8 : bytes < 1 || bytes > 4) {
            throw new DecodeException("unsupported WAV sample size: " + bits + " bits");
        }
        int frameBytes = Math.max(blockAlign, bytes * channels);
        long frames = size / frameBytes;
        if (frames > (long) rate * MAX_SECONDS) {
            throw new DecodeException("longer than " + MAX_SECONDS / 60 + " minutes");
        }
        FloatArrayList left = new FloatArrayList((int) Math.min(frames, 1 << 22));
        FloatArrayList right = new FloatArrayList((int) Math.min(frames, 1 << 22));
        byte[] frame = new byte[frameBytes];
        for (long f = 0; f < frames; f++) {
            try {
                in.readFully(frame);
            } catch (EOFException end) {
                break; // truncated file: keep what was read
            }
            float l = sample(frame, 0, bytes, isFloat);
            left.add(l);
            right.add(channels > 1 ? sample(frame, bytes, bytes, isFloat) : l);
        }
        return resample(left.toFloatArray(), right.toFloatArray(), rate);
    }

    private static float sample(byte[] b, int at, int bytes, boolean isFloat) {
        if (isFloat) {
            if (bytes == 4) {
                return Float.intBitsToFloat(intLe(b, at, 4));
            }
            long lo = intLe(b, at, 4) & 0xFFFFFFFFL;
            long hi = intLe(b, at + 4, 4) & 0xFFFFFFFFL;
            return (float) Double.longBitsToDouble(lo | hi << 32);
        }
        switch (bytes) {
            case 1:
                return ((b[at] & 0xFF) - 128) / 128.0F; // 8-bit WAV is unsigned
            case 2:
                return (short) intLe(b, at, 2) / 32768.0F;
            case 3:
                return (intLe(b, at, 3) << 8 >> 8) / 8388608.0F;
            default:
                return intLe(b, at, 4) / 2147483648.0F;
        }
    }

    private static int intLe(byte[] b, int at, int bytes) {
        int v = 0;
        for (int i = bytes - 1; i >= 0; i--) {
            v = v << 8 | b[at + i] & 0xFF;
        }
        return v;
    }

    private static String fourCc(DataInputStream in) throws IOException {
        byte[] id = new byte[4];
        in.readFully(id);
        return new String(id, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readIntLe(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static int readShortLe(DataInputStream in) throws IOException {
        return Short.reverseBytes(in.readShort()) & 0xFFFF;
    }

    private static void skip(DataInputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException();
                }
                skipped = 1;
            }
            count -= skipped;
        }
    }

    // ---------------------------------------------------------------- rate

    /**
     * Brings the samples to 48 kHz. Upsampling interpolates linearly; downsampling averages the source samples
     * under each output sample first, which keeps most of the aliasing out.
     */
    static AudioPcm resample(float[] left, float[] right, float rate) {
        if (Math.abs(rate - AudioPcm.RATE) < 0.5F) {
            return new AudioPcm(left, right);
        }
        int frames = left.length;
        double step = rate / AudioPcm.RATE;
        int out = (int) Math.floor(frames / step);
        float[] l = new float[out];
        float[] r = new float[out];
        if (step <= 1.0) {
            for (int i = 0; i < out; i++) {
                double pos = i * step;
                int a = (int) pos;
                int b = Math.min(frames - 1, a + 1);
                float t = (float) (pos - a);
                l[i] = left[a] + (left[b] - left[a]) * t;
                r[i] = right[a] + (right[b] - right[a]) * t;
            }
        } else {
            for (int i = 0; i < out; i++) {
                int a = (int) (i * step);
                int b = Math.min(frames, Math.max(a + 1, (int) ((i + 1) * step)));
                float sl = 0.0F;
                float sr = 0.0F;
                for (int j = a; j < b; j++) {
                    sl += left[j];
                    sr += right[j];
                }
                l[i] = sl / (b - a);
                r[i] = sr / (b - a);
            }
        }
        return new AudioPcm(l, r);
    }
}
