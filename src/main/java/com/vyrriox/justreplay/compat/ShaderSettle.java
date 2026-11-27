package com.vyrriox.justreplay.compat;

/**
 * Settling time after a shader pack reload in a replay. Shaders read their frame time from the replay clock, which
 * stands still while the replay is paused: every effect that eases over time (smoothed eye brightness, exposure,
 * temporal accumulation) restarts at a reload and would then barely move until playback resumes. For a short while
 * after a reload the frame time follows the wall clock instead, so the picture settles as it does in game.
 *
 * @author vyrriox
 */
public final class ShaderSettle {
    /** How long the frame time follows the wall clock after a reload. */
    public static final long WINDOW_NANOS = 3_000_000_000L;
    private static volatile long until = Long.MIN_VALUE;

    private ShaderSettle() {
    }

    /** A shader pack was (re)loaded at {@code nowNanos}. */
    public static void reloaded(long nowNanos) {
        until = nowNanos + WINDOW_NANOS;
    }

    /** Whether frames at {@code nowNanos} still settle after the last reload. */
    public static boolean settling(long nowNanos) {
        long end = until;
        return end != Long.MIN_VALUE && nowNanos - end < 0;
    }

    /** Ends any settling window (a replay that closes). */
    public static void reset() {
        until = Long.MIN_VALUE;
    }
}
