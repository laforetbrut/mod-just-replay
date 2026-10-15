package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.model.ModelLibrary;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Lists the Blockbench models of the models folder and returns the chosen file name.
 *
 * @author vyrriox
 */
public class ModelPickScreen extends Screen {
    private static final int ROW = 22;

    private final Screen parent;
    private final Consumer<String> onPick;
    private List<String> files = List.of();
    private int scroll;

    public ModelPickScreen(Screen parent, Consumer<String> onPick) {
        super(Component.translatable("justreplay.model.pick.title"));
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        files = ModelLibrary.list();
        int rows = Math.max(1, (height - 90) / ROW);
        scroll = Math.max(0, Math.min(scroll, files.size() - rows));
        int y = 34;
        for (int i = scroll; i < Math.min(files.size(), scroll + rows); i++) {
            String file = files.get(i);
            addRenderableWidget(Button.builder(Component.literal(file), b -> {
                onPick.accept(file);
                if (minecraft.screen == this) {
                    minecraft.setScreen(parent);
                }
            }).bounds(width / 2 - 100, y, 200, 20).build());
            y += ROW;
        }
        addRenderableWidget(Button.builder(Component.translatable("justreplay.model.pick.folder"),
                b -> Util.getPlatform().openPath(ModelLibrary.folder())).bounds(width / 2 - 100, height - 28, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 2, height - 28, 98, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, scroll - (int) Math.signum(sy));
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        if (files.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("justreplay.model.pick.empty"), width / 2, 50, 0xA0A0A0);
        }
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
