package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Draws the replay timeline: progress, markers, the cut track, keyframes of the selected camera, keyframes of the
 * selected actor and the play head. The compact form is shared by the in-game HUD; the editor uses the laned form.
 *
 * @author vyrriox
 */
public final class Timeline {
    public static final int CAMERA_COLOR = 0xFF4FC3F7;
    public static final int ACTOR_COLOR = 0xFF81C784;
    public static final int MARKER_COLOR = 0xFFFFD54F;
    public static final int SELECTED_COLOR = 0xFFFF5252;

    /** Editor lanes, top to bottom: cuts, camera keyframes, actor keyframes. */
    public static final int CUT_LANE = 11;
    public static final int KEY_LANE = 8;
    public static final int EDITOR_HEIGHT = CUT_LANE + 2 * KEY_LANE;

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
     * Compact timeline of the HUD: a thin cut strip on top, keyframes of the selected camera and actor below.
     *
     * @param ghostTick play head preview while scrubbing, or -1
     */
    public static void render(GuiGraphics g, Font font, int x, int y, int width, int height, ReplaySession session,
                              ReplayProject.CameraKeyframe selectedCamera, ReplayProject.ActorData selectedActor,
                              ReplayProject.ActorKeyframe selectedActorKey, int ghostTick) {
        int duration = Math.max(1, session.playback().duration());
        int cursor = session.playback().cursor();
        background(g, x, y, width, height, cursor, duration);
        drawCuts(g, null, x, y, width, 2, session, null);

        int lane = height / 2;
        drawMarkers(g, x, y, width, height, session, duration);
        drawCameraKeys(g, x, y + lane / 2, width, session, selectedCamera, duration);
        drawActorKeys(g, x, y + lane + lane / 2, width, selectedActor, selectedActorKey, duration);
        playHead(g, font, x, y, width, height, cursor, ghostTick, duration);
    }

    /**
     * Laned timeline of the editor, {@link #EDITOR_HEIGHT} pixels high.
     *
     * @param ghostTick play head preview while scrubbing, or -1
     */
    public static void renderEditor(GuiGraphics g, Font font, int x, int y, int width, ReplaySession session,
                                    ReplayProject.CameraKeyframe selectedKey, ReplayProject.Cut selectedCut,
                                    ReplayProject.ActorData selectedActor, ReplayProject.ActorKeyframe selectedActorKey, int ghostTick) {
        int duration = Math.max(1, session.playback().duration());
        int cursor = session.playback().cursor();
        background(g, x, y, width, EDITOR_HEIGHT, cursor, duration);
        g.fill(x, y + CUT_LANE, x + width, y + CUT_LANE + 1, 0x66FFFFFF);
        g.fill(x, y + CUT_LANE + KEY_LANE, x + width, y + CUT_LANE + KEY_LANE + 1, 0x33FFFFFF);
        drawCuts(g, font, x, y, width, CUT_LANE, session, selectedCut);
        drawMarkers(g, x, y + CUT_LANE, width, 2 * KEY_LANE, session, duration);
        drawCameraKeys(g, x, y + CUT_LANE + KEY_LANE / 2, width, session, selectedKey, duration);
        drawActorKeys(g, x, y + CUT_LANE + KEY_LANE + KEY_LANE / 2, width, selectedActor, selectedActorKey, duration);
        playHead(g, font, x, y, width, EDITOR_HEIGHT, cursor, ghostTick, duration);
    }

    /** Pixel range of the segment of a cut on the timeline: {start, end}. */
    public static int[] segment(ReplayProject project, int index, int x, int width, int duration) {
        List<ReplayProject.Cut> cuts = project.cuts;
        int start = cuts.get(index).tick;
        int end = index + 1 < cuts.size() ? cuts.get(index + 1).tick : project.cutTrackEnd(duration);
        return new int[]{xOf(start, x, width, duration), xOf(Math.min(end, duration), x, width, duration)};
    }

    private static void background(GuiGraphics g, int x, int y, int width, int height, int cursor, int duration) {
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xAA000000);
        g.fill(x, y, x + width, y + height, 0x66303030);
        g.fill(x, y, xOf(cursor, x, width, duration), y + height, 0x553F51B5);
    }

    /** Coloured segments, one per cut, with the camera name when a font is given and it fits. */
    private static void drawCuts(GuiGraphics g, Font font, int x, int y, int width, int height, ReplaySession session,
                                 ReplayProject.Cut selectedCut) {
        ReplayProject project = session.project();
        int duration = Math.max(1, session.playback().duration());
        ReplayProject.Camera selectedCamera = project.selected();
        for (int i = 0; i < project.cuts.size(); i++) {
            ReplayProject.Cut cut = project.cuts.get(i);
            ReplayProject.Camera camera = project.camera(cut.camera);
            int index = project.cameras.indexOf(camera);
            int[] seg = segment(project, i, x, width, duration);
            int color = PathRenderer.cameraColor(index);
            int alpha = camera == selectedCamera ? 0xEE000000 : 0x99000000;
            g.fill(seg[0], y, Math.max(seg[0] + 1, seg[1]), y + height, alpha | (color & 0xFFFFFF));
            if (cut.blend > 0 && i > 0) {
                int bx = xOf(Math.min(duration, cut.tick + cut.blend), x, width, duration);
                g.fill(seg[0], y + height - 2, Math.max(seg[0] + 1, bx), y + height, 0xCCFFFFFF);
            }
            if (cut == selectedCut) {
                g.renderOutline(seg[0], y, Math.max(2, seg[1] - seg[0]), height, 0xFFFFFFFF);
            }
            g.fill(seg[0], y, seg[0] + 1, y + height, 0xFF000000);
            if (font != null && camera != null) {
                String label = font.plainSubstrByWidth((index + 1) + " " + camera.name, seg[1] - seg[0] - 3);
                if (!label.isEmpty()) {
                    g.drawString(font, label, seg[0] + 2, y + (height - 8) / 2 + 1, 0xFF000000, false);
                }
            }
        }
        if (!project.cuts.isEmpty() && project.cutsEnd >= 0) {
            int ex = xOf(Math.min(duration, project.cutTrackEnd(duration)), x, width, duration);
            g.fill(ex, y, ex + 1, y + height, SELECTED_COLOR);
        }
    }

    private static void drawMarkers(GuiGraphics g, int x, int y, int width, int height, ReplaySession session, int duration) {
        for (ReplayProject.Marker marker : session.project().markers) {
            int mx = xOf(marker.tick, x, width, duration);
            g.fill(mx, y, mx + 1, y + height, MARKER_COLOR);
        }
    }

    private static void drawCameraKeys(GuiGraphics g, int x, int cy, int width, ReplaySession session,
                                       ReplayProject.CameraKeyframe selected, int duration) {
        ReplayProject.Camera camera = session.project().selected();
        if (camera == null) {
            return;
        }
        for (ReplayProject.CameraKeyframe key : camera.keyframes) {
            int kx = xOf(key.tick, x, width, duration);
            diamond(g, kx, cy, 3, key == selected ? SELECTED_COLOR : CAMERA_COLOR);
        }
    }

    private static void drawActorKeys(GuiGraphics g, int x, int cy, int width, ReplayProject.ActorData actor,
                                      ReplayProject.ActorKeyframe selected, int duration) {
        if (actor == null) {
            return;
        }
        for (ReplayProject.ActorKeyframe key : actor.keyframes) {
            int kx = xOf(key.tick, x, width, duration);
            diamond(g, kx, cy, 3, key == selected ? SELECTED_COLOR : ACTOR_COLOR);
        }
    }

    private static void playHead(GuiGraphics g, Font font, int x, int y, int width, int height, int cursor, int ghostTick, int duration) {
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
