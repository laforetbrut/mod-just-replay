package com.vyrriox.justreplay.editor.fx;

import java.util.Locale;

/**
 * Picture transitions on a cut. A transition starts at the cut and lasts its duration: the last frame of the
 * outgoing shot is frozen and the incoming shot is revealed over it.
 *
 * @author vyrriox
 */
public enum TransitionType {
    CUT(0, false),
    DISSOLVE(1, false),
    FADE_BLACK(2, false),
    FADE_WHITE(3, false),
    WIPE(4, true),
    SLIDE(5, true),
    ZOOM_BLUR(6, false),
    IRIS(7, false);

    /** Directions of the wipe and slide transitions: where the motion goes. */
    public static final String[] DIRECTIONS = {"LEFT", "RIGHT", "UP", "DOWN"};
    /** Durations offered in the editor, in ticks. */
    public static final int[] DURATIONS = {5, 10, 15, 20, 30, 40, 60};

    /** Code of the transition in the transition shader. */
    public final int code;
    /** The transition has a direction. */
    public final boolean directional;

    TransitionType(int code, boolean directional) {
        this.code = code;
        this.directional = directional;
    }

    public String translationKey() {
        return "justreplay.fx.transition." + name().toLowerCase(Locale.ROOT);
    }

    public static TransitionType parse(String name) {
        if (name != null) {
            for (TransitionType t : values()) {
                if (t.name().equals(name)) {
                    return t;
                }
            }
        }
        return CUT;
    }

    /** Motion of a direction in texture space (y up). */
    public static float dirX(String direction) {
        return "LEFT".equals(direction) ? -1.0F : "RIGHT".equals(direction) ? 1.0F : 0.0F;
    }

    public static float dirY(String direction) {
        return "UP".equals(direction) ? 1.0F : "DOWN".equals(direction) ? -1.0F : 0.0F;
    }
}
