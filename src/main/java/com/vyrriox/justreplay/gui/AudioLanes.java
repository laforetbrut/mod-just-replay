package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.audio.AudioClip;
import com.vyrriox.justreplay.editor.audio.AudioLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPcm;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The audio lanes of the editor timeline, one per audio track under the markers: clips drawn as rounded blocks
 * with their waveform and fades. Click selects a clip, dragging its body moves it (also to another track),
 * dragging an edge trims it; edges snap to markers, cuts, the play head and other clips (Shift disables the snap).
 * S splits at the play head, Ctrl + D duplicates, Delete removes the selected clip.
 *
 * @author vyrriox
 */
final class AudioLanes {
    static final int LANE = 16;
    static final int MAX_VISIBLE = 4;
    /** Track lanes shown at once; the editor lowers it when the window is short. */
    private int maxVisible = MAX_VISIBLE;
    private static final int EDGE = 3;
    private static final int SNAP_PX = 5;
    private static final int[] COLORS = {0xFF8E7CFF, 0xFF4FD1C5, 0xFFFF9F68, 0xFFF06292, 0xFF9CCC65, 0xFF64B5F6, 0xFFFFD54F};

    private enum Mode { MOVE, TRIM_LEFT, TRIM_RIGHT }

    private final EditorScreen screen;
    private final ReplaySession session;
    AudioClip selectedClip;
    AudioTrack selectedTrack;
    private int scroll;

    private AudioClip dragClip;
    private Mode mode;
    private boolean dragMoved;
    private double pressX;
    private double grabTicks;
    private int originalStart;
    private double originalIn;
    private double originalOut;
    private int originalLength;
    private double originalOffset;
    private final List<Integer> snaps = new ArrayList<>();

    AudioLanes(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private List<AudioTrack> tracks() {
        return session.project().audioTracks;
    }

    static int color(int trackIndex) {
        return COLORS[Math.floorMod(trackIndex, COLORS.length)];
    }

    /** Sets how many track lanes are shown at once (1 to {@link #MAX_VISIBLE}); the others scroll with Shift + wheel. */
    void maxVisible(int count) {
        maxVisible = Math.max(1, Math.min(MAX_VISIBLE, count));
    }

    /** Height of the lane group under the markers, 0 without tracks. */
    int height() {
        int visible = Math.min(maxVisible, tracks().size());
        return visible == 0 ? 0 : visible * (LANE + 1);
    }

    private int visibleCount() {
        return Math.min(maxVisible, tracks().size());
    }

    private int clampScroll() {
        scroll = Math.max(0, Math.min(scroll, tracks().size() - visibleCount()));
        return scroll;
    }

    private int laneY(EditorTimeline t, int row) {
        return t.audioY() + row * (LANE + 1);
    }

    /** Track under a y coordinate, or null. */
    private AudioTrack trackAt(EditorTimeline t, double my) {
        int row = (int) Math.floor((my - t.audioY()) / (LANE + 1));
        int index = clampScroll() + row;
        if (row < 0 || row >= visibleCount() || index >= tracks().size()) {
            return null;
        }
        return tracks().get(index);
    }

    /** Keeps the selection valid after edits made elsewhere (undo of a track, project reload). */
    void validate() {
        if (selectedTrack != null && !tracks().contains(selectedTrack)) {
            selectedTrack = null;
        }
        if (selectedClip != null && trackOf(selectedClip) == null) {
            selectedClip = null;
        }
    }

    AudioTrack trackOf(AudioClip clip) {
        for (AudioTrack track : tracks()) {
            if (track.clips.contains(clip)) {
                return track;
            }
        }
        return null;
    }

    void select(AudioClip clip) {
        boolean changed = clip != selectedClip;
        selectedClip = clip;
        if (clip != null) {
            selectedTrack = trackOf(clip);
            reveal(selectedTrack);
        }
        if (changed) {
            screen.refresh();
        }
    }

    /** Scrolls the lanes so a track is visible. */
    void reveal(AudioTrack track) {
        int index = tracks().indexOf(track);
        if (index >= 0) {
            if (index < scroll) {
                scroll = index;
            } else if (index >= scroll + maxVisible) {
                scroll = index - maxVisible + 1;
            }
        }
    }

    // ---------------------------------------------------------------- render

    void renderBack(GuiGraphics g, Font font, EditorTimeline t) {
        int lx = t.x - EditorTimeline.LABELS - 1;
        int first = clampScroll();
        for (int row = 0; row < visibleCount(); row++) {
            AudioTrack track = tracks().get(first + row);
            int y = laneY(t, row);
            boolean sel = track == selectedTrack;
            Paint.rect(g, t.x, y, t.w, LANE, Theme.RADIUS_S, sel ? 0x2EFFFFFF : 0x24FFFFFF, 0x14FFFFFF);
            boolean audible = track.audible(tracks());
            int iconColor = audible ? Theme.withAlpha(color(first + row), 0xCC) : 0x66B9C2CF;
            Paint.iconCentered(g, audible ? Icon.SPEAKER : Icon.SPEAKER_OFF, lx, y, EditorTimeline.LABELS, LANE, 7, iconColor);
        }
        if (tracks().size() > maxVisible) {
            // Thin indicator of the visible part of the track list, right of the lanes.
            int h = height();
            int thumb = Math.max(4, h * maxVisible / tracks().size());
            int ty = t.audioY() + (h - thumb) * first / Math.max(1, tracks().size() - maxVisible);
            g.fill(t.x + t.w + 1, t.audioY(), t.x + t.w + 3, t.audioY() + h - 1, 0x1AFFFFFF);
            g.fill(t.x + t.w + 1, ty, t.x + t.w + 3, ty + thumb - 1, 0x80FFFFFF);
        }
    }

    void renderClips(GuiGraphics g, Font font, EditorTimeline t, ReplaySession s, int mouseX, int mouseY) {
        int first = clampScroll();
        for (int row = 0; row < visibleCount(); row++) {
            AudioTrack track = tracks().get(first + row);
            int y = laneY(t, row);
            boolean audible = track.audible(tracks());
            if (track.clips.isEmpty()) {
                g.drawString(font, Paint.ellipsize(font, track.name, t.w - 8), t.x + 4, y + (LANE - 7) / 2, 0x597F8896, false);
            }
            for (AudioClip clip : track.clips) {
                renderClip(g, font, t, track, clip, color(first + row), y, audible);
            }
        }
    }

    private void renderClip(GuiGraphics g, Font font, EditorTimeline t, AudioTrack track, AudioClip clip, int color, int y, boolean audible) {
        int x0 = t.xOf(clip.start);
        int x1 = t.xOf(clip.end());
        if (x1 < t.x - 2 || x0 > t.x + t.w + 2) {
            return;
        }
        int w = Math.max(3, x1 - x0);
        boolean sel = clip == selectedClip;
        int alpha = audible ? (sel ? 0xE6 : 0xB3) : 0x59;
        Paint.rect(g, x0, y + 1, w, LANE - 2, Theme.RADIUS_S, Theme.withAlpha(color, alpha),
                Theme.withAlpha(Theme.mix(color, 0xFF000000, 0.3F), alpha));
        AudioPcm pcm = AudioLibrary.get(clip.file);
        int cy = y + LANE / 2;
        int half = LANE / 2 - 2;
        int from = Math.max(t.x, x0 + 1);
        int to = Math.min(t.x + t.w, x0 + w - 1);
        if (pcm != null) {
            int wave = Theme.withAlpha(Theme.mix(color, 0xFF0B0F16, 0.55F), audible ? 0xE6 : 0x80);
            float level = Math.min(1.0F, clip.volume * track.volume);
            for (int px = from; px < to; px++) {
                double a = t.timeAt(px);
                double b = t.timeAt(px + 1);
                float peak = peak(clip, pcm, a, b) * level * clip.fadeGain((a + b) / 2 - clip.start);
                int h = Math.round(peak * half);
                if (h > 0) {
                    g.fill(px, cy - h, px + 1, cy + h + 1, wave);
                }
            }
        } else {
            AudioLibrary.State state = AudioLibrary.state(clip.file);
            if (state == AudioLibrary.State.MISSING || state == AudioLibrary.State.FAILED) {
                Paint.outline(g, x0, y + 1, w, LANE - 2, Theme.RADIUS_S, 0xCCFF6B6B);
            }
            g.fill(from, cy, Math.max(from, to), cy + 1, 0x66000000);
        }
        if (clip.loop && clip.span() > 0) {
            // Loop boundaries.
            double period = clip.span() / clip.speed();
            double firstWrap = (clip.span() - clip.offset % clip.span()) / clip.speed();
            int marks = 0;
            for (double at = clip.start + firstWrap; at < clip.end() && marks < 400; at += period, marks++) {
                int lx = t.xOf(at);
                if (lx > from && lx < to) {
                    g.fill(lx, y + 2, lx + 1, y + LANE - 2, 0x8CFFFFFF);
                }
            }
        }
        fadeShade(g, t, clip, y, from, to);
        if (w > 30) {
            String name = Paint.ellipsize(font, label(clip), Math.max(0, Math.min(to, x0 + w) - Math.max(from, x0) - 6));
            if (!name.isEmpty()) {
                g.drawString(font, name, Math.max(from, x0) + 3, y + 2, audible ? 0xF2FFFFFF : 0x99FFFFFF, false);
            }
        }
        if (sel) {
            Paint.outline(g, x0 - 1, y, w + 2, LANE, Theme.RADIUS_S + 1, 0xFFFFFFFF);
        }
    }

    /** Darkens the part of the clip above the fade ramps. */
    private static void fadeShade(GuiGraphics g, EditorTimeline t, AudioClip clip, int y, int from, int to) {
        if (clip.fadeIn <= 0 && clip.fadeOut <= 0) {
            return;
        }
        int top = y + 1;
        int h = LANE - 2;
        int a = Math.max(from, t.xOf(clip.start));
        int b = Math.min(to, t.xOf(clip.start + clip.fadeIn));
        for (int px = a; px < b; px++) {
            float gain = clip.fadeGain(t.timeAt(px + 0.5) - clip.start);
            int dark = Math.round((1.0F - gain) * h);
            if (dark > 0) {
                g.fill(px, top, px + 1, top + dark, 0x8C000000);
            }
        }
        a = Math.max(from, t.xOf(clip.end() - clip.fadeOut));
        b = Math.min(to, t.xOf(clip.end()));
        for (int px = a; px < b; px++) {
            float gain = clip.fadeGain(t.timeAt(px + 0.5) - clip.start);
            int dark = Math.round((1.0F - gain) * h);
            if (dark > 0) {
                g.fill(px, top, px + 1, top + dark, 0x8C000000);
            }
        }
    }

    /** Peak level of the source heard between two timeline times. */
    private static float peak(AudioClip clip, AudioPcm pcm, double a, double b) {
        double src = clip.sourceAt(Math.max(a, clip.start));
        if (Double.isNaN(src)) {
            return 0.0F;
        }
        long f0 = (long) (src * AudioPcm.PER_TICK);
        long n = Math.max(1, (long) ((Math.min(b, clip.end()) - Math.max(a, clip.start)) * clip.speed() * AudioPcm.PER_TICK));
        long outFrame = (long) (clip.out * AudioPcm.PER_TICK);
        if (clip.loop && f0 + n > outFrame) {
            long inFrame = (long) (clip.in * AudioPcm.PER_TICK);
            long rest = Math.min(n - (outFrame - f0), outFrame - inFrame);
            return Math.max(pcm.peak(f0, outFrame), pcm.peak(inFrame, inFrame + rest));
        }
        return pcm.peak(f0, Math.min(outFrame, f0 + n));
    }

    static String label(AudioClip clip) {
        String name = clip.file;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    Component tooltip(EditorTimeline t, double mx, double my) {
        AudioTrack track = trackAt(t, my);
        if (track == null) {
            return null;
        }
        AudioClip clip = clipAt(t, track, mx);
        if (clip == null) {
            return Component.translatable("justreplay.audio.lane.tip", track.name);
        }
        String state = switch (AudioLibrary.state(clip.file)) {
            case LOADING -> " " + Component.translatable("justreplay.audio.loading").getString();
            case MISSING, FAILED -> " " + Component.translatable("justreplay.audio.missing").getString();
            default -> "";
        };
        return Component.literal(label(clip) + "  " + Timeline.formatTicks(clip.start) + " +" + Timeline.formatTicks(clip.length())
                + (clip.loop ? "  loop" : "") + (clip.speed() != 1.0F ? String.format(Locale.ROOT, "  x%.2f", clip.speed()) : "") + state);
    }

    // ---------------------------------------------------------------- input

    private AudioClip clipAt(EditorTimeline t, AudioTrack track, double mx) {
        for (int i = track.clips.size() - 1; i >= 0; i--) {
            AudioClip clip = track.clips.get(i);
            int x0 = t.xOf(clip.start);
            int x1 = Math.max(x0 + 3, t.xOf(clip.end()));
            if (mx >= x0 - EDGE && mx <= x1 + EDGE) {
                if (mx >= x0 && mx <= x1 || clip == selectedClip) {
                    return clip;
                }
            }
        }
        return null;
    }

    /** Click in the audio lanes: selects and grabs a clip. False on an empty spot (the screen scrubs then). */
    boolean mouseClicked(EditorTimeline t, double mx, double my) {
        AudioTrack track = trackAt(t, my);
        if (track == null) {
            return false;
        }
        AudioClip clip = clipAt(t, track, mx);
        if (clip == null) {
            boolean changed = selectedClip != null || selectedTrack != track;
            selectedClip = null;
            selectedTrack = track;
            if (changed) {
                screen.refresh();
            }
            return false;
        }
        int x0 = t.xOf(clip.start);
        int x1 = Math.max(x0 + 3, t.xOf(clip.end()));
        boolean wide = x1 - x0 >= 10;
        mode = wide && Math.abs(mx - x0) <= EDGE ? Mode.TRIM_LEFT : wide && Math.abs(mx - x1) <= EDGE ? Mode.TRIM_RIGHT : Mode.MOVE;
        dragClip = clip;
        dragMoved = false;
        pressX = mx;
        grabTicks = t.timeAt(mx) - clip.start;
        originalStart = clip.start;
        originalIn = clip.in;
        originalOut = clip.out;
        originalLength = Math.max(1, clip.length);
        originalOffset = clip.offset;
        collectSnaps(clip);
        select(clip);
        return true;
    }

    private void collectSnaps(AudioClip except) {
        snaps.clear();
        ReplayProject project = session.project();
        snaps.add(0);
        snaps.add(session.playback().cursor());
        for (ReplayProject.Marker marker : project.markers) {
            snaps.add(marker.tick);
        }
        for (ReplayProject.Cut cut : project.cuts) {
            snaps.add(cut.tick);
        }
        for (AudioTrack track : tracks()) {
            for (AudioClip clip : track.clips) {
                if (clip != except) {
                    snaps.add(clip.start);
                    snaps.add((int) Math.round(clip.end()));
                }
            }
        }
    }

    /** Nearest snap target within reach of {@code tick}, or {@code tick} itself. */
    private double snap(EditorTimeline t, double tick) {
        if (Screen.hasShiftDown()) {
            return tick;
        }
        int px = t.xOf(tick);
        double best = tick;
        int bestDistance = SNAP_PX + 1;
        for (int candidate : snaps) {
            int d = Math.abs(t.xOf(candidate) - px);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    boolean dragging() {
        return dragClip != null;
    }

    boolean mouseDragged(EditorTimeline t, double mx, double my) {
        if (dragClip == null) {
            return false;
        }
        if (!dragMoved && Math.abs(mx - pressX) < 3) {
            return true;
        }
        dragMoved = true;
        AudioClip clip = dragClip;
        double time = t.timeAt(mx);
        int duration = session.playback().duration();
        switch (mode) {
            case MOVE -> {
                double start = time - grabTicks;
                double snappedStart = snap(t, start);
                double snappedEnd = snap(t, start + clip.length());
                if (snappedStart == start && snappedEnd != start + clip.length()) {
                    start = snappedEnd - clip.length();
                } else {
                    start = snappedStart;
                }
                clip.start = (int) Math.round(Math.max(0, Math.min(duration, start)));
                AudioTrack target = trackAt(t, my);
                AudioTrack current = trackOf(clip);
                if (target != null && current != null && target != current) {
                    current.clips.remove(clip);
                    target.clips.add(clip);
                    selectedTrack = target;
                }
            }
            case TRIM_LEFT -> {
                double end = originalStart + (clip.loop ? originalLength : (originalOut - originalIn) / clip.speed());
                int start = (int) Math.round(snap(t, time));
                int min = clip.loop ? 0 : (int) Math.ceil(originalStart - originalIn / clip.speed());
                start = Math.max(Math.max(0, min), Math.min((int) Math.floor(end) - 1, start));
                int delta = start - originalStart;
                clip.start = start;
                if (clip.loop) {
                    clip.length = Math.max(1, originalLength - delta);
                    double span = clip.span();
                    clip.offset = span <= 0 ? 0 : floorMod(originalOffset + delta * clip.speed(), span);
                } else {
                    clip.in = Math.max(0, Math.min(originalOut - 0.05, originalIn + delta * clip.speed()));
                }
                clip.clampFades();
            }
            case TRIM_RIGHT -> {
                int end = (int) Math.round(snap(t, time));
                end = Math.max(clip.start + 1, end);
                if (clip.loop) {
                    clip.length = end - clip.start;
                } else {
                    AudioPcm pcm = AudioLibrary.get(clip.file);
                    double max = pcm != null ? pcm.lengthTicks() : Math.max(originalOut, clip.out);
                    clip.out = Math.max(clip.in + 0.05, Math.min(max, clip.in + (end - clip.start) * clip.speed()));
                }
                clip.clampFades();
            }
        }
        return true;
    }

    private static double floorMod(double value, double modulus) {
        double r = value % modulus;
        return r < 0 ? r + modulus : r;
    }

    boolean mouseReleased() {
        if (dragClip == null) {
            return false;
        }
        dragClip = null;
        for (AudioTrack track : tracks()) {
            track.sort();
        }
        session.saveProject();
        if (dragMoved) {
            screen.refresh();
        }
        return true;
    }

    /** Shift + wheel over the lanes scrolls the track list when it is longer than the lanes. */
    boolean mouseScrolled(double sy) {
        if (tracks().size() <= maxVisible || !Screen.hasShiftDown()) {
            return false;
        }
        scroll += sy > 0 ? -1 : 1;
        clampScroll();
        return true;
    }

    boolean keyPressed(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_S && !Screen.hasControlDown()) {
            return splitAtPlayHead();
        }
        if (keyCode == GLFW.GLFW_KEY_D && Screen.hasControlDown() && selectedClip != null) {
            duplicateSelected();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- edits

    /** Splits the selected clip at the play head, or every clip under it when none is selected there. */
    boolean splitAtPlayHead() {
        int cursor = session.playback().cursor();
        boolean any = false;
        if (selectedClip != null && selectedClip.covers(cursor)) {
            AudioTrack track = trackOf(selectedClip);
            AudioClip right = selectedClip.splitAt(cursor);
            if (track != null && right != null) {
                track.clips.add(right);
                track.sort();
                selectedClip = right;
                any = true;
            }
        } else {
            for (AudioTrack track : tracks()) {
                for (AudioClip clip : new ArrayList<>(track.clips)) {
                    AudioClip right = clip.splitAt(cursor);
                    if (right != null) {
                        track.clips.add(right);
                        any = true;
                    }
                }
                track.sort();
            }
        }
        if (any) {
            session.saveProject();
            screen.refresh();
        }
        return any;
    }

    void duplicateSelected() {
        AudioTrack track = trackOf(selectedClip);
        if (track == null) {
            return;
        }
        AudioClip copy = selectedClip.copy();
        copy.start = (int) Math.ceil(selectedClip.end());
        track.clips.add(copy);
        track.sort();
        selectedClip = copy;
        session.saveProject();
        screen.refresh();
    }

    /** Deletes the selected clip; false when no clip is selected. */
    boolean deleteSelected() {
        AudioTrack track = trackOf(selectedClip);
        if (track == null) {
            return false;
        }
        track.clips.remove(selectedClip);
        selectedClip = null;
        session.saveProject();
        screen.refresh();
        return true;
    }
}
