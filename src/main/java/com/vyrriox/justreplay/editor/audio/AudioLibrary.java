package com.vyrriox.justreplay.editor.audio;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.Util;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

/**
 * The audio files of {@code <game dir>/justreplay/audio/}, decoded once in the background and kept in memory as
 * 48 kHz stereo floats. The cache holds at most {@link #MEMORY_LIMIT} bytes of samples; past that the files used
 * the longest time ago are dropped and decoded again when needed. A file changed on disk is decoded again.
 *
 * @author vyrriox
 */
public final class AudioLibrary {
    /** Samples kept in memory, in bytes (about 14 minutes of audio). */
    public static final long MEMORY_LIMIT = 320L * 1024 * 1024;
    /** How often a cached file is compared with the disk, in milliseconds. */
    private static final long RECHECK_MS = 2000L;

    public enum State { MISSING, LOADING, READY, FAILED }

    private static final class Entry {
        final String stamp;
        final CompletableFuture<AudioPcm> future;
        long usedAt;
        long checkedAt;

        Entry(String stamp, CompletableFuture<AudioPcm> future) {
            this.stamp = stamp;
            this.future = future;
            this.usedAt = System.currentTimeMillis();
            this.checkedAt = usedAt;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    private AudioLibrary() {
    }

    public static Path folder() {
        Path dir = FMLPaths.GAMEDIR.get().resolve(JustReplay.MOD_ID).resolve("audio");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot create folder {}", dir, e);
        }
        return dir;
    }

    /** Readable audio files of the folder, sorted by name. */
    public static List<String> list() {
        return listMatching(true);
    }

    /** Files of the folder in a format that must be converted first (MP3...). */
    public static List<String> unsupported() {
        return listMatching(false);
    }

    private static List<String> listMatching(boolean supported) {
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder())) {
            stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> supported ? AudioDecoder.isSupported(n) : AudioDecoder.isConvertible(n))
                    .forEach(files::add);
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Cannot list the audio folder: {}", e.toString());
        }
        files.sort(String.CASE_INSENSITIVE_ORDER);
        return files;
    }

    /** The file in the audio folder, or null for a name that would leave it. */
    public static Path resolve(String file) {
        if (file == null || file.isBlank() || file.contains("/") || file.contains("\\") || file.contains("..") || file.contains(":")) {
            return null;
        }
        return folder().resolve(file);
    }

    private static String stamp(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.size() + "@" + attributes.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /** Starts decoding a file when needed; the future completes on a background thread. */
    public static synchronized CompletableFuture<AudioPcm> load(String file) {
        Entry entry = entry(file);
        return entry == null ? CompletableFuture.failedFuture(new AudioDecoder.DecodeException("file not found")) : entry.future;
    }

    private static Entry entry(String file) {
        long now = System.currentTimeMillis();
        Entry entry = CACHE.get(file);
        if (entry != null && now - entry.checkedAt < RECHECK_MS) {
            entry.usedAt = now;
            return entry;
        }
        Path path = resolve(file);
        String stamp = path == null ? null : stamp(path);
        if (stamp == null) {
            CACHE.remove(file);
            return null;
        }
        if (entry != null && entry.stamp.equals(stamp)) {
            entry.checkedAt = now;
            entry.usedAt = now;
            return entry;
        }
        CompletableFuture<AudioPcm> future = CompletableFuture.supplyAsync(() -> {
            try {
                long started = System.nanoTime();
                AudioPcm pcm = AudioDecoder.decode(path);
                JustReplay.LOGGER.info("Audio {} decoded: {} s in {} ms", file, String.format(Locale.ROOT, "%.1f", pcm.lengthTicks() / 20.0),
                        (System.nanoTime() - started) / 1_000_000L);
                return pcm;
            } catch (Exception e) {
                JustReplay.LOGGER.warn("Cannot decode audio {}: {}", file, e.getMessage());
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
        entry = new Entry(stamp, future);
        CACHE.put(file, entry);
        future.thenRun(AudioLibrary::trim);
        return entry;
    }

    /** Decoded samples of a file, or null while it is decoding, missing or unreadable. */
    public static synchronized AudioPcm get(String file) {
        Entry entry = entry(file);
        return entry == null ? null : decoded(entry);
    }

    private static AudioPcm decoded(Entry entry) {
        if (!entry.future.isDone() || entry.future.isCompletedExceptionally()) {
            return null;
        }
        return entry.future.getNow(null);
    }

    /** Decoded samples of a file, waiting for the decoding; null when it cannot be read. Never on the client thread. */
    public static AudioPcm getBlocking(String file) {
        CompletableFuture<AudioPcm> future = load(file);
        try {
            return future.join();
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized State state(String file) {
        Entry entry = entry(file);
        if (entry == null) {
            return State.MISSING;
        }
        if (!entry.future.isDone()) {
            return State.LOADING;
        }
        return entry.future.isCompletedExceptionally() ? State.FAILED : State.READY;
    }

    /** Why a file could not be decoded, or null. */
    public static synchronized String error(String file) {
        Entry entry = CACHE.get(file);
        if (entry == null) {
            return resolve(file) == null || stamp(resolve(file)) == null ? "file not found" : null;
        }
        if (!entry.future.isCompletedExceptionally()) {
            return null;
        }
        try {
            entry.future.join();
            return null;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return cause.getMessage() != null ? cause.getMessage() : cause.toString();
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** Drops the files used the longest time ago while the cache is over its memory limit. */
    private static synchronized void trim() {
        long total = 0;
        for (Entry entry : CACHE.values()) {
            AudioPcm pcm = decoded(entry);
            total += pcm == null ? 0 : pcm.bytes();
        }
        while (total > MEMORY_LIMIT) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
                if (e.getValue().usedAt < oldestAt && decoded(e.getValue()) != null) {
                    oldest = e.getKey();
                    oldestAt = e.getValue().usedAt;
                }
            }
            if (oldest == null) {
                break;
            }
            AudioPcm dropped = decoded(CACHE.remove(oldest));
            total -= dropped.bytes();
            JustReplay.LOGGER.debug("Audio cache full, dropped {}", oldest);
        }
    }
}
