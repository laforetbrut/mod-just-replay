package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Glass button: optional icon and label, hover lift and brightening, press feedback, disabled and toggled states.
 *
 * @author vyrriox
 */
public class GlassButton extends Button implements Tipped {
    public enum Style {
        /** Regular glass control. */
        NORMAL,
        /** Accent filled, for the main action of a surface. */
        PRIMARY,
        /** No frame until hovered: toolbars. */
        GHOST,
        /** Turns red when hovered: destructive actions. */
        DANGER,
        /** Left aligned list entry. */
        ROW
    }

    protected Icon icon;
    protected Supplier<Icon> dynamicIcon;
    protected Style style = Style.NORMAL;
    protected BooleanSupplier selected;
    protected Component tip;
    protected int iconColor;
    private final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
    private final Anim.Value on = new Anim.Value(0.0F, Theme.NORMAL);
    private long pressedAt;
    /** Label cache: the trimmed text is only rebuilt when the message or the width changes. */
    private Component cachedMessage;
    private int cachedWidth = -1;
    private String cachedLabel = "";

    public GlassButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    public GlassButton(Component label, OnPress onPress) {
        this(0, 0, 0, Theme.ROW, label, onPress);
    }

    public GlassButton icon(Icon icon) {
        this.icon = icon;
        return this;
    }

    /** Icon re-read every frame (play / pause). */
    public GlassButton icon(Supplier<Icon> icon) {
        this.dynamicIcon = icon;
        return this;
    }

    public GlassButton iconColor(int color) {
        this.iconColor = color;
        return this;
    }

    public GlassButton style(Style style) {
        this.style = style;
        return this;
    }

    /** Toggle state: the button shows as active while the supplier is true. */
    public GlassButton selected(BooleanSupplier selected) {
        this.selected = selected;
        return this;
    }

    public GlassButton tip(Component tip) {
        this.tip = tip;
        return this;
    }

    public GlassButton enabled(boolean enabled) {
        this.active = enabled;
        return this;
    }

    @Override
    public Component tooltip() {
        return tip;
    }

    @Override
    public void onPress() {
        pressedAt = Anim.now();
        super.onPress();
    }

    protected boolean keyboardFocused() {
        return isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
    }

    protected boolean isOn() {
        return selected != null && selected.getAsBoolean();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float h = hover.to(active && (isHovered() || keyboardFocused()) ? 1.0F : 0.0F);
        float o = on.to(isOn() ? 1.0F : 0.0F);
        float press = 1.0F - Anim.easeOutCubic(Anim.progress(pressedAt, 180));
        float alpha = active ? 1.0F : 0.45F;
        float lift = style == Style.ROW ? 0.0F : h * 0.5F - press * 0.5F;
        g.pose().pushPose();
        g.pose().translate(0, -lift, 0);
        drawFrame(g, getX(), getY(), width, height, h, o, press, alpha);
        drawContent(g, Minecraft.getInstance().font, getX(), getY(), width, height, h, o, alpha);
        g.pose().popPose();
    }

    protected float radius() {
        return Math.min(Theme.RADIUS_M, height / 2.0F);
    }

    protected void drawFrame(GuiGraphics g, int x, int y, int w, int hh, float h, float o, float press, float alpha) {
        float r = radius();
        int top, bottom, edgeTop, edgeBottom;
        switch (style) {
            case PRIMARY -> {
                top = Theme.mix(0xE05AB4FF, 0xFF86CBFF, h);
                bottom = Theme.mix(0xE02F86E0, 0xFF4C9EF0, h);
                edgeTop = 0x8CFFFFFF;
                edgeBottom = 0x33FFFFFF;
            }
            case GHOST -> {
                top = Theme.fade(0x2EFFFFFF, h);
                bottom = Theme.fade(0x1AFFFFFF, h);
                edgeTop = Theme.fade(0x40FFFFFF, h);
                edgeBottom = Theme.fade(0x14FFFFFF, h);
            }
            case DANGER -> {
                top = Theme.mix(Theme.CONTROL_TOP, 0x66FF6B6B, h);
                bottom = Theme.mix(Theme.CONTROL_BOTTOM, 0x40FF6B6B, h);
                edgeTop = Theme.mix(0x40FFFFFF, 0x99FF8A8A, h);
                edgeBottom = Theme.mix(0x29FFFFFF, 0x40FF6B6B, h);
            }
            case ROW -> {
                top = Theme.fade(0x1FFFFFFF, h);
                bottom = Theme.fade(0x14FFFFFF, h);
                edgeTop = 0;
                edgeBottom = 0;
            }
            default -> {
                top = Theme.mix(Theme.CONTROL_TOP, Theme.CONTROL_HOVER_TOP, h);
                bottom = Theme.mix(Theme.CONTROL_BOTTOM, Theme.CONTROL_HOVER_BOTTOM, h);
                edgeTop = Theme.mix(0x40FFFFFF, 0x73FFFFFF, h);
                // Strong enough for the bottom edge to read: a fainter one looked like a frame open at the bottom.
                edgeBottom = Theme.mix(0x29FFFFFF, 0x38FFFFFF, h);
            }
        }
        if (o > 0.0F && style != Style.PRIMARY) {
            top = Theme.mix(top, 0x735AB4FF, o);
            bottom = Theme.mix(bottom, 0x4D2F86E0, o);
            if (style != Style.ROW) {
                edgeTop = Theme.mix(edgeTop, 0xCC8FD0FF, o);
                edgeBottom = Theme.mix(edgeBottom, 0x665AB4FF, o);
            }
        }
        if (press > 0.0F) {
            top = Theme.mix(top, 0x59FFFFFF, press * 0.5F);
        }
        Paint.rect(g, x, y, w, hh, r, Theme.fade(top, alpha), Theme.fade(bottom, alpha));
        if (edgeTop != 0) {
            Paint.outline(g, x, y, w, hh, r, Theme.fade(edgeTop, alpha), Theme.fade(edgeBottom, alpha));
        }
        if (style == Style.ROW && o > 0.0F) {
            Paint.rect(g, x + 1, y + 3, 2, hh - 6, 1, Theme.fade(Theme.ACCENT, o * alpha));
        }
        if (keyboardFocused()) {
            Paint.outline(g, x - 1, y - 1, w + 2, hh + 2, r + 1, Theme.fade(Theme.ACCENT, alpha));
        }
    }

    protected int textColor(float h, float o, float alpha) {
        int base;
        if (!active) {
            base = Theme.TEXT_MUTED;
        } else if (style == Style.PRIMARY) {
            base = Theme.TEXT_ON_ACCENT;
        } else if (style == Style.DANGER) {
            base = Theme.mix(Theme.TEXT, 0xFFFFB0B0, h);
        } else if (style == Style.ROW && o <= 0.0F) {
            base = Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h);
        } else {
            base = Theme.TEXT;
        }
        return Theme.text(base, alpha < 1.0F ? 0.85F : 1.0F);
    }

    protected Icon currentIcon() {
        return dynamicIcon != null ? dynamicIcon.get() : icon;
    }

    protected void drawContent(GuiGraphics g, Font font, int x, int y, int w, int hh, float h, float o, float alpha) {
        int color = textColor(h, o, alpha);
        Icon ic = currentIcon();
        float iconSize = Math.min(Theme.ICON, hh - 4);
        boolean row = style == Style.ROW;
        int pad = row ? 5 : 4;
        int avail = w - 2 * pad - (ic != null ? (int) iconSize + 3 : 0);
        String label = label(font, Math.max(0, avail));
        int labelW = label.isEmpty() ? 0 : font.width(label);
        int ty = Paint.textY(y, hh);
        if (labelW == 0) {
            // Icon only: centred in the whole button.
            if (ic != null) {
                Paint.iconCentered(g, ic, x, y, w, hh, iconSize, shownIconColor(color, alpha));
            }
            return;
        }
        // The last column of a glyph is its spacing: leave it out so the content block is truly centred.
        int content = (ic != null ? (int) iconSize + 3 : 0) + labelW - 1;
        int cx = row ? x + pad : x + Math.round((w - content) / 2.0F);
        if (ic != null) {
            Paint.iconOnText(g, ic, cx, ty, iconSize, shownIconColor(color, alpha));
            cx += (int) iconSize + 3;
        }
        g.drawString(font, label, cx, ty, color, false);
    }

    private int shownIconColor(int textColor, float alpha) {
        return iconColor != 0 && active ? Theme.fade(iconColor, alpha < 1.0F ? 0.6F : 1.0F) : textColor;
    }

    /** Message trimmed to the available width, cached. */
    protected String label(Font font, int maxWidth) {
        Component message = getMessage();
        if (message != cachedMessage || maxWidth != cachedWidth) {
            cachedMessage = message;
            cachedWidth = maxWidth;
            cachedLabel = Paint.ellipsize(font, message.getString(), maxWidth);
        }
        return cachedLabel;
    }
}
