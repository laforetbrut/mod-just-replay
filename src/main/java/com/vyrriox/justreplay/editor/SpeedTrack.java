package com.vyrriox.justreplay.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Speed of the replay in previews and exported videos, on top of the recording: keyframes of a speed factor (held
 * until the next key, or ramping smoothly to it), timelapses (a stretch of the replay played in a chosen video
 * length) and freezes (the picture stops for a chosen video length). Positions are replay ticks: cameras, texts and
 * effects keep their keyframes in replay ticks and follow the replay's clock. Plain Java, no game code; see
 * {@link TimeMap} for the video time of each tick.
 *
 * @author vyrriox
 */
public final class SpeedTrack {
    public static final float MIN_SPEED = 0.1F;
    public static final float MAX_SPEED = 8.0F;
    /** Longest freeze or timelapse, in seconds of video. */
    public static final float MAX_SECONDS = 600.0F;

    /** From {@code tick} on, the replay runs at {@code speed}; with {@code ramp} it changes smoothly to the next key. */
    public static final class Key {
        public int tick;
        public float speed = 1.0F;
        public boolean ramp;

        public Key() {
        }

        public Key(int tick, float speed, boolean ramp) {
            this.tick = tick;
            this.speed = speed;
            this.ramp = ramp;
        }
    }

    /** The replay from {@code from} to {@code to} (ticks) plays in {@code seconds} of video. */
    public static final class Timelapse {
        public int from;
        public int to;
        public float seconds = 5.0F;

        public Timelapse() {
        }

        public Timelapse(int from, int to, float seconds) {
            this.from = from;
            this.to = to;
            this.seconds = seconds;
        }

        /** Its speed factor: replay seconds per video second. */
        public double speed() {
            return (to - from) / (20.0 * seconds);
        }
    }

    /** The picture stops on {@code tick} for {@code seconds} of video. */
    public static final class Freeze {
        public int tick;
        public float seconds = 2.0F;

        public Freeze() {
        }

        public Freeze(int tick, float seconds) {
            this.tick = tick;
            this.seconds = seconds;
        }
    }

    public List<Key> keys = new ArrayList<>();
    public List<Timelapse> timelapses = new ArrayList<>();
    public List<Freeze> freezes = new ArrayList<>();
    /** Game sounds and voices play between these speeds; outside them and during a freeze they are silent. */
    public float soundMin = 0.5F;
    public float soundMax = 2.0F;

    public boolean isEmpty() {
        return keys.isEmpty() && timelapses.isEmpty() && freezes.isEmpty();
    }

    /** A copy that later edits of this track never change, as a running export needs. */
    public SpeedTrack copy() {
        SpeedTrack copy = new SpeedTrack();
        for (Key key : keys) {
            copy.keys.add(new Key(key.tick, key.speed, key.ramp));
        }
        for (Timelapse lapse : timelapses) {
            copy.timelapses.add(new Timelapse(lapse.from, lapse.to, lapse.seconds));
        }
        for (Freeze freeze : freezes) {
            copy.freezes.add(new Freeze(freeze.tick, freeze.seconds));
        }
        copy.soundMin = soundMin;
        copy.soundMax = soundMax;
        return copy;
    }

    /** Speed factor at a replay tick: a timelapse's own speed inside it, else the keys (1 before the first). */
    public double speedAt(double tick) {
        Timelapse lapse = timelapseAt(tick);
        return lapse != null ? lapse.speed() : keySpeedAt(tick);
    }

    /** Speed of the keys alone at a replay tick. */
    public double keySpeedAt(double tick) {
        Key before = null;
        Key after = null;
        for (Key key : keys) {
            if (key.tick <= tick) {
                before = key;
            } else {
                after = key;
                break;
            }
        }
        if (before == null) {
            return 1.0;
        }
        if (!before.ramp || after == null) {
            return before.speed;
        }
        double f = (tick - before.tick) / (double) (after.tick - before.tick);
        return before.speed + (after.speed - before.speed) * f;
    }

    /** The timelapse holding a tick ({@code from} included, {@code to} excluded), or null. */
    public Timelapse timelapseAt(double tick) {
        for (Timelapse lapse : timelapses) {
            if (tick >= lapse.from && tick < lapse.to) {
                return lapse;
            }
        }
        return null;
    }

    /** Seconds of video the picture stays on this tick, 0 without a freeze. */
    public double freezeAt(int tick) {
        double seconds = 0.0;
        for (Freeze freeze : freezes) {
            if (freeze.tick == tick) {
                seconds += freeze.seconds;
            }
        }
        return seconds;
    }

    /** True when game sounds and voices play at this speed. */
    public boolean soundsAt(double speed, boolean frozen) {
        return !frozen && speed >= soundMin - 1.0E-6 && speed <= soundMax + 1.0E-6;
    }

    /** Puts a key at a tick, replacing the one already there (which keeps its ramp). */
    public void setKey(int tick, float speed) {
        Key existing = keyAt(tick);
        if (existing != null) {
            existing.speed = clampSpeed(speed);
        } else {
            keys.add(new Key(Math.max(0, tick), clampSpeed(speed), false));
            keys.sort(Comparator.comparingInt(k -> k.tick));
        }
    }

    public Key keyAt(int tick) {
        for (Key key : keys) {
            if (key.tick == tick) {
                return key;
            }
        }
        return null;
    }

    public static float clampSpeed(float speed) {
        return Float.isFinite(speed) ? Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed)) : 1.0F;
    }

    public static float clampSeconds(float seconds) {
        return Float.isFinite(seconds) ? Math.max(0.05F, Math.min(MAX_SECONDS, seconds)) : 1.0F;
    }

    /**
     * Repairs a track read from an older or hand edited project: sorted, one key per tick, speeds and lengths in
     * range, timelapses that hold at least one tick and do not overlap (the earlier one wins), freezes with a length.
     */
    public void normalize() {
        if (keys == null) keys = new ArrayList<>();
        if (timelapses == null) timelapses = new ArrayList<>();
        if (freezes == null) freezes = new ArrayList<>();
        keys.removeIf(k -> k == null);
        for (Key key : keys) {
            key.tick = Math.max(0, key.tick);
            key.speed = clampSpeed(key.speed);
        }
        keys.sort(Comparator.comparingInt(k -> k.tick));
        for (int i = keys.size() - 1; i > 0; i--) {
            if (keys.get(i).tick == keys.get(i - 1).tick) {
                keys.remove(i - 1);
            }
        }
        timelapses.removeIf(t -> t == null || t.to <= t.from || t.from < 0 || !Float.isFinite(t.seconds) || t.seconds <= 0.0F);
        for (Timelapse lapse : timelapses) {
            lapse.seconds = clampSeconds(lapse.seconds);
        }
        timelapses.sort(Comparator.comparingInt(t -> t.from));
        List<Timelapse> kept = new ArrayList<>();
        for (Timelapse lapse : timelapses) {
            if (kept.isEmpty() || lapse.from >= kept.get(kept.size() - 1).to) {
                kept.add(lapse);
            }
        }
        timelapses = kept;
        freezes.removeIf(f -> f == null || f.tick < 0 || !Float.isFinite(f.seconds) || f.seconds <= 0.0F);
        for (Freeze freeze : freezes) {
            freeze.seconds = clampSeconds(freeze.seconds);
        }
        freezes.sort(Comparator.comparingInt(f -> f.tick));
        soundMin = clampSpeed(soundMin);
        soundMax = clampSpeed(soundMax);
        if (soundMin > soundMax) {
            float swap = soundMin;
            soundMin = soundMax;
            soundMax = swap;
        }
    }
}
