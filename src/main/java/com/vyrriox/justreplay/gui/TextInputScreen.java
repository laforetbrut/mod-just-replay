package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Asks for one line of text in a glass dialog, then returns to the parent screen.
 *
 * @author vyrriox
 */
public class TextInputScreen extends GlassScreen {
    private final net.minecraft.client.gui.screens.Screen parent;
    private final Component label;
    private final String initial;
    private final Consumer<String> onDone;
    private GlassTextField box;
    private int boxX, boxY, boxW, boxH;
    private Component cancelLabel = CommonComponents.GUI_CANCEL;
    private Component doneLabel = CommonComponents.GUI_DONE;
    private int maxLength = 128;
    private boolean selectAll;
    private boolean pausesGame;

    public TextInputScreen(net.minecraft.client.gui.screens.Screen parent, Component title, Component label, String initial,
                           Consumer<String> onDone) {
        super(title);
        this.parent = parent;
        this.label = label;
        this.initial = initial;
        this.onDone = onDone;
    }

    /** Labels of the two buttons, Cancel and Done by default. Esc does what the first one does. */
    public TextInputScreen buttons(Component cancel, Component done) {
        this.cancelLabel = cancel;
        this.doneLabel = done;
        return this;
    }

    /** Longest text accepted, 128 characters by default. */
    public TextInputScreen maxLength(int max) {
        this.maxLength = max;
        return this;
    }

    /** Opens with the whole text selected, so typing replaces it. */
    public TextInputScreen selectAll() {
        this.selectAll = true;
        return this;
    }

    /** Pauses a singleplayer world while open. Never inside a replay, whose host world must keep running. */
    public TextInputScreen pausesGame(boolean pause) {
        this.pausesGame = pause;
        return this;
    }

    /** The text in the field now. */
    public String value() {
        return box != null ? box.getValue() : initial;
    }

    @Override
    protected void init() {
        boxW = Math.min(240, width - 24);
        boxH = 96;
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        boolean first = box == null;
        String value = first ? initial : box.getValue();
        box = new GlassTextField(font, boxW - 24, label);
        box.setMaxLength(maxLength);
        box.setValue(value);
        if (first && selectAll) {
            box.setCursorPosition(box.getValue().length());
            box.setHighlightPos(0);
        }
        box.setPosition(boxX + 12, boxY + 42);
        addRenderableWidget(box);
        setInitialFocus(box);
        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 12 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, cancelLabel, b -> onClose()));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, doneLabel, b -> done())
                .icon(Icon.CHECK).style(GlassButton.Style.PRIMARY));
    }

    private void done() {
        // Apply first so the parent screen rebuilds with the change.
        onDone.accept(box.getValue().trim());
        if (minecraft.screen == this) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            done();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderPanels(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float a = intro();
        g.pose().pushPose();
        g.pose().translate(0, (1.0F - a) * 6.0F, 0);
        Paint.glass(g, boxX, boxY, boxW, boxH, Theme.RADIUS_L);
        g.pose().popPose();
    }

    @Override
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Paint.circle(g, boxX + 20, boxY + 18, 8, Theme.ACCENT_SOFT);
        Paint.iconOnText(g, Icon.EDIT, boxX + 16, boxY + 14, 8, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 44), boxX + 34, boxY + 14, Theme.TEXT, false);
        g.drawString(font, Paint.ellipsize(font, label.getString(), boxW - 24), boxX + 13, boxY + 31, Theme.TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return pausesGame;
    }
}
