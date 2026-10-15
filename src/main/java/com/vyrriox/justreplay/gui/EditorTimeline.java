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
 * scrollbar) and follows the play head while playing. The track lanes shown are a list, so each editor mode (and
 * each feature adding a lane) picks its own.
 *
 * @author vyrriox
 */
final class EditorTimeline {
    /** Ruler: a row of time labels over a strip where the ticks and the play head handle sit. */
    static final int RULER = 13;
    /** Height of the strip under the labels. */
    private static final int RULER_STRIP = 5;
    static final int BAR = 4;
    static final int LABELS = 14;

    /**
     * Rows of the timeline. Track lanes are stacked in the order given to {@link #lanes(List)}; a new kind of
     * track adds a constant here with its height and icon, and draws itself in {@link #renderLane}.
     */
    enum Lane {
        RULER(EditorTimeline.RULER, null), CUTS(13, Icon.CUT), CAMERA(10, Icon.CAMERA), ACTOR(12, Icon.PERSON), MARKERS(10, Icon.FLAG),
        /** Audio tracks: their height comes from {@link AudioLanes#height()}, they draw their own backs and labels. */
        AUDIO(0, null), BAR(4, null), NONE(0, null);

        final int height;
        final Icon icon;

        Lane(int height, Icon icon) {
            this.height = height;
            this.icon = icon;
        }
    }

    /** Every track lane, in display order: the advanced editor. */
    static final List<Lane> ALL_LANES = List.of(Lane.CUTS, Lane.CAMERA, Lane.ACTOR, Lane.MARKERS, Lane.AUDIO);
    /** The simple editor works with one camera, so it has no cut track. */
    static final List<Lane> SIMPLE_LANES = List.of(Lane.CAMERA, Lane.ACTOR, Lane.MARKERS);

    private static final int[] STEPS = {1, 2, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400, 6000, 12000, 24000, 72000};
    private static final int MIN_SPAN = 20;

    /** Lane area (without the label column). */
    int x, top, w;
    private List<Lane> lanes = ALL_LANES;
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
    /** Audio lanes drawn in the AUDIO lane, or null. */
    AudioLanes audio;

    void layout(int x, int top, int w) {
        this.x = x;
        this.top = top;
        this.w = Math.max(10, w);
    }

    /** Track lanes shown, top to bottom. */
    void lanes(List<Lane> lanes) {
        this.lanes = List.copyOf(lanes);
    }

    List<Lane> lanes() {
        return lanes;
    }

    /** Height of the timeline with its lanes: ruler, lanes, scrollbar. */
    int height() {
        int h = RULER + BAR - 1;
        for (Lane lane : lanes) {
            int lh = heightOf(lane);
            h += lh > 0 ? lh + 1 : 0;
        }
        return h;
    }

    /** Height of a lane; the audio lane grows with its tracks and is 0 without them. */
    int heightOf(Lane lane) {
        return lane == Lane.AUDIO ? (audio == null ? 0 : audio.height()) : lane.height;
    }

    /** Top of the audio lanes. */
    int audioY() {
        int y = laneY(Lane.AUDIO);
        return y >= 0 ? y : lanesBottom() + 1;
    }

    int audioHeight() {
        return lanes.contains(Lane.AUDIO) ? heightOf(Lane.AUDIO) : 0;
    }

    /** Top of the first lane. */
    int lanesY() {
        return top + RULER;
    }

    /** Top of a track lane, or -1 when it is not shown. */
    int laneY(Lane lane) {
        int y = lanesY();
        for (Lane l : lanes) {
            if (l == lane) {
                return heightOf(l) > 0 ? y : -1;
            }
            int lh = heightOf(l);
            y += lh > 0 ? lh + 1 : 0;
        }
        return -1;
    }

    /** Bottom of the last lane. */
    int lanesBottom() {
        int y = lanesY();
        for (Lane l : lanes) {
            int lh = heightOf(l);
            y += lh > 0 ? lh + 1 : 0;
        }
        return y - 1;
    }

    int barY() {
        return lanesBottom() + 2;
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

    /** Fractional, unclamped time under an x coordinate. */
    double timeAt(double mouseX) {
        return viewStart + (mouseX - x) / Math.max(1, w - 1) * viewSpan;
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
        if (my < lanesY()) {
            return Lane.RULER;
        }
        int y = lanesY();
        Lane last = Lane.BAR;
        for (Lane lane : lanes) {
            int lh = heightOf(lane);
            if (lh <= 0) {
                continue;
            }
            y += lh + 1;
            last = lane;
            if (my < y) {
                return lane;
            }
        }
        return my < barY() - 1 ? last : Lane.BAR;
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

    /** What the lanes draw this frame. */
    record Selection(ReplayProject.CameraKeyframe cameraKey, ReplayProject.Cut cut, ReplayProject.ActorData actor,
                     ReplayProject.ActorKeyframe actorKey, ReplayProject.ActorAction action) {
    }

    void render(GuiGraphics g, Font font, ReplaySession session, ReplayProject.CameraKeyframe selectedKey, ReplayProject.Cut selectedCut,
                ReplayProject.ActorData actor, ReplayProject.ActorKeyframe selectedActorKey, ReplayProject.ActorAction selectedAction,
                int ghostTick, int mouseX, int mouseY) {
        ReplayProject project = session.project();
        Selection selection = new Selection(selectedKey, selectedCut, actor, selectedActorKey, selectedAction);
        int cursor = session.playback().cursor();
        int lx = x - LABELS - 1;
        for (Lane lane : lanes) {
            if (lane == Lane.AUDIO) {
                if (audio != null && audioHeight() > 0) {
                    audio.renderBack(g, font, this);
                }
            } else {
                laneBack(g, lx, laneY(lane), lane.height, lane.icon);
            }
        }
        ruler(g, font);
        g.enableScissor(x, top, x + w, barY());
        int played = Math.min(x + w, xOf(cursor));
        if (played > x) {
            g.fill(x, lanesY(), played, lanesBottom(), 0x145AB4FF);
        }
        for (Lane lane : lanes) {
            renderLane(g, font, lane, laneY(lane), session, selection, mouseX, mouseY);
        }
        g.disableScissor();
        scrollbar(g, mouseX, mouseY);
        playHead(g, font, cursor, ghostTick);
    }

    /** Draws the content of one lane at {@code y}. */
    private void renderLane(GuiGraphics g, Font font, Lane lane, int y, ReplaySession session, Selection s, int mouseX, int mouseY) {
        ReplayProject project = session.project();
        switch (lane) {
            case CUTS -> cuts(g, font, project, y, s.cut());
            case CAMERA -> cameraKeys(g, project, y, s.cameraKey());
            case ACTOR -> actorLane(g, font, y, s.actor(), s.actorKey(), s.action());
            case MARKERS -> markers(g, font, project, y);
            case AUDIO -> {
                if (audio != null && y >= 0) {
                    audio.renderClips(g, font, this, session, mouseX, mouseY);
                }
            }
            default -> {
            }
        }
    }

    private void laneBack(GuiGraphics g, int lx, int y, int h, Icon icon) {
        Paint.rect(g, x, y, w, h, Theme.RADIUS_S, 0x24FFFFFF, 0x14FFFFFF);
        if (icon != null) {
            Paint.iconCentered(g, icon, lx, y, LABELS, h, 7, 0x99B9C2CF);
        }
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
        g.enableScissor(x - 2, top - 1, x + w + 30, lanesY());
        int strip = lanesY() - 1 - RULER_STRIP;
        int labelEnd = Integer.MIN_VALUE;
        for (int t = Math.max(0, first); t <= end; t += minor) {
            int tx = xOf(t);
            if (tx < x - 1 || tx > x + w) {
                continue;
            }
            boolean major = t % step == 0;
            if (major) {
                // A faint line beside the label, a brighter tick in the strip: the label starts right of its tick.
                g.fill(tx, top, tx + 1, strip, 0x26FFFFFF);
                g.fill(tx, strip, tx + 1, lanesY() - 1, 0x8CFFFFFF);
                String label = label(t, step);
                int lw = font.width(label);
                // The last label goes left of its tick rather than out of the timeline.
                int lx = tx + 3 + lw <= x + w + 2 ? tx + 3 : tx - 1 - lw;
                if (lx >= labelEnd + 4) {
                    g.drawString(font, label, lx, top, 0xFF8E98A6, false);
                    labelEnd = lx + lw;
                }
            } else {
                g.fill(tx, lanesY() - 3, tx + 1, lanesY() - 1, 0x33FFFFFF);
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

    private void cuts(GuiGraphics g, Font font, ReplayProject project, int y, ReplayProject.Cut selectedCut) {
        int h = Lane.CUTS.height;
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
            Paint.rect(g, sx, y + 1, sw, h - 2, Theme.RADIUS_S, Theme.withAlpha(color, live ? 0xF2 : 0xA6),
                    Theme.withAlpha(Theme.mix(color, 0xFF000000, 0.25F), live ? 0xF2 : 0xA6));
            if (cut.blend > 0 && i > 0) {
                int bx = xOf(Math.min(duration, cut.tick + cut.blend));
                Paint.rect(g, sx, y + h - 4, Math.max(2, bx - sx), 2, 1, 0xD9FFFFFF);
            }
            if (cut == selectedCut) {
                Paint.outline(g, sx - 1, y, sw + 2, h, Theme.RADIUS_S + 1, 0xFFFFFFFF);
            }
            if (camera != null && sw > 12) {
                String label = font.plainSubstrByWidth((index + 1) + " " + camera.name, sw - 5);
                if (!label.isEmpty()) {
                    g.drawString(font, label, Math.max(sx, x) + 3, y + (h - 7) / 2, 0xFF0B0F16, false);
                }
            }
        }
        EffectsPanel.renderTransitions(g, this, project, y, Lane.CUTS.height);
        if (!project.cuts.isEmpty() && project.cutsEnd >= 0) {
            int ex = xOf(Math.min(duration, project.cutTrackEnd(duration)));
            g.fill(ex, y - 1, ex + 1, y + h + 1, Timeline.SELECTED_COLOR);
            Paint.rect(g, ex - 2, y - 1, 5, 3, 1, Timeline.SELECTED_COLOR);
        }
    }

    private void cameraKeys(GuiGraphics g, ReplayProject project, int y, ReplayProject.CameraKeyframe selected) {
        ReplayProject.Camera camera = project.selected();
        if (camera == null || camera.keyframes.isEmpty()) {
            return;
        }
        int cy = y + Lane.CAMERA.height / 2;
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

    private void actorLane(GuiGraphics g, Font font, int y, ReplayProject.ActorData actor, ReplayProject.ActorKeyframe selectedKey,
                           ReplayProject.ActorAction selectedAction) {
        if (actor == null) {
            return;
        }
        int h = Lane.ACTOR.height;
        int lastLabel = -100000;
        for (ReplayProject.ActorAction action : actor.actions) {
            int ax = xOf(action.tick);
            boolean sel = action == selectedAction;
            int color = sel ? Timeline.SELECTED_COLOR : 0xFFFFB74D;
            if (action.duration > 0 && action.actionType().kind == ActionType.Kind.STATE) {
                int end = xOf(Math.min(duration, action.tick + action.duration));
                Paint.rect(g, ax, y + h - 3, Math.max(2, end - ax), 2, 1, Theme.withAlpha(color, 0xCC));
            }
            if (ax < x - 20 || ax > x + w + 2) {
                continue;
            }
            g.fill(ax, y + 1, ax + 1, y + h - 1, color);
            if (ax - lastLabel >= 16) {
                g.drawString(font, action.actionType().shortLabel, ax + 2, y + 2, Theme.withAlpha(color, 0xE6), false);
                lastLabel = ax;
            }
        }
        int cy = y + h / 2;
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

    private void markers(GuiGraphics g, Font font, ReplayProject project, int y) {
        int h = Lane.MARKERS.height;
        EffectsPanel.renderEffectKeys(g, this, project, y, h);
        int lastEnd = Integer.MIN_VALUE;
        for (ReplayProject.Marker marker : project.markers) {
            int mx = xOf(marker.tick);
            if (mx < x - 60 || mx > x + w + 2) {
                continue;
            }
            g.fill(mx, y + 1, mx + 1, y + h - 1, Timeline.MARKER_COLOR);
            Paint.rect(g, mx + 1, y + 1, 4, 3, 1, Timeline.MARKER_COLOR);
            if (mx + 7 > lastEnd) {
                String name = font.plainSubstrByWidth(marker.name, 70);
                g.drawString(font, name, mx + 7, y + (h - 7) / 2, 0xD9FFE082, false);
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
            // The handle sits in the tick strip, under the time labels, so it never hides one.
            int hy = lanesY() - 1 - RULER_STRIP;
            g.fill(cx, hy, cx + 1, lanesBottom(), 0xFFFFFFFF);
            Paint.rect(g, cx - 3, hy, 7, RULER_STRIP, 2, 0xFFFFFFFF, 0xFFDDE6F0);
        }
        if (ghostTick >= 0) {
            int gx = xOf(ghostTick);
            g.fill(gx, lanesY() - 2, gx + 1, lanesBottom(), 0x99FFFFFF);
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
