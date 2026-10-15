package com.vyrriox.justreplay.voice;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the voice chat heard while recording into {@code voice.bin.gz}, never into the packet stream. Voice chat
 * threads only copy each frame into a queue with the current recording tick; the client thread adds positions
 * (entity voices are placed where the speaker stands) and a dedicated thread encodes and writes. The file is only
 * created once the first voice frame arrives.
 *
 * @author vyrriox
 */
public final class VoiceRecorder {
    private record Pending(int tick, byte type, UUID uuid, double x, double y, double z, float distance, short[] pcm) {
    }

    /** About ten seconds of frames for a few speakers: beyond that the client thread is stalled, drop frames. */
    private static final int MAX_QUEUED = 2000;
    private static final int FLUSH_TICKS = 100;
    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static VoiceRecorder current;

    private final Recorder recorder;
    private final ExecutorService executor;
    private final DataOutputStream out;
    private final Map<String, VoiceBridge.Codec> encoders = new HashMap<>();
    private final Map<UUID, Entity> entities = new HashMap<>();
    private int lastFlushTick;
    private volatile boolean failed;

    private VoiceRecorder(Recorder recorder, DataOutputStream out) {
        this.recorder = recorder;
        this.out = out;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JustReplay voice writer");
            t.setDaemon(true);
            return t;
        });
    }

    // ---------------------------------------------------------------- voice chat threads

    /** A voice frame received from the voice chat; the position is NaN for entity and static voices. */
    public static void onVoice(byte type, UUID uuid, double x, double y, double z, float distance, short[] pcm) {
        Recorder recorder = Recorder.active();
        if (recorder == null || uuid == null || pcm == null || pcm.length == 0 || QUEUE.size() > MAX_QUEUED
                || !JRConfig.CAPTURE_VOICE_CHAT.get()) {
            return;
        }
        QUEUE.add(new Pending(recorder.tick(), type, uuid, x, y, z, distance, pcm.clone()));
    }

    /** A frame of the local microphone, as sent to the other players. */
    public static void onMicrophone(float distance, short[] pcm) {
        Recorder recorder = Recorder.active();
        if (recorder == null || recorder.actorUuid() == null) {
            return;
        }
        onVoice(VoiceTrack.MICROPHONE, recorder.actorUuid(), Double.NaN, Double.NaN, Double.NaN, distance, pcm);
    }

    // ---------------------------------------------------------------- client thread

    /** Places the queued frames and hands them to the writer thread. Called every recording tick. */
    public static void clientTick(Minecraft mc, Recorder recorder) {
        if (QUEUE.isEmpty()) {
            return;
        }
        VoiceRecorder voice = current;
        if (voice == null || voice.recorder != recorder) {
            if (voice != null) {
                voice.close();
            }
            voice = current = open(recorder);
            if (voice == null) {
                QUEUE.clear();
                return;
            }
        }
        Pending pending;
        while ((pending = QUEUE.poll()) != null) {
            voice.place(mc, pending);
        }
    }

    public static void onRecordingStopped(Recorder recorder) {
        QUEUE.clear();
        VoiceRecorder voice = current;
        if (voice != null && voice.recorder == recorder) {
            current = null;
            voice.close();
        }
    }

    private static VoiceRecorder open(Recorder recorder) {
        try {
            Path file = recorder.dir().resolve(VoiceTrack.FILE);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(file), 1 << 16, true), 1 << 16));
            out.writeInt(VoiceTrack.MAGIC);
            out.writeInt(VoiceTrack.VERSION);
            JustReplay.LOGGER.info("Recording voice chat into {}", file);
            return new VoiceRecorder(recorder, out);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot record voice chat", e);
            return null;
        }
    }

    private void place(Minecraft mc, Pending p) {
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
            }
        }
        Pending placed = new Pending(p.tick(), p.type(), p.uuid(), x, y, z, p.distance(), p.pcm());
        if (!failed) {
            executor.execute(() -> write(placed));
        }
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

    private void write(Pending p) {
        try {
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
                return;
            }
            out.writeInt(p.tick());
            out.writeByte(p.type());
            out.writeLong(p.uuid().getMostSignificantBits());
            out.writeLong(p.uuid().getLeastSignificantBits());
            out.writeFloat((float) p.x());
            out.writeFloat((float) p.y());
            out.writeFloat((float) p.z());
            out.writeFloat(p.distance());
            out.writeByte(codec);
            out.writeShort(data.length);
            out.write(data);
            if (p.tick() - lastFlushTick >= FLUSH_TICKS) {
                // Crash safety, as for the packet stream.
                lastFlushTick = p.tick();
                out.flush();
            }
        } catch (IOException e) {
            failed = true;
            JustReplay.LOGGER.error("Failed to write voice chat data", e);
        }
    }

    private void close() {
        executor.execute(() -> {
            try {
                out.close();
            } catch (IOException e) {
                JustReplay.LOGGER.error("Failed to close the voice track", e);
            }
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
