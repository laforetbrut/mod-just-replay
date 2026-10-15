package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Glass confirmation dialog for destructive actions. The callback decides which screen comes next.
 *
 * @author vyrriox
 */
public class ConfirmDialog extends GlassScreen {
    private final Component message;
    private final Component confirmLabel;
    private final BooleanConsumer callback;
    private List<FormattedCharSequence> lines = List.of();
    private int boxX, boxY, boxW, boxH;

    public ConfirmDialog(Component question, Component message, Component confirmLabel, BooleanConsumer callback) {
        super(question);
        this.message = message;
        this.confirmLabel = confirmLabel;
        this.callback = callback;
    }

    @Override
    protected void init() {
        boxW = Math.min(260, width - 24);
        lines = font.split(message, boxW - 24);
        boxH = 50 + lines.size() * 10 + 22;
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 12 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, CommonComponents.GUI_CANCEL, b -> callback.accept(false)));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, confirmLabel, b -> callback.accept(true))
                .icon(Icon.TRASH).style(GlassButton.Style.DANGER));
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
        Paint.circle(g, boxX + 22, boxY + 22, 10, Theme.DANGER_SOFT);
        Paint.icon(g, Icon.TRASH, boxX + 17, boxY + 17, 10, Theme.DANGER);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 50), boxX + 40, boxY + 19, Theme.TEXT, false);
        int y = boxY + 40;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, boxX + 12, y, Theme.TEXT_DIM, false);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        callback.accept(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
