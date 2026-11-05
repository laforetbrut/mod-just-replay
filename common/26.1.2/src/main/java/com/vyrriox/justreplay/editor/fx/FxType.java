package com.vyrriox.justreplay.editor.fx;

import java.util.Locale;

/**
 * The filters of the effect stack. Colour filters run in the order of the stack inside one shader pass; blur,
 * sharpen and chromatic aberration sample neighbouring pixels so they run first whatever their place, and the
 * letterbox always runs last.
 *
 * @author vyrriox
 */
public enum FxType {
    EXPOSURE(1, FxParam.of("exposure", -3.0F, 3.0F, 0.0F, 0.05F), FxParam.of("brightness", -0.5F, 0.5F, 0.0F, 0.01F)),
    CONTRAST(2, FxParam.of("contrast", 0.0F, 2.0F, 1.2F, 0.01F)),
    SATURATION(3, FxParam.of("saturation", 0.0F, 2.0F, 1.2F, 0.01F)),
    TEMPERATURE(4, FxParam.of("temperature", -1.0F, 1.0F, 0.3F, 0.01F), FxParam.of("tint", -1.0F, 1.0F, 0.0F, 0.01F)),
    HUE(5, FxParam.of("hue", -180.0F, 180.0F, 30.0F, 1.0F)),
    VIGNETTE(6, FxParam.of("amount", 0.0F, 1.0F, 0.4F, 0.01F), FxParam.of("softness", 0.05F, 1.0F, 0.5F, 0.01F)),
    GRAIN(7, FxParam.of("amount", 0.0F, 1.0F, 0.15F, 0.01F), FxParam.of("size", 1.0F, 4.0F, 1.5F, 0.1F)),
    BLACK_WHITE(8, FxParam.of("amount", 0.0F, 1.0F, 1.0F, 0.01F)),
    SEPIA(9, FxParam.of("amount", 0.0F, 1.0F, 1.0F, 0.01F)),
    LUT(10, FxParam.of("amount", 0.0F, 1.0F, 1.0F, 0.01F)),
    SHARPEN(0, FxParam.of("amount", 0.0F, 2.0F, 0.5F, 0.01F)),
    BLUR(0, FxParam.of("radius", 0.0F, 24.0F, 4.0F, 0.5F)),
    CHROMATIC(0, FxParam.of("amount", 0.0F, 12.0F, 2.0F, 0.1F)),
    LETTERBOX(0, FxParam.choice("ratio", 0, "2.39:1", "1.85:1", "2:1", "4:3"), FxParam.of("amount", 0.0F, 1.0F, 1.0F, 0.01F));

    /** Width over height of the letterbox choices. */
    public static final float[] LETTERBOX_RATIOS = {2.39F, 1.85F, 2.0F, 4.0F / 3.0F};

    /** Code of the operation in the colour loop of the grade shader, 0 for the filters handled outside it. */
    public final int op;
    public final FxParam[] params;

    FxType(int op, FxParam... params) {
        this.op = op;
        this.params = params;
    }

    public FxParam param(String name) {
        for (FxParam p : params) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public String translationKey() {
        return "justreplay.fx.type." + name().toLowerCase(Locale.ROOT);
    }

    public static FxType parse(String name) {
        if (name != null) {
            for (FxType t : values()) {
                if (t.name().equals(name)) {
                    return t;
                }
            }
        }
        return null;
    }
}
