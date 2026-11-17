package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A floating layer above a {@link GlassScreen} (dropdown list, menu). It gets every input first; a click outside
 * closes it.
 *
 * @author vyrriox
 */
public abstract class Popup {
    protected int x, y, w, h;
    protected final long openedAt = Anim.now();

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Opening progress 0..1, eased. */
    protected float appear() {
        return Anim.easeOutCubic(Anim.progress(openedAt, 140));
    }

    public abstract void render(GuiGraphics g, Font font, int mouseX, int mouseY);

    /** A click inside the popup; return true to close it. */
    public abstract boolean clicked(double mx, double my, int button);

    public boolean scrolled(double mx, double my, double delta) {
        return true;
    }

    /** A key press; return true when consumed. */
    public boolean keyPressed(int keyCode) {
        return false;
    }
}
