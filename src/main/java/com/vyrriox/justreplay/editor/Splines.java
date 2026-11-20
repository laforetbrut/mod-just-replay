package com.vyrriox.justreplay.editor;

/**
 * Spline maths shared by the keyframe paths ({@code Interpolation}) and the Blockbench animations ({@code BbModel}).
 *
 * @author vyrriox
 */
public final class Splines {
    private Splines() {
    }

    /** Catmull-Rom between {@code p1} (t = 0) and {@code p2} (t = 1), shaped by their neighbours {@code p0} and {@code p3}. */
    public static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }
}
