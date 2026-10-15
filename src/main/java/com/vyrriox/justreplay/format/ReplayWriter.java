package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private volatile boolean failed;

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
        executor.execute(() -> {
            try {
                stream.write(tick, data);
            } catch (IOException e) {
                failed = true;
                JustReplay.LOGGER.error("Failed to write replay data", e);
            }
        });
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
                JustReplay.LOGGER.error("Failed to close replay stream", e);
            }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
