package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base of the glass screens: blurred background, scroll areas with clipped input, a popup layer and delayed glass
 * tooltips. Subclasses draw their surfaces in {@link #renderPanels} (under the widgets) and their texts in
 * {@link #renderOverlay} (above them).
 *
 * @author vyrriox
 */
public abstract class GlassScreen extends Screen {
    private final Map<GuiEventListener, ScrollArea> clipped = new IdentityHashMap<>();
    private final List<ScrollArea> areas = new ArrayList<>();
    private Popup popup;
    private Tipped tipOwner;
    private long tipSince;
    private Component tipText;
    private List<FormattedCharSequence> tipLines = List.of();
    /** Blur the world behind the screen (full screens and dialogs); the editor keeps the world sharp. */
    protected boolean blurBackground = true;
    protected final long openedAt = Anim.now();

    protected GlassScreen(Component title) {
        super(title);
    }

    // ---------------------------------------------------------------- registration

    void registerArea(ScrollArea area) {
        areas.add(area);
    }

    /** Adds a widget drawn by a scroll area: it gets input only inside the area's viewport. */
    <T extends AbstractWidget> void addClipped(ScrollArea area, T widget) {
        addWidget(widget);
        clipped.put(widget, area);
    }

    @Override
    protected void clearWidgets() {
        super.clearWidgets();
        clipped.clear();
        areas.clear();
        popup = null;
        tipOwner = null;
    }

    public void openPopup(Popup popup) {
        this.popup = popup;
    }

    public void closePopup() {
        this.popup = null;
    }

    public boolean hasPopup() {
        return popup != null;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!blurBackground) {
            return;
        }
        if (minecraft.level == null) {
            renderPanorama(g, partialTick);
        }
        renderBlurredBackground(partialTick);
        g.fillGradient(0, 0, width, height, Theme.SCRIM_TOP, Theme.SCRIM_BOTTOM);
    }

    /** Opening fade 0..1 of the screen. */
    protected float intro() {
        return Anim.easeOutCubic(Anim.progress(openedAt, 220));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean blocked = popup != null;
        int mx = blocked ? -10000 : mouseX;
        int my = blocked ? -10000 : mouseY;
        renderBackground(g, mouseX, mouseY, partialTick);
        renderPanels(g, mx, my, partialTick);
        for (ScrollArea area : areas) {
            area.render(g, mx, my, partialTick);
        }
        for (Renderable renderable : renderables) {
            renderable.render(g, mx, my, partialTick);
        }
        renderOverlay(g, mx, my, partialTick);
        if (popup != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            popup.render(g, font, mouseX, mouseY);
            g.pose().popPose();
        } else {
            renderTooltip(g, mouseX, mouseY);
        }
    }

    /** Surfaces under the widgets. */
    protected void renderPanels(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** Texts and decorations above the widgets. */
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    private Tipped hoveredTipped() {
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w && child instanceof Tipped t && w.visible && w.isHovered()) {
                return t;
            }
        }
        return null;
    }

    private void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        Tipped owner = hoveredTipped();
        Component text = owner == null ? null : owner.tooltip();
        if (owner != tipOwner) {
            tipOwner = owner;
            tipSince = Anim.now();
        }
        if (text == null) {
            return;
        }
        if (text != tipText) {
            tipText = text;
            tipLines = font.split(text, 200);
        }
        long shown = Anim.now() - tipSince - Theme.TOOLTIP_DELAY;
        if (shown > 0) {
            Paint.tooltip(g, font, tipLines, mouseX, mouseY, Anim.clamp01(shown / 120.0F));
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (popup != null) {
            Popup p = popup;
            if (!p.contains(mx, my) || p.clicked(mx, my, button)) {
                if (popup == p) {
                    popup = null;
                }
            }
            return true;
        }
        for (ScrollArea area : areas) {
            if (area.mouseClicked(mx, my)) {
                return true;
            }
        }
        for (GuiEventListener child : children()) {
            ScrollArea area = clipped.get(child);
            if (area != null && !area.contains(mx, my)) {
                continue;
            }
            if (child.mouseClicked(mx, my, button)) {
                setFocused(child);
                if (button == 0) {
                    setDragging(true);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (popup != null) {
            return true;
        }
        for (ScrollArea area : areas) {
            if (area.mouseDragged(my)) {
                return true;
            }
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean bar = false;
        for (ScrollArea area : areas) {
            bar |= area.mouseReleased();
        }
        if (bar) {
            setDragging(false);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (popup != null) {
            return popup.scrolled(mx, my, sy);
        }
        for (ScrollArea area : areas) {
            if (area.mouseScrolled(mx, my, sy)) {
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (popup != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                popup = null;
                return true;
            }
            popup.keyPressed(keyCode);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
