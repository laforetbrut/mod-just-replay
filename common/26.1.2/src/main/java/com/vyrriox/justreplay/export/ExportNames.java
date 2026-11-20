package com.vyrriox.justreplay.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Names and descriptions of video exports, without game code so they can be unit tested: the exported range as it
 * goes into a file name ({@code 00m10s-00m20s}) and as people read it ({@code 0:10.0}), the file name of an export
 * and the line the log gets when an export starts and ends.
 * <p>
 * A tick is 1/20 s. Times are rounded to the tenth of a second like the timeline, and a file name keeps the whole
 * seconds of that tenth, so the file name, the log and the messages never disagree.
 *
 * @author vyrriox
 */
public final class ExportNames {
    /** Ticks in one hour: times of a replay this long or longer carry hours. */
    public static final int HOUR_TICKS = 20 * 60 * 60;
    /** File name used when nothing is left of the typed name. */
    public static final String DEFAULT_NAME = "export";
    /** Tenths of a second in one hour. */
    private static final long HOUR_TENTHS = 10 * 60 * 60;
    /** Names Windows keeps for devices, with or without an extension (con.mp4 is refused too). */
    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private ExportNames() {
    }

    /** True when the times of a range carry hours: its replay lasts an hour or more, or the range ends past one hour. */
    public static boolean hours(int endTick, int replayDuration) {
        return Math.max(endTick, replayDuration) >= HOUR_TICKS;
    }

    /** Time at a tick as it goes into a file name, in whole seconds: {@code 00m10s}, or {@code 01h02m03s} with hours. */
    public static String fileTime(int tick, boolean hours) {
        long seconds = tenths(tick) / 10;
        if (hours) {
            return String.format(Locale.ROOT, "%02dh%02dm%02ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%02dm%02ds", seconds / 60, seconds % 60);
    }

    /** Range as it goes into a file name: {@code 00m10s-00m20s}, with hours on both ends for a replay of an hour or more. */
    public static String fileRange(int startTick, int endTick, int replayDuration) {
        boolean hours = hours(endTick, replayDuration);
        return fileTime(startTick, hours) + "-" + fileTime(endTick, hours);
    }

    /** Time at a tick for people, to the tenth like the timeline: {@code 0:10.0}, or {@code 1:02:03.5} with hours. */
    public static String clock(int tick, boolean hours) {
        return clockOfTenths(tenths(tick), hours);
    }

    /**
     * Length of a range for people: {@code 0:10.0}, with hours from one hour on. It is the difference of the two
     * times as they are shown, so start, end and length always add up.
     */
    public static String length(int startTick, int endTick) {
        long tenths = Math.max(0L, tenths(endTick) - tenths(startTick));
        return clockOfTenths(tenths, tenths >= HOUR_TENTHS);
    }

    /**
     * The typed name made safe for every file system: characters other than ASCII letters, digits, '.', '_' and '-'
     * become '_'; a final ".mp4" goes (the extension is added back); no dot at either end (a leading one hides the
     * file on Linux and macOS, Windows drops a trailing one); Windows device names (CON, NUL, COM1...) get a '_' in
     * front; a name with nothing left becomes {@value #DEFAULT_NAME}.
     */
    public static String safeName(String typed) {
        return awayFromDevices(clean(typed));
    }

    /**
     * Name of an export without its extension: the safe typed name followed by the range, as in
     * {@code Boss_fight_00m10s-00m20s}, or the safe name alone when the old names are kept. A name that already ends
     * with this range does not get it twice.
     */
    public static String baseName(String typed, int startTick, int endTick, int replayDuration, boolean withRange) {
        String name = clean(typed);
        if (withRange) {
            String range = "_" + fileRange(startTick, endTick, replayDuration);
            if (!name.endsWith(range)) {
                name += range;
            }
        }
        return awayFromDevices(name);
    }

    /**
     * First path of a folder that no file or folder takes: {@code base + extension}, else {@code base_2 + extension},
     * {@code base_3}... so an export never overwrites an earlier one. The extension is empty for a folder.
     */
    public static Path firstFree(Path dir, String base, String extension) {
        Path path = dir.resolve(base + extension);
        for (int n = 2; Files.exists(path); n++) {
            path = dir.resolve(base + "_" + n + extension);
        }
        return path;
    }

    /**
     * The line the log gets when an export starts and ends, as in {@code "Boss fight" from 0:10.0 to 0:20.0
     * (duration 0:10.0), 1920x1080 at 60 fps, file .../Boss_fight_00m10s-00m20s.mp4}. Control characters of the
     * replay name become spaces, so it always stays on one line.
     */
    public static String describe(String replay, int startTick, int endTick, int replayDuration, int width, int height, int fps,
                                  String file) {
        boolean hours = hours(endTick, replayDuration);
        String name = replay == null || replay.isBlank() ? "unnamed replay" : "\"" + replay.replaceAll("\\p{Cntrl}", " ") + "\"";
        return name + " from " + clock(startTick, hours) + " to " + clock(endTick, hours) + " (duration " + length(startTick, endTick)
                + "), " + width + "x" + height + " at " + fps + " fps, file " + file;
    }

    /** Tenths of a second at a tick, rounded half up like the timeline; never negative. */
    private static long tenths(long tick) {
        return Math.round(Math.max(0L, tick) / 2.0);
    }

    private static String clockOfTenths(long tenths, boolean hours) {
        long seconds = tenths / 10;
        if (hours) {
            return String.format(Locale.ROOT, "%d:%02d:%02d.%d", seconds / 3600, seconds % 3600 / 60, seconds % 60, tenths % 10);
        }
        return String.format(Locale.ROOT, "%d:%02d.%d", seconds / 60, seconds % 60, tenths % 10);
    }

    /** Safe characters, no extension, no dot at either end, never empty (see {@link #safeName}). */
    private static String clean(String typed) {
        String name = typed == null ? "" : typed.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.+$", "");
        if (name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("^\\.+|\\.+$", "");
        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    /** A '_' in front of a Windows device name, which Windows refuses with any extension. */
    private static String awayFromDevices(String name) {
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
        return DEVICES.contains(stem) ? "_" + name : name;
    }
}
