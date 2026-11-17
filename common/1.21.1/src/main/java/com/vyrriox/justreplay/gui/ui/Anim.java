package com.vyrriox.justreplay.gui.ui;

import net.minecraft.Util;

/**
 * Animation helpers driven by the wall clock, never by game ticks, so motion stays smooth whatever the frame rate
 * or the replay speed.
 *
 * @author vyrriox
 */
public final class Anim {
    private Anim() {
    }

    /** Monotonic time in milliseconds. */
    public static long now() {
        return Util.getMillis();
    }

    public static float clamp01(float t) {
        return t < 0.0F ? 0.0F : t > 1.0F ? 1.0F : t;
    }

    public static float easeOutCubic(float t) {
        float u = 1.0F - clamp01(t);
        return 1.0F - u * u * u;
    }

    public static float easeInOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3) / 2.0F;
    }

    /** Progress 0..1 of an animation started at {@code start} lasting {@code duration} ms. */
    public static float progress(long start, long duration) {
        return clamp01((now() - start) / (float) Math.max(1L, duration));
    }

    /**
     * A value easing towards its target with an exponential approach: frame rate independent, interruptible at
     * any time, no allocation.
     */
    public static final class Value {
        private float value;
        private float target;
        private final float rate;
        private long last;

        public Value(float initial, float rate) {
            this.value = initial;
            this.target = initial;
            this.rate = rate;
            this.last = now();
        }

        /** Sets the target and returns the current, updated value. */
        public float to(float target) {
            this.target = target;
            return get();
        }

        public float get() {
            long t = now();
            float dt = Math.min(0.25F, (t - last) / 1000.0F);
            last = t;
            if (value != target) {
                value += (target - value) * (1.0F - (float) Math.exp(-rate * dt));
                if (Math.abs(target - value) < 0.0005F * Math.max(1.0F, Math.abs(target))) {
                    value = target;
                }
            }
            return value;
        }

        public float target() {
            return target;
        }

        /** Jumps to a value without animation. */
        public void snap(float v) {
            value = v;
            target = v;
            last = now();
        }

        public boolean settled() {
            return value == target;
        }
    }
}
