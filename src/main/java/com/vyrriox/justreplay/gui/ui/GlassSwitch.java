package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * Labelled on / off switch with an animated knob. The state is read from the model every frame, so the switch
 * always shows the truth even when a key changes it.
 *
 * @author vyrriox
 */
public class GlassSwitch extends AbstractButton implements Tipped {
    private final BooleanSupplier state;
    private final Runnable toggle;
    private Icon icon;
    private Component tip;
    private final Anim.Value knob;
    private final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
    private String cachedLabel;
    private int cachedWidth = -1;

    public GlassSwitch(Component label, BooleanSupplier state, Runnable toggle) {
        super(0, 0, 0, Theme.ROW, label);
        this.state = state;
        this.toggle = toggle;
        this.knob = new Anim.Value(state.getAsBoolean() ? 1.0F : 0.0F, Theme.NORMAL);
    }

    public GlassSwitch icon(Icon icon) {
        this.icon = icon;
        return this;
    }

    public GlassSwitch tip(Component tip) {
        this.tip = tip;
        return this;
    }

    @Override
    public Component tooltip() {
        return tip;
    }

    @Override
    public void onPress() {
        toggle.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean on = state.getAsBoolean();
        float k = knob.to(on ? 1.0F : 0.0F);
        float h = hover.to(active && (isHovered() || isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard()) ? 1.0F : 0.0F);
        float alpha = active ? 1.0F : 0.45F;
        int x = getX(), y = getY();
        Paint.rect(g, x, y, width, height, Theme.RADIUS_M, Theme.fade(0x1AFFFFFF, h * alpha));
        int left = x + 5;
        int textY = Paint.textY(y, height);
        if (icon != null) {
            Paint.iconOnText(g, icon, left, textY, Theme.ICON, Theme.text(Theme.TEXT_DIM, alpha));
            left += Theme.ICON + 4;
        }
        float tw = 18, th = 10;
        // The track shares the optical middle of the label.
        float tx = x + width - tw - 4, ty = textY + Paint.CAP / 2.0F - th / 2.0F;
        int avail = (int) (tx - 4 - left);
        if (avail != cachedWidth) {
            cachedWidth = avail;
            cachedLabel = Paint.ellipsize(font, getMessage().getString(), Math.max(0, avail));
        }
        g.drawString(font, cachedLabel, left, textY, Theme.text(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, Math.max(h, k)), alpha), false);
        int trackTop = Theme.mix(0x33FFFFFF, 0xFF6CC0FF, k);
        int trackBottom = Theme.mix(0x1FFFFFFF, 0xFF3B8FE5, k);
        Paint.rect(g, tx, ty, tw, th, th / 2, Theme.fade(trackTop, alpha), Theme.fade(trackBottom, alpha));
        Paint.outline(g, tx, ty, tw, th, th / 2, Theme.fade(Theme.mix(0x4DFFFFFF, 0x80FFFFFF, h), alpha));
        float kd = th - 3;
        float kx = tx + 1.5F + (tw - 3 - kd) * Anim.easeInOutCubic(k);
        Paint.shadow(g, kx, ty + 1.5F, kd, kd, kd / 2, 1.5F, Theme.fade(0x66000000, alpha));
        Paint.rect(g, kx, ty + 1.5F, kd, kd, kd / 2, Theme.fade(0xFFFFFFFF, alpha), Theme.fade(0xFFE3E8EF, alpha));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
