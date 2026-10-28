package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Typed numbers with small minus and plus steps, laid out three to a row: used for the precise position, angles
 * and scale of actors and recorded entities. The values live here, so they survive a rebuild of the widgets.
 *
 * @author vyrriox
 */
final class TransformFields {
    private static final int STEP_W = 12;

    private final Font font;
    private final String[] labelKeys;
    private final double[] steps;
    private final double[] min;
    private final double[] max;
    final double[] values;
    private final GlassTextField[] fields;
    /** Called after a minus or plus step, null to only update the field. */
    private final Runnable onStep;
    private boolean dirty;

    TransformFields(Font font, String[] labelKeys, double[] steps, double[] min, double[] max, Runnable onStep) {
        this.font = font;
        this.labelKeys = labelKeys;
        this.steps = steps;
        this.min = min;
        this.max = max;
        this.values = new double[labelKeys.length];
        this.fields = new GlassTextField[labelKeys.length];
        this.onStep = onStep;
    }

    void set(double[] next) {
        System.arraycopy(next, 0, values, 0, Math.min(next.length, values.length));
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null) {
                fields[i].setValue(format(values[i]));
            }
        }
        dirty = false;
    }

    boolean dirty() {
        return dirty;
    }

    static String format(double value) {
        String s = String.format(Locale.ROOT, "%.3f", value);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return "-0".equals(s) ? "0" : s;
    }

    /** Reads the typed values; a field that is not a number keeps its last value. */
    void read() {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null) {
                continue;
            }
            try {
                double v = Double.parseDouble(fields[i].getValue().trim().replace(',', '.'));
                if (Double.isFinite(v)) {
                    values[i] = clamp(i, v);
                }
            } catch (NumberFormatException ignored) {
                // Keeps the previous value.
            }
        }
    }

    private double clamp(int i, double v) {
        return Math.max(min[i], Math.min(max[i], v));
    }

    void build(Form f) {
        for (int start = 0; start < labelKeys.length; start += 3) {
            int end = Math.min(labelKeys.length, start + 3);
            for (int i = start; i < end; i++) {
                f.caption(Component.translatable(labelKeys[i]), i - start, 3);
            }
            f.next(9);
            for (int i = start; i < end; i++) {
                int index = i;
                int x = f.cellX(i - start, 3);
                int w = f.cellWidth(1, 3);
                GlassTextField box = new GlassTextField(font, 0, Component.translatable(labelKeys[i]));
                box.setMaxLength(16);
                box.setValue(format(values[i]));
                box.setResponder(s -> dirty = true);
                box.tip(Component.translatable(labelKeys[i]));
                fields[i] = f.at(box, x, w - 2 * STEP_W - 2);
                f.at(new GlassButton(Component.literal("-"), b -> step(index, -1)), x + w - 2 * STEP_W - 1, STEP_W).setHeight(16);
                f.at(new GlassButton(Component.literal("+"), b -> step(index, 1)), x + w - STEP_W, STEP_W).setHeight(16);
            }
            f.next(16);
        }
    }

    private void step(int index, int sign) {
        read();
        double v = Math.round((values[index] + sign * steps[index]) / steps[index]) * steps[index];
        values[index] = clamp(index, v);
        fields[index].setValue(format(values[index]));
        dirty = true;
        if (onStep != null) {
            onStep.run();
        }
    }
}
