package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.export.ExportRenderSize;
import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassSlider;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Video export settings. The exported range is the cut track when it has cuts, else the selected camera path from
 * its first to its last keyframe.
 *
 * @author vyrriox
 */
public class ExportScreen extends GlassScreen {
    private static final List<Integer> FPS = List.of(24, 30, 60, 120);

    private final Screen parent;
    private final ReplaySession session;
    private GlassTextField fileName;
    private GlassTextField ffmpeg;
    private String fileValue;
    private String ffmpegValue;
    private int fps;
    private int crf;
    private ExportResolution resolution;
    private Component error;
    private int boxX, boxY, boxW, boxH;

    public ExportScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("justreplay.export.title"));
        this.parent = parent;
        this.session = session;
        this.fps = JRConfig.EXPORT_FPS.get();
        this.crf = JRConfig.EXPORT_CRF.get();
        this.resolution = JRConfig.EXPORT_RESOLUTION.get();
        this.fileValue = session.meta().name.replaceAll("[^A-Za-z0-9._-]", "_");
        this.ffmpegValue = JRConfig.FFMPEG_PATH.get();
    }

    @Override
    protected void init() {
        if (fileName != null) {
            fileValue = fileName.getValue();
            ffmpegValue = ffmpeg.getValue();
        }
        boxW = Math.min(320, width - 24);
        boxH = Math.min(300, height - 16);
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        ScrollArea area = new ScrollArea(this, boxX + 8, boxY + 28, boxW - 16, boxH - 28 - 34);
        Form f = new Form(area, boxW - 24);

        f.text(() -> Component.translatable("justreplay.export.file"), Theme.TEXT_DIM);
        fileName = new GlassTextField(font, 0, Component.translatable("justreplay.export.file"));
        fileName.setMaxLength(128);
        fileName.setValue(fileValue);
        f.full(fileName);

        f.text(() -> Component.translatable("justreplay.export.ffmpeg"), Theme.TEXT_DIM);
        ffmpeg = new GlassTextField(font, 0, Component.translatable("justreplay.export.ffmpeg"));
        ffmpeg.setMaxLength(512);
        ffmpeg.setValue(ffmpegValue);
        ffmpeg.tip(Component.translatable("justreplay.export.ffmpeg.tip"));
        f.full(ffmpeg);

        f.space(2);
        f.text(() -> Component.translatable("justreplay.export.resolution.label"), Theme.TEXT_DIM);
        f.full(new Segmented<>(List.of(ExportResolution.values()), resolution, ExportScreen::shortName, null, v -> resolution = v));
        f.text(this::sizeLine, Theme.TEXT_MUTED);

        f.space(2);
        f.text(() -> Component.translatable("justreplay.export.fps"), Theme.TEXT_DIM);
        List<Integer> rates = new ArrayList<>(FPS);
        if (!rates.contains(fps)) {
            rates.add(fps);
            rates.sort(Integer::compare);
        }
        f.full(new Segmented<>(rates, fps, v -> Component.literal(v + " fps"), null, v -> fps = v));

        f.space(2);
        // Right means better: the slider shows quality, stored as CRF 40 (small file) down to 12 (near lossless).
        f.full(new GlassSlider(0, 0, 28, 1, 40 - Math.max(12, Math.min(40, crf)), v -> Component.translatable("justreplay.export.quality",
                qualityName(40 - (int) v), 40 - (int) v), v -> crf = 40 - (int) v));

        f.space(2);
        f.text(this::rangeLine, Theme.TEXT);
        f.text(() -> error, Theme.DANGER);
        f.end();

        int bw = (boxW - 24 - 2 * Theme.GAP) / 3;
        int by = boxY + boxH - 10 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, Component.translatable("justreplay.export.folder.short"),
                b -> Util.getPlatform().openPath(ReplayStorage.exportRoot())).icon(Icon.FOLDER)
                .tip(Component.translatable("justreplay.export.folder")));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(new GlassButton(boxX + 12 + 2 * (bw + Theme.GAP), by, bw, 16, Component.translatable("justreplay.export.start"),
                b -> start()).icon(Icon.FILM).style(GlassButton.Style.PRIMARY));
    }

    private static Component shortName(ExportResolution resolution) {
        return resolution.isWindow() ? Component.translatable("justreplay.export.resolution.window")
                : Component.literal(resolution.height() + "p");
    }

    private static Component qualityName(int crf) {
        String key = crf <= 18 ? "lossless" : crf <= 23 ? "high" : crf <= 28 ? "medium" : "low";
        return Component.translatable("justreplay.export.quality." + key);
    }

    private void start() {
        int[] range = range();
        if (range == null) {
            error = Component.translatable("justreplay.export.error.nothing");
            return;
        }
        JRConfig.FFMPEG_PATH.set(ffmpeg.getValue().trim().isEmpty() ? "ffmpeg" : ffmpeg.getValue().trim());
        JRConfig.EXPORT_FPS.set(fps);
        JRConfig.EXPORT_CRF.set(crf);
        JRConfig.EXPORT_RESOLUTION.set(resolution);
        JRConfig.SPEC.save();
        error = VideoExporter.start(session, range[0], range[1], fileName.getValue());
    }

    /** Ticks to export: the cut track, else the keyframes of the selected camera; null when there is nothing. */
    private int[] range() {
        return session.project().exportRange(session.playback().duration());
    }

    private Component rangeLine() {
        int[] ticks = range();
        return ticks == null ? Component.translatable("justreplay.export.error.nothing")
                : Component.translatable(session.project().cuts.isEmpty() ? "justreplay.export.range" : "justreplay.export.range.cuts",
                Timeline.formatTicks(ticks[0]), Timeline.formatTicks(ticks[1]));
    }

    private Component sizeLine() {
        if (resolution.isWindow()) {
            return Component.translatable("justreplay.export.resolution",
                    minecraft.getMainRenderTarget().width, minecraft.getMainRenderTarget().height);
        } else if (ExportRenderSize.fits(resolution.width(), resolution.height())) {
            return Component.translatable("justreplay.export.resolution.render", resolution.width(), resolution.height());
        }
        return Component.translatable("justreplay.export.resolution.scaled", resolution.width(), resolution.height());
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
        Paint.icon(g, Icon.FILM, boxX + 12, boxY + 9, 10, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 40), boxX + 27, boxY + 11, Theme.TEXT, false);
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
