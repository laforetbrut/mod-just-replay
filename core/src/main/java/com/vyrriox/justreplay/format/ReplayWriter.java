package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplayCore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends packets to a gzip compressed stream on a dedicated low priority thread so the network and render threads
 * never wait on the disk or on compression. The stream is cut into independent gzip members of about five seconds
 * whose start tick and file offset go to {@code index.bin} (see {@link SegmentedStream}), so playback only needs a
 * few segments in memory. Plain gzip readers still read the whole file as one stream.
 * <p>
 * Packets are handed to the writer thread in batches (see {@link #write}): waking that thread for every packet cost
 * the network thread about two microseconds per packet, a batch costs it a list append. Batches keep the order of
 * every call, so the file is the same byte for byte.
 *
 * @author vyrriox
 */
public final class ReplayWriter implements AutoCloseable {
    public static final String INDEX = SegmentedStream.INDEX;

    private final ExecutorService executor;
    private final SegmentedStream stream;
    /** Queued packet bytes above which a warning is logged once (nothing is dropped). */
    private static final long BACKLOG_WARN_BYTES = 256L << 20;

    /** A batch is handed to the writer thread once it holds this many packets... */
    public static final int BATCH_PACKETS = 512;
    /** ...or this many bytes... */
    public static final int BATCH_BYTES = 256 << 10;
    /** ...or spans this many ticks, so a quiet connection still reaches the writer within about a second. */
    public static final int BATCH_TICKS = 20;

    private final AtomicLong queuedBytes = new AtomicLong();
    /** Packets not handed to the writer thread yet, guarded by {@code this}. */
    private int[] batchTicks = new int[64];
    private byte[][] batchData = new byte[64][];
    private int batchSize;
    private int batchBytes;
    private int batchFirstTick;
    private volatile boolean failed;
    private volatile boolean backlogWarned;
    private volatile boolean closedCleanly = true;
    private volatile int lastTick;

    public ReplayWriter(Path file) throws IOException {
        this.stream = new SegmentedStream(file);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JustReplay writer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * Queues a packet. It reaches the writer thread with its batch, at the latest at the next {@link #flush},
     * {@link #execute} or {@link #close}; the order of all calls is kept.
     */
    public void write(int tick, byte[] data) {
        if (failed) {
            return;
        }
        long queued = queuedBytes.addAndGet(data.length);
        if (queued > BACKLOG_WARN_BYTES && !backlogWarned) {
            backlogWarned = true;
            JustReplayCore.LOGGER.warn("The replay writer is {} MB behind, the disk or the CPU cannot keep up", queued >> 20);
        }
        synchronized (this) {
            if (batchSize == batchTicks.length) {
                batchTicks = java.util.Arrays.copyOf(batchTicks, batchSize * 2);
                batchData = java.util.Arrays.copyOf(batchData, batchSize * 2);
            }
            if (batchSize == 0) {
                batchFirstTick = tick;
            }
            batchTicks[batchSize] = tick;
            batchData[batchSize] = data;
            batchSize++;
            batchBytes += data.length;
            if (batchSize >= BATCH_PACKETS || batchBytes >= BATCH_BYTES || tick - batchFirstTick >= BATCH_TICKS) {
                handOff();
            }
        }
    }

    /** Gives the queued packets to the writer thread, in order. Caller holds {@code this}. */
    private void handOff() {
        if (batchSize == 0) {
            return;
        }
        int[] ticks = java.util.Arrays.copyOf(batchTicks, batchSize);
        byte[][] data = java.util.Arrays.copyOf(batchData, batchSize);
        java.util.Arrays.fill(batchData, 0, batchSize, null);
        long bytes = batchBytes;
        batchSize = 0;
        batchBytes = 0;
        try {
            executor.execute(() -> writeBatch(ticks, data, bytes));
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            queuedBytes.addAndGet(-bytes);
        }
    }

    /** Writer thread: writes a batch handed off by {@link #handOff}. */
    private void writeBatch(int[] ticks, byte[][] data, long bytes) {
        try {
            for (int i = 0; i < ticks.length && !failed; i++) {
                stream.write(ticks[i], data[i]);
                lastTick = Math.max(lastTick, ticks[i]);
            }
        } catch (IOException e) {
            failed = true;
            JustReplayCore.LOGGER.error("Failed to write replay data", e);
        } finally {
            queuedBytes.addAndGet(-bytes);
        }
    }

    /** Whether a write or flush failed: the stream on disk stops at {@link #lastTick()}. */
    public boolean failed() {
        return failed;
    }

    /** Tick of the last packet written to the stream. */
    public int lastTick() {
        return lastTick;
    }

    /** False when the last {@link #close()} timed out or failed to finish the stream. */
    public boolean closedCleanly() {
        return closedCleanly && !failed;
    }

    /** Pushes buffered data to disk so a crash loses at most a few seconds. */
    public void flush() {
        if (failed) {
            return;
        }
        synchronized (this) {
            handOff();
        }
        executor.execute(() -> {
            try {
                stream.flush();
            } catch (IOException e) {
                failed = true;
                JustReplayCore.LOGGER.error("Failed to flush replay data", e);
            }
        });
    }

    /** Runs a small task on the writer thread, after every packet written so far; keeps file work off the client thread. */
    public void execute(Runnable task) {
        try {
            synchronized (this) {
                handOff();
            }
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // The recording ended in between.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            handOff();
        }
        executor.execute(() -> {
            try {
                stream.close();
            } catch (IOException e) {
                closedCleanly = false;
                JustReplayCore.LOGGER.error("Failed to close replay stream", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                closedCleanly = false;
                JustReplayCore.LOGGER.error("The replay writer did not finish within 30 s, the end of the recording may be missing");
            }
        } catch (InterruptedException e) {
            closedCleanly = false;
            Thread.currentThread().interrupt();
        }
    }
}
