package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.JRKeys;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists the recorded replays as glass cards: search, sort by date, name or size, open, rename, delete, open the
 * folder. A double click opens a replay.
 *
 * @author vyrriox
 */
public class ReplayListScreen extends GlassScreen {
    private static final int CARD_H = 46;
    private static final int GAP = 6;
    private static final int MIN_CARD_W = 200;
    private enum Sort { DATE, NAME, SIZE }
    private static Sort sort = Sort.DATE;

    private final Screen parent;
    private List<ReplayStorage.Entry> all = List.of();
    private final List<ReplayStorage.Entry> shown = new ArrayList<>();
    private final Map<ReplayStorage.Entry, Card> cards = new IdentityHashMap<>();
    private ReplayStorage.Entry selected;
    private GlassTextField search;
    private String query = "";
    private GlassButton openButton;
    private GlassButton renameButton;
    private GlassButton deleteButton;
    private int listX, listY, listW, listH, columns, cardW;
    private final Anim.Value scroll = new Anim.Value(0.0F, Theme.NORMAL + 4);
    private boolean draggingBar;
    private long lastClick;
    private ReplayStorage.Entry lastClicked;

    /** Cached texts and hover animation of one card. */
    private static final class Card {
        final String info;
        final String duration;
        final String size;
        final String mods;
        final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
        final Anim.Value pick = new Anim.Value(0.0F, Theme.NORMAL);

        Card(ReplayStorage.Entry entry) {
            ReplayMeta meta = entry.meta();
            String date = new SimpleDateFormat("yyyy-MM-dd  HH:mm").format(new Date(meta.startedAt));
            String where = meta.server == null || meta.server.isBlank()
                    ? Component.translatable("justreplay.list.singleplayer").getString() : meta.server;
            this.info = date + "   " + where;
            this.duration = Timeline.formatTicks(meta.durationTicks);
            this.size = formatSize(entry.sizeBytes());
            this.mods = Component.translatable("justreplay.list.mods", meta.mods.size()).getString();
        }
    }

    public ReplayListScreen(Screen parent) {
        super(Component.translatable("justreplay.list.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        all = ReplayStorage.list();
        if (selected != null) {
            ReplayStorage.Entry previous = selected;
            selected = null;
            for (ReplayStorage.Entry e : all) {
                if (e.dir().equals(previous.dir())) {
                    selected = e;
                }
            }
        }
        cards.clear();
        int contentW = Math.min(width - 24, 660);
        listX = (width - contentW) / 2;
        listW = contentW;
        int headerY = 10;
        int searchW = Math.min(170, contentW / 2 - 20);
        int sortW = Math.min(170, contentW - searchW - 120);
        search = new GlassTextField(font, searchW, Component.translatable("justreplay.list.search"));
        search.leading(Icon.SEARCH, 12);
        search.setHint(Component.translatable("justreplay.list.search").withColor(Theme.TEXT_MUTED));
        search.setValue(query);
        search.setPosition(listX + listW - searchW, headerY + 20);
        search.setResponder(text -> {
            query = text;
            filter();
        });
        addRenderableWidget(search);
        Segmented<Sort> sorter = addRenderableWidget(new Segmented<>(List.of(Sort.values()), sort,
                s -> Component.translatable("justreplay.list.sort." + s.name().toLowerCase(Locale.ROOT)), null, s -> {
            sort = s;
            filter();
        }));
        sorter.setRectangle(sortW, 16, listX, headerY + 20);

        listY = headerY + 44;
        int bottomH = 30;
        listH = height - listY - bottomH - 6;
        columns = Math.max(1, (listW + GAP) / (MIN_CARD_W + GAP));
        cardW = (listW - (columns - 1) * GAP) / columns;

        int by = height - bottomH + 4;
        int n = 5;
        int bw = Math.min(96, (listW - (n - 1) * Theme.GAP) / n);
        int bx = listX + (listW - (bw * n + Theme.GAP * (n - 1))) / 2;
        openButton = addRenderableWidget(new GlassButton(bx, by, bw, 16, Component.translatable("justreplay.list.open"), b -> openSelected())
                .icon(Icon.PLAY).style(GlassButton.Style.PRIMARY));
        bx += bw + Theme.GAP;
        renameButton = addRenderableWidget(new GlassButton(bx, by, bw, 16, Component.translatable("justreplay.list.rename"), b -> renameSelected())
                .icon(Icon.EDIT));
        bx += bw + Theme.GAP;
        deleteButton = addRenderableWidget(new GlassButton(bx, by, bw, 16, Component.translatable("justreplay.list.delete"), b -> deleteSelected())
                .icon(Icon.TRASH).style(GlassButton.Style.DANGER));
        bx += bw + Theme.GAP;
        addRenderableWidget(new GlassButton(bx, by, bw, 16, Component.translatable("justreplay.list.folder"),
                b -> Util.getPlatform().openPath(ReplayStorage.root())).icon(Icon.FOLDER));
        bx += bw + Theme.GAP;
        addRenderableWidget(new GlassButton(bx, by, bw, 16, CommonComponents.GUI_BACK, b -> onClose()).icon(Icon.CHEVRON_LEFT));
        filter();
    }

    private void filter() {
        shown.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (ReplayStorage.Entry e : all) {
            ReplayMeta meta = e.meta();
            if (q.isEmpty() || meta.name.toLowerCase(Locale.ROOT).contains(q)
                    || meta.server != null && meta.server.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(e);
            }
        }
        Comparator<ReplayStorage.Entry> order = switch (sort) {
            case NAME -> Comparator.comparing(e -> e.meta().name.toLowerCase(Locale.ROOT));
            case SIZE -> Comparator.comparingLong(ReplayStorage.Entry::sizeBytes).reversed();
            default -> Comparator.comparingLong((ReplayStorage.Entry e) -> e.meta().startedAt).reversed();
        };
        shown.sort(order);
        scroll.to(Math.min(scroll.target(), maxScroll()));
        updateButtons();
    }

    private Card card(ReplayStorage.Entry entry) {
        return cards.computeIfAbsent(entry, Card::new);
    }

    private int rows() {
        return (shown.size() + columns - 1) / columns;
    }

    private int contentHeight() {
        return Math.max(0, rows() * (CARD_H + GAP) - GAP);
    }

    private float maxScroll() {
        return Math.max(0, contentHeight() - listH);
    }

    void updateButtons() {
        boolean has = selected != null;
        openButton.active = has;
        renameButton.active = has;
        deleteButton.active = has;
    }

    private void openSelected() {
        if (selected != null) {
            ReplaySession.open(selected.dir(), this);
        }
    }

    private void renameSelected() {
        ReplayStorage.Entry entry = selected;
        if (entry == null) {
            return;
        }
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.list.rename"),
                Component.translatable("justreplay.list.name"), entry.meta().name, name -> {
            if (!name.isBlank()) {
                entry.meta().name = name;
                ReplayStorage.writeMeta(entry.dir(), entry.meta());
            }
        }));
    }

    private void deleteSelected() {
        ReplayStorage.Entry entry = selected;
        if (entry == null) {
            return;
        }
        minecraft.setScreen(new ConfirmDialog(Component.translatable("justreplay.list.delete.confirm"), Component.literal(entry.meta().name),
                Component.translatable("justreplay.list.delete"), confirmed -> {
            if (confirmed) {
                try {
                    ReplayStorage.deleteRecursively(entry.dir());
                } catch (IOException e) {
                    JustReplay.LOGGER.error("Cannot delete replay {}", entry.dir(), e);
                }
                selected = null;
            }
            minecraft.setScreen(this);
        }));
    }

    // ---------------------------------------------------------------- input

    private ReplayStorage.Entry entryAt(double mx, double my) {
        if (mx < listX || mx >= listX + listW || my < listY || my >= listY + listH) {
            return null;
        }
        double cy = my - listY + scroll.get();
        int row = (int) (cy / (CARD_H + GAP));
        int col = (int) ((mx - listX) / (cardW + GAP));
        if (cy - row * (CARD_H + GAP) > CARD_H || mx - listX - col * (cardW + GAP) > cardW || col >= columns) {
            return null;
        }
        int index = row * columns + col;
        return index >= 0 && index < shown.size() ? shown.get(index) : null;
    }

    private boolean onBar(double mx, double my) {
        return maxScroll() > 0 && mx >= listX + listW + 2 && mx < listX + listW + 10 && my >= listY && my < listY + listH;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (onBar(mx, my)) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        ReplayStorage.Entry entry = entryAt(mx, my);
        if (entry != null && button == 0) {
            setFocused(null);
            long now = Util.getMillis();
            if (entry == lastClicked && now - lastClick < 300) {
                selected = entry;
                openSelected();
                return true;
            }
            lastClick = now;
            lastClicked = entry;
            selected = entry;
            updateButtons();
            return true;
        }
        return false;
    }

    private void dragBar(double my) {
        float bar = barHeight();
        float t = (float) ((my - listY - bar / 2) / Math.max(1, listH - bar));
        scroll.snap(Math.max(0, Math.min(maxScroll(), t * maxScroll())));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingBar) {
            dragBar(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!hasPopup() && my >= listY && my < listY + listH) {
            scroll.to(Math.max(0, Math.min(maxScroll(), scroll.target() - (float) sy * 30)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!search.isFocused() && !shown.isEmpty()) {
            int index = selected == null ? -1 : shown.indexOf(selected);
            int step = switch (keyCode) {
                case GLFW.GLFW_KEY_DOWN -> columns;
                case GLFW.GLFW_KEY_UP -> -columns;
                case GLFW.GLFW_KEY_RIGHT -> 1;
                case GLFW.GLFW_KEY_LEFT -> -1;
                default -> 0;
            };
            if (step != 0) {
                selected = shown.get(Math.max(0, Math.min(shown.size() - 1, index < 0 ? 0 : index + step)));
                revealSelected();
                updateButtons();
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && selected != null) {
                openSelected();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void revealSelected() {
        int index = shown.indexOf(selected);
        if (index < 0) {
            return;
        }
        int top = index / columns * (CARD_H + GAP);
        if (top < scroll.target()) {
            scroll.to(top);
        } else if (top + CARD_H > scroll.target() + listH) {
            scroll.to(top + CARD_H - listH);
        }
    }

    // ---------------------------------------------------------------- render

    private float barHeight() {
        return Math.max(16, listH * (listH / (float) Math.max(1, contentHeight())));
    }

    @Override
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float a = intro();
        Paint.icon(g, Icon.FILM, listX, 10, 14, Theme.ACCENT);
        g.drawString(font, title, listX + 19, 11, Theme.TEXT, false);
        Component count = Component.translatable("justreplay.list.count", shown.size());
        g.drawString(font, count, listX + 19 + font.width(title) + 8, 11, Theme.TEXT_MUTED, false);

        if (all.isEmpty()) {
            renderEmpty(g);
            return;
        }
        float s = scroll.to(Math.max(0, Math.min(maxScroll(), scroll.target())));
        g.enableScissor(listX - 4, listY - 2, listX + listW + 4, listY + listH);
        int first = Math.max(0, (int) (s / (CARD_H + GAP)) * columns);
        boolean inList = mouseY >= listY && mouseY < listY + listH;
        for (int i = first; i < shown.size(); i++) {
            int row = i / columns, col = i % columns;
            float cy = listY + row * (CARD_H + GAP) - s + (1.0F - a) * (6 + row * 4);
            if (cy > listY + listH) {
                break;
            }
            int cx = listX + col * (cardW + GAP);
            ReplayStorage.Entry entry = shown.get(i);
            boolean hover = inList && mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + CARD_H;
            renderCard(g, entry, cx, cy, hover);
        }
        g.disableScissor();
        if (shown.isEmpty()) {
            Component none = Component.translatable("justreplay.list.no_match");
            g.drawString(font, none, width / 2 - font.width(none) / 2, listY + 20, Theme.TEXT_DIM, false);
        }
        if (maxScroll() > 0) {
            float bar = barHeight();
            float by = listY + (listH - bar) * (s / maxScroll());
            int bx = listX + listW + 4;
            Paint.rect(g, bx, listY, 3, listH, 1.5F, 0x1AFFFFFF);
            Paint.rect(g, bx, by, 3, bar, 1.5F, draggingBar || onBar(mouseX, mouseY) ? 0xCCFFFFFF : 0x80FFFFFF);
        }
    }

    private void renderCard(GuiGraphics g, ReplayStorage.Entry entry, int x, float y, boolean hover) {
        Card card = card(entry);
        float h = card.hover.to(hover ? 1.0F : 0.0F);
        float p = card.pick.to(entry == selected ? 1.0F : 0.0F);
        float lift = h * 1.0F;
        y -= lift;
        Paint.shadow(g, x, y + 2, cardW, CARD_H, Theme.RADIUS_L, 4 + h * 3, Theme.fade(0x59000000, 0.6F + h * 0.4F));
        int top = Theme.mix(Theme.mix(0x9E141A23, 0xB31E2633, h), 0xB31B3550, p);
        int bottom = Theme.mix(Theme.mix(0x9E0E1219, 0xB3141A23, h), 0xB3122436, p);
        Paint.rect(g, x, y, cardW, CARD_H, Theme.RADIUS_L, top, bottom);
        Paint.outline(g, x, y, cardW, CARD_H, Theme.RADIUS_L, Theme.mix(Theme.mix(0x4DFFFFFF, 0x80FFFFFF, h), 0xE65AB4FF, p),
                Theme.mix(0x1AFFFFFF, 0x805AB4FF, p));
        // Film badge
        Paint.rect(g, x + 8, y + 8, 30, 30, Theme.RADIUS_M, Theme.mix(0x26FFFFFF, 0x665AB4FF, p), Theme.mix(0x0FFFFFFF, 0x332F86E0, p));
        Paint.icon(g, Icon.FILM, x + 15, y + 15, 16, Theme.mix(Theme.TEXT_DIM, Theme.ACCENT, Math.max(p, h * 0.5F)));
        int tx = x + 46;
        int iy = (int) y;
        int textW = cardW - 46 - 8;
        g.drawString(font, Paint.ellipsize(font, entry.meta().name, textW), tx, iy + 7, Theme.TEXT, false);
        g.drawString(font, Paint.ellipsize(font, card.info, textW), tx, iy + 18, Theme.TEXT_DIM, false);
        int cx = tx;
        cx = chip(g, Icon.CLOCK, card.duration, cx, iy + 29);
        cx = chip(g, Icon.SAVE, card.size, cx, iy + 29);
        if (cx + font.width(card.mods) + 16 < x + cardW - 6) {
            chip(g, Icon.PUZZLE, card.mods, cx, iy + 29);
        }
    }

    private int chip(GuiGraphics g, Icon icon, String text, int x, int y) {
        int w = font.width(text) + 16;
        Paint.rect(g, x, y, w, 11, 5.5F, 0x1FFFFFFF);
        Paint.icon(g, icon, x + 3, y + 2, 7, Theme.TEXT_MUTED);
        g.drawString(font, text, x + 12, y + 2, Theme.TEXT_DIM, false);
        return x + w + 4;
    }

    private void renderEmpty(GuiGraphics g) {
        int w = Math.min(280, width - 40);
        int h = 74;
        int x = (width - w) / 2;
        int y = listY + Math.max(0, (listH - h) / 2);
        Paint.glass(g, x, y, w, h, Theme.RADIUS_L);
        Paint.circle(g, width / 2.0F, y + 22, 12, Theme.ACCENT_SOFT);
        Paint.icon(g, Icon.FILM, width / 2.0F - 7, y + 15, 14, Theme.ACCENT);
        Component text = Component.translatable("justreplay.list.empty.key", JRKeys.RECORD.getTranslatedKeyMessage());
        int ly = y + 42;
        for (var line : font.split(text, w - 20)) {
            g.drawString(font, line, width / 2 - font.width(line) / 2, ly, Theme.TEXT_DIM, false);
            ly += 10;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
