package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the replay timeline: progress, markers, camera keyframes, keyframes of the selected actor and the
 * play head. Shared by the in-game HUD and the editor.
 *
 * @author vyrriox
 */
public final class Timeline {
    public static final int CAMERA_COLOR = 0xFF4FC3F7;
    public static final int ACTOR_COLOR = 0xFF81C784;
    public static final int MARKER_COLOR = 0xFFFFD54F;
    public static final int SELECTED_COLOR = 0xFFFF5252;

    private Timeline() {
    }

    public static String formatTicks(double ticks) {
        int totalTenths = (int) Math.round(ticks / 2.0);
        int tenths = totalTenths % 10;
        int seconds = totalTenths / 10;
        return String.format("%d:%02d.%d", seconds / 60, seconds % 60, tenths);
    }

    public static int xOf(int tick, int x, int width, int duration) {
        return x + (int) Math.round((double) tick / Math.max(1, duration) * (width - 1));
    }

    public static int tickAt(double mouseX, int x, int width, int duration) {
        double t = (mouseX - x) / Math.max(1, width - 1);
        return (int) Math.round(Math.max(0, Math.min(1, t)) * duration);
    }

    /**
     * @param ghostTick play head preview while scrubbing, or -1
     */
    public static void render(GuiGraphics g, Font font, int x, int y, int width, int height, ReplaySession session,
                              ReplayProject.CameraKeyframe selectedCamera, ReplayProject.ActorData selectedActor,
                              ReplayProject.ActorKeyframe selectedActorKey, int ghostTick) {
        int duration = Math.max(1, session.playback().duration());
        int cursor = session.playback().cursor();
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xAA000000);
        g.fill(x, y, x + width, y + height, 0x66303030);
        g.fill(x, y, xOf(cursor, x, width, duration), y + height, 0x553F51B5);

        int lane = height / 2;
        for (ReplayProject.Marker marker : session.project().markers) {
            int mx = xOf(marker.tick, x, width, duration);
            g.fill(mx, y, mx + 1, y + height, MARKER_COLOR);
        }
        for (ReplayProject.CameraKeyframe key : session.project().cameraKeyframes) {
            int kx = xOf(key.tick, x, width, duration);
            int color = key == selectedCamera ? SELECTED_COLOR : CAMERA_COLOR;
            diamond(g, kx, y + lane / 2, 3, color);
        }
        if (selectedActor != null) {
            for (ReplayProject.ActorKeyframe key : selectedActor.keyframes) {
                int kx = xOf(key.tick, x, width, duration);
                int color = key == selectedActorKey ? SELECTED_COLOR : ACTOR_COLOR;
                diamond(g, kx, y + lane + lane / 2, 3, color);
            }
        }
        int cx = xOf(cursor, x, width, duration);
        g.fill(cx - 1, y - 3, cx + 1, y + height + 3, 0xFFFFFFFF);
        if (ghostTick >= 0) {
            int gx = xOf(ghostTick, x, width, duration);
            g.fill(gx, y - 3, gx + 1, y + height + 3, 0xAAFFFFFF);
            g.drawString(font, formatTicks(ghostTick), gx + 3, y - 11, 0xFFFFFFFF, true);
        }
    }

    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, color);
        }
    }
}
