package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The multi-lane timeline of the editor: time ruler, cut track, keyframes of the selected camera, keyframes and
 * actions of the selected actor, markers, play head. The view zooms and scrolls smoothly (Ctrl + wheel, wheel,
 * scrollbar) and follows the play head while playing.
 *
 * @author vyrriox
 */
final class EditorTimeline {
    static final int RULER = 10;
    static final int CUTS = 13;
    static final int CAMERA = 10;
    static final int ACTOR = 12;
    static final int MARKERS = 10;
    static final int BAR = 4;
    static final int LABELS = 14;
    /** Ruler, lanes, scrollbar. */
    static final int HEIGHT = RULER + CUTS + CAMERA + ACTOR + MARKERS + 3 + BAR;

    enum Lane { RULER, CUTS, CAMERA, ACTOR, MARKERS, BAR, NONE }

    private static final int[] STEPS = {1, 2, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400, 6000, 12000, 24000, 72000};
    private static final int MIN_SPAN = 20;

    /** Lane area (without the label column). */
    int x, top, w;
    private int duration = 1;
    private final Anim.Value start = new Anim.Value(0.0F, 16.0F);
    private final Anim.Value span = new Anim.Value(1.0F, 16.0F);
    private boolean fitted;
    /** View of the current frame. */
    private float viewStart;
    private float viewSpan = 1.0F;
    private boolean draggingBar;
    private double barGrab;
    private String[] rulerLabels = new String[0];
    private int[] rulerTicks = new int[0];

    void layout(int x, int top, int w) {
        this.x = x;
        this.top = top;
        this.w = Math.max(10, w);
    }

    int cutsY() {
        return top + RULER;
    }

    int cameraY() {
        return cutsY() + CUTS + 1;
    }

    int actorY() {
        return cameraY() + CAMERA + 1;
    }

    int markersY() {
        return actorY() + ACTOR + 1;
    }

    int barY() {
        return markersY() + MARKERS + 2;
    }

    int bottom() {
        return barY() + BAR;
    }

    /** Updates the animated view for this frame. */
    void begin(int duration, int cursor, boolean playing) {
        this.duration = Math.max(1, duration);
        if (!fitted) {
            fitted = true;
            start.snap(0);
            span.snap(this.duration);
        }
        if (span.target() > this.duration) {
            setView(start.target(), this.duration);
        }
        if (playing && span.target() < this.duration) {
            // Keep the play head in sight while playing.
            float s = start.target(), sp = span.target();
            if (cursor > s + sp * 0.92F || cursor < s) {
                setView(cursor - sp * 0.1F, sp);
            }
        }
        viewStart = start.get();
        viewSpan = Math.max(1.0F, span.get());
    }

    private void setView(float s, float sp) {
        sp = Math.max(Math.min(MIN_SPAN, duration), Math.min(duration, sp));
        s = Math.max(0, Math.min(duration - sp, s));
        start.to(s);
        span.to(sp);
    }

    boolean zoomed() {
        return span.target() < duration - 0.5F;
    }

    /** Zooms by {@code factor} (below 1 zooms in) keeping the time under {@code anchorX} in place. */
    void zoom(double factor, double anchorX) {
        float k = (float) Math.max(0, Math.min(1, (anchorX - x) / Math.max(1, w - 1)));
        float anchor = start.target() + k * span.target();
        float sp = (float) (span.target() * factor);
        sp = Math.max(Math.min(MIN_SPAN, duration), Math.min(duration, sp));
        setView(anchor - k * sp, sp);
    }

    void fit() {
        setView(0, duration);
    }

    /** Scrolls by a fraction of the visible span. */
    void scroll(double fraction) {
        setView((float) (start.target() + span.target() * fraction), span.target());
    }

    int xOf(double tick) {
        return x + (int) Math.round((tick - viewStart) / viewSpan * (w - 1));
    }

    int tickAt(double mouseX) {
        double t = viewStart + (mouseX - x) / Math.max(1, w - 1) * viewSpan;
        return (int) Math.round(Math.max(0, Math.min(duration, t)));
    }

    boolean contains(double mx, double my) {
        return mx >= x - LABELS - 2 && mx <= x + w + 3 && my >= top - 2 && my <= bottom() + 2;
    }

    Lane laneAt(double my) {
        if (my < top - 2 || my > bottom() + 2) {
            return Lane.NONE;
        }
        if (my < cutsY()) {
            return Lane.RULER;
        }
        if (my < cameraY()) {
            return Lane.CUTS;
        }
        if (my < actorY()) {
            return Lane.CAMERA;
        }
        if (my < markersY()) {
            return Lane.ACTOR;
        }
        if (my < barY() - 1) {
            return Lane.MARKERS;
        }
        return Lane.BAR;
    }

    /** Pixel range of the segment of a cut: {start, end}. */
    int[] segment(ReplayProject project, int index) {
        List<ReplayProject.Cut> cuts = project.cuts;
        int s = cuts.get(index).tick;
        int e = index + 1 < cuts.size() ? cuts.get(index + 1).tick : project.cutTrackEnd(duration);
        return new int[]{xOf(s), xOf(Math.min(e, duration))};
    }

    // ---------------------------------------------------------------- scrollbar

    private float thumbW() {
        return Math.max(16, w * (viewSpan / duration));
    }

    private float thumbX() {
        return x + (w - thumbW()) * (viewStart / Math.max(1, duration - viewSpan));
    }

    boolean barClicked(double mx, double my) {
        if (!zoomed() || my < barY() - 2 || my > bottom() + 2 || mx < x || mx > x + w) {
            return false;
        }
        float tx = thumbX();
        barGrab = mx >= tx && mx <= tx + thumbW() ? mx - tx : thumbW() / 2;
        draggingBar = true;
        barDragged(mx);
        return true;
    }

    boolean barDragged(double mx) {
        if (!draggingBar) {
            return false;
        }
        float t = (float) ((mx - barGrab - x) / Math.max(1, w - thumbW()));
        float sp = span.target();
        float s = Math.max(0, Math.min(duration - sp, t * (duration - sp)));
        start.snap(s);
        span.snap(sp);
        return true;
    }

    boolean barReleased() {
        boolean was = draggingBar;
        draggingBar = false;
        return was;
    }

    // ---------------------------------------------------------------- render

    void render(GuiGraphics g, Font font, ReplaySession session, ReplayProject.CameraKeyframe selectedKey, ReplayProject.Cut selectedCut,
                ReplayProject.ActorData actor, ReplayProject.ActorKeyframe selectedActorKey, ReplayProject.ActorAction selectedAction,
                int ghostTick, int mouseX, int mouseY) {
        ReplayProject project = session.project();
        int cursor = session.playback().cursor();
        lanes(g, font);
        ruler(g, font);
        g.enableScissor(x, top, x + w, barY());
        int played = Math.min(x + w, xOf(cursor));
        if (played > x) {
            g.fill(x, cutsY(), played, markersY() + MARKERS, 0x145AB4FF);
        }
        cuts(g, font, project, selectedCut);
        cameraKeys(g, project, selectedKey);
        actorLane(g, font, actor, selectedActorKey, selectedAction);
        markers(g, font, project);
        g.disableScissor();
        scrollbar(g, mouseX, mouseY);
        playHead(g, font, cursor, ghostTick);
    }

    private void lanes(GuiGraphics g, Font font) {
        int lx = x - LABELS - 1;
        laneBack(g, lx, cutsY(), CUTS, Icon.CUT);
        laneBack(g, lx, cameraY(), CAMERA, Icon.CAMERA);
        laneBack(g, lx, actorY(), ACTOR, Icon.PERSON);
        laneBack(g, lx, markersY(), MARKERS, Icon.FLAG);
    }

    private void laneBack(GuiGraphics g, int lx, int y, int h, Icon icon) {
        Paint.rect(g, x, y, w, h, Theme.RADIUS_S, 0x24FFFFFF, 0x14FFFFFF);
        Paint.iconCentered(g, icon, lx, y, LABELS, h, 7, 0x99B9C2CF);
    }

    private void ruler(GuiGraphics g, Font font) {
        float pxPerTick = (w - 1) / viewSpan;
        int step = STEPS[STEPS.length - 1];
        for (int s : STEPS) {
            if (s * pxPerTick >= 46) {
                step = s;
                break;
            }
        }
        int minor = step % 5 == 0 && step * pxPerTick / 5 >= 5 ? step / 5 : step % 4 == 0 && step * pxPerTick / 4 >= 5 ? step / 4 : step;
        int first = (int) Math.floor(viewStart / minor) * minor;
        int end = (int) Math.ceil(viewStart + viewSpan);
        g.enableScissor(x - 2, top - 1, x + w + 30, cutsY());
        for (int t = Math.max(0, first); t <= end; t += minor) {
            int tx = xOf(t);
            if (tx < x - 1 || tx > x + w) {
                continue;
            }
            boolean major = t % step == 0;
            g.fill(tx, cutsY() - (major ? 4 : 2), tx + 1, cutsY() - 1, major ? 0x8CFFFFFF : 0x33FFFFFF);
            if (major && tx + 2 < x + w - 8) {
                g.drawString(font, label(t, step), tx + 2, top, 0xFF8E98A6, false);
            }
        }
        g.disableScissor();
    }

    /** Ruler label of a tick, cached per tick value. */
    private String label(int tick, int step) {
        int slot = Math.floorMod(tick / Math.max(1, step), 64);
        if (rulerLabels.length != 64) {
            rulerLabels = new String[64];
            rulerTicks = new int[64];
            java.util.Arrays.fill(rulerTicks, -1);
        }
        int key = step < 20 ? -tick - 1 : tick;
        if (rulerTicks[slot] != key || rulerLabels[slot] == null) {
            rulerTicks[slot] = key;
            int seconds = tick / 20;
            rulerLabels[slot] = step < 20 ? Timeline.formatTicks(tick) : seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        }
        return rulerLabels[slot];
    }

    private void cuts(GuiGraphics g, Font font, ReplayProject project, ReplayProject.Cut selectedCut) {
        int y = cutsY();
        ReplayProject.Camera selectedCamera = project.selected();
        for (int i = 0; i < project.cuts.size(); i++) {
            ReplayProject.Cut cut = project.cuts.get(i);
            ReplayProject.Camera camera = project.camera(cut.camera);
            int index = project.cameras.indexOf(camera);
            int[] seg = segment(project, i);
            if (seg[1] < x - 2 || seg[0] > x + w + 2) {
                continue;
            }
            int color = PathRenderer.cameraColor(index);
            boolean live = camera == selectedCamera;
            int sx = seg[0], sw = Math.max(2, seg[1] - seg[0] - 1);
            Paint.rect(g, sx, y + 1, sw, CUTS - 2, Theme.RADIUS_S, Theme.withAlpha(color, live ? 0xF2 : 0xA6),
                    Theme.withAlpha(Theme.mix(color, 0xFF000000, 0.25F), live ? 0xF2 : 0xA6));
            if (cut.blend > 0 && i > 0) {
                int bx = xOf(Math.min(duration, cut.tick + cut.blend));
                Paint.rect(g, sx, y + CUTS - 4, Math.max(2, bx - sx), 2, 1, 0xD9FFFFFF);
            }
            if (cut == selectedCut) {
                Paint.outline(g, sx - 1, y, sw + 2, CUTS, Theme.RADIUS_S + 1, 0xFFFFFFFF);
            }
            if (camera != null && sw > 12) {
                String label = font.plainSubstrByWidth((index + 1) + " " + camera.name, sw - 5);
                if (!label.isEmpty()) {
                    g.drawString(font, label, Math.max(sx, x) + 3, y + (CUTS - 7) / 2, 0xFF0B0F16, false);
                }
            }
        }
        if (!project.cuts.isEmpty() && project.cutsEnd >= 0) {
            int ex = xOf(Math.min(duration, project.cutTrackEnd(duration)));
            g.fill(ex, y - 1, ex + 1, y + CUTS + 1, Timeline.SELECTED_COLOR);
            Paint.rect(g, ex - 2, y - 1, 5, 3, 1, Timeline.SELECTED_COLOR);
        }
    }

    private void cameraKeys(GuiGraphics g, ReplayProject project, ReplayProject.CameraKeyframe selected) {
        ReplayProject.Camera camera = project.selected();
        if (camera == null || camera.keyframes.isEmpty()) {
            return;
        }
        int cy = cameraY() + CAMERA / 2;
        int color = PathRenderer.cameraColor(project.cameras.indexOf(camera));
        List<ReplayProject.CameraKeyframe> keys = camera.keyframes;
        if (keys.size() > 1) {
            int a = xOf(keys.get(0).tick), b = xOf(keys.get(keys.size() - 1).tick);
            g.fill(a, cy, b, cy + 1, Theme.withAlpha(color, 0x66));
        }
        for (ReplayProject.CameraKeyframe key : keys) {
            int kx = xOf(key.tick);
            if (kx < x - 5 || kx > x + w + 5) {
                continue;
            }
            boolean sel = key == selected;
            Paint.icon(g, Icon.KEY, kx - 4, cy - 4, 9, sel ? Theme.TEXT : color);
            if (sel) {
                Paint.icon(g, Icon.KEY, kx - 2.5F, cy - 2.5F, 6, Timeline.SELECTED_COLOR);
            }
        }
    }

    private void actorLane(GuiGraphics g, Font font, ReplayProject.ActorData actor, ReplayProject.ActorKeyframe selectedKey,
                           ReplayProject.ActorAction selectedAction) {
        if (actor == null) {
            return;
        }
        int y = actorY();
        int lastLabel = -100000;
        for (ReplayProject.ActorAction action : actor.actions) {
            int ax = xOf(action.tick);
            boolean sel = action == selectedAction;
            int color = sel ? Timeline.SELECTED_COLOR : 0xFFFFB74D;
            if (action.duration > 0 && action.actionType().kind == ActionType.Kind.STATE) {
                int end = xOf(Math.min(duration, action.tick + action.duration));
                Paint.rect(g, ax, y + ACTOR - 3, Math.max(2, end - ax), 2, 1, Theme.withAlpha(color, 0xCC));
            }
            if (ax < x - 20 || ax > x + w + 2) {
                continue;
            }
            g.fill(ax, y + 1, ax + 1, y + ACTOR - 1, color);
            if (ax - lastLabel >= 16) {
                g.drawString(font, action.actionType().shortLabel, ax + 2, y + 2, Theme.withAlpha(color, 0xE6), false);
                lastLabel = ax;
            }
        }
        int cy = y + ACTOR / 2;
        for (ReplayProject.ActorKeyframe key : actor.keyframes) {
            int kx = xOf(key.tick);
            if (kx < x - 5 || kx > x + w + 5) {
                continue;
            }
            boolean sel = key == selectedKey;
            Paint.icon(g, Icon.KEY, kx - 4, cy - 4, 9, sel ? Theme.TEXT : Timeline.ACTOR_COLOR);
            if (sel) {
                Paint.icon(g, Icon.KEY, kx - 2.5F, cy - 2.5F, 6, Timeline.SELECTED_COLOR);
            }
        }
    }

    private void markers(GuiGraphics g, Font font, ReplayProject project) {
        int y = markersY();
        int lastEnd = Integer.MIN_VALUE;
        for (ReplayProject.Marker marker : project.markers) {
            int mx = xOf(marker.tick);
            if (mx < x - 60 || mx > x + w + 2) {
                continue;
            }
            g.fill(mx, y + 1, mx + 1, y + MARKERS - 1, Timeline.MARKER_COLOR);
            Paint.rect(g, mx + 1, y + 1, 4, 3, 1, Timeline.MARKER_COLOR);
            if (mx + 7 > lastEnd) {
                String name = font.plainSubstrByWidth(marker.name, 70);
                g.drawString(font, name, mx + 7, y + (MARKERS - 7) / 2, 0xD9FFE082, false);
                lastEnd = mx + 7 + font.width(name) + 4;
            }
        }
    }

    private void scrollbar(GuiGraphics g, int mouseX, int mouseY) {
        if (!zoomed() && span.settled()) {
            return;
        }
        int y = barY();
        boolean hover = draggingBar || mouseY >= y - 2 && mouseY <= y + BAR + 2 && mouseX >= x && mouseX <= x + w;
        Paint.rect(g, x, y, w, BAR, BAR / 2.0F, 0x1AFFFFFF);
        Paint.rect(g, thumbX(), y, thumbW(), BAR, BAR / 2.0F, hover ? 0xB3FFFFFF : 0x66FFFFFF);
    }

    private void playHead(GuiGraphics g, Font font, int cursor, int ghostTick) {
        int cx = xOf(cursor);
        if (cx >= x - 1 && cx <= x + w + 1) {
            g.fill(cx, cutsY() - 2, cx + 1, markersY() + MARKERS, 0xFFFFFFFF);
            Paint.rect(g, cx - 3, top - 1, 7, 6, 2, 0xFFFFFFFF, 0xFFDDE6F0);
        }
        if (ghostTick >= 0) {
            int gx = xOf(ghostTick);
            g.fill(gx, cutsY() - 2, gx + 1, markersY() + MARKERS, 0x99FFFFFF);
            String time = Timeline.formatTicks(ghostTick);
            int tw = font.width(time) + 8;
            int bx = Math.max(x - LABELS, Math.min(x + w - tw, gx - tw / 2));
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            Paint.rect(g, bx, top - 13, tw, 11, Theme.RADIUS_S, 0xF25AB4FF, 0xF22F86E0);
            g.drawString(font, time, bx + 4, top - 11, Theme.TEXT_ON_ACCENT, false);
            g.pose().popPose();
        }
    }
}
