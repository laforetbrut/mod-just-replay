package com.vyrriox.justreplay.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recording writer hands packets to its thread in batches and buffers the gzip stream: the files must stay the
 * same byte for byte as a stream fed one packet at a time, and work queued after packets must run after them.
 *
 * @author vyrriox
 */
class ReplayWriterTest {
    @TempDir
    Path dir;

    private record Rec(int tick, byte[] data) {
    }

    /** A recording-like mix: small entity packets every tick, chunk-sized ones now and then, a few huge ones. */
    private static List<Rec> records() {
        Random random = new Random(507);
        List<Rec> list = new ArrayList<>();
        for (int tick = 0; tick < 2600; tick++) {
            int count = tick % 97 == 0 ? 0 : 1 + random.nextInt(12);
            for (int i = 0; i < count; i++) {
                int size = random.nextInt(100) < 3 ? 2000 + random.nextInt(30000) : 8 + random.nextInt(120);
                if (tick % 700 == 3 && i == 0) {
                    size = 300_000;
                }
                byte[] data = new byte[size];
                // Repetitive content, as packets are, so the deflater finds matches across packet boundaries.
                for (int k = 0; k < size; k++) {
                    data[k] = (byte) (k % 7 == 0 ? random.nextInt(256) : (tick + k / 5) & 0x3F);
                }
                list.add(new Rec(tick, data));
            }
        }
        return list;
    }

    /** The stream as written before batching and buffering: one deflater call per field, same segments and flushes. */
    private static void writeReference(Path file, List<Rec> records) throws IOException {
        Files.createDirectories(file.getParent());
        long[] count = new long[1];
        OutputStream raw = new FilterOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                count[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                count[0] += len;
            }
        };
        try (DataOutputStream index = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file.resolveSibling(SegmentedStream.INDEX))))) {
            SegmentedStream.EndingGzip gzip = null;
            DataOutputStream out = null;
            int segmentStart = Integer.MIN_VALUE;
            int lastFlush = 0;
            for (Rec r : records) {
                if (out == null || r.tick() - segmentStart >= SegmentedStream.SEGMENT_TICKS) {
                    if (gzip != null) {
                        out.flush();
                        gzip.finish();
                        gzip.endDeflater();
                    }
                    segmentStart = r.tick();
                    index.writeInt(r.tick());
                    index.writeLong(count[0]);
                    gzip = SegmentedStream.gzip(raw, 1 << 16, true, SegmentedStream.STREAM_LEVEL);
                    out = new DataOutputStream(gzip);
                }
                out.writeInt(r.tick());
                out.writeInt(r.data().length);
                out.write(r.data());
                if (r.tick() / 600 > lastFlush) {
                    lastFlush = r.tick() / 600;
                    out.flush();
                    raw.flush();
                    index.flush();
                }
            }
            out.flush();
            gzip.finish();
            gzip.endDeflater();
            raw.close();
        }
    }

    @Test
    void batchedWriterWritesTheSameBytesAsOnePacketAtATime() throws IOException {
        List<Rec> records = records();
        Path reference = dir.resolve("reference/stream.bin.gz");
        writeReference(reference, records);

        Path file = dir.resolve("batched/stream.bin.gz");
        ReplayWriter writer = new ReplayWriter(file);
        int lastFlush = 0;
        for (Rec r : records) {
            writer.write(r.tick(), r.data());
            if (r.tick() / 600 > lastFlush) {
                lastFlush = r.tick() / 600;
                writer.flush();
            }
        }
        writer.close();
        assertTrue(writer.closedCleanly());
        assertEquals(records.get(records.size() - 1).tick(), writer.lastTick());

        assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(file), "stream.bin.gz");
        assertArrayEquals(Files.readAllBytes(reference.resolveSibling(SegmentedStream.INDEX)),
                Files.readAllBytes(file.resolveSibling(SegmentedStream.INDEX)), "index.bin");
        assertEquals(records.size(), readBack(file, records));
    }

    @Test
    void workQueuedAfterPacketsRunsAfterThem() throws Exception {
        ReplayWriter writer = new ReplayWriter(dir.resolve("order/stream.bin.gz"));
        int[] seen = new int[]{-1, -1};
        for (int tick = 0; tick < 5; tick++) {
            writer.write(tick, new byte[]{(byte) tick});
        }
        // Five packets stay in the batch; the task must still see them all written.
        writer.execute(() -> seen[0] = writer.lastTick());
        writer.write(5, new byte[]{5});
        writer.flush();
        writer.execute(() -> seen[1] = writer.lastTick());
        writer.close();
        assertEquals(4, seen[0]);
        assertEquals(5, seen[1]);
    }

    @Test
    void aQuietConnectionReachesTheWriterWithinABatchSpan() throws Exception {
        ReplayWriter writer = new ReplayWriter(dir.resolve("quiet/stream.bin.gz"));
        writer.write(0, new byte[]{1});
        writer.write(ReplayWriter.BATCH_TICKS, new byte[]{2});
        long until = System.nanoTime() + 5_000_000_000L;
        while (writer.lastTick() != ReplayWriter.BATCH_TICKS && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertEquals(ReplayWriter.BATCH_TICKS, writer.lastTick(), "two packets a second apart reach the writer without a flush");
        writer.close();
    }

    /** Reads the whole file as one gzip stream, as plain gzip readers do, and checks every record. */
    private static int readBack(Path file, List<Rec> expected) throws IOException {
        int n = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(
                new ByteArrayInputStream(Files.readAllBytes(file)), 1 << 16)))) {
            while (true) {
                int tick;
                try {
                    tick = in.readInt();
                } catch (EOFException end) {
                    return n;
                }
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                assertEquals(expected.get(n).tick(), tick);
                assertArrayEquals(expected.get(n).data(), data);
                n++;
            }
        }
    }
}
