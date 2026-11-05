package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertically scrolling viewport. Widgets keep content coordinates and are moved under the viewport every frame;
 * the area draws them clipped, with smooth wheel scrolling and a draggable scrollbar. Clicks outside the viewport
 * never reach its widgets (see {@link GlassScreen}).
 *
 * @author vyrriox
 */
public final class ScrollArea {
    /** Draws a decoration (text, separator) at absolute coordinates. */
    @FunctionalInterface
    public interface Deco {
        void render(GuiGraphics g, int x, int y, int width, int mouseX, int mouseY);
    }

    private record Item(AbstractWidget widget, int relX, int relY) {
    }

    private record DecoItem(Deco deco, int relY, int height) {
    }

    private final GlassScreen screen;
    public int x, y, w, h;
    private int contentHeight;
    private final List<Item> items = new ArrayList<>();
    private final List<DecoItem> decos = new ArrayList<>();
    private final Anim.Value scroll = new Anim.Value(0.0F, Theme.NORMAL + 4);
    private boolean draggingBar;
    private double dragOffset;
    private final Anim.Value barHover = new Anim.Value(0.0F, Theme.FAST);

    public ScrollArea(GlassScreen screen, int x, int y, int w, int h) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        screen.registerArea(this);
    }

    public <T extends AbstractWidget> T add(T widget, int relX, int relY) {
        items.add(new Item(widget, relX, relY));
        screen.addClipped(this, widget);
        return widget;
    }

    public void deco(int relY, int height, Deco deco) {
        decos.add(new DecoItem(deco, relY, height));
    }

    public void setContentHeight(int height) {
        this.contentHeight = height;
    }

    public int contentHeight() {
        return contentHeight;
    }

    public float maxScroll() {
        return Math.max(0, contentHeight - h);
    }

    public float scrollTarget() {
        return scroll.target();
    }

    /** Sets the scroll position, clamped; {@code instant} skips the animation (after a rebuild). */
    public void setScroll(float value, boolean instant) {
        float v = Math.max(0, Math.min(maxScroll(), value));
        if (instant) {
            scroll.snap(v);
        } else {
            scroll.to(v);
        }
    }

    /** Scrolls so that the content range [top, bottom] is visible. */
    public void reveal(int top, int bottom) {
        float target = scroll.target();
        if (top < target) {
            setScroll(top - 4, false);
        } else if (bottom > target + h) {
            setScroll(bottom - h + 4, false);
        }
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean scrollable() {
        return contentHeight > h;
    }

    /** Places the widgets for the current scroll offset; call once per frame before rendering. */
    public void layout() {
        float s = scroll.to(Math.max(0, Math.min(maxScroll(), scroll.target())));
        int base = (int) Math.floor(s);
        for (Item item : items) {
            int wy = y + item.relY - base;
            item.widget.setX(x + item.relX);
            item.widget.setY(wy);
            item.widget.visible = wy + item.widget.getHeight() > y && wy < y + h;
        }
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        float s = scroll.get();
        float frac = s - (float) Math.floor(s);
        int base = (int) Math.floor(s);
        boolean inside = contains(mouseX, mouseY);
        int mx = inside ? mouseX : -10000;
        int my = inside ? mouseY : -10000;
        g.enableScissor(x, y, x + w, y + h);
        g.pose().pushPose();
        g.pose().translate(0, -frac, 0);
        for (DecoItem d : decos) {
            int dy = y + d.relY - base;
            if (dy + d.height > y && dy < y + h) {
                d.deco.render(g, x, dy, w, mx, my);
            }
        }
        for (Item item : items) {
            if (item.widget.visible) {
                item.widget.render(g, mx, my, partialTick);
            }
        }
        g.pose().popPose();
        g.disableScissor();
        if (scrollable()) {
            renderBar(g, mouseX, mouseY, s);
        }
    }

    private int barHeight() {
        return Math.max(12, (int) (h * (h / (float) contentHeight)));
    }

    private float barY(float s) {
        return y + (h - barHeight()) * (s / Math.max(1.0F, maxScroll()));
    }

    private void renderBar(GuiGraphics g, int mouseX, int mouseY, float s) {
        boolean near = draggingBar || mouseX >= x + w - 6 && mouseX < x + w + 2 && mouseY >= y && mouseY < y + h;
        float hv = barHover.to(near ? 1.0F : 0.0F);
        float bw = 2.0F + hv;
        float bx = x + w - bw - 1;
        Paint.rect(g, bx, y + 1, bw, h - 2, bw / 2, Theme.fade(0x1AFFFFFF, 0.4F + hv * 0.6F));
        Paint.rect(g, bx, barY(s) + 1, bw, barHeight() - 2, bw / 2, Theme.mix(0x66FFFFFF, 0xB3FFFFFF, hv));
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!contains(mx, my) || !scrollable()) {
            return false;
        }
        setScroll(scroll.target() - (float) delta * 24.0F, false);
        return true;
    }

    /** Press on the scrollbar; returns true when it starts a drag. */
    public boolean mouseClicked(double mx, double my) {
        if (!scrollable() || mx < x + w - 6 || mx >= x + w + 2 || my < y || my >= y + h) {
            return false;
        }
        float by = barY(scroll.get());
        if (my >= by && my < by + barHeight()) {
            dragOffset = my - by;
        } else {
            dragOffset = barHeight() / 2.0;
        }
        draggingBar = true;
        mouseDragged(my);
        return true;
    }

    public boolean mouseDragged(double my) {
        if (!draggingBar) {
            return false;
        }
        float t = (float) ((my - dragOffset - y) / Math.max(1, h - barHeight()));
        setScroll(t * maxScroll(), true);
        return true;
    }

    public boolean mouseReleased() {
        boolean was = draggingBar;
        draggingBar = false;
        return was;
    }
}
