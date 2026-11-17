package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;

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
 *
 * @author vyrriox
 */
public final class ReplayWriter implements AutoCloseable {
    public static final String INDEX = SegmentedStream.INDEX;

    private final ExecutorService executor;
    private final SegmentedStream stream;
    /** Queued packet bytes above which a warning is logged once (nothing is dropped). */
    private static final long BACKLOG_WARN_BYTES = 256L << 20;

    private final AtomicLong queuedBytes = new AtomicLong();
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

    public void write(int tick, byte[] data) {
        if (failed) {
            return;
        }
        long queued = queuedBytes.addAndGet(data.length);
        if (queued > BACKLOG_WARN_BYTES && !backlogWarned) {
            backlogWarned = true;
            JustReplay.LOGGER.warn("The replay writer is {} MB behind, the disk or the CPU cannot keep up", queued >> 20);
        }
        executor.execute(() -> {
            try {
                if (!failed) {
                    stream.write(tick, data);
                    lastTick = Math.max(lastTick, tick);
                }
            } catch (IOException e) {
                failed = true;
                JustReplay.LOGGER.error("Failed to write replay data", e);
            } finally {
                queuedBytes.addAndGet(-data.length);
            }
        });
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
        executor.execute(() -> {
            try {
                stream.flush();
            } catch (IOException e) {
                failed = true;
                JustReplay.LOGGER.error("Failed to flush replay data", e);
            }
        });
    }

    /** Runs a small task on the writer thread, after every packet written so far; keeps file work off the client thread. */
    public void execute(Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // The recording ended in between.
        }
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                stream.close();
            } catch (IOException e) {
                closedCleanly = false;
                JustReplay.LOGGER.error("Failed to close replay stream", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                closedCleanly = false;
                JustReplay.LOGGER.error("The replay writer did not finish within 30 s, the end of the recording may be missing");
            }
        } catch (InterruptedException e) {
            closedCleanly = false;
            Thread.currentThread().interrupt();
        }
    }
}
