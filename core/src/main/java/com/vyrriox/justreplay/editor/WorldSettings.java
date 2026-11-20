package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.editor.fx.FxEffect.Key;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Time and weather of the replayed world, set by the editor on top of the recording. Everything is off by default:
 * the recorded time and weather are shown unchanged. The time of day, the rain strength and the thunder strength
 * each have a fixed value, or keyframes over replay ticks once at least one keyframe exists (same model as the
 * filter parameters).
 *
 * @author vyrriox
 */
public final class WorldSettings {
    public static final int DAY = 24000;
    public static final String TIME_RECORDED = "RECORDED";
    public static final String TIME_FIXED = "FIXED";
    public static final String WEATHER_RECORDED = "RECORDED";
    public static final String WEATHER_CLEAR = "CLEAR";
    public static final String WEATHER_RAIN = "RAIN";
    public static final String WEATHER_THUNDER = "THUNDER";
    public static final List<String> WEATHERS = List.of(WEATHER_RECORDED, WEATHER_CLEAR, WEATHER_RAIN, WEATHER_THUNDER);
    public static final String FOG_NORMAL = "NORMAL";
    public static final String FOG_REDUCED = "REDUCED";
    public static final String FOG_OFF = "OFF";
    public static final List<String> FOGS = List.of(FOG_NORMAL, FOG_REDUCED, FOG_OFF);
    public static final String PARTICLES_ALL = "ALL";
    public static final String PARTICLES_NONE = "NONE";
    public static final String PARTICLES_CHOSEN = "CHOSEN";
    public static final List<String> PARTICLE_MODES = List.of(PARTICLES_ALL, PARTICLES_NONE, PARTICLES_CHOSEN);
    /** Fog start and end used by {@link #FOG_OFF}: past any view distance. */
    public static final float NO_FOG = 1.0E6F;

    /** {@link #TIME_RECORDED} or {@link #TIME_FIXED}. */
    public String timeMode = TIME_RECORDED;
    /** Fixed time of day in ticks, 0 to 23999 (0 is sunrise, 6000 noon, 18000 midnight). */
    public float time = 6000.0F;
    /** Time of day keyframes; from one key to the next the day always moves forward, through midnight if needed. */
    public List<Key> timeKeys = new ArrayList<>();
    /** Moon phase 0 to 7, or -1 for the recorded one. */
    public int moonPhase = -1;
    /** One of {@link #WEATHERS}. */
    public String weather = WEATHER_RECORDED;
    public float rain = 1.0F;
    public List<Key> rainKeys = new ArrayList<>();
    public float thunder = 1.0F;
    public List<Key> thunderKeys = new ArrayList<>();
    /** Full light everywhere, as night vision without its tint. */
    public boolean nightVision;
    /** One of {@link #FOGS}: the distance fog and the fog of biomes such as the Nether's, not the fog under water. */
    public String fog = FOG_NORMAL;
    /** One of {@link #PARTICLE_MODES}. */
    public String particles = PARTICLES_ALL;
    /** Particle type ids hidden in {@link #PARTICLES_CHOSEN} mode, such as {@code minecraft:smoke}. */
    public List<String> hiddenParticles = new ArrayList<>();

    public boolean timeOverridden() {
        return TIME_FIXED.equals(timeMode);
    }

    public boolean weatherOverridden() {
        return !WEATHER_RECORDED.equals(weather);
    }

    /** True when the time or the weather differ from the recording. */
    public boolean active() {
        return timeOverridden() || weatherOverridden() || moonPhase >= 0;
    }

    /** True when the light, the fog or the particles differ from the recording. */
    public boolean visualsActive() {
        return nightVision || !FOG_NORMAL.equals(fog) || !PARTICLES_ALL.equals(particles);
    }

    /** True when particles of this type ({@code namespace:path}) are not shown. */
    public boolean particleHidden(String typeId) {
        return switch (particles == null ? PARTICLES_ALL : particles) {
            case PARTICLES_NONE -> true;
            case PARTICLES_CHOSEN -> typeId != null && hiddenParticles.contains(typeId);
            default -> false;
        };
    }

    /** Hides or shows one particle type; the list stays sorted and without duplicates. */
    public void toggleParticle(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return;
        }
        if (!hiddenParticles.remove(typeId)) {
            hiddenParticles.add(typeId);
        }
        hiddenParticles = cleanIds(hiddenParticles);
    }

    /**
     * Fog distances in blocks for the chosen fog. NORMAL keeps the game's; REDUCED moves the end out to the view
     * distance (fog of biomes and weather is short) and keeps only the far half of the fading band; OFF moves both
     * past any view distance. {@code viewDistance} is the render distance in blocks.
     */
    public static float[] fogDistances(String mode, float start, float end, float viewDistance) {
        if (FOG_OFF.equals(mode)) {
            return new float[]{NO_FOG, NO_FOG};
        }
        if (FOG_REDUCED.equals(mode)) {
            float far = Math.max(end, Math.min(end * 2.0F, viewDistance));
            float near = Math.min(far, start + (far - start) * 0.5F);
            return new float[]{Math.max(start, near), far};
        }
        return new float[]{start, end};
    }

    /** Ids trimmed, without blanks or duplicates, sorted: the form a project keeps. */
    public static List<String> cleanIds(List<String> ids) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    set.add(id.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** Time of day at a replay time, in ticks, not wrapped (can exceed one day when keyframes pass midnight). */
    public double timeAt(double t) {
        if (timeKeys.isEmpty()) {
            return time;
        }
        // Unwrap so each key is at or after the previous one: the day only moves forward.
        int n = timeKeys.size();
        double[] values = new double[n];
        double prev = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double v = timeKeys.get(i).value;
            while (v < prev) {
                v += DAY;
            }
            values[i] = v;
            prev = v;
        }
        return interpolate(timeKeys, values, t);
    }

    public float rainAt(double t) {
        return rainKeys.isEmpty() ? rain : (float) interpolate(rainKeys, null, t);
    }

    public float thunderAt(double t) {
        return thunderKeys.isEmpty() ? thunder : (float) interpolate(thunderKeys, null, t);
    }

    private static double interpolate(List<Key> keys, double[] values, double t) {
        int n = keys.size();
        if (t <= keys.get(0).tick) {
            return v(keys, values, 0);
        }
        if (t >= keys.get(n - 1).tick) {
            return v(keys, values, n - 1);
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).tick <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Key a = keys.get(lo), b = keys.get(hi);
        double f = (t - a.tick) / Math.max(1, b.tick - a.tick);
        if (a.ease) {
            f = f * f * (3.0 - 2.0 * f);
        }
        double va = v(keys, values, lo);
        return va + (v(keys, values, hi) - va) * f;
    }

    private static double v(List<Key> keys, double[] values, int i) {
        return values != null ? values[i] : keys.get(i).value;
    }

    /** Sets a keyframe at a tick, replacing one already there. */
    public static void setKey(List<Key> keys, int tick, float value) {
        for (Key k : keys) {
            if (k.tick == tick) {
                k.value = value;
                return;
            }
        }
        keys.add(new Key(tick, value));
        keys.sort(Comparator.comparingInt(k -> k.tick));
    }

    public static Key keyAt(List<Key> keys, int tick) {
        for (Key k : keys) {
            if (k.tick == tick) {
                return k;
            }
        }
        return null;
    }

    /** Repairs settings read from an older or hand edited project. */
    public void normalize() {
        if (!TIME_FIXED.equals(timeMode)) timeMode = TIME_RECORDED;
        // List.of(...).contains(null) throws: a hand edited null is checked first.
        if (weather == null || !WEATHERS.contains(weather)) weather = WEATHER_RECORDED;
        if (timeKeys == null) timeKeys = new ArrayList<>();
        if (rainKeys == null) rainKeys = new ArrayList<>();
        if (thunderKeys == null) thunderKeys = new ArrayList<>();
        time = wrap(time);
        rain = unit(rain);
        thunder = unit(thunder);
        moonPhase = Math.max(-1, Math.min(7, moonPhase));
        if (fog == null || !FOGS.contains(fog)) fog = FOG_NORMAL;
        if (particles == null || !PARTICLE_MODES.contains(particles)) particles = PARTICLES_ALL;
        hiddenParticles = cleanIds(hiddenParticles);
        fix(timeKeys, true);
        fix(rainKeys, false);
        fix(thunderKeys, false);
    }

    private static void fix(List<Key> keys, boolean time) {
        keys.removeIf(k -> k == null);
        for (Key k : keys) {
            k.tick = Math.max(0, k.tick);
            k.value = time ? wrap(k.value) : unit(k.value);
        }
        keys.sort(Comparator.comparingInt(k -> k.tick));
    }

    public static float wrap(float time) {
        if (!Float.isFinite(time)) {
            return 6000.0F;
        }
        float t = time % DAY;
        return t < 0 ? t + DAY : t;
    }

    private static float unit(float v) {
        return Float.isFinite(v) ? Math.max(0.0F, Math.min(1.0F, v)) : 1.0F;
    }
}
