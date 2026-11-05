package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.model.ModelLibrary;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
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
public class ModelPickScreen extends GlassScreen {
    private final Screen parent;
    private final Consumer<String> onPick;
    private List<String> files = List.of();
    private int boxX, boxY, boxW, boxH;

    public ModelPickScreen(Screen parent, Consumer<String> onPick) {
        super(Component.translatable("justreplay.model.pick.title"));
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        files = ModelLibrary.list();
        boxW = Math.min(300, width - 24);
        boxH = Math.min(260, height - 24);
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        ScrollArea area = new ScrollArea(this, boxX + 8, boxY + 30, boxW - 16, boxH - 30 - 34);
        Form form = new Form(area, boxW - 24);
        for (String file : files) {
            form.full(new ListRow(Component.literal(file), b -> {
                onPick.accept(file);
                if (minecraft.screen == this) {
                    minecraft.setScreen(parent);
                }
            }).icon(Icon.CUBE));
        }
        form.end();
        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 10 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, Component.translatable("justreplay.model.pick.folder"),
                b -> Util.getPlatform().openPath(ModelLibrary.folder())).icon(Icon.FOLDER));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, CommonComponents.GUI_CANCEL, b -> onClose()));
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
        Paint.iconOnText(g, Icon.CUBE, boxX + 12, boxY + 12, 10, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 40), boxX + 27, boxY + 12, Theme.TEXT, false);
        if (files.isEmpty()) {
            Component empty = Component.translatable("justreplay.model.pick.empty");
            Paint.icon(g, Icon.FOLDER, width / 2.0F - 8, boxY + boxH / 2.0F - 22, 16, Theme.TEXT_MUTED);
            int ly = boxY + boxH / 2 + 2;
            for (var line : font.split(empty, boxW - 30)) {
                g.drawString(font, line, width / 2 - font.width(line) / 2, ly, Theme.TEXT_DIM, false);
                ly += 10;
            }
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
