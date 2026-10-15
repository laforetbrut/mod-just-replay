package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.voice.VoiceTrack;
import net.minecraft.Util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Instant replay: keeps the last minutes of a recording in memory, compressed, so they can be saved as a normal
 * replay after the fact. The packet stream is held as gzip segments of about five seconds, like {@link ReplayWriter}
 * writes them on disk, next to compressed checkpoints. A saved clip starts at an <em>anchor</em>: the oldest
 * checkpoint still needed to cover the window, or the start of the buffer itself while it is younger than the
 * window. Everything older than that anchor is dropped as time passes, so memory stays bounded by the window.
 * <p>
 * All the work runs on a dedicated low priority thread; callers only enqueue.
 *
 * @author vyrriox
 */
public final class ReplayBuffer {
    /** Compressed packets recorded between {@code startTick} and {@code endTick}, both included. */
    private record Segment(int startTick, int endTick, byte[] data) {
    }

    /** A point a clip can start from: a checkpoint, or the start of the buffered stream when {@code data} is null. */
    private record Anchor(int tick, byte[] data) {
    }

    /** One voice frame: its tick and the record that follows the tick in {@code voice.bin.gz}. */
    private record VoiceFrame(int tick, byte[] body) {
    }

    /** Result of a save. */
    public record Saved(Path dir, int durationTicks, int packets, int checkpoints, int voiceFrames) {
    }

    /** Hard cap of the memory held, whatever happens (a world change loop that never lets a checkpoint finish...). */
    private static final long MAX_BYTES = 768L << 20;
    private static final long LOG_INTERVAL_MS = 60_000L;

    private final ExecutorService executor;
    private volatile int windowTicks;
    private volatile boolean closed;

    // ---------------------------------------------------------------- buffer thread state
    private final List<Anchor> anchors = new ArrayList<>();
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private final ArrayDeque<VoiceFrame> voice = new ArrayDeque<>();
    private ByteArrayOutputStream segmentBytes;
    private DataOutputStream out;
    private int segmentStart;
    private int segmentEnd;
    private int lastTick;
    private long bytes;
    private long lastLog;
    private boolean warnedFull;

    /**
     * @param startTick   tick of the first packet the buffer receives; the stream is complete from there
     * @param windowTicks length kept in memory
     */
    public ReplayBuffer(int startTick, int windowTicks) {
        this.windowTicks = Math.max(20, windowTicks);
        this.lastTick = startTick;
        anchors.add(new Anchor(startTick - 1, null));
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JustReplay buffer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    public int windowTicks() {
        return windowTicks;
    }

    public void setWindowTicks(int ticks) {
        windowTicks = Math.max(20, ticks);
    }

    // ---------------------------------------------------------------- any thread

    /** Appends a packet; ticks never go back. */
    public void write(int tick, byte[] data) {
        run(() -> append(tick, data));
    }

    /**
     * Adds a checkpoint taken at a tick: its records describe the whole client state at that tick. Checkpoints closer
     * than {@code spacing} ticks to the previous one are skipped (a disk recording may ask for more than needed).
     */
    public void addCheckpoint(int tick, List<byte[]> records, int spacing) {
        run(() -> {
            Anchor last = anchors.isEmpty() ? null : anchors.get(anchors.size() - 1);
            if (last != null && tick <= last.tick()) {
                return;
            }
            if (last != null && last.data() != null && tick - last.tick() < spacing - spacing / 10) {
                return;
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);
            try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(SegmentedStream.gzip(raw, 1 << 16, false, SegmentedStream.MAX_LEVEL), 1 << 16))) {
                for (byte[] record : records) {
                    data.writeInt(0);
                    data.writeInt(record.length);
                    data.write(record);
                }
            } catch (IOException e) {
                JustReplay.LOGGER.warn("Cannot buffer the checkpoint at tick {}: {}", tick, e.toString());
                return;
            }
            byte[] compressed = raw.toByteArray();
            anchors.add(new Anchor(tick, compressed));
            bytes += compressed.length;
            trim();
        });
    }

    /** Adds a voice frame record (everything after the tick, see {@code VoiceTrack}). */
    public void writeVoice(int tick, byte[] body) {
        run(() -> {
            voice.add(new VoiceFrame(tick, body));
            bytes += body.length + 16;
        });
    }

    /**
     * Checkpoints the buffer needs for a window: clips start at the newest checkpoint before the window, so they
     * last between the window and the window plus this interval. Long windows use sparser checkpoints to stay small.
     */
    public static int checkpointInterval(int windowTicks) {
        return Math.max(1200, windowTicks / 5);
    }

    /**
     * Saves the buffer as a replay folder: {@code meta.json}, {@code registries.bin}, the stream starting at the
     * anchor with ticks rebased to 0, later checkpoints and the voice track. The buffer keeps running; the files are
     * written on a background thread.
     *
     * @param meta       metadata of the clip; its duration and start time are filled in here
     * @param registries registry ids of the connection, see {@link RegistryIds#encode()}
     */
    public CompletableFuture<Saved> save(Path dir, ReplayMeta meta, byte[] registries) {
        CompletableFuture<Saved> result = new CompletableFuture<>();
        if (!run(() -> {
            try {
                closeSegment();
                trim();
                if (anchors.isEmpty() || segments.isEmpty() && anchors.get(0).data() == null) {
                    result.completeExceptionally(new IllegalStateException("The replay buffer is empty"));
                    return;
                }
                List<Anchor> a = List.copyOf(anchors);
                List<Segment> s = List.copyOf(segments);
                List<VoiceFrame> v = List.copyOf(voice);
                int end = lastTick;
                Util.ioPool().execute(() -> {
                    try {
                        result.complete(writeClip(dir, meta, registries, a, s, v, end));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        })) {
            result.completeExceptionally(new IllegalStateException("The replay buffer is closed"));
        }
        return result;
    }

    /** Drops everything; saves already started still complete. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.execute(() -> {
            anchors.clear();
            segments.clear();
            voice.clear();
            out = null;
            segmentBytes = null;
            bytes = 0;
        });
        executor.shutdown();
    }

    private boolean run(Runnable task) {
        if (closed) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- buffer thread

    private void append(int tick, byte[] data) {
        try {
            if (out == null || tick - segmentStart >= SegmentedStream.SEGMENT_TICKS) {
                closeSegment();
                segmentBytes = new ByteArrayOutputStream(1 << 16);
                out = new DataOutputStream(new BufferedOutputStream(SegmentedStream.gzip(segmentBytes, 1 << 14, false, SegmentedStream.STREAM_LEVEL), 1 << 14));
                segmentStart = tick;
            }
            out.writeInt(tick);
            out.writeInt(data.length);
            out.write(data);
            segmentEnd = tick;
            if (tick > lastTick) {
                lastTick = tick;
            }
        } catch (IOException e) {
            // Memory streams only fail on a broken gzip state: start a fresh segment.
            JustReplay.LOGGER.warn("Replay buffer segment failed: {}", e.toString());
            out = null;
        }
    }

    private void closeSegment() throws IOException {
        if (out == null) {
            return;
        }
        out.close();
        byte[] data = segmentBytes.toByteArray();
        out = null;
        segmentBytes = null;
        segments.add(new Segment(segmentStart, segmentEnd, data));
        bytes += data.length;
        trim();
        long now = System.currentTimeMillis();
        if (now - lastLog >= LOG_INTERVAL_MS) {
            lastLog = now;
            JustReplay.LOGGER.debug("Replay buffer: {} s in {} segments, {} anchors, {} voice frames, {} KB",
                    anchors.isEmpty() ? 0 : (lastTick - anchors.get(0).tick()) / 20, segments.size(), anchors.size(), voice.size(), bytes / 1024);
        }
    }

    /** Drops what no clip can need any more: anchors before the newest one older than the window, and their data. */
    private void trim() {
        int cutoff = lastTick - windowTicks;
        while (anchors.size() > 1 && anchors.get(1).tick() <= cutoff) {
            dropFirstAnchor();
        }
        dropBeforeFirstAnchor();
        while (bytes > MAX_BYTES && !anchors.isEmpty()) {
            if (!warnedFull) {
                warnedFull = true;
                JustReplay.LOGGER.warn("Replay buffer over {} MB, dropping its oldest part early", MAX_BYTES >> 20);
            }
            dropFirstAnchor();
            dropBeforeFirstAnchor();
        }
    }

    /** Packets and voice up to the first anchor are covered by it (or useless without one). */
    private void dropBeforeFirstAnchor() {
        int keepAfter = anchors.isEmpty() ? Integer.MAX_VALUE : anchors.get(0).tick();
        while (!segments.isEmpty() && segments.peekFirst().endTick() <= keepAfter) {
            bytes -= segments.pollFirst().data().length;
        }
        while (!voice.isEmpty() && voice.peekFirst().tick() <= keepAfter) {
            bytes -= voice.pollFirst().body().length + 16;
        }
    }

    private void dropFirstAnchor() {
        Anchor first = anchors.remove(0);
        if (first.data() != null) {
            bytes -= first.data().length;
        }
    }

    // ---------------------------------------------------------------- save thread

    private static Saved writeClip(Path dir, ReplayMeta meta, byte[] registries, List<Anchor> anchors, List<Segment> segments,
                                   List<VoiceFrame> voice, int lastTick) throws IOException {
        Anchor start = anchors.get(0);
        // A checkpoint is played at tick 0 and the stream resumes after it; the stream start needs no checkpoint.
        int base = start.data() == null ? start.tick() + 1 : start.tick();
        Files.createDirectories(dir);
        meta.durationTicks = Math.max(0, lastTick - base);
        meta.startedAt -= meta.durationTicks * 50L;
        ReplayStorage.writeMeta(dir, meta);
        if (registries != null) {
            RegistryIds.write(dir, registries);
        }
        int packets = 0;
        try (SegmentedStream stream = new SegmentedStream(dir.resolve(ReplayStorage.STREAM))) {
            if (start.data() != null) {
                for (RecordedPacket packet : decode(start.data())) {
                    stream.write(0, packet.data());
                    packets++;
                }
            }
            for (Segment segment : segments) {
                for (RecordedPacket packet : decode(segment.data())) {
                    if (packet.tick() > start.tick()) {
                        stream.write(packet.tick() - base, packet.data());
                        packets++;
                    }
                }
            }
        }
        int checkpoints = 0;
        for (int i = 1; i < anchors.size(); i++) {
            Anchor anchor = anchors.get(i);
            if (anchor.data() == null || anchor.tick() - base <= 0) {
                continue;
            }
            List<byte[]> records = new ArrayList<>();
            for (RecordedPacket packet : decode(anchor.data())) {
                records.add(packet.data());
            }
            Checkpoints.write(dir, anchor.tick() - base, records);
            checkpoints++;
        }
        int frames = 0;
        if (!voice.isEmpty()) {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(dir.resolve(VoiceTrack.FILE)), 1 << 16), 1 << 16))) {
                out.writeInt(VoiceTrack.MAGIC);
                out.writeInt(VoiceTrack.VERSION);
                for (VoiceFrame frame : voice) {
                    if (frame.tick() > start.tick()) {
                        out.writeInt(frame.tick() - base);
                        out.write(frame.body());
                        frames++;
                    }
                }
            }
        }
        JustReplay.LOGGER.info("Replay buffer saved to {}: {} ticks, {} packets, {} checkpoints, {} voice frames", dir,
                meta.durationTicks, packets, checkpoints, frames);
        return new Saved(dir, meta.durationTicks, packets, checkpoints, frames);
    }

    private static List<RecordedPacket> decode(byte[] gz) throws IOException {
        List<RecordedPacket> packets = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(gz), 1 << 16), 1 << 16))) {
            PacketStream.readRecords(in, packets);
        }
        return packets;
    }
}
