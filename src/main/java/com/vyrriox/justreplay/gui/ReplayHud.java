package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.client.JRKeys;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * In-game overlay: a REC pill with a pulsing dot while recording; while watching, a glass timeline pill at the
 * bottom with transport state, a key hint built from the real key bindings, and a badge when spectating.
 *
 * @author vyrriox
 */
public final class ReplayHud {
    /** Texts rebuilt only when their value changes, so the HUD allocates nothing per frame. */
    private static int recTick = -1;
    private static String recText = "";
    private static int shownCursor = -1;
    private static int shownDuration = -1;
    private static String timeText = "";
    private static float shownSpeed = -1;
    private static String speedText = "";
    private static long hintBuilt;
    private static String hintText = "";

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
            renderRec(g, font, recorder.tick());
        }

        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning() || !session.isHudVisible() || mc.screen instanceof EditorScreen) {
            return;
        }
        int width = g.guiWidth();
        int height = g.guiHeight();
        Playback playback = session.playback();
        int pillH = 18;
        int x = 8;
        int w = width - 16;
        int y = height - pillH - 6;
        Paint.glass(g, x, y, w, pillH, pillH / 2.0F);

        int cx = x + 7;
        int textY = Paint.textY(y, pillH);
        Paint.iconOnText(g, playback.isPaused() ? Icon.PLAY : Icon.PAUSE, cx, textY, 8, Theme.TEXT);
        cx += 12;
        String time = time(playback.cursor(), playback.duration());
        g.drawString(font, time, cx, textY, Theme.TEXT, false);
        cx += font.width(time) + 8;

        int right = x + w - 8;
        if (session.isCameraPathActive()) {
            String cam = "CAM";
            int bw = font.width(cam) + 14;
            right -= bw;
            Paint.rect(g, right, textY - 2, bw, Paint.CAP + 4, (Paint.CAP + 4) / 2.0F, 0xCC5AB4FF, 0xCC2F86E0);
            Paint.iconOnText(g, Icon.CAMERA, right + 3, textY, 7, Theme.TEXT_ON_ACCENT);
            g.drawString(font, cam, right + 11, textY, Theme.TEXT_ON_ACCENT, false);
            right -= 6;
        }
        String speed = speed(playback.speed());
        right -= font.width(speed);
        g.drawString(font, speed, right, textY, Theme.TEXT_DIM, false);
        right -= 8;
        if (right - cx > 20) {
            Timeline.renderCompact(g, cx, y + 4, right - cx, 9, session);
        }

        int used = x;
        if (session.spectate().isActive()) {
            Component leave = mc.options.keyShift.getTranslatedKeyMessage();
            Component spectating = Component.translatable("justreplay.hud.spectating", session.spectate().targetName(), leave);
            int sw = font.width(spectating) + 22;
            Paint.glass(g, x, y - 16, sw, 13, 6.5F);
            Paint.iconOnText(g, Icon.EYE, x + 5, y - 13, 9, Theme.WARNING);
            g.drawString(font, spectating, x + 17, y - 13, 0xFFFFE08A, false);
            used = x + sw + 6;
        }
        String hint = hint();
        int hw = font.width(hint) + 12;
        int hx = x + w - hw;
        if (hx > used) {
            Paint.glass(g, hx, y - 16, hw, 13, 6.5F);
            g.drawString(font, hint, hx + 6, y - 13, Theme.TEXT_DIM, false);
        }
    }

    private static void renderRec(GuiGraphics g, Font font, int tick) {
        if (tick != recTick) {
            recTick = tick;
            recText = "REC  " + Timeline.formatTicks(tick);
        }
        int w = font.width(recText) + 22;
        Paint.glass(g, 6, 6, w, 14, 7);
        float pulse = 0.5F + 0.5F * (float) Math.sin(Anim.now() / 1000.0 * Math.PI * 1.6);
        Paint.circle(g, 13, 13, 3.0F + pulse * 1.6F, Theme.withAlpha(Theme.RECORD, (int) (0x30 + 0x40 * pulse)));
        Paint.circle(g, 13, 13, 2.6F, Theme.mix(0xFFB33636, Theme.RECORD, pulse));
        g.drawString(font, recText, 20, 10, Theme.TEXT, false);
    }

    private static String time(int cursor, int duration) {
        if (cursor != shownCursor || duration != shownDuration) {
            shownCursor = cursor;
            shownDuration = duration;
            timeText = Timeline.formatTicks(cursor) + " / " + Timeline.formatTicks(duration);
        }
        return timeText;
    }

    private static String speed(float speed) {
        if (speed != shownSpeed) {
            shownSpeed = speed;
            speedText = "x" + trim(speed);
        }
        return speedText;
    }

    /** Key hint built from the current key bindings, refreshed once a second. */
    private static String hint() {
        long now = Anim.now();
        if (now - hintBuilt > 1000L || hintText.isEmpty()) {
            hintBuilt = now;
            StringBuilder b = new StringBuilder();
            append(b, JRKeys.EDITOR, "justreplay.hud.hint.editor");
            append(b, JRKeys.PLAY_PAUSE, "justreplay.hud.hint.play");
            append(b, JRKeys.CAMERA_KEYFRAME, "justreplay.hud.hint.keyframe");
            append(b, JRKeys.CAMERA_PATH, "justreplay.hud.hint.path");
            append(b, JRKeys.TOGGLE_HUD, "justreplay.hud.hint.hide");
            hintText = b.toString();
        }
        return hintText;
    }

    private static void append(StringBuilder b, KeyMapping key, String label) {
        if (key.isUnbound()) {
            return;
        }
        if (!b.isEmpty()) {
            b.append("   ");
        }
        b.append(key.getTranslatedKeyMessage().getString()).append(' ').append(Component.translatable(label).getString());
    }

    static String trim(float value) {
        return value == (int) value ? Integer.toString((int) value) : Float.toString(value);
    }
}
