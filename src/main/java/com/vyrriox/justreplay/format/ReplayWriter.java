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
 * wait on the disk.
 *
 * @author vyrriox
 */
public final class ReplayWriter implements AutoCloseable {
    private final ExecutorService executor;
    private final DataOutputStream out;
    private volatile boolean failed;

    public ReplayWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16), 1 << 16));
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
                out.writeInt(tick);
                out.writeInt(data.length);
                out.write(data);
            } catch (IOException e) {
                failed = true;
                JustReplay.LOGGER.error("Failed to write replay data", e);
            }
        });
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                out.close();
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
