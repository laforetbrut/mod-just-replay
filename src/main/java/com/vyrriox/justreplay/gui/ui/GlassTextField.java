package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Text field on a recessed glass well with an accent ring when focused. The vanilla editing logic is kept: only
 * the frame changes (the text keeps the bordered insets).
 *
 * @author vyrriox
 */
public class GlassTextField extends EditBox implements Tipped {
    private final Anim.Value focus = new Anim.Value(0.0F, Theme.FAST);
    private final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
    private Icon leading;
    private int leadingWidth;
    private Component tip;

    public GlassTextField(Font font, int width, Component label) {
        super(font, 0, 0, width, Theme.ROW + 2, label);
        setTextColor(Theme.TEXT);
        setTextColorUneditable(Theme.TEXT_MUTED);
        setTextShadow(false);
    }

    /** Draws an icon in a zone of {@code width} pixels on the left of the field (outside its click area). */
    public GlassTextField leading(Icon icon, int width) {
        this.leading = icon;
        this.leadingWidth = width;
        return this;
    }

    public GlassTextField tip(Component tip) {
        this.tip = tip;
        return this;
    }

    @Override
    public Component tooltip() {
        return tip;
    }

    /** The vanilla sprite frame is replaced by the glass one. */
    @Override
    public boolean isBordered() {
        return false;
    }

    @Override
    public int getInnerWidth() {
        return width - 8;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        float f = focus.to(isFocused() ? 1.0F : 0.0F);
        float h = hover.to(isHovered() ? 1.0F : 0.0F);
        int x = getX() - leadingWidth, y = getY(), w = width + leadingWidth;
        if (f > 0.01F) {
            Paint.shadow(g, x, y, w, height, Theme.RADIUS_M, 2, Theme.fade(0x405AB4FF, f));
        }
        Paint.rect(g, x, y, w, height, Theme.RADIUS_M, Theme.mix(Theme.FIELD, 0xB3080B10, f), Theme.mix(0x400A0D12, 0xA6080B10, f));
        Paint.outline(g, x, y, w, height, Theme.RADIUS_M, Theme.mix(Theme.mix(0x33FFFFFF, 0x59FFFFFF, h), 0xE65AB4FF, f),
                Theme.mix(Theme.mix(0x1AFFFFFF, 0x33FFFFFF, h), 0x995AB4FF, f));
        if (leading != null) {
            Paint.iconCentered(g, leading, x, y, leadingWidth + 3, height, 8, Theme.mix(Theme.TEXT_MUTED, Theme.ACCENT, f));
        }
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }
}
