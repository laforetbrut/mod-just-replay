package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Glass list of choices opened under (or above) an anchor: hover highlight, check mark on the current choice,
 * smooth scrolling when the list is long, arrow keys and Enter.
 *
 * @author vyrriox
 */
public final class ListPopup extends Popup {
    private static final int ROW = 13;

    private final List<String> labels;
    private final int current;
    private final IntConsumer onPick;
    private final int rows;
    private final Anim.Value scroll = new Anim.Value(0.0F, Theme.NORMAL + 6);
    private int keyIndex = -1;

    public ListPopup(int anchorX, int anchorY, int anchorW, int anchorH, int screenW, int screenH, Font font, List<String> labels,
                     int current, IntConsumer onPick) {
        this.labels = labels;
        this.current = current;
        this.onPick = onPick;
        int widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, font.width(label));
        }
        w = Math.min(screenW - 8, Math.max(anchorW, widest + 26));
        int below = screenH - (anchorY + anchorH) - 6;
        int above = anchorY - 6;
        int maxRows = Math.max(3, (Math.max(below, above) - 6) / ROW);
        rows = Math.min(labels.size(), maxRows);
        h = rows * ROW + 6;
        x = Math.max(4, Math.min(anchorX, screenW - w - 4));
        y = below >= h || below >= above ? anchorY + anchorH + 2 : anchorY - h - 2;
        if (current >= rows) {
            scroll.snap(Math.min(labels.size() - rows, current - rows / 2) * ROW);
        }
    }

    private float maxScroll() {
        return Math.max(0, (labels.size() - rows) * ROW);
    }

    @Override
    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        float a = appear();
        g.pose().pushPose();
        g.pose().translate(0, (1.0F - a) * -4.0F, 0);
        Paint.shadow(g, x, y + 2, w, h, Theme.RADIUS_M, 8, Theme.fade(0x80000000, a));
        Paint.rect(g, x, y, w, h, Theme.RADIUS_M, Theme.fade(0xF01A202A, a), Theme.fade(0xF0121720, a));
        Paint.outline(g, x, y, w, h, Theme.RADIUS_M, Theme.fade(0x73FFFFFF, a), Theme.fade(0x26FFFFFF, a));
        float s = scroll.get();
        int first = (int) (s / ROW);
        g.enableScissor(x + 1, y + 3, x + w - 1, y + h - 3);
        for (int i = first; i < Math.min(labels.size(), first + rows + 1); i++) {
            float ry = y + 3 + i * ROW - s;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW && contains(mouseX, mouseY);
            if (hover || i == keyIndex) {
                Paint.rect(g, x + 3, ry, w - 6, ROW, Theme.RADIUS_S, Theme.fade(0x2EFFFFFF, a));
            }
            boolean selected = i == current;
            if (selected) {
                Paint.icon(g, Icon.CHECK, x + 6, ry + 2.5F, 8, Theme.fade(Theme.ACCENT, a));
            }
            g.drawString(font, Paint.ellipsize(font, labels.get(i), w - 26), x + 18, (int) ry + 3,
                    Theme.text(selected ? Theme.ACCENT : hover ? Theme.TEXT : Theme.TEXT_DIM, a), false);
        }
        g.disableScissor();
        if (labels.size() > rows) {
            float bh = Math.max(10, (h - 6) * rows / (float) labels.size());
            float by = y + 3 + (h - 6 - bh) * (s / Math.max(1, maxScroll()));
            Paint.rect(g, x + w - 4, by, 2, bh, 1, Theme.fade(0x80FFFFFF, a));
        }
        g.pose().popPose();
    }

    private int indexAt(double my) {
        int i = (int) Math.floor((my - y - 3 + scroll.get()) / ROW);
        return i >= 0 && i < labels.size() ? i : -1;
    }

    @Override
    public boolean clicked(double mx, double my, int button) {
        int i = indexAt(my);
        if (i < 0) {
            return false;
        }
        onPick.accept(i);
        return true;
    }

    @Override
    public boolean scrolled(double mx, double my, double delta) {
        scroll.to(Math.max(0, Math.min(maxScroll(), scroll.target() - (float) delta * ROW * 2)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP) {
            int base = keyIndex < 0 ? current : keyIndex;
            keyIndex = Math.max(0, Math.min(labels.size() - 1, base + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            float top = keyIndex * ROW;
            if (top < scroll.target()) {
                scroll.to(top);
            } else if (top + ROW > scroll.target() + rows * ROW) {
                scroll.to(top + ROW - rows * ROW);
            }
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && keyIndex >= 0) {
            onPick.accept(keyIndex);
            return true;
        }
        return false;
    }
}
