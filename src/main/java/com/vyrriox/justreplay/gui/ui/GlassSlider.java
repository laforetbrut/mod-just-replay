package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Glass slider over a numeric range with a step: caption on top, track with an accent fill and a knob below.
 * Mouse dragging and keyboard handling come from the vanilla slider.
 *
 * @author vyrriox
 */
public class GlassSlider extends AbstractSliderButton {
    private final double min;
    private final double max;
    private final double step;
    private final DoubleFunction<Component> format;
    private final DoubleConsumer onChange;
    private final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
    private final Anim.Value shown;

    public GlassSlider(int width, double min, double max, double step, double initial, DoubleFunction<Component> format,
                       DoubleConsumer onChange) {
        super(0, 0, width, 22, Component.empty(), (initial - min) / (max - min));
        this.min = min;
        this.max = max;
        this.step = step;
        this.format = format;
        this.onChange = onChange;
        this.shown = new Anim.Value((float) value, Theme.FAST);
        updateMessage();
    }

    public double actual() {
        double v = min + value * (max - min);
        return Math.max(min, Math.min(max, Math.round(v / step) * step));
    }

    @Override
    protected void updateMessage() {
        setMessage(format.apply(actual()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(actual());
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        float h = hover.to(isHovered() || isFocused() ? 1.0F : 0.0F);
        float t = shown.to((float) ((actual() - min) / (max - min)));
        int x = getX(), y = getY();
        g.drawString(font, getMessage(), x + 2, y + 1, Theme.TEXT, false);
        float tx = x + 4, tw = width - 8, ty = y + height - 7, th = 4;
        Paint.rect(g, tx, ty, tw, th, 2, Theme.TRACK);
        Paint.rect(g, tx, ty, Math.max(th, tw * t), th, 2, 0xFF7CC4FF, Theme.ACCENT_DEEP);
        float kr = 4.0F + h;
        float kx = tx + tw * t;
        Paint.shadow(g, kx - kr, ty + th / 2 - kr, 2 * kr, 2 * kr, kr, 2, 0x66000000);
        Paint.circle(g, kx, ty + th / 2, kr, 0xFFFFFFFF);
        Paint.circle(g, kx, ty + th / 2, kr - 2.5F, Theme.ACCENT);
    }
}
