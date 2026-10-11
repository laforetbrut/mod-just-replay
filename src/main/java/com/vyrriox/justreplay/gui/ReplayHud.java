package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * In-game overlay: the REC indicator while recording, and the timeline with transport state while watching.
 *
 * @author vyrriox
 */
public final class ReplayHud {
    private ReplayHud() {
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || VideoExporter.isActive()) {
            return;
        }
        Font font = mc.font;
        Recorder recorder = Recorder.active();
        if (recorder != null && JRConfig.SHOW_RECORDING_INDICATOR.get()) {
            boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
            g.fill(6, 6, 14, 14, blink ? 0xFFE53935 : 0x88E53935);
            g.drawString(font, "REC " + Timeline.formatTicks(recorder.tick()), 18, 6, 0xFFFFFFFF, true);
        }

        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning() || !session.isHudVisible() || mc.screen instanceof EditorScreen) {
            return;
        }
        int width = g.guiWidth();
        int height = g.guiHeight();
        int x = 10;
        int w = width - 20;
        int y = height - 22;
        Timeline.render(g, font, x, y, w, 10, session, null, null, null, -1);
        Playback playback = session.playback();
        String state = playback.isPaused() ? "||" : ">";
        String line = state + "  " + Timeline.formatTicks(playback.cursor()) + " / " + Timeline.formatTicks(playback.duration())
                + "   x" + trim(playback.speed()) + (session.isCameraPathActive() ? "   [CAM]" : "");
        g.drawString(font, line, x, y - 12, 0xFFFFFFFF, true);
        Component hint = Component.translatable("justreplay.hud.hint");
        g.drawString(font, hint, width - 10 - font.width(hint), y - 12, 0xFFBBBBBB, true);
    }

    static String trim(float value) {
        return value == (int) value ? Integer.toString((int) value) : Float.toString(value);
    }
}
