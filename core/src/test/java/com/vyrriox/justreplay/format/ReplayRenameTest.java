package com.vyrriox.justreplay.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renaming saved replays on a real folder: the folder and the shown name change together, another replay is never
 * written over, and the files of the replay come through unchanged.
 *
 * @author vyrriox
 */
class ReplayRenameTest {
    @TempDir
    Path root;

    private Path replay(String folder, String name) throws IOException {
        Path dir = Files.createDirectories(root.resolve(folder));
        Files.write(dir.resolve(ReplayStorage.STREAM), stream(folder));
        ReplayMeta meta = new ReplayMeta();
        meta.name = name;
        meta.durationTicks = 1234;
        meta.server = "Test server";
        ReplayStorage.writeMeta(dir, meta);
        return dir;
    }

    private static byte[] stream(String folder) {
        return ("packets of " + folder).getBytes(StandardCharsets.UTF_8);
    }

    private List<String> folders() throws IOException {
        try (Stream<Path> list = Files.list(root)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void renamesTheFolderAndTheShownName() throws IOException {
        Path dir = replay("2026-11-19_10-15-00", "2026-11-19_10-15-00");
        ReplayRename.Result result = ReplayRename.apply(dir, "  Wither: round 2?  ");
        assertEquals(ReplayRename.Status.RENAMED, result.status());
        assertEquals(root.resolve("Wither_ round 2_"), result.dir());
        assertEquals("Wither: round 2?", result.name());
        assertFalse(Files.exists(dir));
        assertEquals(List.of("Wither_ round 2_"), folders());
        ReplayMeta meta = ReplayStorage.readMeta(result.dir());
        assertEquals("Wither: round 2?", meta.name);
        // Everything else in meta.json and the stream itself come through unchanged.
        assertEquals(1234, meta.durationTicks);
        assertEquals("Test server", meta.server);
        assertArrayEquals(stream("2026-11-19_10-15-00"), Files.readAllBytes(result.dir().resolve(ReplayStorage.STREAM)));
    }

    @Test
    void neverWritesOverAnotherReplay() throws IOException {
        Path first = replay("Boss", "Boss");
        Path second = replay("2026-11-19_10-15-00_buffer", "2026-11-19_10-15-00");
        ReplayRename.Result result = ReplayRename.apply(second, "Boss");
        assertEquals(root.resolve("Boss (2)"), result.dir());
        assertEquals("Boss (2)", result.name());
        assertEquals("Boss (2)", ReplayStorage.readMeta(result.dir()).name);
        assertArrayEquals(stream("2026-11-19_10-15-00_buffer"), Files.readAllBytes(result.dir().resolve(ReplayStorage.STREAM)));
        // The replay that had the name first is untouched.
        assertArrayEquals(stream("Boss"), Files.readAllBytes(first.resolve(ReplayStorage.STREAM)));
        assertEquals("Boss", ReplayStorage.readMeta(first).name);
        assertEquals(root.resolve("Boss (3)"), ReplayRename.apply(replay("third", "third"), "Boss").dir());
        assertEquals(List.of("Boss", "Boss (2)", "Boss (3)"), folders());
    }

    @Test
    void theNumberFollowsTheFolderEvenWhenShownNamesDiffer() throws IOException {
        replay("Wither_", "Wither?");
        ReplayRename.Result result = ReplayRename.apply(replay("other", "other"), "Wither*");
        assertEquals(root.resolve("Wither_ (2)"), result.dir());
        assertEquals("Wither* (2)", result.name());
    }

    @Test
    void anythingInTheWayCountsAsTaken() throws IOException {
        Files.writeString(root.resolve("Boss"), "not a replay");
        Files.createDirectories(root.resolve("Boss (2)"));
        ReplayRename.Result result = ReplayRename.apply(replay("other", "other"), "Boss");
        assertEquals(root.resolve("Boss (3)"), result.dir());
        assertEquals("not a replay", Files.readString(root.resolve("Boss")));
        try (Stream<Path> empty = Files.list(root.resolve("Boss (2)"))) {
            assertEquals(0, empty.count());
        }
    }

    @Test
    void blankOrSameNameChangesNothing() throws IOException {
        Path dir = replay("2026-11-19_10-15-00", "2026-11-19_10-15-00");
        assertEquals(ReplayRename.Status.UNCHANGED, ReplayRename.apply(dir, "   ").status());
        assertEquals(ReplayRename.Status.UNCHANGED, ReplayRename.apply(dir, " 2026-11-19_10-15-00 ").status());
        assertEquals(ReplayRename.Status.UNCHANGED, ReplayRename.apply(dir, null).status());
        assertEquals(List.of("2026-11-19_10-15-00"), folders());
        assertEquals("2026-11-19_10-15-00", ReplayStorage.readMeta(dir).name);
    }

    @Test
    void aReplayKeepsItsOwnFolderWhenRenamedAgain() throws IOException {
        ReplayRename.Result first = ReplayRename.apply(replay("other", "other"), "Boss?");
        assertEquals(root.resolve("Boss_"), first.dir());
        // The new name gives the same folder: the replay is not set apart from itself with a "(2)".
        ReplayRename.Result again = ReplayRename.apply(first.dir(), "Boss*");
        assertEquals(ReplayRename.Status.RENAMED, again.status());
        assertEquals(root.resolve("Boss_"), again.dir());
        assertEquals("Boss*", again.name());
        assertEquals(List.of("Boss_"), folders());
    }

    @Test
    void aNameWithoutAUsableFolderOnlyChangesTheList() throws IOException {
        Path dir = replay("2026-11-19_10-15-00", "2026-11-19_10-15-00");
        ReplayRename.Result result = ReplayRename.apply(dir, "...");
        assertEquals(ReplayRename.Status.LIST_ONLY, result.status());
        assertEquals(dir, result.dir());
        assertEquals("...", ReplayStorage.readMeta(dir).name);
        assertEquals(List.of("2026-11-19_10-15-00"), folders());
    }

    @Test
    void aMissingReplayIsNotCreatedAgain() throws IOException {
        Path gone = root.resolve("gone");
        assertEquals(ReplayRename.Status.UNCHANGED, ReplayRename.apply(gone, "Boss").status());
        assertEquals(List.of(), folders());
    }

    @Test
    void otherAlphabetsAreKept() throws IOException {
        ReplayRename.Result result = ReplayRename.apply(replay("other", "other"), "Défi 中文");
        assertEquals("Défi 中文", result.name());
        assertTrue(Files.isDirectory(result.dir()));
        // A file system that cannot store the name gets the plain ASCII folder instead.
        String folder = result.dir().getFileName().toString();
        assertTrue(folder.equals("Défi 中文") || folder.equals("D_fi __"), folder);
    }
}
