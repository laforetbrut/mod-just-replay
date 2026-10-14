package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.Util;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * Sequential reader over a replay stream that keeps only a few segments in memory. Recordings are written as
 * independent gzip members of about five seconds, listed in {@code index.bin}; older recordings without an
 * index are read as a single segment.
 *
 * @author vyrriox
 */
public final class PacketStream {
    private record Segment(int startTick, long offset, long length) {
    }

    private static final int CACHE = 4;

    private final Path file;
    private final List<Segment> segments;
    private final Map<Integer, CompletableFuture<List<RecordedPacket>>> cache = new LinkedHashMap<>(8, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, CompletableFuture<List<RecordedPacket>>> eldest) {
            return size() > CACHE;
        }
    };
    private int segment;
    private int position;
    private List<RecordedPacket> current;

    private PacketStream(Path file, List<Segment> segments) {
        this.file = file;
        this.segments = segments;
    }

    public static PacketStream open(Path dir) throws IOException {
        Path file = dir.resolve(ReplayStorage.STREAM);
        long size = Files.size(file);
        List<Segment> segments = new ArrayList<>();
        Path index = dir.resolve(ReplayWriter.INDEX);
        if (Files.exists(index)) {
            List<long[]> entries = new ArrayList<>();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
                while (true) {
                    int tick;
                    try {
                        tick = in.readInt();
                    } catch (EOFException end) {
                        break;
                    }
                    long offset = in.readLong();
                    if (offset >= 0 && offset < size) {
                        entries.add(new long[]{tick, offset});
                    }
                }
            } catch (EOFException truncated) {
                // A crash can cut the last index entry in half.
            }
            for (int i = 0; i < entries.size(); i++) {
                long offset = entries.get(i)[1];
                long next = i + 1 < entries.size() ? entries.get(i + 1)[1] : size;
                if (next > offset) {
                    segments.add(new Segment((int) entries.get(i)[0], offset, next - offset));
                }
            }
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(0, 0, size));
        }
        PacketStream stream = new PacketStream(file, segments);
        stream.rewind();
        return stream;
    }

    /** Goes back to the first packet. */
    public void rewind() {
        segment = 0;
        position = 0;
        current = load(0);
        prefetch(1);
    }

    /** Next packet without consuming it, or null at the end of the replay. */
    public RecordedPacket peek() {
        while (current != null && position >= current.size()) {
            if (segment + 1 >= segments.size()) {
                return null;
            }
            segment++;
            position = 0;
            current = load(segment);
            prefetch(segment + 1);
        }
        return current == null ? null : current.get(position);
    }

    public void advance() {
        position++;
    }

    /** Tick of the last packet; reads the last segment once. */
    public int lastTick() {
        List<RecordedPacket> last = load(segments.size() - 1);
        return last.isEmpty() ? segments.get(segments.size() - 1).startTick() : last.get(last.size() - 1).tick();
    }

    public boolean isEmpty() {
        return peek() == null;
    }

    private List<RecordedPacket> load(int index) {
        return future(index).join();
    }

    private void prefetch(int index) {
        if (index < segments.size()) {
            future(index);
        }
    }

    private synchronized CompletableFuture<List<RecordedPacket>> future(int index) {
        return cache.computeIfAbsent(index, i -> CompletableFuture.supplyAsync(() -> read(segments.get(i)), Util.backgroundExecutor()));
    }

    private List<RecordedPacket> read(Segment s) {
        List<RecordedPacket> packets = new ArrayList<>();
        byte[] bytes;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            bytes = new byte[(int) s.length()];
            raf.seek(s.offset());
            raf.readFully(bytes);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot read replay segment at {}", s.offset(), e);
            return packets;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes), 1 << 16), 1 << 16))) {
            while (true) {
                int tick;
                try {
                    tick = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                int length = in.readInt();
                if (length < 0 || length > 64 * 1024 * 1024) {
                    throw new IOException("Corrupted packet length " + length);
                }
                byte[] data = new byte[length];
                in.readFully(data);
                packets.add(new RecordedPacket(tick, data));
            }
        } catch (IOException damaged) {
            // Crash while recording: keep what could be read.
            JustReplay.LOGGER.warn("Replay segment at {} is damaged ({}), keeping {} packets", s.offset(), damaged.toString(), packets.size());
        }
        return packets;
    }
}
