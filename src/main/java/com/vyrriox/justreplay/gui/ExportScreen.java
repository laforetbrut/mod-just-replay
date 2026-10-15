package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.ExportRenderSize;
import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Video export settings. The exported range is the camera path, from its first to its last keyframe.
 *
 * @author vyrriox
 */
public class ExportScreen extends Screen {
    private final Screen parent;
    private final ReplaySession session;
    private EditBox fileName;
    private EditBox ffmpeg;
    private int fps;
    private ExportResolution resolution;
    private Component error;

    public ExportScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("justreplay.export.title"));
        this.parent = parent;
        this.session = session;
        this.fps = JRConfig.EXPORT_FPS.get();
        this.resolution = JRConfig.EXPORT_RESOLUTION.get();
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 2 - 74;
        fileName = new EditBox(font, x, y, 200, 20, Component.translatable("justreplay.export.file"));
        fileName.setMaxLength(128);
        fileName.setValue(session.meta().name.replaceAll("[^A-Za-z0-9._-]", "_"));
        addRenderableWidget(fileName);
        ffmpeg = new EditBox(font, x, y + 36, 200, 20, Component.translatable("justreplay.export.ffmpeg"));
        ffmpeg.setMaxLength(512);
        ffmpeg.setValue(JRConfig.FFMPEG_PATH.get());
        addRenderableWidget(ffmpeg);
        addRenderableWidget(CycleButton.builder((Integer v) -> Component.literal(v + " fps"))
                .withValues(List.of(24, 30, 60, 120)).withInitialValue(fps)
                .create(x, y + 62, 200, 20, Component.translatable("justreplay.export.fps"), (b, v) -> fps = v));
        addRenderableWidget(CycleButton.builder(ExportResolution::getTranslatedName)
                .withValues(ExportResolution.values()).withInitialValue(resolution)
                .create(x, y + 86, 200, 20, Component.translatable("justreplay.export.resolution.label"), (b, v) -> resolution = v));
        addRenderableWidget(Button.builder(Component.translatable("justreplay.export.start"), b -> start())
                .bounds(x, y + 110, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + 102, y + 110, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("justreplay.export.folder"), b -> Util.getPlatform().openPath(ReplayStorage.exportRoot()))
                .bounds(x, y + 134, 200, 20).build());
    }

    private void start() {
        List<ReplayProject.CameraKeyframe> keys = session.project().cameraKeyframes;
        if (keys.size() < 2) {
            error = Component.translatable("justreplay.export.error.keyframes");
            return;
        }
        JRConfig.FFMPEG_PATH.set(ffmpeg.getValue().trim().isEmpty() ? "ffmpeg" : ffmpeg.getValue().trim());
        JRConfig.EXPORT_FPS.set(fps);
        JRConfig.EXPORT_RESOLUTION.set(resolution);
        JRConfig.SPEC.save();
        error = VideoExporter.start(session, keys.get(0).tick, keys.get(keys.size() - 1).tick, fileName.getValue());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = width / 2 - 100;
        int y = height / 2 - 74;
        g.drawCenteredString(font, title, width / 2, y - 34, 0xFFFFFF);
        g.drawString(font, Component.translatable("justreplay.export.file"), x, y - 10, 0xA0A0A0);
        g.drawString(font, Component.translatable("justreplay.export.ffmpeg"), x, y + 26, 0xA0A0A0);
        List<ReplayProject.CameraKeyframe> keys = session.project().cameraKeyframes;
        Component range = keys.size() < 2 ? Component.translatable("justreplay.export.error.keyframes")
                : Component.translatable("justreplay.export.range", Timeline.formatTicks(keys.get(0).tick),
                Timeline.formatTicks(keys.get(keys.size() - 1).tick));
        g.drawCenteredString(font, range, width / 2, y + 160, 0xFFFFFF);
        Component size;
        if (resolution.isWindow()) {
            size = Component.translatable("justreplay.export.resolution",
                    minecraft.getMainRenderTarget().width, minecraft.getMainRenderTarget().height);
        } else if (ExportRenderSize.fits(resolution.width(), resolution.height())) {
            size = Component.translatable("justreplay.export.resolution.render", resolution.width(), resolution.height());
        } else {
            size = Component.translatable("justreplay.export.resolution.scaled", resolution.width(), resolution.height());
        }
        g.drawCenteredString(font, size, width / 2, y + 172, 0xA0A0A0);
        if (error != null) {
            g.drawCenteredString(font, error, width / 2, y + 184, 0xFF5555);
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
