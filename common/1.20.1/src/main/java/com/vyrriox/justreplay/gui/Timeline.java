package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Timeline helpers shared by the HUD and the editor: time formatting, tick to pixel mapping over the whole replay,
 * and the compact glass track of the in-game HUD. The editor draws its own laned timeline
 * ({@link EditorTimeline}).
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

    /** Pixel range of the segment of a cut on a whole-replay timeline: {start, end}. */
    public static int[] segment(ReplayProject project, int index, int x, int width, int duration) {
        List<ReplayProject.Cut> cuts = project.cuts;
        int start = cuts.get(index).tick;
        int end = index + 1 < cuts.size() ? cuts.get(index + 1).tick : project.cutTrackEnd(duration);
        return new int[]{xOf(start, x, width, duration), xOf(Math.min(end, duration), x, width, duration)};
    }

    /**
     * Compact track of the HUD, {@code height} pixels high: the cut strip on top, progress, markers, keyframes of
     * the selected camera and a round play head.
     */
    public static void renderCompact(GuiGraphics g, int x, int y, int width, int height, ReplaySession session) {
        ReplayProject project = session.project();
        int duration = Math.max(1, session.playback().duration());
        int cursor = session.playback().cursor();
        int trackY = y + 3;
        int trackH = height - 3;
        Paint.rect(g, x, trackY, width, trackH, trackH / 2.0F, 0x33FFFFFF, 0x1FFFFFFF);
        int cx = xOf(cursor, x, width, duration);
        if (cx > x) {
            Paint.rect(g, x, trackY, Math.max(trackH, cx - x), trackH, trackH / 2.0F, 0xE67CC4FF, 0xE62F86E0);
        }
        for (int i = 0; i < project.cuts.size(); i++) {
            ReplayProject.Cut cut = project.cuts.get(i);
            int[] seg = segment(project, i, x, width, duration);
            int color = PathRenderer.cameraColor(project.cameras.indexOf(project.camera(cut.camera)));
            g.fill(seg[0], y, Math.max(seg[0] + 1, seg[1] - 1), y + 2, Theme.withAlpha(color, 0xE6));
        }
        for (ReplayProject.Marker marker : project.markers) {
            int mx = xOf(marker.tick, x, width, duration);
            g.fill(mx, trackY, mx + 1, trackY + trackH, MARKER_COLOR);
        }
        ReplayProject.Camera camera = project.selected();
        if (camera != null) {
            for (ReplayProject.CameraKeyframe key : camera.keyframes) {
                int kx = xOf(key.tick, x, width, duration);
                Paint.icon(g, Icon.KEY, kx - 2.5F, trackY + (trackH - 5) / 2.0F, 5, 0xE6FFFFFF);
            }
        }
        float r = trackH / 2.0F + 1.5F;
        Paint.shadow(g, cx - r, trackY + trackH / 2.0F - r, 2 * r, 2 * r, r, 2, 0x80000000);
        Paint.circle(g, cx, trackY + trackH / 2.0F, r, 0xFFFFFFFF);
    }
}
