package com.vyrriox.justreplay.format;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Synchronous writer of the replay stream format: {@code [tick, length, bytes]} records cut into independent gzip
 * members of about five seconds, whose start tick and file offset go to {@code index.bin}. Not thread safe; the
 * live recorder drives it from its writer thread, saved clips from a background thread.
 *
 * @author vyrriox
 */
public final class SegmentedStream implements Closeable {
    public static final String INDEX = "index.bin";
    public static final int SEGMENT_TICKS = 100;

    private final CountingOutputStream raw;
    private final DataOutputStream index;
    private DataOutputStream out;
    private GZIPOutputStream gzip;
    private int segmentStart = Integer.MIN_VALUE;
    private boolean dirty;

    public SegmentedStream(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.raw = new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
        this.index = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file.resolveSibling(INDEX))));
    }

    /**
     * Compression of the packet stream. Compression runs on background threads only, so the stream favours fewer
     * bytes written to the drive over CPU time.
     */
    public static final int STREAM_LEVEL = 6;
    /** Checkpoints are written once and read rarely: maximum compression. */
    public static final int MAX_LEVEL = Deflater.BEST_COMPRESSION;

    /** A gzip stream with a given compression level. */
    public static GZIPOutputStream gzip(OutputStream out, int size, boolean syncFlush, int level) throws IOException {
        return new GZIPOutputStream(out, size, syncFlush) {
            {
                def.setLevel(level);
            }
        };
    }

    public void write(int tick, byte[] data) throws IOException {
        if (out == null || tick - segmentStart >= SEGMENT_TICKS) {
            startSegment(tick);
        }
        out.writeInt(tick);
        out.writeInt(data.length);
        out.write(data);
        dirty = true;
    }

    private void startSegment(int tick) throws IOException {
        if (gzip != null) {
            out.flush();
            gzip.finish();
        }
        segmentStart = tick;
        index.writeInt(tick);
        index.writeLong(raw.count);
        gzip = gzip(raw, 1 << 16, true, STREAM_LEVEL);
        out = new DataOutputStream(gzip);
    }

    /**
     * Pushes buffered data to disk so a crash loses little; does nothing when nothing was written since the last
     * flush. A crash may lose the last index entries: readers then read the remaining gzip members as one segment.
     */
    public void flush() throws IOException {
        if (!dirty) {
            return;
        }
        dirty = false;
        if (out != null) {
            out.flush();
        }
        raw.flush();
        index.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            if (gzip != null) {
                out.flush();
                gzip.finish();
            }
            raw.close();
        } finally {
            index.close();
        }
    }

    /** Counts the bytes written to the file, to know where each segment starts. */
    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
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
}
