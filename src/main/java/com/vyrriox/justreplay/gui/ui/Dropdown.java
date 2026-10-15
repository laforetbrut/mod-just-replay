package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Selector showing a caption and the current value; a click opens a {@link ListPopup} with every choice.
 *
 * @author vyrriox
 */
public class Dropdown<T> extends GlassButton {
    private final GlassScreen screen;
    private final List<T> values;
    private final Function<T, Component> labeler;
    private final Consumer<T> onChange;
    private T value;
    private String valueLabel = "";
    private int valueWidth;

    public Dropdown(GlassScreen screen, Component caption, List<T> values, T initial, Function<T, Component> labeler,
                    Consumer<T> onChange) {
        super(caption, b -> {
        });
        this.screen = screen;
        this.values = values;
        this.labeler = labeler;
        this.onChange = onChange;
        setValue(initial);
    }

    public T value() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
        this.valueLabel = value == null ? "-" : labeler.apply(value).getString();
        this.valueWidth = Minecraft.getInstance().font.width(valueLabel);
    }

    @Override
    public void onPress() {
        super.onPress();
        Font font = Minecraft.getInstance().font;
        List<String> labels = new ArrayList<>(values.size());
        for (T v : values) {
            labels.add(labeler.apply(v).getString());
        }
        screen.openPopup(new ListPopup(getX(), getY(), width, height, screen.width, screen.height, font, labels,
                values.indexOf(value), i -> {
            screen.closePopup();
            T picked = values.get(i);
            setValue(picked);
            onChange.accept(picked);
        }));
    }

    @Override
    protected void drawContent(GuiGraphics g, Font font, int x, int y, int w, int hh, float h, float o, float alpha) {
        int textY = y + (hh - 7) / 2;
        float iconSize = Math.min(8, hh - 4);
        int right = x + w - 4 - (int) iconSize;
        Paint.icon(g, Icon.CHEVRON_DOWN, right, y + (hh - iconSize) / 2.0F, iconSize, Theme.text(Theme.TEXT_DIM, alpha));
        int left = x + 5;
        Icon ic = currentIcon();
        if (ic != null) {
            float s = Math.min(Theme.ICON, hh - 4);
            Paint.icon(g, ic, left, y + (hh - s) / 2.0F, s, Theme.text(Theme.TEXT_DIM, alpha));
            left += (int) s + 3;
        }
        String caption = label(font, Math.max(0, (right - left) / 2));
        if (!caption.isEmpty()) {
            g.drawString(font, caption, left, textY, Theme.text(Theme.TEXT_DIM, alpha), false);
            left += font.width(caption) + 4;
        }
        int space = right - 3 - left;
        String shown = valueWidth <= space ? valueLabel : Paint.ellipsize(font, valueLabel, space);
        int vw = shown == valueLabel ? valueWidth : font.width(shown);
        int vx = caption.isEmpty() ? left : right - 3 - vw;
        g.drawString(font, shown, vx, textY, textColor(h, o, alpha), false);
    }
}
