package com.vyrriox.justreplay.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * The veil over the window while a video is made: the frame shows dimmed behind a panel with the progress, the time
 * spent and left, and how to cancel. It is drawn once the frame is captured, on the picture the window shows, so it
 * never reaches the video. Meanwhile mouse look, clicks, scrolling and every key but Esc are ignored (see
 * {@link #blocksInput()}): nothing the player does moves the camera or changes the render.
 *
 * @author vyrriox
 */
public final class RenderVeil {
    /** Dim over the frame: dark enough to read as covered, light enough to follow the render. */
    private static final int DIM = 0xC8060A10;
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 74;
    private static final int BAR_HEIGHT = 5;
    /** Frames the veil was drawn on and mouse look movements dropped since the game started, for the self-test. */
    private static int drawnFrames;
    private static int droppedMouseLooks;
    private static boolean warned;

    private RenderVeil() {
    }

    /** True while an export runs: player input must not reach the camera or the game. */
    public static boolean blocksInput() {
        return VideoExporter.isActive();
    }

    public static int drawnFrames() {
        return drawnFrames;
    }

    /** True when a mouse look movement must be dropped, which is then counted: the veil is up. */
    public static boolean dropMouseLook() {
        if (!blocksInput()) {
            return false;
        }
        droppedMouseLooks++;
        return true;
    }

    public static int droppedMouseLooks() {
        return droppedMouseLooks;
    }

    /** Draws the veil over the frame the window is about to show. Never throws: the veil is only cosmetic. */
    static void draw(VideoExporter exporter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getOverlay() != null || mc.level == null) {
            return;
        }
        try {
            drawOn(mc, exporter);
            drawnFrames++;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                JustReplay.LOGGER.warn("Cannot draw the render veil, the export goes on without it", t);
            }
        }
    }

    private static void drawOn(Minecraft mc, VideoExporter exporter) {
        Window window = mc.getWindow();
        RenderTarget target = mc.getMainRenderTarget();
        target.bindWrite(true);
        RenderSystem.clear(256, Minecraft.ON_OSX);
        double guiScale = window.getGuiScale();
        float guiWidth = (float) (window.getWidth() / guiScale);
        float guiHeight = (float) (window.getHeight() / guiScale);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, guiWidth, guiHeight, 0.0F, 1000.0F, 3000.0F),
                VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0.0F, 0.0F, -2000.0F);
        RenderSystem.applyModelViewMatrix();
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            g.fill(0, 0, (int) Math.ceil(guiWidth), (int) Math.ceil(guiHeight), DIM);
            // The game picks its GUI scale from the render size, which the export changed: the veil is laid out in
            // the GUI pixels of the real window, so it keeps the size of the game's interface there.
            int screenWidth = ExportRenderSize.screenWidth(window.getWidth());
            int screenHeight = ExportRenderSize.screenHeight(window.getHeight());
            int screenScale = ExportProgress.guiScale(screenWidth, screenHeight, mc.options.guiScale().get(),
                    mc.options.forceUnicodeFont().get());
            float canvasWidth = (float) screenWidth / screenScale;
            float canvasHeight = (float) screenHeight / screenScale;
            g.pose().pushPose();
            g.pose().scale(guiWidth / canvasWidth, guiHeight / canvasHeight, 1.0F);
            panel(g, mc.font, exporter, canvasWidth, canvasHeight);
            g.pose().popPose();
            g.flush();
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void panel(GuiGraphics g, Font font, VideoExporter e, float canvasWidth, float canvasHeight) {
        int w = (int) Math.min(PANEL_WIDTH, canvasWidth - 20);
        int h = PANEL_HEIGHT;
        int x = (int) ((canvasWidth - w) / 2);
        int y = (int) ((canvasHeight - h) / 2);
        Paint.glass(g, x, y, w, h, 6);
        int pad = Theme.PAD + 2;
        int inner = w - 2 * pad;

        // Title, with a dot that pulses while frames are made.
        long now = System.currentTimeMillis();
        float pulse = 0.55F + 0.45F * (float) Math.sin(now / 250.0);
        Paint.circle(g, x + pad + 3, y + pad + 4, 3, Theme.fade(Theme.RECORD, pulse));
        Component title = Component.translatable(e.isStill() ? "justreplay.export.veil.title.still" : "justreplay.export.veil.title");
        g.drawString(font, title, x + pad + 10, y + pad, Theme.TEXT, false);

        long done = e.frames();
        long total = e.totalFrames();
        int percent = ExportProgress.percent(done, total);
        String share = percent + "%";
        g.drawString(font, share, x + w - pad - font.width(share), y + pad, Theme.TEXT, false);

        // Progress bar; while the world loads, a segment sweeps the track instead.
        int barY = y + pad + 15;
        Paint.rect(g, x + pad, barY, inner, BAR_HEIGHT, BAR_HEIGHT / 2.0F, Theme.TRACK);
        if (e.isPreparing()) {
            int segment = Math.max(BAR_HEIGHT * 2, inner / 4);
            int travel = inner - segment;
            float t = (float) (now % 1600L) / 1600.0F;
            float at = t < 0.5F ? t * 2 : 2 - t * 2;
            Paint.rect(g, x + pad + travel * at, barY, segment, BAR_HEIGHT, BAR_HEIGHT / 2.0F, Theme.ACCENT_SOFT);
        } else {
            float fill = inner * ExportProgress.fraction(done, total);
            if (fill >= 1.0F) {
                Paint.rect(g, x + pad, barY, Math.max(BAR_HEIGHT, fill), BAR_HEIGHT, BAR_HEIGHT / 2.0F, Theme.ACCENT, Theme.ACCENT_DEEP);
            }
        }

        // What is going on, and the times.
        int lineY = barY + BAR_HEIGHT + 6;
        Component status = e.isPreparing() ? Component.translatable("justreplay.export.veil.preparing")
                : e.isDraining() ? Component.translatable("justreplay.export.veil.finishing")
                : Component.translatable("justreplay.export.veil.frames", done, total);
        g.drawString(font, status, x + pad, lineY, Theme.TEXT_DIM, false);
        Component elapsed = Component.translatable("justreplay.export.veil.elapsed", ExportProgress.clock(e.elapsedMillis() / 1000L));
        g.drawString(font, elapsed, x + w - pad - font.width(elapsed), lineY, Theme.TEXT_DIM, false);

        int timeY = lineY + 12;
        long left = e.isPreparing() ? -1L : ExportProgress.remainingSeconds(done, total, e.captureMillis());
        Component remaining = left < 0 ? Component.translatable("justreplay.export.veil.estimating")
                : Component.translatable("justreplay.export.veil.remaining", ExportProgress.clock(left));
        g.drawString(font, remaining, x + pad, timeY, Theme.TEXT_DIM, false);

        String file = Paint.ellipsize(font, e.shownFileName(), inner);
        g.drawString(font, file, x + pad, timeY + 12, Theme.TEXT_MUTED, false);

        Component hint = Component.translatable("justreplay.export.veil.hint");
        int hintWidth = font.width(hint);
        g.drawString(font, hint, (int) ((canvasWidth - hintWidth) / 2), y + h + 8, Theme.TEXT_MUTED, false);
    }
}
