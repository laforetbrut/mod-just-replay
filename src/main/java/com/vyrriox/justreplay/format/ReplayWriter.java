package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Appends packets to a gzip compressed stream on a dedicated thread so the network and render threads never
 * wait on the disk. The stream is cut into independent gzip members of about five seconds whose start tick and
 * file offset go to {@code index.bin}, so playback only needs a few segments in memory. Plain gzip readers still
 * read the whole file as one stream.
 *
 * @author vyrriox
 */
public final class ReplayWriter implements AutoCloseable {
    public static final String INDEX = "index.bin";
    private static final int SEGMENT_TICKS = 100;

    private final ExecutorService executor;
    private final CountingOutputStream raw;
    private final DataOutputStream index;
    private DataOutputStream out;
    private GZIPOutputStream gzip;
    private int segmentStart = Integer.MIN_VALUE;
    private volatile boolean failed;

    public ReplayWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.raw = new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
        this.index = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file.resolveSibling(INDEX))));
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JustReplay writer");
            t.setDaemon(true);
            return t;
        });
    }

    public void write(int tick, byte[] data) {
        if (failed) {
            return;
        }
        executor.execute(() -> {
            try {
                if (out == null || tick - segmentStart >= SEGMENT_TICKS) {
                    startSegment(tick);
                }
                out.writeInt(tick);
                out.writeInt(data.length);
                out.write(data);
            } catch (IOException e) {
                failed = true;
                JustReplay.LOGGER.error("Failed to write replay data", e);
            }
        });
    }

    private void startSegment(int tick) throws IOException {
        if (gzip != null) {
            gzip.finish();
        }
        segmentStart = tick;
        index.writeInt(tick);
        index.writeLong(raw.count);
        index.flush();
        gzip = new GZIPOutputStream(raw, 1 << 16, true);
        out = new DataOutputStream(gzip);
    }

    /** Counts the bytes written to the file, to know where each segment starts. */
    private static final class CountingOutputStream extends java.io.FilterOutputStream {
        long count;

        CountingOutputStream(java.io.OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    /** Pushes buffered data to disk so a crash loses at most a few seconds. */
    public void flush() {
        if (failed) {
            return;
        }
        executor.execute(() -> {
            try {
                if (out != null) {
                    out.flush();
                }
                raw.flush();
            } catch (IOException e) {
                failed = true;
                JustReplay.LOGGER.error("Failed to flush replay data", e);
            }
        });
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                if (gzip != null) {
                    gzip.finish();
                }
                raw.close();
                index.close();
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
