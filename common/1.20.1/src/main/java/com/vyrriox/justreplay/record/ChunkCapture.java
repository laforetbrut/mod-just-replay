package com.vyrriox.justreplay.record;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Chunk packets the recorder builds itself, always a few per client tick so recording never shows in frame times:
 * <ul>
 *     <li>extra chunks: chunks the client holds beyond what the server sent (cached chunks of mods such as Bobby,
 *     which serve them through {@code ClientChunkCache.getChunk}), scanned around the player and written to the
 *     main stream once each;</li>
 *     <li>checkpoint chunks: the chunks of a checkpoint, built over several ticks instead of in one hitch.</li>
 * </ul>
 * Chunks of a checkpoint built after its tick may already carry a few later block changes; replaying the stream
 * from the checkpoint applies those same absolute changes again, so the world converges on the recorded state.
 * <p>
 * Building a chunk packet is the most expensive thing the recorder does on the client thread, so each tick has a
 * small time budget, and the scan does not run while frames are slow. A checkpoint waits for a fast frame once, then
 * keeps going on slow frames with a few builds per tick so it ends in bounded time. A chunk the server forgets while
 * a checkpoint is running is built just before the client unloads it: unloading clears its light and block entities,
 * so a reference kept from the start of the job would not be enough.
 *
 * @author vyrriox
 */
final class ChunkCapture {
    /** Writes a packet into a sink with the recorder's codec. */
    interface Output {
        void write(Packet<?> packet, Consumer<byte[]> sink);
    }

    private static final int SCAN_MAX_CHECKS = 256;
    private static final int SCAN_MAX_BUILDS = 2;
    private static final long SCAN_BUDGET_NANOS = 250_000L;
    private static final int CHECKPOINT_MAX_CHECKS = 2048;
    private static final int CHECKPOINT_MAX_BUILDS = 12;
    private static final long CHECKPOINT_BUDGET_NANOS = 600_000L;
    /** A frame slower than this (under 40 fps) skips the optional work of the tick. */
    private static final long SLOW_FRAME_NANOS = 25_000_000L;
    /** A checkpoint waits for faster frames only before its first step, and never more than this many ticks. */
    private static final int MAX_DEFERRED_TICKS = 40;
    /** Ticks a checkpoint aims to finish in, whatever the frame rate; sets its minimum builds per tick. */
    private static final int CHECKPOINT_TARGET_TICKS = 600;
    static final int MAX_RADIUS = 64;

    /** What the network thread saw; drained on the client thread so the sets below need no locking or boxing. */
    private record Event(int kind, long key) {
        static final int SENT = 0;
        static final int FORGOTTEN = 1;
        static final int RESET = 2;
    }

    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    /** Chunks the server sent and has not forgotten (client thread only). */
    private final LongOpenHashSet serverChunks = new LongOpenHashSet();
    /**
     * Chunks already in the main stream, from the server or from the scan (client thread only). A cached chunk is
     * written once per level: the server sends no change for it while it is out of range, and a chunk the server
     * sends again is written by the server itself, so leaving and coming back never rewrites unchanged chunks.
     */
    private final LongOpenHashSet written = new LongOpenHashSet();

    private int scanRadius = -1;
    private int[] scanOffsets = new int[0];
    private int scanIndex;
    private CheckpointJob checkpoint;
    /** Self test: frame duration forced on the budget logic, 0 for the real one. */
    static volatile long forcedFrameNanos;

    private static final class CheckpointJob {
        final int at;
        final int epoch;
        final ClientLevel level;
        final int centerX;
        final int centerZ;
        final int[] offsets;
        final List<byte[]> head;
        final List<byte[]> tail;
        final List<byte[]> chunks = new ArrayList<>();
        /** Chunks built so far, so one built just before its unload is not built again if the server resends it. */
        final LongOpenHashSet built = new LongOpenHashSet();
        final Consumer<List<byte[]>> done;
        final int radius;
        int index;
        int deferred;
        int steps;

        CheckpointJob(int at, int epoch, ClientLevel level, int centerX, int centerZ, int radius, List<byte[]> head, List<byte[]> tail,
                      Consumer<List<byte[]>> done) {
            this.at = at;
            this.epoch = epoch;
            this.level = level;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.offsets = offsets(radius);
            this.head = head;
            this.tail = tail;
            this.done = done;
        }
    }

    // ---------------------------------------------------------------- network thread

    /** Tracks which chunks the server itself provides; called for every captured packet. */
    void onPacket(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            events.add(new Event(Event.SENT, ChunkPos.asLong(chunk.getX(), chunk.getZ())));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            events.add(new Event(Event.FORGOTTEN, ChunkPos.asLong(forget.getX(), forget.getZ())));
        } else if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            events.add(new Event(Event.RESET, 0L));
        }
    }

    // ---------------------------------------------------------------- client thread

    /**
     * Applies what the network thread saw. A chunk is captured before the client handles it, so any chunk the
     * client level holds is already known here once drained.
     */
    private void drain() {
        Event event;
        while ((event = events.poll()) != null) {
            switch (event.kind()) {
                case Event.SENT -> {
                    serverChunks.add(event.key());
                    written.add(event.key());
                }
                // Playback keeps forgotten chunks, so they stay written.
                case Event.FORGOTTEN -> serverChunks.remove(event.key());
                default -> {
                    serverChunks.clear();
                    written.clear();
                }
            }
        }
    }

    /** Records a chunk written by a snapshot into the main stream. */
    void markWritten(int x, int z) {
        written.add(ChunkPos.asLong(x, z));
    }

    /**
     * A new output joined the stream with a snapshot of the nearby chunks only: scan the cached chunks again so it
     * gets them too (the other outputs receive them a second time, which playback handles like any chunk resend).
     */
    void rescanExtraChunks() {
        drain();
        written.clear();
        written.addAll(serverChunks);
        scanIndex = 0;
    }

    boolean isCheckpointRunning() {
        return checkpoint != null;
    }

    /**
     * Starts building the chunks of a checkpoint; {@code done} receives head + chunks + tail once complete, or null
     * when the level changed in between.
     */
    void startCheckpoint(int at, int epoch, ClientLevel level, int centerX, int centerZ, int radius, List<byte[]> head,
                         List<byte[]> tail, Consumer<List<byte[]>> done) {
        if (checkpoint != null) {
            checkpoint.done.accept(null);
        }
        checkpoint = new CheckpointJob(at, epoch, level, centerX, centerZ, Math.min(MAX_RADIUS, radius), head, tail, done);
    }

    void cancel() {
        if (checkpoint != null) {
            CheckpointJob job = checkpoint;
            checkpoint = null;
            job.done.accept(null);
        }
    }

    /**
     * The client is about to unload a chunk: a running checkpoint that still needs it builds it now. Runs on the client
     * thread right before the packet's own handling, while the chunk still has its light and block entities.
     */
    void beforeForget(ClientLevel level, ChunkPos pos, Output out) {
        CheckpointJob job = checkpoint;
        if (job == null || level != job.level || Math.abs(pos.x - job.centerX) > job.radius
                || Math.abs(pos.z - job.centerZ) > job.radius) {
            return;
        }
        buildInto(job, level, pos.x, pos.z, out);
    }

    /** Builds a chunk of the job unless it is already built or absent; true when a packet was built. */
    private static boolean buildInto(CheckpointJob job, ClientLevel level, int x, int z, Output out) {
        long key = ChunkPos.asLong(x, z);
        if (job.built.contains(key)) {
            return false;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        job.built.add(key);
        out.write(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), job.chunks::add);
        return true;
    }

    /**
     * One client tick of work: the running checkpoint first, otherwise the extra chunk scan.
     *
     * @param scanRadius     radius of the extra chunk scan, or a negative value to skip it
     * @param epoch          level epoch of the recorder; a checkpoint started in another epoch is dropped
     * @param lastFrameNanos duration of the last rendered frame; slow frames postpone the work
     * @param mainSink       sink of the main stream, which only accepts data of the epoch the chunk was built in
     */
    void tick(ClientLevel level, int playerChunkX, int playerChunkZ, int scanRadius, int epoch, long lastFrameNanos, Output out,
              Consumer<byte[]> mainSink) {
        drain();
        boolean slow = Math.max(lastFrameNanos, forcedFrameNanos) > SLOW_FRAME_NANOS;
        long start = System.nanoTime();
        if (checkpoint != null) {
            if (slow && checkpoint.steps == 0 && ++checkpoint.deferred <= MAX_DEFERRED_TICKS) {
                return;
            }
            checkpoint.steps++;
            stepCheckpoint(level, epoch, out, start + CHECKPOINT_BUDGET_NANOS);
            return;
        }
        if (scanRadius > 0 && !slow) {
            scan(level, playerChunkX, playerChunkZ, Math.min(MAX_RADIUS, scanRadius), out, mainSink, start + SCAN_BUDGET_NANOS);
        }
    }

    private void stepCheckpoint(ClientLevel level, int epoch, Output out, long deadline) {
        CheckpointJob job = checkpoint;
        if (level != job.level || epoch != job.epoch) {
            cancel();
            return;
        }
        int count = job.offsets.length / 2;
        int builds = 0;
        // The time budget only counts past this minimum, spread over the ticks left of the target.
        int ticksLeft = Math.max(1, CHECKPOINT_TARGET_TICKS - job.steps);
        int minBuilds = Math.min(CHECKPOINT_MAX_BUILDS, Math.max(1, (count - job.index + ticksLeft - 1) / ticksLeft));
        for (int checks = 0; job.index < count && checks < CHECKPOINT_MAX_CHECKS && builds < CHECKPOINT_MAX_BUILDS; checks++) {
            int x = job.centerX + job.offsets[job.index * 2];
            int z = job.centerZ + job.offsets[job.index * 2 + 1];
            job.index++;
            if (!buildInto(job, level, x, z, out)) {
                continue;
            }
            builds++;
            if (builds >= minBuilds && System.nanoTime() > deadline) {
                break;
            }
        }
        if (job.index >= count) {
            checkpoint = null;
            List<byte[]> records = new ArrayList<>(job.head.size() + job.chunks.size() + job.tail.size());
            records.addAll(job.head);
            records.addAll(job.chunks);
            records.addAll(job.tail);
            job.done.accept(records);
        }
    }

    private void scan(ClientLevel level, int cx, int cz, int radius, Output out, Consumer<byte[]> sink, long deadline) {
        if (radius != scanRadius) {
            scanRadius = radius;
            scanOffsets = offsets(radius);
            scanIndex = 0;
        }
        int count = scanOffsets.length / 2;
        int builds = 0;
        for (int checks = 0; checks < SCAN_MAX_CHECKS && builds < SCAN_MAX_BUILDS; checks++) {
            if (scanIndex >= count) {
                scanIndex = 0;
                break;
            }
            int x = cx + scanOffsets[scanIndex * 2];
            int z = cz + scanOffsets[scanIndex * 2 + 1];
            scanIndex++;
            long key = ChunkPos.asLong(x, z);
            if (written.contains(key) || serverChunks.contains(key)) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            written.add(key);
            out.write(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), sink);
            builds++;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    private static final Map<Integer, int[]> OFFSETS = new ConcurrentHashMap<>();

    /** {@code (dx, dz)} pairs of a square of the given radius, ring by ring from the center; computed once per radius. */
    private static int[] offsets(int radius) {
        return OFFSETS.computeIfAbsent(radius, ChunkCapture::computeOffsets);
    }

    private static int[] computeOffsets(int radius) {
        int side = radius * 2 + 1;
        int[] result = new int[side * side * 2];
        int n = 0;
        result[n++] = 0;
        result[n++] = 0;
        for (int ring = 1; ring <= radius; ring++) {
            for (int d = -ring; d < ring; d++) {
                result[n++] = d;
                result[n++] = -ring;
                result[n++] = ring;
                result[n++] = d;
                result[n++] = -d;
                result[n++] = ring;
                result[n++] = -ring;
                result[n++] = -d;
            }
        }
        return result;
    }
}
