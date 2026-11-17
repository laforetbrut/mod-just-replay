package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.JustReplay;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader of Emotecraft binary emote files ({@code *.emotecraft}), written from the bytes of sample files. Everything
 * is big-endian:
 * <ul>
 *     <li>header: int format version, 4 unknown bytes, int size of the animation chunk that follows;</li>
 *     <li>animation: int (unknown, 0), int begin tick, int end tick, int stop tick, byte looped, int return tick,
 *     3 unknown bytes, int part count;</li>
 *     <li>each part: int length and ASCII name, then axes. An axis is a byte "enabled", an int key count and keys of
 *     9 bytes (int tick, float value, byte ease id). Axes 0 to 5 are x, y, z (pixels) and pitch, yaw, roll
 *     (radians); later axes (bend, bend direction, scale) are skipped. The axis count differs between parts and
 *     versions, so a part ends where the next name starts, or 16 bytes (a UUID) before the end of the chunk;</li>
 *     <li>after the chunk: optional chunks of a byte id and an int size; id 2 holds three strings (int length and
 *     text): name, description, author.</li>
 * </ul>
 *
 * @author vyrriox
 */
final class EmotecraftBinary {
    private static final int UUID_BYTES = 16;
    private static final int MAX_AXES = 32;

    private EmotecraftBinary() {
    }

    /** Reads one emote; throws with a short reason when the file does not hold together. */
    static EmoteLibrary.Emote read(String id, byte[] data) {
        try {
            return parse(id, ByteBuffer.wrap(data));
        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("truncated Emotecraft file");
        }
    }

    private static EmoteLibrary.Emote parse(String id, ByteBuffer buf) {
        int version = buf.getInt();
        if (version < 1 || version > 64) {
            throw new IllegalArgumentException("not an Emotecraft binary file (version " + version + ")");
        }
        buf.getInt();
        int size = buf.getInt();
        int start = buf.position();
        int chunkEnd = start + size;
        if (size < 33 || chunkEnd > buf.limit()) {
            throw new IllegalArgumentException("bad animation size " + size);
        }
        buf.getInt();
        int begin = buf.getInt();
        int end = buf.getInt();
        buf.getInt(); // stop tick
        boolean loop = buf.get() != 0;
        int returnTick = buf.getInt();
        buf.position(buf.position() + 3);
        int parts = buf.getInt();
        if (parts < 0 || parts > 64) {
            throw new IllegalArgumentException("bad part count " + parts);
        }

        Map<Integer, List<EmoteLibrary.Key>> channels = new HashMap<>();
        int last = 0;
        boolean unknownEase = false;
        for (int p = 0; p < parts; p++) {
            if (!isName(buf, buf.position(), chunkEnd)) {
                throw new IllegalArgumentException("part " + p + " has no name");
            }
            byte[] nameBytes = new byte[buf.getInt()];
            buf.get(nameBytes);
            int part = EmoteLibrary.emotecraftPart(new String(nameBytes, StandardCharsets.US_ASCII));
            boolean lastPart = p == parts - 1;
            for (int axis = 0; axis < MAX_AXES; axis++) {
                int pos = buf.position();
                if (pos >= chunkEnd || (lastPart ? chunkEnd - pos <= UUID_BYTES : isName(buf, pos, chunkEnd))) {
                    break;
                }
                buf.get(); // enabled
                int count = buf.getInt();
                if (count < 0 || pos + 5L + count * 9L > chunkEnd) {
                    throw new IllegalArgumentException("bad key count " + count);
                }
                int ch = axis < 3 ? axis + 3 : axis < 6 ? axis - 3 : -1;
                List<EmoteLibrary.Key> keys = part >= 0 && ch >= 0
                        ? channels.computeIfAbsent(part * 6 + ch, k -> new ArrayList<>()) : null;
                for (int k = 0; k < count; k++) {
                    int tick = buf.getInt();
                    float value = buf.getFloat();
                    int ease = buf.get() & 0xFF;
                    int mode = EmoteLibrary.binaryEase(ease);
                    if (mode < 0) {
                        unknownEase = true;
                        mode = 0;
                    }
                    if (keys != null && Float.isFinite(value)) {
                        keys.add(EmoteLibrary.key(tick, value, mode));
                        last = Math.max(last, tick);
                    }
                }
            }
        }
        if (unknownEase) {
            JustReplay.LOGGER.debug("Emote {} uses unknown ease ids, played linear", id);
        }

        String name = null;
        buf.position(chunkEnd);
        while (buf.remaining() >= 5) {
            int chunk = buf.get() & 0xFF;
            if (chunk == 0x11 && buf.remaining() >= 5 && (buf.get(buf.position()) & 0xFF) == 2) {
                continue; // marker byte before the info chunk
            }
            int len = buf.getInt();
            if (len < 0 || len > buf.remaining()) {
                break;
            }
            int next = buf.position() + len;
            if (chunk == 2 && len >= 4) {
                name = text(buf, next);
            }
            buf.position(next);
        }
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("no animated part");
        }
        EmoteLibrary.Emote emote = EmoteLibrary.newEmote(id, name, loop, Math.max(begin, returnTick));
        EmoteLibrary.fillEmotecraft(emote, channels, begin, end + 1, last);
        return emote;
    }

    /** True when an int length of 1 to 64 followed by that many printable ASCII bytes starts at {@code pos}. */
    private static boolean isName(ByteBuffer buf, int pos, int limit) {
        if (pos + 4 > limit) {
            return false;
        }
        int n = buf.getInt(pos);
        if (n < 1 || n > 64 || pos + 4 + n > limit) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            int c = buf.get(pos + 4 + i) & 0xFF;
            if (c <= 32 || c >= 127) {
                return false;
            }
        }
        return true;
    }

    /** First string of the info chunk, without the JSON quotes it is stored with. */
    private static String text(ByteBuffer buf, int limit) {
        int n = buf.getInt();
        if (n < 0 || buf.position() + n > limit) {
            return null;
        }
        byte[] b = new byte[n];
        buf.get(b);
        String s = new String(b, StandardCharsets.UTF_8).trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.isBlank() ? null : s;
    }
}
