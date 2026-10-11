package com.vyrriox.justreplay.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;

import java.util.function.Consumer;

/**
 * Asks for one line of text, then returns to the parent screen.
 *
 * @author vyrriox
 */
public class TextInputScreen extends Screen {
    private final Screen parent;
    private final Component label;
    private final String initial;
    private final Consumer<String> onDone;
    private EditBox box;

    public TextInputScreen(Screen parent, Component title, Component label, String initial, Consumer<String> onDone) {
        super(title);
        this.parent = parent;
        this.label = label;
        this.initial = initial;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        box = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, label);
        box.setMaxLength(128);
        box.setValue(initial);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> done())
                .bounds(width / 2 - 100, height / 2 + 20, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 20, 98, 20).build());
    }

    private void done() {
        minecraft.setScreen(parent);
        onDone.accept(box.getValue().trim());
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - 50, 0xFFFFFF);
        g.drawString(font, label, width / 2 - 100, height / 2 - 22, 0xA0A0A0);
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
