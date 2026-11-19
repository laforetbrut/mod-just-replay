package com.vyrriox.justreplay.format;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names typed for a replay: what the list shows, the folder made from it on every system, and the suffix that keeps
 * two replays of one name apart.
 *
 * @author vyrriox
 */
class ReplayNamesTest {
    private static final String FORBIDDEN = "<>:\"/\\|?*";

    @Test
    void cleanTrimsAndJoinsWhiteSpace() {
        assertEquals("Boss fight", ReplayNames.clean("  Boss \t\n  fight  "));
        assertEquals("Boss fight", ReplayNames.clean("Boss\u00a0fight"));
        assertEquals("", ReplayNames.clean("   "));
        assertEquals("", ReplayNames.clean(null));
    }

    @Test
    void cleanDropsControlInvisibleAndFormattingCharacters() {
        assertEquals("Boss", ReplayNames.clean("B\u0000o\u0007s\u007fs"));
        // Formatting codes would restyle the name in the game; direction marks could disguise it.
        assertEquals("cRed", ReplayNames.clean("\u00a7cRed"));
        assertEquals("abcfdp.exe", ReplayNames.clean("abc\u202efdp.exe"));
        assertEquals("ab", ReplayNames.clean("a\u200bb"));
        // A lone half of a surrogate pair is not a character.
        assertEquals("ab", ReplayNames.clean("a\ud83db"));
    }

    @Test
    void cleanKeepsWhatWindowsRefusesForTheShownName() {
        assertEquals("Wither: round 2?", ReplayNames.clean("Wither: round 2?"));
        assertEquals("Défi 中文 \ud83c\udfac", ReplayNames.clean("Défi 中文 \ud83c\udfac"));
    }

    @Test
    void cleanCutsLongNamesWithoutSplittingACharacter() {
        String longName = "a".repeat(100);
        assertEquals(ReplayNames.MAX_LENGTH, ReplayNames.clean(longName).length());
        // 63 letters then an emoji (two chars): the emoji does not fit and is dropped whole.
        String edge = "b".repeat(ReplayNames.MAX_LENGTH - 1) + "\ud83c\udfac";
        String cut = ReplayNames.clean(edge);
        assertEquals("b".repeat(ReplayNames.MAX_LENGTH - 1), cut);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        // A space is never left at the end by the cut.
        String spaced = "c".repeat(ReplayNames.MAX_LENGTH) + " d";
        assertEquals("c".repeat(ReplayNames.MAX_LENGTH), ReplayNames.clean(spaced));
    }

    @Test
    void folderNameReplacesEveryForbiddenCharacter() {
        for (char c : FORBIDDEN.toCharArray()) {
            assertEquals("a_b", ReplayNames.folderName("a" + c + "b"), "character " + c);
        }
        assertEquals("Wither_ round 2_", ReplayNames.folderName("Wither: round 2?"));
        assertEquals("Défi 中文", ReplayNames.folderName("Défi 中文"));
    }

    @Test
    void folderNameHasNoDotOrSpaceAtEitherEnd() {
        assertEquals("boss", ReplayNames.folderName(" boss. "));
        assertEquals("hidden", ReplayNames.folderName(".hidden"));
        assertEquals("", ReplayNames.folderName("."));
        assertEquals("", ReplayNames.folderName(".."));
        assertEquals("", ReplayNames.folderName(" . . "));
    }

    @Test
    void folderNameNeverLeavesTheReplayFolder() {
        Path root = Path.of("replays");
        for (String typed : new String[]{"../../evil", "..\\..\\evil", "/etc/passwd", "C:\\Windows", "a/../../b", "..", "~"}) {
            String folder = ReplayNames.folderName(typed);
            if (folder.isEmpty()) {
                continue;
            }
            Path resolved = root.resolve(folder).normalize();
            assertEquals(root, resolved.getParent(), typed);
            for (char c : FORBIDDEN.toCharArray()) {
                assertTrue(folder.indexOf(c) < 0, typed + " kept " + c);
            }
        }
    }

    @Test
    void folderNameAvoidsWindowsDeviceNames() {
        assertEquals("_CON", ReplayNames.folderName("CON"));
        assertEquals("_nul", ReplayNames.folderName("nul"));
        assertEquals("_Com1", ReplayNames.folderName("Com1"));
        assertEquals("_LPT9", ReplayNames.folderName("LPT9"));
        assertEquals("_con.txt", ReplayNames.folderName("con.txt"));
        assertEquals("_aux .mp4", ReplayNames.folderName("aux .mp4"));
        assertEquals("CONSOLE", ReplayNames.folderName("CONSOLE"));
        assertEquals("my con", ReplayNames.folderName("my con"));
    }

    @Test
    void folderNameIsNeverSkippedByTheReplayList() {
        // The list skips folders ending in .tmp: those are clips still being written.
        assertEquals("clip.tmp_", ReplayNames.folderName("clip.tmp"));
        assertEquals("clip.TMP_", ReplayNames.folderName("clip.TMP"));
        assertEquals("clip.tmp_", ReplayNames.folderName("clip.tmp..."));
        assertFalse(ReplayNames.folderName("x.tmp").toLowerCase().endsWith(".tmp"));
    }

    @Test
    void folderNameStaysShort() {
        String folder = ReplayNames.folderName("é".repeat(300));
        assertEquals(ReplayNames.MAX_LENGTH, folder.length());
        // Even in UTF-8 it stays under the 255 bytes Linux allows, with room for a suffix.
        assertTrue(ReplayNames.withSuffix(folder, 9999).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 255);
    }

    @Test
    void asciiFolderNameKeepsOnlyPrintableAscii() {
        assertEquals("D_fi _ boss", ReplayNames.asciiFolderName("Défi \ud83c\udfac boss"));
        assertEquals("_t_", ReplayNames.asciiFolderName("été"));
        assertEquals("plain", ReplayNames.asciiFolderName("plain"));
        String ascii = ReplayNames.asciiFolderName("中文: 2?");
        for (char c : ascii.toCharArray()) {
            assertTrue(c >= 0x20 && c < 0x7F, ascii);
        }
    }

    @Test
    void uniqueKeepsAFreeName() {
        assertEquals("Boss", ReplayNames.unique("Boss", name -> false));
    }

    @Test
    void uniqueAddsTheFirstFreeNumber() {
        Set<String> taken = new HashSet<>(Set.of("Boss", "Boss (2)", "Boss (3)"));
        assertEquals("Boss (4)", ReplayNames.unique("Boss", taken::contains));
        taken.remove("Boss (2)");
        assertEquals("Boss (2)", ReplayNames.unique("Boss", taken::contains));
    }

    @Test
    void uniqueNeverReturnsATakenName() {
        // Every numbered name taken: the clock still finds a free one.
        String free = ReplayNames.unique("Boss", name -> name.equals("Boss") || name.matches("Boss \\(\\d+\\)"));
        assertTrue(free.startsWith("Boss ("), free);
        assertFalse(free.matches("Boss \\(\\d+\\)"), free);
    }
}
