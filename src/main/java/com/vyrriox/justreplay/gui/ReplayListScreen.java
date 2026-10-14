package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Lists the recorded replays: open, rename, delete, open the folder.
 *
 * @author vyrriox
 */
public class ReplayListScreen extends Screen {
    private final Screen parent;
    private ReplayList list;
    private Button openButton;
    private Button renameButton;
    private Button deleteButton;

    public ReplayListScreen(Screen parent) {
        super(Component.translatable("justreplay.list.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = new ReplayList(minecraft, width, height - 96, 32, 36);
        for (ReplayStorage.Entry entry : ReplayStorage.list()) {
            list.addReplay(entry);
        }
        addRenderableWidget(list);

        int y = height - 56;
        openButton = addRenderableWidget(Button.builder(Component.translatable("justreplay.list.open"), b -> openSelected())
                .bounds(width / 2 - 154, y, 100, 20).build());
        renameButton = addRenderableWidget(Button.builder(Component.translatable("justreplay.list.rename"), b -> renameSelected())
                .bounds(width / 2 - 50, y, 100, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("justreplay.list.delete"), b -> deleteSelected())
                .bounds(width / 2 + 54, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("justreplay.list.folder"), b -> Util.getPlatform().openPath(ReplayStorage.root()))
                .bounds(width / 2 - 154, y + 24, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(width / 2 + 4, y + 24, 150, 20).build());
        updateButtons();
    }

    private ReplayStorage.Entry selected() {
        ReplayList.Row row = list.getSelected();
        return row == null ? null : row.entry;
    }

    void updateButtons() {
        boolean has = selected() != null;
        openButton.active = has;
        renameButton.active = has;
        deleteButton.active = has;
    }

    private void openSelected() {
        ReplayStorage.Entry entry = selected();
        if (entry != null) {
            ReplaySession.open(entry.dir(), this);
        }
    }

    private void renameSelected() {
        ReplayStorage.Entry entry = selected();
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
        ReplayStorage.Entry entry = selected();
        if (entry == null) {
            return;
        }
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    ReplayStorage.deleteRecursively(entry.dir());
                } catch (IOException e) {
                    JustReplay.LOGGER.error("Cannot delete replay {}", entry.dir(), e);
                }
            }
            minecraft.setScreen(this);
        }, Component.translatable("justreplay.list.delete.confirm"), Component.literal(entry.meta().name)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (list.children().isEmpty()) {
            g.drawCenteredString(font, Component.translatable("justreplay.list.empty"), width / 2, height / 2 - 20, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    class ReplayList extends ObjectSelectionList<ReplayList.Row> {
        ReplayList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void addReplay(ReplayStorage.Entry entry) {
            addEntry(new Row(entry));
        }

        @Override
        public int getRowWidth() {
            return 320;
        }

        @Override
        public void setSelected(Row row) {
            super.setSelected(row);
            updateButtons();
        }

        class Row extends ObjectSelectionList.Entry<Row> {
            final ReplayStorage.Entry entry;
            private long lastClick;

            Row(ReplayStorage.Entry entry) {
                this.entry = entry;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovered, float partialTick) {
                ReplayMeta meta = entry.meta();
                g.drawString(font, meta.name, left + 4, top + 2, 0xFFFFFF);
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(meta.startedAt));
                String where = meta.server == null || meta.server.isBlank() ? "" : "  " + meta.server;
                g.drawString(font, Component.literal(date + where).withStyle(ChatFormatting.GRAY), left + 4, top + 13, 0xFFFFFF);
                String info = Timeline.formatTicks(meta.durationTicks) + "  " + formatSize(entry.sizeBytes()) + "  "
                        + meta.mods.size() + " mods";
                g.drawString(font, Component.literal(info).withStyle(ChatFormatting.DARK_GRAY), left + 4, top + 24, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                setSelected(this);
                long now = Util.getMillis();
                if (now - lastClick < 250) {
                    openSelected();
                }
                lastClick = now;
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(entry.meta().name);
            }
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
