package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Checkpoints are full snapshots of the client state taken while recording, stored next to the stream as
 * {@code checkpoints/<tick>.bin.gz} in the stream's own record format. Playback restores the latest one before a
 * seek target instead of replaying the whole stream from its start.
 *
 * @author vyrriox
 */
public final class Checkpoints {
    public static final String FOLDER = "checkpoints";
    private static final Pattern NAME = Pattern.compile("(\\d+)\\.bin\\.gz");

    private Checkpoints() {
    }

    /** Ticks of the checkpoints of a replay, in increasing order; empty when there are none. */
    public static int[] list(Path dir) {
        Path folder = dir.resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            return new int[0];
        }
        List<Integer> ticks = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(file -> {
                Matcher m = NAME.matcher(file.getFileName().toString());
                if (m.matches()) {
                    try {
                        ticks.add(Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Cannot list the checkpoints of {}", dir, e);
        }
        return ticks.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /** Writes a checkpoint; the file only appears once complete, so a crash never leaves a partial one. */
    public static void write(Path dir, int tick, List<byte[]> records) throws IOException {
        Path folder = dir.resolve(FOLDER);
        Files.createDirectories(folder);
        Path file = folder.resolve(tick + ".bin.gz");
        Path tmp = folder.resolve(tick + ".bin.gz.tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(tmp), 1 << 16), 1 << 16))) {
            for (byte[] data : records) {
                out.writeInt(tick);
                out.writeInt(data.length);
                out.write(data);
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Reads every packet of a checkpoint. A damaged checkpoint is rejected as a whole. */
    public static List<RecordedPacket> read(Path dir, int tick) throws IOException {
        List<RecordedPacket> packets = new ArrayList<>();
        Path file = dir.resolve(FOLDER).resolve(tick + ".bin.gz");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
            PacketStream.readRecords(in, packets);
        }
        return packets;
    }
}
