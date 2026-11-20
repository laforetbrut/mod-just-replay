package com.vyrriox.justreplay.format;

import com.vyrriox.justreplay.JustReplay;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Renames a saved replay: its folder, under a cleaned name that never takes the folder of another replay, and the
 * name the replay list shows ({@link ReplayMeta#name} in {@code meta.json}). Only for replays whose files are
 * complete: nothing may still be writing into the folder. Runs off the client thread.
 *
 * @author vyrriox
 */
public final class ReplayRename {
    /** Tries per rename: on Windows an indexer or an antivirus may hold a file of the folder for a moment. */
    private static final int ATTEMPTS = 5;
    private static final long RETRY_MILLIS = 200;

    private ReplayRename() {
    }

    public enum Status {
        /** Blank name, the name the replay already has, or no replay in that folder: nothing changed. */
        UNCHANGED,
        /** The folder (when its name had to change) and the shown name were renamed. */
        RENAMED,
        /** Only the shown name changed: the folder could not be renamed and keeps its name. */
        LIST_ONLY
    }

    /** The replay after {@link #apply}: its folder now, the name the list shows now, what happened. */
    public record Result(Path dir, String name, Status status) {
    }

    /**
     * Renames the replay in {@code dir} to {@code typed}. The shown name keeps what was typed (see
     * {@link ReplayNames#clean}); the folder gets {@link ReplayNames#folderName}, and when a folder of that name
     * exists, " (2)", " (3)"... is added to both.
     */
    public static Result apply(Path dir, String typed) {
        if (!Files.isDirectory(dir)) {
            return new Result(dir, ReplayNames.clean(typed), Status.UNCHANGED);
        }
        ReplayMeta meta = ReplayStorage.readMeta(dir);
        String name = ReplayNames.clean(typed);
        if (name.isEmpty() || name.equals(meta.name)) {
            return new Result(dir, meta.name, Status.UNCHANGED);
        }
        String folder = ReplayNames.folderName(name);
        Path moved;
        try {
            moved = move(dir, folder);
        } catch (InvalidPathException refused) {
            // A Linux game whose file name encoding cannot store the name: plain ASCII then.
            JustReplay.LOGGER.warn("The file system refuses the replay folder name '{}', using an ASCII one: {}", folder, refused.getMessage());
            folder = ReplayNames.asciiFolderName(name);
            try {
                moved = move(dir, folder);
            } catch (InvalidPathException again) {
                moved = null;
            }
        }
        Path target = moved != null ? moved : dir;
        String shown = name;
        if (moved != null) {
            // The number that set the folder apart goes to the shown name as well, so the list tells them apart.
            shown = name + target.getFileName().toString().substring(folder.length());
        }
        meta.name = shown;
        ReplayStorage.writeMeta(target, meta);
        return new Result(target, shown, moved != null ? Status.RENAMED : Status.LIST_ONLY);
    }

    /**
     * Moves {@code dir} to the first free folder named {@code folder} or {@code folder (n)} next to it, which may be
     * {@code dir} itself. Null when the name is empty or the folder could not be moved.
     *
     * @throws InvalidPathException when the file system cannot store the name
     */
    private static Path move(Path dir, String folder) {
        if (folder.isEmpty()) {
            return null;
        }
        Path root = dir.getParent();
        String current = dir.getFileName().toString();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String free = ReplayNames.unique(folder, candidate -> !candidate.equals(current) && taken(root, candidate, dir));
            if (free.equals(current)) {
                return dir;
            }
            Path target = root.resolve(free);
            try {
                // No REPLACE_EXISTING: an existing folder makes the move fail instead of being written over.
                Files.move(dir, target);
                return target;
            } catch (FileAlreadyExistsException race) {
                // Taken between the check and the move: the next attempt picks the next free name.
            } catch (IOException e) {
                if (attempt == ATTEMPTS) {
                    JustReplay.LOGGER.warn("Cannot rename the replay folder {} to {}: {}", dir, free, e.toString());
                    return null;
                }
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /** Whether something of that name exists next to {@code dir}, other than {@code dir} itself. */
    private static boolean taken(Path root, String candidate, Path dir) {
        Path path = root.resolve(candidate);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            // Windows and macOS ignore case: "boss" may be the very folder "Boss" being renamed.
            return !Files.isSameFile(path, dir);
        } catch (IOException e) {
            return true;
        }
    }
}
