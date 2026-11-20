package com.vyrriox.justreplay.export;

import java.util.Locale;

/**
 * What the render veil shows while a video is made, without game code so it can be unit tested: the share of frames
 * done, the time spent and the time left as people read them, and the GUI scale the veil is drawn at.
 * <p>
 * The veil keeps the size of the game's own interface in the window whatever the export size: the game picks its
 * GUI scale from the render size, which an export changes, so the veil works out the scale the real window would get.
 *
 * @author vyrriox
 */
public final class ExportProgress {
    /** Frames captured before the time left is estimated: the first ones are slow (shaders, chunks, caches). */
    public static final int ESTIMATE_MIN_FRAMES = 10;
    /** Time spent capturing before the time left is estimated. */
    public static final long ESTIMATE_MIN_MILLIS = 2000L;
    /** Smallest width and height of the game's interface at any GUI scale, in GUI pixels. */
    private static final int MIN_GUI_WIDTH = 320;
    private static final int MIN_GUI_HEIGHT = 240;

    private ExportProgress() {
    }

    /** Share of the frames done, from 0 to 1; 0 when the total is unknown. */
    public static float fraction(long done, long total) {
        if (total <= 0 || done <= 0) {
            return 0.0F;
        }
        return done >= total ? 1.0F : (float) ((double) done / total);
    }

    /** Whole percent done, rounded down so 100 only shows once every frame is done. */
    public static int percent(long done, long total) {
        if (total <= 0 || done <= 0) {
            return 0;
        }
        return done >= total ? 100 : (int) Math.min(99L, done * 100L / total);
    }

    /**
     * Seconds left at the pace so far, rounded up so it never reads 0:00 while frames are left; -1 while it is too
     * early to tell (see {@link #ESTIMATE_MIN_FRAMES} and {@link #ESTIMATE_MIN_MILLIS}).
     */
    public static long remainingSeconds(long done, long total, long elapsedMillis) {
        if (total <= 0 || done < ESTIMATE_MIN_FRAMES || elapsedMillis < ESTIMATE_MIN_MILLIS) {
            return -1L;
        }
        if (done >= total) {
            return 0L;
        }
        double millis = (double) elapsedMillis * (total - done) / done;
        return (long) Math.ceil(millis / 1000.0);
    }

    /** A wall time for people: {@code 0:05}, {@code 12:34}, {@code 1:02:03}; a negative time reads {@code -:--}. */
    public static String clock(long seconds) {
        if (seconds < 0) {
            return "-:--";
        }
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * GUI scale the game picks for a window of this size: the largest scale up to {@code option} (0 for automatic)
     * that keeps the interface at least 320 x 240 GUI pixels, made even with the Unicode font forced.
     */
    public static int guiScale(int width, int height, int option, boolean forceUnicode) {
        int scale = 1;
        while (scale != option && scale < width && scale < height
                && width / (scale + 1) >= MIN_GUI_WIDTH && height / (scale + 1) >= MIN_GUI_HEIGHT) {
            scale++;
        }
        if (forceUnicode && scale % 2 != 0) {
            scale++;
        }
        return scale;
    }
}
