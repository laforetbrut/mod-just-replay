package com.vyrriox.justreplay.editor.fx;

/**
 * Definition of one parameter of an effect: range, default and step of the slider. A parameter with
 * {@link #choices} holds the index of a choice and cannot be keyframed.
 *
 * @author vyrriox
 */
public record FxParam(String name, float min, float max, float def, float step, String[] choices) {
    public static FxParam of(String name, float min, float max, float def, float step) {
        return new FxParam(name, min, max, def, step, null);
    }

    public static FxParam choice(String name, int def, String... choices) {
        return new FxParam(name, 0, choices.length - 1, def, 1, choices);
    }

    public boolean isChoice() {
        return choices != null;
    }

    public boolean keyframeable() {
        return choices == null;
    }

    public float clamp(float value) {
        if (!Float.isFinite(value)) {
            return def;
        }
        float v = Math.max(min, Math.min(max, value));
        return isChoice() ? Math.round(v) : v;
    }

    /** Translation key of the parameter label. */
    public String translationKey() {
        return "justreplay.fx.param." + name;
    }
}
