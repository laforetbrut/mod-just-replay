package com.vyrriox.justreplay.voice;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.ReplayBuffer;
import com.vyrriox.justreplay.format.SegmentedStream;
import com.vyrriox.justreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records the voice chat heard while capturing, never into the packet stream: into {@code voice.bin.gz} of the
 * running recording and into the replay buffer. Voice chat threads only copy each frame into a queue with the
 * current capture tick; the client thread adds positions (entity voices are placed where the speaker stands) and
 * hands the tick's frames to a low priority thread in one batch, which encodes each frame once and writes it to
 * every output. The file is only created once the first voice frame arrives.
 *
 * @author vyrriox
 */
public final class VoiceRecorder {
    private record Pending(int tick, byte type, UUID uuid, double x, double y, double z, float distance, short[] pcm) {
    }

    /** About ten seconds of frames for a few speakers: beyond that the client thread is stalled, drop frames. */
    private static final int MAX_QUEUED = 2000;
    /** Crash safety flushes, rare enough to spare the drive. */
    private static final int FLUSH_TICKS = 600;
    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static VoiceRecorder current;

    private final Recorder capture;
    private final ExecutorService executor;
    private final Map<UUID, Entity> entities = new HashMap<>();

    // ---------------------------------------------------------------- writer thread state
    private final Map<String, VoiceBridge.Codec> encoders = new HashMap<>();
    private Recorder.Recording fileOwner;
    /** Recording whose track was completed; late frames never reopen (and truncate) it. */
    private Recorder.Recording closedOwner;
    private DataOutputStream out;
    private int lastFlushTick;
    private boolean failed;

    private VoiceRecorder(Recorder capture) {
        this.capture = capture;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JustReplay voice writer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ---------------------------------------------------------------- voice chat threads

    /** A voice frame received from the voice chat; the position is NaN for entity and static voices. */
    public static void onVoice(byte type, UUID uuid, double x, double y, double z, float distance, short[] pcm) {
        Recorder capture = Recorder.capturing();
        if (capture == null || uuid == null || pcm == null || pcm.length == 0 || QUEUED.get() > MAX_QUEUED
                || !JRConfig.CAPTURE_VOICE_CHAT.get()) {
            return;
        }
        QUEUED.incrementAndGet();
        QUEUE.add(new Pending(capture.captureTick(), type, uuid, x, y, z, distance, pcm.clone()));
    }

    /** A frame of the local microphone, as sent to the other players. */
    public static void onMicrophone(float distance, short[] pcm) {
        Recorder capture = Recorder.capturing();
        if (capture == null || capture.actorUuid() == null) {
            return;
        }
        onVoice(VoiceTrack.MICROPHONE, capture.actorUuid(), Double.NaN, Double.NaN, Double.NaN, distance, pcm);
    }

    // ---------------------------------------------------------------- client thread

    /** Places the queued frames and hands them to the writer thread in one batch. Called every capture tick. */
    public static void clientTick(Minecraft mc, Recorder capture) {
        if (QUEUE.isEmpty()) {
            return;
        }
        VoiceRecorder voice = current;
        if (voice == null || voice.capture != capture) {
            if (voice != null) {
                voice.shutdown();
            }
            voice = current = new VoiceRecorder(capture);
        }
        List<Pending> batch = new ArrayList<>();
        Pending pending;
        while ((pending = QUEUE.poll()) != null) {
            QUEUED.decrementAndGet();
            batch.add(voice.place(mc, pending));
        }
        Recorder.Recording recording = capture.recording();
        ReplayBuffer buffer = capture.buffer();
        if (recording == null && buffer == null) {
            return;
        }
        VoiceRecorder target = voice;
        target.submit(() -> target.writeBatch(batch, recording, buffer));
    }

    /** Completes the voice track of a recording that just stopped; the buffer, if any, keeps receiving frames. */
    public static void onRecordingStopped(Minecraft mc, Recorder capture, Recorder.Recording recording) {
        VoiceRecorder voice = current;
        if (voice == null || voice.capture != capture) {
            return;
        }
        // Frames heard up to now still belong to the recording.
        clientTick(mc, capture);
        Future<?> closed = voice.submit(() -> voice.closeFile(recording));
        if (closed != null) {
            try {
                closed.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                JustReplay.LOGGER.warn("Voice track of {} not completed in time: {}", recording.dir(), e.toString());
            }
        }
    }

    /** The capture ended: drops pending frames and closes everything. */
    public static void onCaptureEnded(Recorder capture) {
        QUEUE.clear();
        QUEUED.set(0);
        VoiceRecorder voice = current;
        if (voice != null && voice.capture == capture) {
            current = null;
            voice.shutdown();
        }
    }

    private Future<?> submit(Runnable task) {
        try {
            return executor.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return null;
        }
    }

    private Pending place(Minecraft mc, Pending p) {
        double x = p.x();
        double y = p.y();
        double z = p.z();
        if (p.type() == VoiceTrack.MICROPHONE) {
            LocalPlayer player = mc.player;
            if (player != null) {
                x = player.getX();
                y = player.getEyeY();
                z = player.getZ();
            }
        } else if (p.type() == VoiceTrack.ENTITY) {
            Entity entity = findEntity(mc.level, p.uuid());
            if (entity != null) {
                x = entity.getX();
                y = entity.getEyeY();
                z = entity.getZ();
            } else {
                return p;
            }
        } else {
            return p;
        }
        return new Pending(p.tick(), p.type(), p.uuid(), x, y, z, p.distance(), p.pcm());
    }

    private Entity findEntity(ClientLevel level, UUID uuid) {
        if (level == null) {
            return null;
        }
        Entity cached = entities.get(uuid);
        if (cached != null && !cached.isRemoved() && cached.level() == level) {
            return cached;
        }
        Entity found = level.getPlayerByUUID(uuid);
        if (found == null) {
            for (Entity entity : level.entitiesForRendering()) {
                if (uuid.equals(entity.getUUID())) {
                    found = entity;
                    break;
                }
            }
        }
        if (found != null) {
            entities.put(uuid, found);
        } else {
            entities.remove(uuid);
        }
        return found;
    }

    // ---------------------------------------------------------------- writer thread

    private void writeBatch(List<Pending> batch, Recorder.Recording recording, ReplayBuffer buffer) {
        for (Pending p : batch) {
            byte[] body = encode(p);
            if (body == null) {
                continue;
            }
            if (recording != null && p.tick() >= recording.startTick()) {
                writeFile(recording, p.tick() - recording.startTick(), body);
            }
            if (buffer != null) {
                buffer.writeVoice(p.tick(), body);
            }
        }
    }

    /** The record of a frame after its tick: type, uuid, position, distance, codec, length, data. */
    private byte[] encode(Pending p) {
        byte codec = VoiceTrack.CODEC_PCM;
        byte[] data = null;
        if (p.pcm().length == VoiceTrack.FRAME_SAMPLES) {
            String key = p.type() + ":" + p.uuid();
            VoiceBridge.Codec encoder = encoders.get(key);
            if (encoder == null && !encoders.containsKey(key)) {
                encoder = VoiceBridge.newEncoder();
                encoders.put(key, encoder);
            }
            if (encoder != null) {
                try {
                    data = encoder.encode(p.pcm());
                    codec = VoiceTrack.CODEC_OPUS;
                } catch (Throwable t) {
                    data = null;
                }
            }
        }
        if (data == null || data.length > 0xFFFF) {
            data = VoiceTrack.encodePcm(p.pcm());
            codec = VoiceTrack.CODEC_PCM;
        }
        if (data.length > 0xFFFF) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(40 + data.length);
        try (DataOutputStream body = new DataOutputStream(bytes)) {
            body.writeByte(p.type());
            body.writeLong(p.uuid().getMostSignificantBits());
            body.writeLong(p.uuid().getLeastSignificantBits());
            body.writeFloat((float) p.x());
            body.writeFloat((float) p.y());
            body.writeFloat((float) p.z());
            body.writeFloat(p.distance());
            body.writeByte(codec);
            body.writeShort(data.length);
            body.write(data);
        } catch (IOException impossible) {
            return null;
        }
        return bytes.toByteArray();
    }

    private void writeFile(Recorder.Recording recording, int tick, byte[] body) {
        if (recording == closedOwner) {
            return;
        }
        if (fileOwner != recording) {
            closeFile(fileOwner);
            fileOwner = recording;
            failed = false;
            lastFlushTick = 0;
            try {
                Path file = recording.dir().resolve(VoiceTrack.FILE);
                out = new DataOutputStream(new BufferedOutputStream(SegmentedStream.gzip(Files.newOutputStream(file), 1 << 16, true, SegmentedStream.STREAM_LEVEL), 1 << 16));
                out.writeInt(VoiceTrack.MAGIC);
                out.writeInt(VoiceTrack.VERSION);
                JustReplay.LOGGER.info("Recording voice chat into {}", file);
            } catch (IOException e) {
                failed = true;
                out = null;
                JustReplay.LOGGER.error("Cannot record voice chat", e);
            }
        }
        if (failed || out == null) {
            return;
        }
        try {
            out.writeInt(tick);
            out.write(body);
            if (tick - lastFlushTick >= FLUSH_TICKS) {
                // Crash safety, as for the packet stream.
                lastFlushTick = tick;
                out.flush();
            }
        } catch (IOException e) {
            failed = true;
            JustReplay.LOGGER.error("Failed to write voice chat data", e);
        }
    }

    private void closeFile(Recorder.Recording recording) {
        if (recording == null) {
            return;
        }
        closedOwner = recording;
        if (fileOwner != recording) {
            return;
        }
        fileOwner = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                JustReplay.LOGGER.error("Failed to close the voice track", e);
            }
            out = null;
        }
    }

    private void shutdown() {
        submit(() -> {
            closeFile(fileOwner);
            for (VoiceBridge.Codec encoder : encoders.values()) {
                if (encoder != null) {
                    try {
                        encoder.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            encoders.clear();
        });
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
