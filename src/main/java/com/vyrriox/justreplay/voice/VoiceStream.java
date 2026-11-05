package com.vyrriox.justreplay.voice;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * Live audio of one speaker, fed by the replay clock on the client thread and read by the sound engine's
 * streaming channel. The channel only reads again when a queued buffer finished playing, so an empty stream
 * returns a short silence instead of nothing: the source never runs dry while the speaker is live.
 *
 * @author vyrriox
 */
final class VoiceStream implements AudioStream {
    static final AudioFormat FORMAT = new AudioFormat(VoiceTrack.SAMPLE_RATE, 16, 1, true, false);
    /** 40 ms: four queued silences outlast one client tick even at twice the speed. */
    private static final int SILENCE_SAMPLES = VoiceTrack.SAMPLE_RATE / 25;
    /** Audio queued beyond 300 ms is dropped so a stalled channel never drifts behind the replay. */
    private static final int MAX_QUEUED_SAMPLES = VoiceTrack.SAMPLE_RATE * 3 / 10;

    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private int offset;
    private int queuedSamples;

    synchronized void push(short[] pcm) {
        queue.add(pcm);
        queuedSamples += pcm.length;
        while (queuedSamples > MAX_QUEUED_SAMPLES && queue.size() > 1) {
            short[] dropped = queue.poll();
            queuedSamples -= dropped.length - offset;
            offset = 0;
        }
    }

    synchronized void clear() {
        queue.clear();
        queuedSamples = 0;
        offset = 0;
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public synchronized ByteBuffer read(int size) {
        int samples = Math.min(size / 2, queuedSamples);
        boolean silence = samples == 0;
        if (silence) {
            samples = Math.min(size / 2, SILENCE_SAMPLES);
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        if (silence) {
            for (int i = 0; i < samples; i++) {
                buffer.putShort((short) 0);
            }
        } else {
            int remaining = samples;
            while (remaining > 0) {
                short[] head = queue.peek();
                int count = Math.min(remaining, head.length - offset);
                for (int i = 0; i < count; i++) {
                    buffer.putShort(head[offset + i]);
                }
                offset += count;
                remaining -= count;
                queuedSamples -= count;
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
