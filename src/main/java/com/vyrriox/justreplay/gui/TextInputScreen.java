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

    public TextInputScreen(net.minecraft.client.gui.screens.Screen parent, Component title, Component label, String initial,
                           Consumer<String> onDone) {
        super(title);
        this.parent = parent;
        this.label = label;
        this.initial = initial;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        boxW = Math.min(240, width - 24);
        boxH = 96;
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        String value = box != null ? box.getValue() : initial;
        box = new GlassTextField(font, boxW - 24, label);
        box.setMaxLength(128);
        box.setValue(value);
        box.setPosition(boxX + 12, boxY + 42);
        addRenderableWidget(box);
        setInitialFocus(box);
        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 12 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, CommonComponents.GUI_DONE, b -> done())
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
        Paint.icon(g, Icon.EDIT, boxX + 16, boxY + 14, 8, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 44), boxX + 34, boxY + 14, Theme.TEXT, false);
        g.drawString(font, Paint.ellipsize(font, label.getString(), boxW - 24), boxX + 13, boxY + 31, Theme.TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
