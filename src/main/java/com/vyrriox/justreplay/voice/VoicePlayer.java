package com.vyrriox.justreplay.voice;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Plays the recorded voice chat of a replay live, following the replay clock: each client tick hands the frames of
 * the recorded ticks just played to one streaming source per speaker, placed at the speaker's entity (or recorded
 * position). Pausing starves the sources, a seek stops them and restarts from the new tick, and speeds between
 * x0.5 and x2 change their pitch; outside that range, and during video exports (which mix voices offline), voices
 * are silent. Without a voice track this does nothing.
 *
 * @author vyrriox
 */
public final class VoicePlayer {
    /** Ticks without a frame after which a speaker's source is stopped. */
    private static final int IDLE_TICKS = 20;
    private static final int ENTITY_LOOKUP_TICKS = 20;

    private static final class Channel {
        final VoiceTrack.Speaker speaker;
        VoiceBridge.Codec decoder;
        boolean decoderCreated;
        VoiceStream stream;
        VoiceSoundInstance instance;
        Entity entity;
        int lookupTimer;
        int idle;

        Channel(VoiceTrack.Speaker speaker) {
            this.speaker = speaker;
        }
    }

    private final CompletableFuture<VoiceTrack> loading;
    private final Map<Integer, Channel> channels = new HashMap<>();
    private VoiceTrack track;
    private boolean loaded;
    private int next;
    private int lastCursor = Integer.MIN_VALUE;
    private int lastSeek = Integer.MIN_VALUE;
    private boolean warnedOpus;

    public VoicePlayer(Path dir) {
        this.loading = CompletableFuture.supplyAsync(() -> VoiceTrack.read(dir), Util.backgroundExecutor());
    }

    /** The voice track once loaded, or null while loading or when the replay has none. */
    public VoiceTrack track() {
        if (!loaded && loading.isDone()) {
            loaded = true;
            try {
                track = loading.join();
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Cannot read the voice track: {}", t.toString());
            }
            if (track != null && !track.isEmpty()) {
                JustReplay.LOGGER.info("Voice track: {} frames from {} speakers", track.frames().size(), track.speakers().size());
            }
        }
        return track;
    }

    /** Called every client tick after the playback tick. */
    public void tick(Minecraft mc, ReplaySession session) {
        VoiceTrack t = track();
        if (t == null || t.isEmpty()) {
            return;
        }
        Playback playback = session.playback();
        int cursor = playback.cursor();
        if (playback.seekCount() != lastSeek || cursor < lastCursor) {
            stopAll(mc);
            lastSeek = playback.seekCount();
            lastCursor = cursor;
            next = t.firstAfter(cursor);
            return;
        }
        int from = next;
        next = t.firstAfter(cursor);
        lastCursor = cursor;
        float speed = playback.speed();
        boolean audible = session.project().voices && !VideoExporter.isActive() && mc.level != null
                && speed >= 0.5F && speed <= 2.0F && mc.options.getSoundSourceVolume(SoundSource.VOICE) > 0.0F
                && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        if (!audible) {
            stopAll(mc);
            return;
        }
        for (int i = from; i < next; i++) {
            feed(mc, t, t.frames().get(i));
        }
        for (Channel channel : channels.values()) {
            update(mc, channel, speed);
        }
    }

    private void feed(Minecraft mc, VoiceTrack t, VoiceTrack.Frame frame) {
        Channel channel = channels.computeIfAbsent(frame.speaker(), id -> new Channel(t.speakers().get(id)));
        if (!channel.decoderCreated) {
            channel.decoderCreated = true;
            channel.decoder = VoiceBridge.newDecoder();
        }
        short[] pcm = VoiceTrack.decode(frame, channel.decoder);
        if (pcm == null) {
            if (frame.codec() == VoiceTrack.CODEC_OPUS && !warnedOpus) {
                warnedOpus = true;
                JustReplay.LOGGER.warn("This replay has voice chat audio, install Simple Voice Chat to hear it");
            }
            return;
        }
        channel.idle = 0;
        if (channel.instance == null || channel.instance.isStopped() || !mc.getSoundManager().isActive(channel.instance)) {
            start(mc, channel, frame);
        }
        channel.stream.push(pcm);
    }

    private void start(Minecraft mc, Channel channel, VoiceTrack.Frame frame) {
        stop(mc, channel);
        Entity entity = channel.speaker.followsEntity() ? entity(mc.level, channel) : null;
        boolean positional = channel.speaker.positional() && (entity != null || frame.hasPosition());
        double x = entity != null ? entity.getX() : frame.x();
        double y = entity != null ? entity.getEyeY() : frame.y();
        double z = entity != null ? entity.getZ() : frame.z();
        float distance = frame.distance() > 0.0F ? frame.distance() : 48.0F;
        channel.stream = new VoiceStream();
        channel.instance = new VoiceSoundInstance(channel.stream, positional, x, y, z, distance);
        mc.getSoundManager().play(channel.instance);
    }

    private void update(Minecraft mc, Channel channel, float speed) {
        if (channel.instance == null) {
            return;
        }
        if (++channel.idle > IDLE_TICKS) {
            stop(mc, channel);
            return;
        }
        channel.instance.setPitch(speed);
        if (channel.speaker.followsEntity()) {
            Entity entity = entity(mc.level, channel);
            if (entity != null) {
                channel.instance.moveTo(entity.getX(), entity.getEyeY(), entity.getZ());
            }
        }
    }

    /** The speaker's entity in the replay world, looked up again at most once a second. */
    private static Entity entity(ClientLevel level, Channel channel) {
        if (level == null) {
            return null;
        }
        Entity entity = channel.entity;
        if (entity != null && !entity.isRemoved() && entity.level() == level) {
            return entity;
        }
        channel.entity = null;
        if (channel.lookupTimer-- > 0) {
            return null;
        }
        channel.lookupTimer = ENTITY_LOOKUP_TICKS;
        UUID uuid = channel.speaker.uuid();
        for (Entity candidate : level.entitiesForRendering()) {
            if (uuid.equals(candidate.getUUID())) {
                channel.entity = candidate;
                channel.lookupTimer = 0;
                return candidate;
            }
        }
        return null;
    }

    private static void stop(Minecraft mc, Channel channel) {
        if (channel.instance != null) {
            channel.instance.stopVoice();
            mc.getSoundManager().stop(channel.instance);
            channel.instance = null;
        }
        if (channel.stream != null) {
            channel.stream.clear();
            channel.stream = null;
        }
    }

    private void stopAll(Minecraft mc) {
        for (Channel channel : channels.values()) {
            stop(mc, channel);
            if (channel.decoder != null) {
                // A seek breaks the frame sequence the decoder state follows.
                try {
                    channel.decoder.close();
                } catch (Throwable ignored) {
                }
                channel.decoder = null;
            }
            channel.decoderCreated = false;
        }
    }

    /** Stops every voice; called when the replay closes. */
    public void close() {
        stopAll(Minecraft.getInstance());
        channels.clear();
    }
}
