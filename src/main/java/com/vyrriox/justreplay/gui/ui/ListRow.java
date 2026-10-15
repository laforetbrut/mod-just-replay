package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Selectable list entry: a colour dot or an icon, a label, a dim detail on the right and an optional action icon
 * (visibility, delete) with its own click zone.
 *
 * @author vyrriox
 */
public class ListRow extends GlassButton {
    private int dot;
    private String detail = "";
    private Supplier<Icon> action;
    private Runnable onAction;
    private Component actionTip;
    private final Anim.Value actionHover = new Anim.Value(0.0F, Theme.FAST);
    private boolean overAction;

    public ListRow(Component label, OnPress onPress) {
        super(label, onPress);
        style(Style.ROW);
    }

    @Override
    public ListRow icon(Icon icon) {
        super.icon(icon);
        return this;
    }

    @Override
    public ListRow selected(java.util.function.BooleanSupplier selected) {
        super.selected(selected);
        return this;
    }

    @Override
    public ListRow tip(Component tip) {
        super.tip(tip);
        return this;
    }

    /** Colour dot before the label (camera colour). */
    public ListRow dot(int color) {
        this.dot = color;
        return this;
    }

    public ListRow detail(String detail) {
        this.detail = detail == null ? "" : detail;
        return this;
    }

    public ListRow action(Supplier<Icon> icon, Component tip, Runnable onAction) {
        this.action = icon;
        this.actionTip = tip;
        this.onAction = onAction;
        return this;
    }

    @Override
    public Component tooltip() {
        return overAction ? actionTip : super.tooltip();
    }

    private int actionX() {
        return getX() + width - 14;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (action != null && mouseX >= actionX()) {
            onAction.run();
            return;
        }
        super.onClick(mouseX, mouseY);
    }

    @Override
    protected void drawContent(GuiGraphics g, Font font, int x, int y, int w, int hh, float h, float o, float alpha) {
        int color = textColor(h, o, alpha);
        int left = x + 6;
        if (dot != 0) {
            Paint.circle(g, left + 2.5F, y + hh / 2.0F, 2.5F, Theme.fade(dot, alpha));
            left += 9;
        }
        Icon ic = currentIcon();
        if (ic != null) {
            Paint.icon(g, ic, left, y + (hh - 8) / 2.0F, 8, Theme.text(o > 0 ? Theme.ACCENT : Theme.TEXT_DIM, alpha));
            left += 11;
        }
        int right = x + w - 5;
        overAction = false;
        if (action != null) {
            int ax = actionX();
            overAction = isHovered() && lastMouseX >= ax;
            float ah = actionHover.to(overAction ? 1.0F : 0.0F);
            Paint.rect(g, ax, y + 1, 13, hh - 2, Theme.RADIUS_S, Theme.fade(0x33FFFFFF, ah));
            Paint.iconCentered(g, action.get(), ax, y, 13, hh, 8, Theme.text(Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, Math.max(ah, h * 0.6F)), alpha));
            right = ax - 3;
        }
        int dw = detail.isEmpty() ? 0 : font.width(detail);
        if (dw > 0) {
            g.drawString(font, detail, right - dw, y + (hh - 7) / 2, Theme.text(Theme.TEXT_MUTED, alpha), false);
            right -= dw + 5;
        }
        String label = label(font, Math.max(0, right - left));
        g.drawString(font, label, left, y + (hh - 7) / 2, color, false);
    }

    private int lastMouseX;

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }
}
