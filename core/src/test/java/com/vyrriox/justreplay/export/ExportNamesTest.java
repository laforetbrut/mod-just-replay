package com.vyrriox.justreplay.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The exported range as it goes into file names, the log and the messages, and the final file name of an export.
 * A tick is 1/20 s.
 *
 * @author vyrriox
 */
class ExportNamesTest {
    /** Ticks at a time of the replay. */
    private static int at(int hours, int minutes, double seconds) {
        return (int) Math.round(((hours * 60 + minutes) * 60 + seconds) * 20);
    }

    @Test
    void fileTimeGivesSeconds() {
        assertEquals("00m00s", ExportNames.fileTime(0, false));
        assertEquals("00m10s", ExportNames.fileTime(at(0, 0, 10), false));
        assertEquals("00m59s", ExportNames.fileTime(at(0, 0, 59), false));
        // A time keeps the whole seconds of the tenth the timeline shows: 10.5 s is 10 s, 9.95 s shows as 10.0.
        assertEquals("00m10s", ExportNames.fileTime(at(0, 0, 10.5), false));
        assertEquals("00m10s", ExportNames.fileTime(at(0, 0, 9.95), false));
        assertEquals("00m11s", ExportNames.fileTime(at(0, 0, 10.95), false));
    }

    @Test
    void fileTimeGivesMinutes() {
        assertEquals("01m00s", ExportNames.fileTime(at(0, 1, 0), false));
        assertEquals("01m05s", ExportNames.fileTime(at(0, 1, 5), false));
        assertEquals("59m59s", ExportNames.fileTime(at(0, 59, 59), false));
    }

    @Test
    void fileTimeGivesHours() {
        assertEquals("00h00m10s", ExportNames.fileTime(at(0, 0, 10), true));
        assertEquals("01h00m00s", ExportNames.fileTime(at(1, 0, 0), true));
        assertEquals("02h03m04s", ExportNames.fileTime(at(2, 3, 4), true));
        assertEquals("12h59m59s", ExportNames.fileTime(at(12, 59, 59), true));
    }

    @Test
    void fileTimeNeverGoesNegative() {
        assertEquals("00m00s", ExportNames.fileTime(-40, false));
        assertEquals("00h00m00s", ExportNames.fileTime(-40, true));
    }

    @Test
    void hoursComeFromALongReplayOrALateEnd() {
        assertFalse(ExportNames.hours(at(0, 0, 20), at(0, 1, 0)));
        assertFalse(ExportNames.hours(at(0, 0, 20), ExportNames.HOUR_TICKS - 1));
        assertTrue(ExportNames.hours(at(0, 0, 20), ExportNames.HOUR_TICKS));
        assertTrue(ExportNames.hours(ExportNames.HOUR_TICKS, 0));
    }

    @Test
    void fileRangeOfAPartOfAMinute() {
        // From 00:10 to 00:20 in a one minute replay.
        assertEquals("00m10s-00m20s", ExportNames.fileRange(at(0, 0, 10), at(0, 0, 20), at(0, 1, 0)));
        assertEquals("00m00s-01m00s", ExportNames.fileRange(0, at(0, 1, 0), at(0, 1, 0)));
    }

    @Test
    void fileRangeOfALongReplayHasHoursOnBothEnds() {
        assertEquals("00h00m10s-00h00m20s", ExportNames.fileRange(at(0, 0, 10), at(0, 0, 20), at(1, 30, 0)));
        assertEquals("00h59m10s-01h00m50s", ExportNames.fileRange(at(0, 59, 10), at(1, 0, 50), at(1, 0, 50)));
        // A replay just short of an hour keeps minutes.
        assertEquals("59m00s-59m59s", ExportNames.fileRange(at(0, 59, 0), at(0, 59, 59), at(0, 59, 59.95)));
    }

    @Test
    void fileRangeIsSafeForFileSystems() {
        String range = ExportNames.fileRange(at(0, 12, 34.5), at(2, 3, 4), at(2, 3, 4));
        assertTrue(range.matches("[0-9hms-]+"), range);
    }

    @Test
    void timesDoNotFollowTheDefaultLocale() {
        Locale arabicDigits = Locale.forLanguageTag("ar-SA-u-nu-arab");
        assumeFalse("5".equals(String.format(arabicDigits, "%d", 5)), "this Java has no Arabic digits");
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(arabicDigits);
            assertEquals("00m10s-00m20s", ExportNames.fileRange(at(0, 0, 10), at(0, 0, 20), at(0, 1, 0)));
            assertEquals("1:02:03.5", ExportNames.clock(at(1, 2, 3.5), true));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void clockReadsLikeTheTimeline() {
        assertEquals("0:00.0", ExportNames.clock(0, false));
        assertEquals("0:10.0", ExportNames.clock(at(0, 0, 10), false));
        assertEquals("0:10.5", ExportNames.clock(at(0, 0, 10.5), false));
        // Half a tenth rounds up, as on the timeline.
        assertEquals("0:00.1", ExportNames.clock(1, false));
        assertEquals("1:05.0", ExportNames.clock(at(0, 1, 5), false));
        assertEquals("59:59.9", ExportNames.clock(at(0, 59, 59.9), false));
    }

    @Test
    void clockWithHours() {
        assertEquals("0:00:10.0", ExportNames.clock(at(0, 0, 10), true));
        assertEquals("1:02:03.5", ExportNames.clock(at(1, 2, 3.5), true));
        assertEquals("10:00:00.0", ExportNames.clock(at(10, 0, 0), true));
    }

    @Test
    void lengthIsTheDifferenceOfTheShownTimes() {
        assertEquals("0:10.0", ExportNames.length(at(0, 0, 10), at(0, 0, 20)));
        assertEquals("1:00.0", ExportNames.length(0, at(0, 1, 0)));
        assertEquals("1:00:00.0", ExportNames.length(0, at(1, 0, 0)));
        assertEquals("0:00.0", ExportNames.length(at(0, 0, 20), at(0, 0, 10)));
        // 0:00.1 to 0:00.2: one tenth, though the three ticks between them make 0.15 s.
        assertEquals("0:00.1", ExportNames.length(1, 4));
    }

    @Test
    void safeNameKeepsWhatEverySystemAccepts() {
        assertEquals("Boss_fight", ExportNames.safeName("Boss fight"));
        assertEquals("a_b_c_d_e", ExportNames.safeName("a/b\\c:d*e"));
        assertEquals("D_fi", ExportNames.safeName("Défi"));
        assertEquals("v1.2_final-cut", ExportNames.safeName("v1.2_final-cut"));
        // No folder change can come out of a name.
        assertEquals("_evil", ExportNames.safeName("../evil"));
    }

    @Test
    void safeNameDropsTheExtensionAndEdgeDots() {
        assertEquals("clip", ExportNames.safeName("clip.mp4"));
        assertEquals("clip", ExportNames.safeName("clip.MP4"));
        assertEquals("clip", ExportNames.safeName("clip..mp4"));
        assertEquals("hidden", ExportNames.safeName(".hidden"));
        assertEquals("trailing", ExportNames.safeName("trailing..."));
    }

    @Test
    void safeNameFallsBackWhenNothingIsLeft() {
        assertEquals(ExportNames.DEFAULT_NAME, ExportNames.safeName(""));
        assertEquals(ExportNames.DEFAULT_NAME, ExportNames.safeName(null));
        assertEquals(ExportNames.DEFAULT_NAME, ExportNames.safeName("..."));
        assertEquals(ExportNames.DEFAULT_NAME, ExportNames.safeName(".mp4"));
    }

    @Test
    void safeNameStepsAroundWindowsDevices() {
        assertEquals("_con", ExportNames.safeName("con"));
        assertEquals("_NUL", ExportNames.safeName("NUL"));
        assertEquals("_com1.backup", ExportNames.safeName("com1.backup"));
        assertEquals("console", ExportNames.safeName("console"));
        assertEquals("con_00m10s-00m20s", ExportNames.baseName("con", at(0, 0, 10), at(0, 0, 20), at(0, 1, 0), true));
    }

    @Test
    void baseNameAddsTheRangeUnlessTheOldNamesAreKept() {
        assertEquals("monreplay_00m10s-00m20s", ExportNames.baseName("monreplay", at(0, 0, 10), at(0, 0, 20), at(0, 1, 0), true));
        assertEquals("monreplay", ExportNames.baseName("monreplay", at(0, 0, 10), at(0, 0, 20), at(0, 1, 0), false));
        assertEquals("Boss_fight_00h10m00s-01h05m00s",
                ExportNames.baseName("Boss fight.mp4", at(0, 10, 0), at(1, 5, 0), at(2, 0, 0), true));
        assertEquals("export_00m00s-00m05s", ExportNames.baseName("", 0, at(0, 0, 5), at(0, 0, 5), true));
    }

    @Test
    void baseNameNeverRepeatsTheRange() {
        assertEquals("monreplay_00m10s-00m20s",
                ExportNames.baseName("monreplay_00m10s-00m20s", at(0, 0, 10), at(0, 0, 20), at(0, 1, 0), true));
        assertEquals("monreplay_00m10s-00m20s_00m30s-00m40s",
                ExportNames.baseName("monreplay_00m10s-00m20s", at(0, 0, 30), at(0, 0, 40), at(0, 1, 0), true));
    }

    @Test
    void finalFileNameOfAVideo(@TempDir Path exports) {
        String base = ExportNames.baseName("monreplay", at(0, 0, 10), at(0, 0, 20), at(0, 1, 0), true);
        assertEquals("monreplay_00m10s-00m20s.mp4", ExportNames.firstFree(exports, base, ".mp4").getFileName().toString());
    }

    @Test
    void firstFreeNeverOverwrites(@TempDir Path exports) throws IOException {
        String base = "monreplay_00m10s-00m20s";
        Path first = ExportNames.firstFree(exports, base, ".mp4");
        assertEquals(exports.resolve(base + ".mp4"), first);
        Files.createFile(first);
        Path second = ExportNames.firstFree(exports, base, ".mp4");
        assertEquals(exports.resolve(base + "_2.mp4"), second);
        Files.createFile(second);
        assertEquals(exports.resolve(base + "_3.mp4"), ExportNames.firstFree(exports, base, ".mp4"));
        // Another range is another name.
        assertEquals(exports.resolve("monreplay_00m20s-00m30s.mp4"), ExportNames.firstFree(exports, "monreplay_00m20s-00m30s", ".mp4"));
    }

    @Test
    void firstFreeForAPngSequenceFolder(@TempDir Path exports) throws IOException {
        String base = "monreplay_00m10s-00m20s";
        Files.createDirectories(exports.resolve(base));
        assertEquals(exports.resolve(base + "_2"), ExportNames.firstFree(exports, base, ""));
        // A folder of that name takes the name of the video too.
        Files.createDirectories(exports.resolve("clip.mp4"));
        assertEquals(exports.resolve("clip_2.mp4"), ExportNames.firstFree(exports, "clip", ".mp4"));
    }

    @Test
    void describeGivesEverythingOnOneLine() {
        String line = ExportNames.describe("Boss fight", at(0, 0, 10), at(0, 0, 20), at(0, 1, 0), 1920, 1080, 60,
                "exports/Boss_fight_00m10s-00m20s.mp4");
        assertEquals("\"Boss fight\" from 0:10.0 to 0:20.0 (duration 0:10.0), 1920x1080 at 60 fps,"
                + " file exports/Boss_fight_00m10s-00m20s.mp4", line);
    }

    @Test
    void describeALongReplay() {
        String line = ExportNames.describe("Raid", at(0, 59, 30), at(1, 0, 30), at(2, 0, 0), 3840, 2160, 30, "raid.mp4");
        assertEquals("\"Raid\" from 0:59:30.0 to 1:00:30.0 (duration 1:00.0), 3840x2160 at 30 fps, file raid.mp4", line);
    }

    @Test
    void describeKeepsOneLineWhateverTheName() {
        String line = ExportNames.describe("Boss\nfight\r\tend", 0, 20, 20, 1280, 720, 24, "x.mp4");
        assertFalse(line.contains("\n") || line.contains("\r") || line.contains("\t"), line);
        assertTrue(line.startsWith("\"Boss fight  end\" from 0:00.0 to 0:01.0"), line);
        assertTrue(ExportNames.describe("  ", 0, 20, 20, 1280, 720, 24, "x.mp4").startsWith("unnamed replay from 0:00.0"));
        assertTrue(ExportNames.describe(null, 0, 20, 20, 1280, 720, 24, "x.mp4").startsWith("unnamed replay from"));
    }
}
