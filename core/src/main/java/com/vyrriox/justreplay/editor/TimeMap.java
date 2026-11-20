package com.vyrriox.justreplay.editor;

/**
 * Video time of a range of replay ticks under a {@link SpeedTrack}: how long the video of the range lasts, which
 * replay tick a moment of the video shows, and the reverse. Built once per export from the track, one entry per
 * tick: the seconds of video from a tick to the next follow the average of 1/speed over that tick (exact for held
 * speeds and timelapses, within a few microseconds for ramps), and a freeze adds its seconds on its tick. Plain Java,
 * no game code.
 *
 * @author vyrriox
 */
public final class TimeMap {
    private final int start;
    private final int end;
    /** Without a track: one replay second per video second, nothing stored. */
    private final boolean identity;
    /** {@code reach[i]}: video seconds when the replay reaches tick {@code start + i}, before any freeze on it. */
    private final double[] reach;
    /** {@code hold[i]}: seconds of video the picture stays on tick {@code start + i}. */
    private final double[] hold;
    private final SpeedTrack track;

    private TimeMap(int start, int end, SpeedTrack track) {
        this.start = start;
        this.end = Math.max(start, end);
        this.track = track;
        this.identity = track == null || track.isEmpty();
        int n = this.end - start;
        if (identity) {
            reach = null;
            hold = null;
            return;
        }
        reach = new double[n + 1];
        hold = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            hold[i] = track.freezeAt(start + i);
        }
        for (int i = 0; i < n; i++) {
            reach[i + 1] = reach[i] + hold[i] + stepSeconds(track, start + i);
        }
    }

    /** The video time of the ticks {@code start} to {@code end} under a track (null or empty: unchanged). */
    public static TimeMap of(SpeedTrack track, int start, int end) {
        return new TimeMap(start, end, track);
    }

    /** Video seconds from a tick to the next. */
    private static double stepSeconds(SpeedTrack track, int tick) {
        SpeedTrack.Timelapse lapse = track.timelapseAt(tick);
        if (lapse != null) {
            return 1.0 / (20.0 * lapse.speed());
        }
        // Keys sit on whole ticks: the end of the step takes the speed just before the next tick, not a key on it.
        double a = track.keySpeedAt(tick);
        double b = track.keySpeedAt(tick + 0.5);
        double c = track.keySpeedAt(tick + 1 - 1.0E-7);
        if (a == b && b == c) {
            return 1.0 / (20.0 * a);
        }
        // Simpson's rule on 1/speed: a ramp is linear in speed, so its inverse is smooth over one tick.
        return (1.0 / a + 4.0 / b + 1.0 / c) / 6.0 / 20.0;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    /** True when the track changes nothing: video time is replay time. */
    public boolean identity() {
        return identity;
    }

    /** Length of the video of the range, freezes included. */
    public double durationSeconds() {
        if (identity) {
            return (end - start) / 20.0;
        }
        int n = end - start;
        return reach[n] + hold[n];
    }

    /** Replay tick shown at a moment of the video, with its fraction; on a freeze, the frozen tick. */
    public double tickAt(double seconds) {
        if (identity) {
            return Math.max(start, Math.min(end, start + seconds * 20.0));
        }
        int n = end - start;
        if (seconds <= 0.0) {
            return start;
        }
        if (seconds >= reach[n]) {
            return end;
        }
        // Last tick reached at or before this moment.
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (reach[mid] <= seconds) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        double after = reach[lo] + hold[lo];
        if (seconds < after) {
            return start + lo;
        }
        double step = reach[lo + 1] - after;
        return step <= 0.0 ? start + lo + 1 : start + lo + Math.min(1.0, (seconds - after) / step);
    }

    /** Moment of the video where the replay reaches a tick (the start of a freeze on it). */
    public double secondsAt(double tick) {
        if (identity) {
            return (Math.max(start, Math.min(end, tick)) - start) / 20.0;
        }
        double t = Math.max(start, Math.min(end, tick)) - start;
        int i = (int) Math.floor(t);
        double frac = t - i;
        if (i >= end - start) {
            return reach[end - start];
        }
        if (frac <= 0.0) {
            return reach[i];
        }
        double after = reach[i] + hold[i];
        return after + frac * (reach[i + 1] - after);
    }

    /** True when the picture is held by a freeze at this moment of the video. */
    public boolean frozenAt(double seconds) {
        if (identity || seconds < 0.0) {
            return false;
        }
        double tick = tickAt(seconds);
        int i = (int) tick - start;
        return tick == Math.floor(tick) && i >= 0 && i <= end - start && hold[i] > 0.0
                && seconds >= reach[i] && seconds < reach[i] + hold[i];
    }

    /** Speed factor at a moment of the video: 0 on a freeze. */
    public double speedAt(double seconds) {
        if (identity) {
            return 1.0;
        }
        return frozenAt(seconds) ? 0.0 : track.speedAt(tickAt(seconds));
    }

    /** True when game sounds and voices play at this moment of the video (see {@link SpeedTrack#soundsAt}). */
    public boolean soundsAt(double seconds) {
        if (identity) {
            return true;
        }
        boolean frozen = frozenAt(seconds);
        return track.soundsAt(frozen ? 0.0 : track.speedAt(tickAt(seconds)), frozen);
    }

    /** Frames of a video of the range at this rate: the first shows {@code start}, the last is the first at or past {@code end}. */
    public int frames(int fps) {
        return (int) Math.ceil(durationSeconds() * fps - 1.0E-6) + 1;
    }
}
