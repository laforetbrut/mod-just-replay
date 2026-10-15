package com.vyrriox.justreplay.gui.ui;

/**
 * Design tokens of the glass interface: colours (ARGB), radii, spacing and animation speeds. Every screen and
 * widget reads them from here so the look stays consistent.
 *
 * @author vyrriox
 */
public final class Theme {
    // ---------------------------------------------------------------- glass

    /** Smoked base under the glass, keeps text readable over a bright world. */
    public static final int TINT = 0x700B0F16;
    /** Light glass fill, top and bottom of its vertical gradient. */
    public static final int GLASS_TOP = 0x2EFFFFFF;
    public static final int GLASS_BOTTOM = 0x14FFFFFF;
    /** Inner border of glass surfaces. */
    public static final int BORDER = 0x4DFFFFFF;
    public static final int BORDER_SOFT = 0x24FFFFFF;
    /** Drop shadow under floating surfaces. */
    public static final int SHADOW = 0x59000000;
    /** Scrim drawn over a blurred background behind full screens and dialogs. */
    public static final int SCRIM_TOP = 0x66060A10;
    public static final int SCRIM_BOTTOM = 0x99060A10;

    // ---------------------------------------------------------------- controls

    public static final int CONTROL_TOP = 0x1FFFFFFF;
    public static final int CONTROL_BOTTOM = 0x14FFFFFF;
    public static final int CONTROL_HOVER_TOP = 0x40FFFFFF;
    public static final int CONTROL_HOVER_BOTTOM = 0x24FFFFFF;
    public static final int FIELD = 0x590A0D12;
    public static final int TRACK = 0x33FFFFFF;

    // ---------------------------------------------------------------- accents

    public static final int ACCENT = 0xFF5AB4FF;
    public static final int ACCENT_DEEP = 0xFF2F86E0;
    public static final int ACCENT_SOFT = 0x405AB4FF;
    public static final int DANGER = 0xFFFF6B6B;
    public static final int DANGER_SOFT = 0x40FF6B6B;
    public static final int SUCCESS = 0xFF5BE49B;
    public static final int WARNING = 0xFFFFC857;
    public static final int RECORD = 0xFFFF4D4D;

    // ---------------------------------------------------------------- text

    public static final int TEXT = 0xFFF4F7FB;
    public static final int TEXT_DIM = 0xFFB9C2CF;
    public static final int TEXT_MUTED = 0xFF7F8896;
    public static final int TEXT_ON_ACCENT = 0xFF06121F;

    // ---------------------------------------------------------------- metrics (GUI pixels)

    public static final float RADIUS_S = 3.0F;
    public static final float RADIUS_M = 5.0F;
    public static final float RADIUS_L = 7.0F;
    public static final int GAP = 3;
    public static final int PAD = 6;
    /** Height of a control row. */
    public static final int ROW = 14;
    public static final int ICON = 9;

    // ---------------------------------------------------------------- motion (exponential approach rates, 1/s)

    public static final float FAST = 22.0F;
    public static final float NORMAL = 14.0F;
    public static final float SLOW = 9.0F;
    /** Delay before a tooltip shows, in milliseconds. */
    public static final long TOOLTIP_DELAY = 420L;

    private Theme() {
    }

    /** Linear blend of two ARGB colours. */
    public static int mix(int a, int b, float t) {
        if (t <= 0.0F) {
            return a;
        }
        if (t >= 1.0F) {
            return b;
        }
        int aa = a >>> 24, ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8
                | (int) (ab + (bb - ab) * t);
    }

    /** The colour with its alpha multiplied by {@code alpha} (0..1). */
    public static int fade(int color, float alpha) {
        if (alpha >= 1.0F) {
            return color;
        }
        int a = (int) ((color >>> 24) * Math.max(0.0F, alpha));
        return a << 24 | color & 0xFFFFFF;
    }

    /** The RGB of a colour with a new alpha (0..255). */
    public static int withAlpha(int color, int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | color & 0xFFFFFF;
    }

    /**
     * Text colour safe for {@code Font}: a nearly transparent colour is drawn opaque by the font renderer, so the
     * alpha never drops below the visible threshold.
     */
    public static int text(int color, float alpha) {
        int a = (int) ((color >>> 24) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return Math.max(5, a) << 24 | color & 0xFFFFFF;
    }
}
