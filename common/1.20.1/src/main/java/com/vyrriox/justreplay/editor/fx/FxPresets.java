package com.vyrriox.justreplay.editor.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ready-made looks: each preset adds a few filters to the stack, which stay editable afterwards.
 *
 * @author vyrriox
 */
public enum FxPresets {
    CINEMATIC, VINTAGE, NOIR, WARM, COLD;

    public String translationKey() {
        return "justreplay.fx.preset." + name().toLowerCase(Locale.ROOT);
    }

    /** The filters of the preset, bound to a camera id or global for an empty id. */
    public List<FxEffect> build(String camera) {
        List<FxEffect> list = new ArrayList<>();
        switch (this) {
            case CINEMATIC -> {
                list.add(new FxEffect(FxType.CONTRAST, camera).set("contrast", 1.12F));
                list.add(new FxEffect(FxType.SATURATION, camera).set("saturation", 0.9F));
                list.add(new FxEffect(FxType.TEMPERATURE, camera).set("temperature", 0.12F).set("tint", -0.04F));
                list.add(new FxEffect(FxType.VIGNETTE, camera).set("amount", 0.35F).set("softness", 0.6F));
                list.add(new FxEffect(FxType.GRAIN, camera).set("amount", 0.06F).set("size", 1.5F));
                list.add(new FxEffect(FxType.LETTERBOX, camera).set("ratio", 0).set("amount", 1.0F));
            }
            case VINTAGE -> {
                list.add(new FxEffect(FxType.SATURATION, camera).set("saturation", 0.7F));
                list.add(new FxEffect(FxType.SEPIA, camera).set("amount", 0.45F));
                list.add(new FxEffect(FxType.CONTRAST, camera).set("contrast", 0.9F));
                list.add(new FxEffect(FxType.EXPOSURE, camera).set("exposure", 0.0F).set("brightness", 0.03F));
                list.add(new FxEffect(FxType.VIGNETTE, camera).set("amount", 0.5F).set("softness", 0.7F));
                list.add(new FxEffect(FxType.GRAIN, camera).set("amount", 0.22F).set("size", 2.0F));
            }
            case NOIR -> {
                list.add(new FxEffect(FxType.BLACK_WHITE, camera).set("amount", 1.0F));
                list.add(new FxEffect(FxType.CONTRAST, camera).set("contrast", 1.35F));
                list.add(new FxEffect(FxType.VIGNETTE, camera).set("amount", 0.6F).set("softness", 0.5F));
                list.add(new FxEffect(FxType.GRAIN, camera).set("amount", 0.18F).set("size", 1.5F));
            }
            case WARM -> {
                list.add(new FxEffect(FxType.TEMPERATURE, camera).set("temperature", 0.35F).set("tint", 0.05F));
                list.add(new FxEffect(FxType.SATURATION, camera).set("saturation", 1.1F));
            }
            case COLD -> {
                list.add(new FxEffect(FxType.TEMPERATURE, camera).set("temperature", -0.35F).set("tint", 0.0F));
                list.add(new FxEffect(FxType.SATURATION, camera).set("saturation", 0.9F));
                list.add(new FxEffect(FxType.EXPOSURE, camera).set("exposure", -0.1F).set("brightness", 0.0F));
            }
        }
        return list;
    }
}
