package com.vyrriox.justreplay.record;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * @author vyrriox
 */
final class ChunkCapture {
    /** Writes a packet into a sink with the recorder's codec. */
    interface Output {
        void write(Packet<?> packet, Consumer<byte[]> sink);
    }

    private static final int SCAN_MAX_CHECKS = 256;
    private static final int SCAN_MAX_BUILDS = 4;
    private static final long SCAN_BUDGET_NANOS = 1_500_000L;
    private static final int CHECKPOINT_MAX_CHECKS = 2048;
    private static final int CHECKPOINT_MAX_BUILDS = 24;
    private static final long CHECKPOINT_BUDGET_NANOS = 3_000_000L;
    static final int MAX_RADIUS = 64;

    /** Chunks the server sent and has not forgotten (network thread writes, client thread reads). */
    private final Set<Long> serverChunks = ConcurrentHashMap.newKeySet();
    /** Chunks already in the main stream, from the server or from the scan. */
    private final Set<Long> written = ConcurrentHashMap.newKeySet();

    private int scanRadius = -1;
    private int[] scanOffsets = new int[0];
    private int scanIndex;
    private CheckpointJob checkpoint;

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
        final Consumer<List<byte[]>> done;
        int index;

        CheckpointJob(int at, int epoch, ClientLevel level, int centerX, int centerZ, int radius, List<byte[]> head, List<byte[]> tail,
                      Consumer<List<byte[]>> done) {
            this.at = at;
            this.epoch = epoch;
            this.level = level;
            this.centerX = centerX;
            this.centerZ = centerZ;
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
            long key = ChunkPos.asLong(chunk.getX(), chunk.getZ());
            serverChunks.add(key);
            written.add(key);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            // Playback keeps forgotten chunks, so they stay written.
            serverChunks.remove(forget.pos().toLong());
        } else if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            serverChunks.clear();
            written.clear();
        }
    }

    // ---------------------------------------------------------------- client thread

    /** Records a chunk written by a snapshot into the main stream. */
    void markWritten(int x, int z) {
        written.add(ChunkPos.asLong(x, z));
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
     * One client tick of work: the running checkpoint first, otherwise the extra chunk scan.
     *
     * @param scanRadius radius of the extra chunk scan, or a negative value to skip it
     * @param epoch      level epoch of the recorder; a checkpoint started in another epoch is dropped
     * @param mainSink   sink of the main stream, which only accepts data of the epoch the chunk was built in
     */
    void tick(ClientLevel level, int playerChunkX, int playerChunkZ, int scanRadius, int epoch, Output out, Consumer<byte[]> mainSink) {
        long start = System.nanoTime();
        if (checkpoint != null) {
            stepCheckpoint(level, epoch, out, start + CHECKPOINT_BUDGET_NANOS);
            return;
        }
        if (scanRadius > 0) {
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
        for (int checks = 0; job.index < count && checks < CHECKPOINT_MAX_CHECKS && builds < CHECKPOINT_MAX_BUILDS; checks++) {
            int x = job.centerX + job.offsets[job.index * 2];
            int z = job.centerZ + job.offsets[job.index * 2 + 1];
            job.index++;
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            out.write(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), job.chunks::add);
            builds++;
            if (System.nanoTime() > deadline) {
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
                forgetFar(cx, cz, radius + 2);
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

    /** Far chunks are written again when the player comes back: playback may have evicted them in between. */
    private void forgetFar(int cx, int cz, int radius) {
        written.removeIf(key -> !serverChunks.contains(key)
                && Math.max(Math.abs(ChunkPos.getX(key) - cx), Math.abs(ChunkPos.getZ(key) - cz)) > radius);
    }

    private static final java.util.Map<Integer, int[]> OFFSETS = new ConcurrentHashMap<>();

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
