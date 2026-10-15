package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Segmented control (also used as tabs): one choice among a few, a glass pill slides to the selected segment.
 * Segments may show an icon; when the labels do not fit only the icons are drawn and the label becomes a tooltip.
 *
 * @author vyrriox
 */
public class Segmented<T> extends AbstractWidget implements Tipped {
    private final List<T> values;
    private final List<Component> labels = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();
    private final Icon[] icons;
    private final Consumer<T> onChange;
    private int index;
    private final Anim.Value slide;
    private int hovered = -1;

    public Segmented(List<T> values, T initial, Function<T, Component> labeler, Function<T, Icon> icons, Consumer<T> onChange) {
        super(0, 0, 0, Theme.ROW, Component.empty());
        this.values = values;
        this.onChange = onChange;
        this.icons = new Icon[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Component label = labeler.apply(values.get(i));
            labels.add(label);
            texts.add(label.getString());
            this.icons[i] = icons == null ? null : icons.apply(values.get(i));
        }
        this.index = Math.max(0, values.indexOf(initial));
        this.slide = new Anim.Value(index, Theme.NORMAL);
    }

    /** Starts the selection pill at a previous value, so it slides in after a rebuild. */
    public Segmented<T> slideFrom(T previous) {
        int i = values.indexOf(previous);
        if (i >= 0) {
            slide.snap(i);
        }
        return this;
    }

    public T value() {
        return values.get(index);
    }

    public void select(T value) {
        int i = values.indexOf(value);
        if (i >= 0) {
            index = i;
        }
    }

    private boolean iconsOnly(Font font, float segment) {
        for (int i = 0; i < texts.size(); i++) {
            int need = font.width(texts.get(i)) + (icons[i] != null ? Theme.ICON + 3 : 0) + 6;
            if (need > segment) {
                return icons[i] != null;
            }
        }
        return false;
    }

    @Override
    public Component tooltip() {
        Font font = Minecraft.getInstance().font;
        if (hovered >= 0 && iconsOnly(font, width / (float) values.size())) {
            return labels.get(hovered);
        }
        return null;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int n = values.size();
        float seg = width / (float) n;
        int x = getX(), y = getY();
        hovered = isHovered() ? Math.min(n - 1, (int) ((mouseX - x) / seg)) : -1;
        float alpha = active ? 1.0F : 0.45F;
        Paint.rect(g, x, y, width, height, Theme.RADIUS_M, Theme.fade(Theme.FIELD, alpha));
        Paint.outline(g, x, y, width, height, Theme.RADIUS_M, Theme.fade(0x2EFFFFFF, alpha));
        float pos = slide.to(index);
        float px = x + 1 + pos * seg;
        Paint.rect(g, px, y + 1, seg - 2, height - 2, Theme.RADIUS_M - 1, Theme.fade(0x8C5AB4FF, alpha), Theme.fade(0x662F86E0, alpha));
        Paint.outline(g, px, y + 1, seg - 2, height - 2, Theme.RADIUS_M - 1, Theme.fade(0x998FD0FF, alpha), Theme.fade(0x335AB4FF, alpha));
        boolean iconsOnly = iconsOnly(font, seg);
        for (int i = 0; i < n; i++) {
            float sx = x + i * seg;
            boolean sel = i == index;
            int color = Theme.text(sel ? Theme.TEXT : i == hovered ? Theme.TEXT : Theme.TEXT_DIM, alpha);
            Icon icon = icons[i];
            String text = iconsOnly ? "" : Paint.ellipsize(font, texts.get(i), (int) seg - 6 - (icon != null ? Theme.ICON + 3 : 0));
            int tw = text.isEmpty() ? 0 : font.width(text);
            int content = (icon != null ? Theme.ICON : 0) + (icon != null && tw > 0 ? 3 : 0) + tw;
            float cx = sx + (seg - content) / 2.0F;
            if (icon != null) {
                Paint.icon(g, icon, cx, y + (height - Theme.ICON) / 2.0F, Theme.ICON, color);
                cx += Theme.ICON + 3;
            }
            if (tw > 0) {
                g.drawString(font, text, Math.round(cx), y + (height - 7) / 2, color, false);
            }
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        int n = values.size();
        int i = Math.max(0, Math.min(n - 1, (int) ((mouseX - getX()) / (width / (float) n))));
        if (i != index) {
            index = i;
            onChange.accept(values.get(i));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
