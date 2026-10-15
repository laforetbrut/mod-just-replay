package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

/**
 * Glass notification in the toast column. A new message replaces the one on screen instead of queueing behind it.
 *
 * @author vyrriox
 */
public final class GlassToast implements Toast {
    private static final Object TOKEN = new Object();
    private static final long DISPLAY = 3500L;

    private Icon icon;
    private int accent;
    private Component title;
    private Component detail;
    private long shownAt = -1;
    private boolean changed;

    private GlassToast(Icon icon, int accent, Component title, Component detail) {
        this.icon = icon;
        this.accent = accent;
        this.title = title;
        this.detail = detail;
    }

    /** Shows a notification; {@code detail} may be null. */
    public static void show(Icon icon, int accent, Component title, Component detail) {
        ToastComponent toasts = Minecraft.getInstance().getToasts();
        GlassToast existing = toasts.getToast(GlassToast.class, TOKEN);
        if (existing != null) {
            existing.icon = icon;
            existing.accent = accent;
            existing.title = title;
            existing.detail = detail;
            existing.changed = true;
        } else {
            toasts.addToast(new GlassToast(icon, accent, title, detail));
        }
    }

    @Override
    public Object getToken() {
        return TOKEN;
    }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        int text = font.width(title);
        if (detail != null) {
            text = Math.max(text, font.width(detail));
        }
        return Math.max(120, Math.min(240, text + 36));
    }

    @Override
    public int height() {
        return detail == null ? 26 : 32;
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent component, long timeSinceLastVisible) {
        if (changed || shownAt < 0) {
            shownAt = timeSinceLastVisible;
            changed = false;
        }
        Font font = component.getMinecraft().font;
        int w = width(), h = height();
        Paint.shadow(g, 2, 2, w - 4, h - 4, Theme.RADIUS_L, 5, 0x66000000);
        Paint.rect(g, 2, 2, w - 4, h - 4, Theme.RADIUS_L, 0xE61B212B, 0xE6131820);
        Paint.outline(g, 2, 2, w - 4, h - 4, Theme.RADIUS_L, 0x66FFFFFF, 0x1FFFFFFF);
        Paint.circle(g, 15, h / 2.0F, 7, Theme.withAlpha(accent, 0x40));
        Paint.icon(g, icon, 10.5F, h / 2.0F - 4.5F, 9, accent);
        if (detail == null) {
            g.drawString(font, title, 27, (h - 7) / 2, Theme.TEXT, false);
        } else {
            g.drawString(font, title, 27, 7, Theme.TEXT, false);
            g.drawString(font, font.plainSubstrByWidth(detail.getString(), w - 34), 27, 18, Theme.TEXT_DIM, false);
        }
        long elapsed = timeSinceLastVisible - shownAt;
        float progress = Anim.clamp01(elapsed / (float) (DISPLAY * component.getNotificationDisplayTimeMultiplier()));
        Paint.rect(g, 8, h - 4, (w - 16) * (1.0F - progress), 1, 0.5F, Theme.withAlpha(accent, 0x99));
        return elapsed >= DISPLAY * component.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
