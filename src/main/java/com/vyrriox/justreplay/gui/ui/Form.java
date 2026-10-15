package com.vyrriox.justreplay.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Lays out a column of controls in a {@link ScrollArea}: rows split in equal cells, section headers and live text
 * lines. Positions are content coordinates; the area moves them while scrolling.
 *
 * @author vyrriox
 */
public final class Form {
    private final ScrollArea area;
    private final int width;
    private int y;
    private int rowHeight = Theme.ROW;

    public Form(ScrollArea area, int width) {
        this.area = area;
        this.width = width;
        this.y = 2;
    }

    public ScrollArea area() {
        return area;
    }

    public int width() {
        return width;
    }

    /** Current content y. */
    public int y() {
        return y;
    }

    /** Places a widget in cell {@code col} (spanning {@code span} cells) of a row of {@code cols} cells. */
    public <T extends AbstractWidget> T put(T widget, int col, int span, int cols) {
        float cell = (width - Theme.GAP * (cols - 1)) / (float) cols;
        int x0 = Math.round(col * (cell + Theme.GAP));
        int x1 = Math.round((col + span) * (cell + Theme.GAP) - Theme.GAP);
        widget.setWidth(x1 - x0);
        area.add(widget, x0, y);
        return widget;
    }

    /** Left edge of cell {@code col} of a row of {@code cols} cells, in content coordinates. */
    public int cellX(int col, int cols) {
        float cell = (width - Theme.GAP * (cols - 1)) / (float) cols;
        return Math.round(col * (cell + Theme.GAP));
    }

    /** Width of {@code span} cells of a row of {@code cols} cells. */
    public int cellWidth(int span, int cols) {
        float cell = (width - Theme.GAP * (cols - 1)) / (float) cols;
        return Math.round(span * (cell + Theme.GAP) - Theme.GAP);
    }

    /** Places a widget at an exact content x on the current row. */
    public <T extends AbstractWidget> T at(T widget, int relX, int w) {
        widget.setWidth(w);
        area.add(widget, relX, y);
        return widget;
    }

    /** Small caption above the widgets of the next row, in cell {@code col} of {@code cols}; does not advance. */
    public void caption(Component text, int col, int cols) {
        caption(text, col, 1, cols);
    }

    public void caption(Component text, int col, int span, int cols) {
        int x0 = cellX(col, cols);
        int w = cellWidth(span, cols);
        String s = text.getString();
        area.deco(y, 9, (g, x, dy, aw, mx, my) -> {
            Font font = Minecraft.getInstance().font;
            g.drawString(font, Paint.ellipsize(font, s, w - 2), x + x0 + 1, dy, Theme.TEXT_DIM, false);
        });
    }

    public <T extends AbstractWidget> T put(T widget, int col, int cols) {
        return put(widget, col, 1, cols);
    }

    /** A widget on its own row. */
    public <T extends AbstractWidget> T full(T widget) {
        T w = put(widget, 0, 1, 1);
        next(widget.getHeight());
        return w;
    }

    /** Ends the current row. */
    public void next() {
        next(rowHeight);
    }

    /** Ends a row of {@code height} pixels. */
    public void next(int height) {
        y += height + Theme.GAP;
    }

    public void space(int px) {
        y += px;
    }

    /** Section title in small dim capitals with a hairline. */
    public void header(Component title, Icon icon) {
        y += 3;
        String text = title.getString().toUpperCase(java.util.Locale.ROOT);
        area.deco(y, 10, (g, x, dy, w, mx, my) -> {
            Font font = Minecraft.getInstance().font;
            int left = x + 1;
            if (icon != null) {
                Paint.iconOnText(g, icon, left, dy + 1, 8, Theme.ACCENT);
                left += 11;
            }
            g.drawString(font, text, left, dy + 1, Theme.TEXT_MUTED, false);
            int lineX = left + font.width(text) + 4;
            if (lineX < x + width - 2) {
                // Hairline on the middle of the capitals.
                g.fill(lineX, dy + 4, x + width - 2, dy + 5, 0x1FFFFFFF);
            }
        });
        y += 13;
    }

    /** A live line of text, re-read every frame. */
    public void text(Supplier<Component> text, int color) {
        area.deco(y, 10, (g, x, dy, w, mx, my) -> {
            Font font = Minecraft.getInstance().font;
            Component c = text.get();
            if (c != null) {
                g.drawString(font, Paint.ellipsize(font, c.getString(), width - 4), x + 2, dy + 1, color, false);
            }
        });
        y += 11;
    }

    /** Wrapped static text. */
    public void paragraph(Component text, int color) {
        Font font = Minecraft.getInstance().font;
        var lines = font.split(text, width - 4);
        int top = y;
        area.deco(top, lines.size() * 10, (g, x, dy, w, mx, my) -> {
            int ly = dy + 1;
            for (var line : lines) {
                g.drawString(font, line, x + 2, ly, color, false);
                ly += 10;
            }
        });
        y += lines.size() * 10 + 2;
    }

    /** Closes the layout: sets the content height of the area. */
    public int end() {
        area.setContentHeight(y + 2);
        return y;
    }
}
