package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Numeric value with minus and plus buttons: {@code label .... [-] value [+]}. The value is read from the model
 * every frame; NaN shows a dash.
 *
 * @author vyrriox
 */
public class Stepper extends AbstractWidget implements Tipped {
    private static final int BUTTON = 13;
    private static final int VALUE = 30;

    private final DoubleSupplier value;
    private final String suffix;
    private final int decimals;
    private final Runnable minus;
    private final Runnable plus;
    private BooleanSupplier enabled = () -> true;
    private Icon icon;
    private Component tip;
    private final Anim.Value hoverMinus = new Anim.Value(0.0F, Theme.FAST);
    private final Anim.Value hoverPlus = new Anim.Value(0.0F, Theme.FAST);
    private double shownValue = Double.MIN_VALUE;
    private String shownText = "";
    private String cachedLabel;
    private int cachedWidth = -1;

    public Stepper(Component label, DoubleSupplier value, String suffix, int decimals, Runnable minus, Runnable plus) {
        super(0, 0, 0, Theme.ROW, label);
        this.value = value;
        this.suffix = suffix;
        this.decimals = decimals;
        this.minus = minus;
        this.plus = plus;
    }

    public Stepper icon(Icon icon) {
        this.icon = icon;
        return this;
    }

    public Stepper tip(Component tip) {
        this.tip = tip;
        return this;
    }

    public Stepper enabled(BooleanSupplier enabled) {
        this.enabled = enabled;
        return this;
    }

    @Override
    public Component tooltip() {
        return tip;
    }

    private int minusX() {
        return getX() + width - 2 * BUTTON - VALUE - 2;
    }

    private int plusX() {
        return getX() + width - BUTTON - 2;
    }

    private String text() {
        double v = value.getAsDouble();
        if (v != shownValue) {
            shownValue = v;
            // No value (nothing selected): an empty field, a dash there read as a third minus button.
            shownText = Double.isNaN(v) ? "" : (decimals == 0 ? Long.toString(Math.round(v))
                    : String.format(Locale.ROOT, "%." + decimals + "f", v)) + suffix;
        }
        return shownText;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean on = active && enabled.getAsBoolean();
        float alpha = on ? 1.0F : 0.45F;
        int x = getX(), y = getY();
        int mx = minusX(), px = plusX();
        boolean overMinus = on && isHovered() && mouseX >= mx && mouseX < mx + BUTTON;
        boolean overPlus = on && isHovered() && mouseX >= px && mouseX < px + BUTTON;
        float hm = hoverMinus.to(overMinus ? 1.0F : 0.0F);
        float hp = hoverPlus.to(overPlus ? 1.0F : 0.0F);
        int left = x + 5;
        int textY = Paint.textY(y, height);
        if (icon != null) {
            Paint.iconOnText(g, icon, left, textY, Theme.ICON, Theme.text(Theme.TEXT_DIM, alpha));
            left += Theme.ICON + 4;
        }
        int avail = mx - 3 - left;
        if (avail != cachedWidth) {
            cachedWidth = avail;
            cachedLabel = Paint.ellipsize(font, getMessage().getString(), Math.max(0, avail));
        }
        g.drawString(font, cachedLabel, left, textY, Theme.text(Theme.TEXT_DIM, alpha), false);
        float r = Theme.RADIUS_M;
        // Field holding the value, the two buttons sit at its ends.
        Paint.rect(g, mx, y, px + BUTTON - mx, height, r, Theme.fade(Theme.FIELD, alpha));
        Paint.outline(g, mx, y, px + BUTTON - mx, height, r, Theme.fade(0x33FFFFFF, alpha));
        Paint.rect(g, mx + 1, y + 1, BUTTON - 2, height - 2, r - 1, Theme.fade(0x40FFFFFF, hm * alpha));
        Paint.rect(g, px + 1, y + 1, BUTTON - 2, height - 2, r - 1, Theme.fade(0x40FFFFFF, hp * alpha));
        Paint.iconOnText(g, Icon.MINUS, mx + (BUTTON - 7) / 2.0F, textY, 7, Theme.text(Theme.TEXT, alpha));
        Paint.iconOnText(g, Icon.PLUS, px + (BUTTON - 7) / 2.0F, textY, 7, Theme.text(Theme.TEXT, alpha));
        String t = text();
        if (!t.isEmpty()) {
            g.drawString(font, t, mx + BUTTON + Math.round((VALUE - font.width(t) + 1) / 2.0F), textY, Theme.text(Theme.TEXT, alpha), false);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        if (mouseX >= minusX() && mouseX < minusX() + BUTTON) {
            minus.run();
        } else if (mouseX >= plusX() && mouseX < plusX() + BUTTON) {
            plus.run();
        }
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return super.clicked(mouseX, mouseY) && (mouseX >= minusX() && mouseX < minusX() + BUTTON || mouseX >= plusX() && mouseX < plusX() + BUTTON);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
