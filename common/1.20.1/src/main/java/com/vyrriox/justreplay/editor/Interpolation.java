package com.vyrriox.justreplay.editor;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Catmull-Rom interpolation over keyframes placed on the tick axis. Angles are unwrapped so a path never
 * spins the long way round.
 *
 * @author vyrriox
 */
public final class Interpolation {
    private Interpolation() {
    }

    /** Values of one keyframe, in a fixed order. Angle channels are flagged by the caller. */
    public interface Channels<T> {
        double[] values(T keyframe);
    }

    /**
     * Samples a path at {@code time}. Returns {@code null} when there is no keyframe; holds the first or last
     * keyframe outside the covered range.
     */
    public static <T> double[] sample(List<T> keyframes, ToIntFunction<T> tickOf, Channels<T> channels, boolean[] angle, double time) {
        int n = keyframes.size();
        if (n == 0) {
            return null;
        }
        if (n == 1 || time <= tickOf.applyAsInt(keyframes.get(0))) {
            return channels.values(keyframes.get(0)).clone();
        }
        if (time >= tickOf.applyAsInt(keyframes.get(n - 1))) {
            return channels.values(keyframes.get(n - 1)).clone();
        }
        int i = 0;
        while (i < n - 2 && time >= tickOf.applyAsInt(keyframes.get(i + 1))) {
            i++;
        }
        T k1 = keyframes.get(i);
        T k2 = keyframes.get(i + 1);
        T k0 = keyframes.get(Math.max(0, i - 1));
        T k3 = keyframes.get(Math.min(n - 1, i + 2));
        double t1 = tickOf.applyAsInt(k1);
        double t2 = tickOf.applyAsInt(k2);
        double t = t2 > t1 ? (time - t1) / (t2 - t1) : 0.0;

        double[] v0 = channels.values(k0);
        double[] v1 = channels.values(k1);
        double[] v2 = channels.values(k2);
        double[] v3 = channels.values(k3);
        double[] out = new double[v1.length];
        for (int c = 0; c < out.length; c++) {
            double a = v0[c], b = v1[c], d = v2[c], e = v3[c];
            if (angle[c]) {
                a = unwrap(b, a);
                d = unwrap(b, d);
                e = unwrap(d, e);
            }
            out[c] = catmullRom(a, b, d, e, t);
        }
        return out;
    }

    private static double unwrap(double reference, double value) {
        double delta = (value - reference) % 360.0;
        if (delta > 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return reference + delta;
    }

    public static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    public static final boolean[] CAMERA_ANGLES = {false, false, false, true, false, true, false};
    public static final boolean[] ACTOR_ANGLES = {false, false, false, true, false};

    /** x, y, z, yaw, pitch, roll, fov. */
    public static double[] camera(List<ReplayProject.CameraKeyframe> keyframes, double time) {
        return sample(keyframes, k -> k.tick, k -> new double[]{k.x, k.y, k.z, k.yaw, k.pitch, k.roll, k.fov}, CAMERA_ANGLES, time);
    }

    /** x, y, z, yaw, pitch. */
    public static double[] actor(List<ReplayProject.ActorKeyframe> keyframes, double time) {
        return sample(keyframes, k -> k.tick, k -> new double[]{k.x, k.y, k.z, k.yaw, k.pitch}, ACTOR_ANGLES, time);
    }
}
