package com.vyrriox.justreplay.voice;

import com.vyrriox.justreplay.JustReplay;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Recorded voice chat of a replay, stored beside the packet stream as {@code voice.bin.gz}: a gzip stream holding
 * a header ({@link #MAGIC}, {@link #VERSION}) followed by one record per 20 ms voice frame:
 * {@code tick, type, uuid (two longs), x, y, z, distance (floats), codec, length, data}. Frames are 48 kHz mono,
 * Opus encoded when the voice chat encoder is available, 16-bit PCM otherwise.
 *
 * @author vyrriox
 */
public final class VoiceTrack {
    public static final String FILE = "voice.bin.gz";
    public static final int MAGIC = 0x4A525643;
    public static final int VERSION = 1;
    public static final int SAMPLE_RATE = 48_000;
    /** Samples of one voice chat frame (20 ms). */
    public static final int FRAME_SAMPLES = 960;

    /** Voice of another player, heard at its entity. */
    public static final byte ENTITY = 0;
    /** Voice heard at a fixed position (voice chat API sounds). */
    public static final byte LOCATIONAL = 1;
    /** Voice heard without position (groups, API sounds). */
    public static final byte STATIC = 2;
    /** Microphone of the recording player, heard at its actor. */
    public static final byte MICROPHONE = 3;

    public static final byte CODEC_PCM = 0;
    public static final byte CODEC_OPUS = 1;

    /** A speaker: one voice channel, keyed by UUID and type. */
    public record Speaker(UUID uuid, byte type) {
        public boolean positional() {
            return type != STATIC;
        }

        /** True when the voice follows an entity of the replay with this UUID. */
        public boolean followsEntity() {
            return type == ENTITY || type == MICROPHONE;
        }
    }

    /** One voice frame; the position is NaN when unknown. */
    public record Frame(int tick, int speaker, float x, float y, float z, float distance, byte codec, byte[] data) {
        public boolean hasPosition() {
            return !Float.isNaN(x) && !Float.isNaN(y) && !Float.isNaN(z);
        }
    }

    private final List<Speaker> speakers;
    private final List<Frame> frames;

    private VoiceTrack(List<Speaker> speakers, List<Frame> frames) {
        this.speakers = speakers;
        this.frames = frames;
    }

    public List<Speaker> speakers() {
        return speakers;
    }

    public List<Frame> frames() {
        return frames;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /** Index of the first frame recorded after the given tick ({@code frames().size()} when there is none). */
    public int firstAfter(int tick) {
        int low = 0;
        int high = frames.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (frames.get(mid).tick() <= tick) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** Reads the voice track of a replay, or null when it has none. A truncated file is read up to the damage. */
    public static VoiceTrack read(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            return null;
        }
        List<Speaker> speakers = new ArrayList<>();
        Map<Speaker, Integer> index = new HashMap<>();
        List<Frame> frames = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
            if (in.readInt() != MAGIC) {
                JustReplay.LOGGER.warn("Ignoring {}: not a voice track", file);
                return null;
            }
            int version = in.readInt();
            if (version > VERSION) {
                JustReplay.LOGGER.warn("Ignoring {}: made by a newer version (format {})", file, version);
                return null;
            }
            while (true) {
                int tick;
                try {
                    tick = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                byte type = in.readByte();
                UUID uuid = new UUID(in.readLong(), in.readLong());
                float x = in.readFloat();
                float y = in.readFloat();
                float z = in.readFloat();
                float distance = in.readFloat();
                byte codec = in.readByte();
                int length = in.readUnsignedShort();
                byte[] data = new byte[length];
                in.readFully(data);
                Speaker speaker = new Speaker(uuid, type);
                Integer id = index.get(speaker);
                if (id == null) {
                    id = speakers.size();
                    speakers.add(speaker);
                    index.put(speaker, id);
                }
                frames.add(new Frame(tick, id, x, y, z, distance, codec, data));
            }
        } catch (IOException damaged) {
            JustReplay.LOGGER.warn("Voice track {} is damaged ({}), keeping {} frames", file, damaged.toString(), frames.size());
        }
        // Frames are written in arrival order; ticks never go back, but keep the order stable if they do.
        frames.sort(java.util.Comparator.comparingInt(Frame::tick));
        return new VoiceTrack(speakers, frames);
    }

    /** Decodes a frame with a speaker's decoder (needed for Opus frames); null when it cannot be decoded. */
    public static short[] decode(Frame frame, VoiceBridge.Codec decoder) {
        byte[] data = frame.data();
        if (frame.codec() == CODEC_PCM) {
            short[] pcm = new short[data.length / 2];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) ((data[i * 2] & 0xFF) | (data[i * 2 + 1] << 8));
            }
            return pcm;
        }
        if (frame.codec() == CODEC_OPUS && decoder != null) {
            try {
                return decoder.decode(data);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Little endian 16-bit PCM bytes of a frame. */
    static byte[] encodePcm(short[] pcm) {
        byte[] data = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            data[i * 2] = (byte) pcm[i];
            data[i * 2 + 1] = (byte) (pcm[i] >> 8);
        }
        return data;
    }
}
