package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.client.JRKeys;
import com.vyrriox.justreplay.config.JRConfig.EditorMode;
import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.MarkerRenderer;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListPopup;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Markers" tab of the editor: markers at the play head, over a stretch of time, on a point of the world or on an
 * entity, each with a name, a note and a colour. Lists them, edits the selected one (name, note, time, place, colour)
 * and draws the markers lane of the timeline, where a marker drags to another time and the ends of a range drag to
 * change it.
 *
 * @author vyrriox
 */
final class MarkersPanel {
    private static final int CUSTOM = -1;
    private static String selectedId;

    static {
        // The selected marker is drawn bright, with its note, while the editor or one of its tools is open.
        MarkerRenderer.highlight(() -> Minecraft.getInstance().screen instanceof EditorScreen || EntityTool.isActive() ? selectedId : null);
    }

    private enum Drag { MOVE, START, END }

    private final EditorScreen screen;
    private final ReplaySession session;
    private Marker dragging;
    private Drag dragMode;
    private boolean dragMoved;
    private double pressX;
    private double grab;

    MarkersPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** Selects a marker by id (null for none); kept across rebuilds and reopenings. */
    static void select(String id) {
        selectedId = id;
    }

    private ReplayProject project() {
        return session.project();
    }

    private void save() {
        session.saveProject();
    }

    private static boolean simple() {
        return EditorScreen.mode() == EditorMode.SIMPLE;
    }

    Marker selected() {
        return selectedId == null ? null : project().marker(selectedId);
    }

    private int cursor() {
        return session.playback().cursor();
    }

    private int duration() {
        return Math.max(1, session.playback().duration());
    }

    private GlassButton button(Component label, Icon icon, Component tip, GlassButton.OnPress action) {
        GlassButton b = new GlassButton(label, action).icon(icon).tip(tip);
        if (simple()) {
            b.setHeight(18);
        }
        return b;
    }

    private int rowHeight() {
        return simple() ? 18 : Theme.ROW;
    }

    static Icon icon(Marker m) {
        if (m.isPoint()) {
            return Icon.PIN;
        }
        if (m.isEntity()) {
            return Icon.TARGET;
        }
        return m.isRange() ? Icon.CLOCK : Icon.FLAG;
    }

    private static String label(Marker m) {
        return m.name.isBlank() ? tr("justreplay.marker.unnamed") : m.name;
    }

    /** {@code 0:12.0}, or {@code 0:12.0-0:20.0} for a stretch of time. */
    static String timeText(Marker m) {
        return m.isRange() ? Timeline.formatTicks(m.tick) + "-" + Timeline.formatTicks(m.end) : Timeline.formatTicks(m.tick);
    }

    // ---------------------------------------------------------------- tab

    void build(Form f) {
        ReplayProject p = project();
        f.header(tc("justreplay.editor.markers"), Icon.FLAG);
        if (p.markers.isEmpty()) {
            f.paragraph(Component.translatable("justreplay.marker.help", JRKeys.MARKER.getTranslatedKeyMessage()), Theme.TEXT_MUTED);
        }
        f.put(button(tc("justreplay.marker.add.instant"), Icon.FLAG, tc("justreplay.marker.add.instant.tip"), b -> addAtPlayHead())
                .style(GlassButton.Style.PRIMARY), 0, 2);
        f.put(button(tc("justreplay.marker.add.range"), Icon.CLOCK, tc("justreplay.marker.add.range.tip"), b -> addRange())
                .style(GlassButton.Style.PRIMARY), 1, 2);
        f.next(rowHeight());
        f.put(button(tc("justreplay.marker.add.point"), Icon.PIN, tc("justreplay.marker.add.point.tip"), b -> addPoint()), 0, 2);
        f.put(button(tc("justreplay.marker.add.entity"), Icon.TARGET, tc("justreplay.marker.add.entity.tip"), b -> addEntity()), 1, 2);
        f.next(rowHeight());
        f.full(new GlassSwitch(tc("justreplay.marker.show"), () -> project().showMarkers, () -> {
            project().showMarkers = !project().showMarkers;
            save();
        }).icon(Icon.EYE).tip(tc("justreplay.marker.show.tip")));
        for (Marker m : p.markers) {
            f.full(new ListRow(Component.literal(label(m)), b -> {
                selectedId = m.id;
                session.playback().seek(m.tick);
                screen.refresh();
            }).dot(0xFF000000 | m.color).detail(timeText(m)).icon(icon(m))
                    .action(() -> Icon.TRASH, tc("justreplay.marker.delete.tip"), () -> delete(m))
                    .selected(() -> m.id.equals(selectedId)).tip(tc("justreplay.marker.row.tip")));
        }
        Marker selected = selected();
        if (selected == null) {
            if (!p.markers.isEmpty()) {
                f.paragraph(tc("justreplay.marker.select"), Theme.TEXT_MUTED);
            }
            return;
        }
        buildSelected(f, selected);
    }

    private void buildSelected(Form f, Marker m) {
        f.header(Component.literal(label(m)), icon(m));
        f.put(button(Component.empty(), Icon.EDIT, tc("justreplay.marker.rename.tip"), b -> rename(m)), 0, 4);
        f.put(button(Component.empty(), Icon.COPY, tc("justreplay.marker.duplicate.tip"), b -> duplicate(m)), 1, 4);
        f.put(button(Component.empty(), Icon.SKIP_START, tc("justreplay.marker.goto.tip"), b -> session.playback().seek(m.tick)), 2, 4);
        f.put(button(Component.empty(), Icon.TRASH, tc("justreplay.marker.delete.tip"),
                b -> screen.confirm(tc("justreplay.marker.delete.confirm"), () -> delete(m))).style(GlassButton.Style.DANGER), 3, 4);
        f.next(rowHeight());

        f.header(tc("justreplay.marker.note"), Icon.EDIT);
        if (m.note.isBlank()) {
            f.paragraph(tc("justreplay.marker.note.none"), Theme.TEXT_MUTED);
        } else {
            f.paragraph(Component.literal(m.note), Theme.TEXT_DIM);
        }
        f.full(button(tc("justreplay.marker.note.edit"), Icon.EDIT, tc("justreplay.marker.note.edit.tip"), b -> editNote(m)));

        f.header(tc("justreplay.marker.time"), Icon.CLOCK);
        f.text(() -> m.isRange()
                ? Component.translatable("justreplay.marker.time.range", Timeline.formatTicks(m.tick), Timeline.formatTicks(m.end),
                String.format(Locale.ROOT, "%.1f", m.length() / 20.0))
                : Component.translatable("justreplay.marker.time.at", Timeline.formatTicks(m.tick)), Theme.TEXT);
        f.put(button(tc("justreplay.marker.move_here"), Icon.FLAG, tc("justreplay.marker.move_here.tip"), b -> moveHere(m)), 0, 2);
        f.put(button(tc("justreplay.marker.end_here"), Icon.SKIP_END, tc("justreplay.marker.end_here.tip"), b -> endHere(m)), 1, 2);
        f.next(rowHeight());
        if (m.isRange()) {
            f.put(button(tc("justreplay.marker.start_here"), Icon.SKIP_START, tc("justreplay.marker.start_here.tip"), b -> startHere(m)), 0, 2);
            f.put(button(tc("justreplay.marker.clear_end"), Icon.CLOSE, tc("justreplay.marker.clear_end.tip"), b -> {
                m.clearEnd();
                save();
                screen.refresh();
            }), 1, 2);
            f.next(rowHeight());
        }

        f.header(tc("justreplay.marker.place"), Icon.PIN);
        f.text(() -> placeLine(m), Theme.TEXT_DIM);
        f.text(() -> m.isEntity() && MarkerRenderer.entity(session, m) == null ? tc("justreplay.marker.entity.missing")
                : m.isPoint() && !m.dimension.isEmpty() && !m.dimension.equals(MarkerRenderer.dimension())
                ? tc("justreplay.marker.point.elsewhere") : null, Theme.WARNING);
        f.put(button(tc("justreplay.marker.place.world"), Icon.PIN, tc("justreplay.marker.place.world.tip"), b -> placeInWorld(m)), 0, 2);
        f.put(button(tc("justreplay.marker.place.entity"), Icon.TARGET, tc("justreplay.marker.place.entity.tip"), b -> pickEntity(m)), 1, 2);
        f.next(rowHeight());
        f.put(button(tc("justreplay.marker.place.camera"), Icon.CAMERA, tc("justreplay.marker.place.camera.tip"), b -> atCamera(m)), 0, 2);
        f.put(button(tc("justreplay.marker.place.actor"), Icon.PUZZLE, tc("justreplay.marker.place.actor.tip"), b -> pickActor(m, b))
                .enabled(!project().actors.isEmpty()), 1, 2);
        f.next(rowHeight());
        f.put(button(tc("justreplay.marker.place.recorder"), Icon.PERSON, tc("justreplay.marker.place.recorder.tip"),
                b -> follow(m, Marker.RECORDER, tr("justreplay.marker.recorder"))), 0, 2);
        f.put(button(tc("justreplay.marker.unpin"), Icon.CLOSE, tc("justreplay.marker.unpin.tip"), b -> {
            m.unpin();
            save();
            screen.refresh();
        }).enabled(m.isPoint() || m.isEntity()), 1, 2);
        f.next(rowHeight());

        f.header(tc("justreplay.marker.color"), Icon.EYE);
        f.full(colors(m));
    }

    private Component placeLine(Marker m) {
        if (m.isPoint()) {
            String at = String.format(Locale.ROOT, "%.1f %.1f %.1f", m.x, m.y, m.z);
            return m.dimension.isEmpty() ? Component.translatable("justreplay.marker.place.point", at)
                    : Component.translatable("justreplay.marker.place.point.in", at, m.dimension);
        }
        if (m.isEntity()) {
            return Component.translatable("justreplay.marker.place.on", m.targetName.isBlank() ? m.target : m.targetName);
        }
        return tc("justreplay.marker.place.none");
    }

    // ---------------------------------------------------------------- edits

    /** Asks for a name, then adds a marker at the play head (the editor's marker button too). */
    void addAtPlayHead() {
        int tick = cursor();
        Minecraft.getInstance().setScreen(new TextInputScreen(screen, tc("justreplay.editor.marker"), tc("justreplay.editor.marker.name"),
                project().nextMarkerName(), name -> added(Marker.instant(tick, name))).maxLength(Marker.MAX_NAME));
    }

    private void added(Marker m) {
        project().addMarker(m);
        selectedId = m.id;
        save();
        screen.refresh();
    }

    /** Five seconds from the play head, or the last five seconds when the play head is at the end. */
    private void addRange() {
        int duration = duration();
        int from = Math.min(cursor(), duration - 1);
        int to = Math.min(duration, from + Marker.DEFAULT_LENGTH);
        if (to - from < 20) {
            from = Math.max(0, to - Marker.DEFAULT_LENGTH);
        }
        if (Marker.checkRange(from, to, duration) != Marker.RangeCheck.OK) {
            refused(Marker.checkRange(from, to, duration));
            return;
        }
        added(Marker.range(from, to, duration, project().nextMarkerName()));
    }

    private void addPoint() {
        EntityTool.startMarker(screen, session, EntityTool.Mode.MARK_POINT, null,
                p -> added(Marker.point(cursor(), p.x, p.y, p.z, MarkerRenderer.dimension(), project().nextMarkerName())));
    }

    /** The Spot button's tool, or the Entity button's. */
    void startTool(boolean entity) {
        if (entity) {
            addEntity();
        } else {
            addPoint();
        }
    }

    private void addEntity() {
        EntityTool.startMarker(screen, session, EntityTool.Mode.MARK_ENTITY, e -> {
            String[] target = targetOf(e);
            added(Marker.entity(cursor(), target[0], target[1], project().nextMarkerName()));
        }, null);
    }

    private void rename(Marker m) {
        Minecraft.getInstance().setScreen(new TextInputScreen(screen, tc("justreplay.marker.rename"), tc("justreplay.editor.marker.name"),
                m.name, s -> {
            m.name = s;
            save();
        }).maxLength(Marker.MAX_NAME));
    }

    private void editNote(Marker m) {
        String current = m.note.replace("\n", "\\n");
        Minecraft.getInstance().setScreen(new TextInputScreen(screen, tc("justreplay.marker.note.title"), tc("justreplay.marker.note.label"),
                current, s -> {
            m.note = s.replace("\\n", "\n").strip();
            m.normalize();
            save();
        }).maxLength(Marker.MAX_NOTE));
    }

    private void duplicate(Marker m) {
        Marker copy = m.copy();
        copy.name = project().nextMarkerName();
        added(copy);
    }

    void delete(Marker m) {
        project().removeMarker(m.id);
        if (m.id.equals(selectedId)) {
            selectedId = null;
        }
        save();
        screen.refresh();
    }

    /** Deletes the selected marker; false when none is selected. */
    boolean deleteSelected() {
        Marker m = selected();
        if (m == null) {
            return false;
        }
        delete(m);
        return true;
    }

    private void moveHere(Marker m) {
        if (m.moveTo(cursor(), duration())) {
            save();
            screen.refresh();
        }
    }

    private void endHere(Marker m) {
        applyRange(m, m.tick, cursor());
    }

    private void startHere(Marker m) {
        applyRange(m, cursor(), m.end);
    }

    private void applyRange(Marker m, int from, int to) {
        Marker.RangeCheck check = m.setRange(from, to, duration());
        if (check != Marker.RangeCheck.OK) {
            refused(check);
            return;
        }
        save();
        screen.refresh();
    }

    private void refused(Marker.RangeCheck check) {
        screen.status(tc("justreplay.marker.range." + check.name().toLowerCase(Locale.ROOT)), Theme.WARNING);
    }

    private void placeInWorld(Marker m) {
        String id = m.id;
        EntityTool.startMarker(screen, session, EntityTool.Mode.MARK_POINT, null, p -> {
            Marker target = project().marker(id);
            if (target != null) {
                target.placeAt(p.x, p.y, p.z, MarkerRenderer.dimension());
                save();
                screen.refresh();
            }
        });
    }

    private void pickEntity(Marker m) {
        String id = m.id;
        EntityTool.startMarker(screen, session, EntityTool.Mode.MARK_ENTITY, e -> {
            Marker target = project().marker(id);
            if (target != null) {
                String[] t = targetOf(e);
                follow(target, t[0], t[1]);
            }
        }, null);
    }

    /** The camera position, where the view of the editor is. */
    private void atCamera(Marker m) {
        Vec3 at = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        m.placeAt(at.x, at.y, at.z, MarkerRenderer.dimension());
        save();
        screen.refresh();
    }

    private void pickActor(Marker m, net.minecraft.client.gui.components.Button b) {
        List<ReplayProject.ActorData> actors = project().actors;
        List<String> labels = new ArrayList<>();
        for (ReplayProject.ActorData actor : actors) {
            labels.add(OverlaysPanel.actorName(actor));
        }
        screen.openPopup(new ListPopup(b.getX(), b.getY(), b.getWidth(), b.getHeight(), screen.width, screen.height,
                Minecraft.getInstance().font, labels, -1, i -> {
            screen.closePopup();
            ReplayProject.ActorData actor = actors.get(i);
            follow(m, Marker.ACTOR_PREFIX + actor.id, OverlaysPanel.actorName(actor));
        }));
    }

    private void follow(Marker m, String target, String name) {
        m.follow(target, name);
        save();
        screen.refresh();
    }

    /** Target and readable name of a picked entity: a project actor, the recording player, or a recorded entity. */
    private String[] targetOf(Entity e) {
        if (ActorManager.isActorEntity(e)) {
            for (ReplayProject.ActorData actor : project().actors) {
                if (session.actors().entityOf(actor) == e) {
                    return new String[]{Marker.ACTOR_PREFIX + actor.id, OverlaysPanel.actorName(actor)};
                }
            }
        }
        if (e == session.findRecorder(Minecraft.getInstance())) {
            return new String[]{Marker.RECORDER, tr("justreplay.marker.recorder")};
        }
        return new String[]{e.getUUID().toString(), EntitiesPanel.labelOf(e)};
    }

    /** Colour selector: the presets and a custom hex value. */
    private GlassButton colors(Marker m) {
        List<Integer> values = new ArrayList<>();
        for (int c : Marker.COLORS) {
            values.add(c);
        }
        if (!values.contains(m.color)) {
            values.add(m.color);
        }
        values.add(CUSTOM);
        Component caption = tc("justreplay.marker.color");
        return new Dropdown<>(screen, caption, values, m.color, MarkersPanel::colorLabel, v -> {
            if (v == CUSTOM) {
                Minecraft.getInstance().setScreen(new TextInputScreen(screen, caption, tc("justreplay.overlay.color.hex"),
                        String.format(Locale.ROOT, "%06X", m.color), s -> {
                    Integer parsed = OverlaysPanel.parseHex(s);
                    if (parsed != null) {
                        m.color = parsed;
                        save();
                    }
                }));
                return;
            }
            m.color = v;
            save();
            screen.refresh();
        }).icon(Icon.EDIT);
    }

    private static Component colorLabel(Integer value) {
        if (value == CUSTOM) {
            return tc("justreplay.overlay.color.custom");
        }
        for (int i = 0; i < Marker.COLORS.length; i++) {
            if (Marker.COLORS[i] == value) {
                return tc("justreplay.marker.color." + Marker.COLOR_NAMES[i]);
            }
        }
        return Component.literal(String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF));
    }

    // ---------------------------------------------------------------- timeline lane

    /** Draws every marker, then the names, each in the room left before the next marker. */
    void renderLane(GuiGraphicsExtractor g, Font font, EditorTimeline t, int y, int h) {
        List<Marker> markers = project().markers;
        for (Marker m : markers) {
            int x0 = t.xOf(m.tick);
            int x1 = m.isRange() ? t.xOf(m.end) : x0;
            if (x1 < t.x - 60 || x0 > t.x + t.w + 2) {
                continue;
            }
            boolean sel = m.id.equals(selectedId);
            int color = 0xFF000000 | m.color;
            if (m.isRange()) {
                Paint.rect(g, x0, y + h - 4, Math.max(2, x1 - x0), 3, 1, Theme.withAlpha(color, sel ? 0xF2 : 0x99));
                g.fill(x1, y + h - 6, x1 + 1, y + h - 1, Theme.withAlpha(color, 0xCC));
            }
            g.fill(x0, y + 1, x0 + 1, y + h - 1, sel ? 0xFFFFFFFF : color);
            if (m.isPoint() || m.isEntity()) {
                Paint.icon(g, icon(m), x0 + 1, y, 6, sel ? 0xFFFFFFFF : color);
            } else {
                Paint.rect(g, x0 + 1, y + 1, 4, 3, 1, sel ? 0xFFFFFFFF : color);
            }
        }
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            int x0 = t.xOf(m.tick);
            // Markers are sorted by tick: the name stops a little before the next one's flag.
            int room = Math.min(70, (i + 1 < markers.size() ? t.xOf(markers.get(i + 1).tick) - 2 : x0 + 80) - (x0 + 8));
            if (room < 10 || x0 + 8 + room < t.x || x0 > t.x + t.w + 2) {
                continue;
            }
            boolean sel = m.id.equals(selectedId);
            int color = 0xFF000000 | m.color;
            String name = font.plainSubstrByWidth(m.name, room);
            g.text(font, name, x0 + 8, y + (h - 7) / 2, sel ? 0xFFFFFFFF : Theme.withAlpha(Theme.mix(color, 0xFFFFFFFF, 0.35F), 0xD9), false);
        }
    }

    /** The marker under the mouse: its flag, a range end or a range bar; the selected one first. */
    private Marker markerAt(EditorTimeline t, double mx) {
        List<Marker> list = new ArrayList<>(project().markers);
        list.sort((a, b) -> Boolean.compare(b.id.equals(selectedId), a.id.equals(selectedId)));
        Marker inside = null;
        for (Marker m : list) {
            int x0 = t.xOf(m.tick);
            if (Math.abs(mx - x0) <= 3) {
                return m;
            }
            if (m.isRange()) {
                int x1 = t.xOf(m.end);
                if (Math.abs(mx - x1) <= 3) {
                    return m;
                }
                if (inside == null && mx > x0 && mx < x1) {
                    inside = m;
                }
            }
        }
        return inside;
    }

    boolean mouseClicked(EditorTimeline t, double mx, double my) {
        Marker m = markerAt(t, mx);
        if (m == null) {
            return false;
        }
        int x0 = t.xOf(m.tick);
        int x1 = t.xOf(m.lastTick());
        boolean wide = m.isRange() && x1 - x0 >= 10;
        if (wide && Math.abs(mx - x1) <= 3) {
            dragMode = Drag.END;
            grab = mx - x1;
        } else if (wide && Math.abs(mx - x0) <= 3 && mx > x0) {
            dragMode = Drag.START;
            grab = mx - x0;
        } else {
            dragMode = Drag.MOVE;
            grab = mx - x0;
        }
        dragging = m;
        dragMoved = false;
        pressX = mx;
        session.playback().seek(m.tick);
        if (!m.id.equals(selectedId)) {
            selectedId = m.id;
            screen.refresh();
        }
        return true;
    }

    boolean mouseDragged(EditorTimeline t, double mx) {
        if (dragging == null) {
            return false;
        }
        if (!dragMoved && Math.abs(mx - pressX) < 3) {
            return true;
        }
        dragMoved = true;
        int duration = duration();
        int tick = t.tickAt(mx - grab);
        Marker m = dragging;
        switch (dragMode) {
            case START -> m.setRange(Math.max(0, Math.min(m.end - 1, tick)), m.end, duration);
            case END -> m.setRange(m.tick, Math.max(m.tick + 1, Math.min(duration, tick)), duration);
            default -> m.moveTo(tick, duration);
        }
        return true;
    }

    boolean mouseReleased() {
        if (dragging == null) {
            return false;
        }
        dragging = null;
        project().sort();
        save();
        if (dragMoved) {
            screen.refresh();
        }
        return true;
    }

    boolean dragging() {
        return dragging != null;
    }

    Component tooltip(EditorTimeline t, double mx) {
        Marker m = markerAt(t, mx);
        if (m == null) {
            return tc("justreplay.marker.lane.tip");
        }
        String note = m.note.isBlank() ? "" : "\n" + m.note;
        return Component.literal(label(m) + "  " + timeText(m) + note);
    }
}
