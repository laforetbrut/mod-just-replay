package com.vyrriox.justreplay.editor.audio;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * The live mix of the audio tracks, filled by the replay clock on the client thread and read by the streaming
 * channel of the sound engine. Like the recorded voices, an empty stream returns a short silence instead of
 * nothing, so the source never stops while the replay is paused.
 *
 * @author vyrriox
 */
final class MixStream implements AudioStream {
    static final AudioFormat FORMAT = new AudioFormat(AudioPcm.RATE, 16, 2, true, false);
    /** 40 ms: queued silences outlast one client tick even at twice the speed. */
    private static final int SILENCE_FRAMES = AudioPcm.RATE / 25;
    /** Audio queued beyond half a second is dropped so a stalled channel never drifts behind the replay. */
    private static final int MAX_QUEUED_FRAMES = AudioPcm.RATE / 2;

    /** Interleaved stereo blocks. */
    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private int offset;
    private int queuedFrames;

    synchronized void push(short[] interleaved) {
        queue.add(interleaved);
        queuedFrames += interleaved.length / 2;
        while (queuedFrames > MAX_QUEUED_FRAMES && queue.size() > 1) {
            short[] dropped = queue.poll();
            queuedFrames -= (dropped.length - offset) / 2;
            offset = 0;
        }
    }

    synchronized void clear() {
        queue.clear();
        queuedFrames = 0;
        offset = 0;
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public synchronized ByteBuffer read(int size) {
        int frames = Math.min(size / 4, queuedFrames);
        boolean silence = frames == 0;
        if (silence) {
            frames = Math.min(size / 4, SILENCE_FRAMES);
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (silence) {
            for (int i = 0; i < frames * 2; i++) {
                buffer.putShort((short) 0);
            }
        } else {
            int remaining = frames * 2;
            while (remaining > 0) {
                short[] head = queue.peek();
                int count = Math.min(remaining, head.length - offset);
                for (int i = 0; i < count; i++) {
                    buffer.putShort(head[offset + i]);
                }
                offset += count;
                remaining -= count;
                queuedFrames -= count / 2;
                if (offset >= head.length) {
                    queue.poll();
                    offset = 0;
                }
            }
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public void close() {
        clear();
    }
}
