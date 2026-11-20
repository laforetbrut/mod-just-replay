package com.vyrriox.justreplay.editor;

import net.minecraft.util.Mth;

import java.util.List;

/**
 * Camera path built from keyframes. The curve shape (linear, Catmull-Rom through the keyframes, or a smooth
 * Bezier/B-spline that rounds every corner) is independent from the timing: either each keyframe is reached at
 * its own tick, or the path is travelled at a constant speed between the first and last keyframe, optionally
 * with a gentle start and stop. Constant speed means only the first and last keyframe times matter.
 *
 * <p>Channels: x, y, z, yaw, pitch, roll, fov.
 *
 * @author vyrriox
 */
public final class CameraPath {
    public enum Curve { CATMULL_ROM, BEZIER, LINEAR }

    public enum Timing { KEYFRAMES, CONSTANT_SPEED }

    private static final int CHANNELS = 7;
    private static final int SAMPLES_PER_SEGMENT = 48;

    private final double[][] points;
    private final int[] ticks;
    private final Curve curve;
    private final Timing timing;
    private final boolean ease;
    private double[] arcLength;

    public CameraPath(List<ReplayProject.CameraKeyframe> keyframes, Curve curve, Timing timing, boolean ease) {
        int n = keyframes.size();
        this.points = new double[n][];
        this.ticks = new int[n];
        this.curve = curve;
        this.timing = timing;
        this.ease = ease;
        for (int i = 0; i < n; i++) {
            ReplayProject.CameraKeyframe k = keyframes.get(i);
            ticks[i] = k.tick;
            points[i] = new double[]{k.x, k.y, k.z, k.yaw, k.pitch, k.roll, k.fov};
            if (i > 0) {
                // Unwrap angles over the whole path so the camera always turns the short way.
                double[] prev = points[i - 1];
                points[i][3] = prev[3] + Mth.wrapDegrees(points[i][3] - prev[3]);
                points[i][5] = prev[5] + Mth.wrapDegrees(points[i][5] - prev[5]);
            }
        }
    }

    public static CameraPath of(ReplayProject.Camera camera) {
        return new CameraPath(camera.keyframes, parse(Curve.class, camera.curve, Curve.CATMULL_ROM),
                parse(Timing.class, camera.timing, Timing.CONSTANT_SPEED), camera.ease);
    }

    /** Path of the selected camera of a project. */
    public static CameraPath of(ReplayProject project) {
        return of(project.selected());
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return name == null ? fallback : Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public int size() {
        return points.length;
    }

    /** Camera state at a replay time, or null without keyframes. */
    public double[] sample(double time) {
        int n = points.length;
        if (n == 0) {
            return null;
        }
        if (n == 1) {
            return points[0].clone();
        }
        double start = ticks[0];
        double end = ticks[n - 1];
        double u;
        if (time <= start || end <= start) {
            u = 0;
        } else if (time >= end) {
            u = n - 1;
        } else if (timing == Timing.KEYFRAMES) {
            int i = 0;
            while (i < n - 2 && time >= ticks[i + 1]) {
                i++;
            }
            double span = Math.max(1e-6, ticks[i + 1] - ticks[i]);
            u = i + (time - ticks[i]) / span;
        } else {
            double g = (time - start) / (end - start);
            if (ease) {
                g = g * g * (3.0 - 2.0 * g);
            }
            u = uAtDistance(g * totalLength());
        }
        return clamp(evaluate(u));
    }

    /** Point of the curve for a path parameter in [0, n-1]; used to draw the path. */
    public double[] evaluate(double u) {
        int n = points.length;
        u = Math.max(0, Math.min(n - 1, u));
        return switch (curve) {
            case LINEAR -> linear(u);
            case CATMULL_ROM -> catmullRom(u);
            case BEZIER -> bspline(u);
        };
    }

    private double[] linear(double u) {
        int i = Math.min((int) Math.floor(u), points.length - 2);
        double f = u - i;
        double[] a = points[i];
        double[] b = points[i + 1];
        double[] out = new double[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            out[c] = a[c] + (b[c] - a[c]) * f;
        }
        return out;
    }

    private double[] catmullRom(double u) {
        int n = points.length;
        int i = Math.min((int) Math.floor(u), n - 2);
        double t = u - i;
        double[] p0 = points[Math.max(0, i - 1)];
        double[] p1 = points[i];
        double[] p2 = points[i + 1];
        double[] p3 = points[Math.min(n - 1, i + 2)];
        double t2 = t * t;
        double t3 = t2 * t;
        double[] out = new double[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            out[c] = 0.5 * ((2 * p1[c]) + (-p0[c] + p2[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2
                    + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t3);
        }
        return out;
    }

    /**
     * Uniform cubic B-spline (a chain of smooth Bezier segments) over the keyframes, with the first and last
     * keyframe repeated so the path starts and ends exactly on them. Corners are rounded off: the camera passes
     * near the middle keyframes rather than through them.
     */
    private double[] bspline(double u) {
        int n = points.length;
        int segments = n + 1;
        double v = u / (n - 1) * segments;
        int s = Math.min((int) Math.floor(v), segments - 1);
        double t = v - s;
        double[] p0 = control(s);
        double[] p1 = control(s + 1);
        double[] p2 = control(s + 2);
        double[] p3 = control(s + 3);
        double it = 1 - t;
        double b0 = it * it * it / 6.0;
        double b1 = (3 * t * t * t - 6 * t * t + 4) / 6.0;
        double b2 = (-3 * t * t * t + 3 * t * t + 3 * t + 1) / 6.0;
        double b3 = t * t * t / 6.0;
        double[] out = new double[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            out[c] = b0 * p0[c] + b1 * p1[c] + b2 * p2[c] + b3 * p3[c];
        }
        return out;
    }

    /** Control points with both ends tripled: index 0..2 = first keyframe, last three = last keyframe. */
    private double[] control(int index) {
        int i = index - 2;
        return points[Math.max(0, Math.min(points.length - 1, i))];
    }

    private double totalLength() {
        double[] table = arcTable();
        return table[table.length - 1];
    }

    private double[] arcTable() {
        if (arcLength == null) {
            int n = points.length;
            int samples = (n - 1) * SAMPLES_PER_SEGMENT;
            arcLength = new double[samples + 1];
            double[] prev = evaluate(0);
            for (int j = 1; j <= samples; j++) {
                double[] cur = evaluate((double) j / SAMPLES_PER_SEGMENT);
                double dx = cur[0] - prev[0];
                double dy = cur[1] - prev[1];
                double dz = cur[2] - prev[2];
                // Rotation-only moves still need time: count 1 degree as 0.05 block.
                double turn = Math.abs(cur[3] - prev[3]) + Math.abs(cur[4] - prev[4]);
                arcLength[j] = arcLength[j - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz) + turn * 0.05 + 1e-9;
                prev = cur;
            }
        }
        return arcLength;
    }

    private double uAtDistance(double distance) {
        double[] table = arcTable();
        int lo = 0;
        int hi = table.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (table[mid] <= distance) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = table[hi] - table[lo];
        double f = span <= 0 ? 0 : (distance - table[lo]) / span;
        return (lo + f) / SAMPLES_PER_SEGMENT;
    }

    static double[] clamp(double[] state) {
        state[4] = Math.max(-90, Math.min(90, state[4]));
        state[6] = Math.max(1, Math.min(170, state[6]));
        return state;
    }
}
