package com.vyrriox.justreplay.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ReplayProject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Layout of the replay folder: {@code <game dir>/justreplay/replays/<id>/} holding {@code stream.bin.gz},
 * {@code meta.json} and {@code project.json}.
 *
 * @author vyrriox
 */
public final class ReplayStorage {
    public static final String STREAM = "stream.bin.gz";
    public static final String META = "meta.json";
    public static final String PROJECT = "project.json";
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ReplayStorage() {
    }

    public static Path root() {
        return ensure(FMLPaths.GAMEDIR.get().resolve(JustReplay.MOD_ID).resolve("replays"));
    }

    public static Path exportRoot() {
        return ensure(FMLPaths.GAMEDIR.get().resolve(JustReplay.MOD_ID).resolve("exports"));
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot create folder {}", dir, e);
        }
        return dir;
    }

    public record Entry(Path dir, ReplayMeta meta, long sizeBytes) {
    }

    public static List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root())) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                if (!Files.exists(dir.resolve(STREAM))) {
                    return;
                }
                long size = 0;
                try {
                    size = Files.size(dir.resolve(STREAM));
                } catch (IOException ignored) {
                }
                result.add(new Entry(dir, readMeta(dir), size));
            });
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot list replays", e);
        }
        result.sort(Comparator.comparingLong((Entry e) -> e.meta().startedAt).reversed());
        return result;
    }

    public static ReplayMeta readMeta(Path dir) {
        ReplayMeta meta = readJson(dir.resolve(META), ReplayMeta.class);
        if (meta == null) {
            meta = new ReplayMeta();
            meta.name = dir.getFileName().toString();
        }
        return meta;
    }

    public static void writeMeta(Path dir, ReplayMeta meta) {
        writeJson(dir.resolve(META), meta);
    }

    public static ReplayProject readProject(Path dir) {
        ReplayProject project = readJson(dir.resolve(PROJECT), ReplayProject.class);
        if (project == null) {
            project = new ReplayProject();
        }
        project.normalize();
        return project;
    }

    public static void writeProject(Path dir, ReplayProject project) {
        writeJson(dir.resolve(PROJECT), project);
    }

    /** Reads every packet of a stream. A truncated stream (crash while recording) is read up to the damage. */
    public static List<RecordedPacket> readStream(Path dir) throws IOException {
        List<RecordedPacket> packets = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(dir.resolve(STREAM)), 1 << 16), 1 << 16))) {
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
            // Crash while recording or damaged file: keep everything read so far.
            if (packets.isEmpty()) {
                throw damaged;
            }
            JustReplay.LOGGER.warn("Replay stream in {} is damaged ({}), keeping {} packets", dir, damaged.toString(), packets.size());
        }
        return packets;
    }

    private static <T> T readJson(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (Exception e) {
            JustReplay.LOGGER.error("Cannot read {}", file, e);
            return null;
        }
    }

    private static void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot write {}", file, e);
        }
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
