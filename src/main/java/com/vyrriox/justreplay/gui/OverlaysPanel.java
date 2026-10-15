package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.config.JRConfig.EditorMode;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.fx.FxEffect;
import com.vyrriox.justreplay.editor.fx.FxParam;
import com.vyrriox.justreplay.editor.overlay.ImageLibrary;
import com.vyrriox.justreplay.editor.overlay.Overlay;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListPopup;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.gui.ui.Tipped;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The "Overlays" tab of the editor: texts and images placed in the world. Adds them (then places them with the
 * crosshair), lists them with a visibility toggle, and edits the selected one: content, size, colours, plate,
 * image file, anchor, facing, visible range with fades, and keyframed offset, scale, rotation and opacity in the
 * advanced mode. Also draws the overlays lane of the timeline, where each overlay is a bar of its visible range
 * that moves by dragging, and whose ends drag to change the start and the end.
 *
 * @author vyrriox
 */
final class OverlaysPanel {
    static final int TEXT_COLOR = 0xFF4DD0E1;
    static final int IMAGE_COLOR = 0xFFF48FB1;
    /** Most rows the lane splits into when overlays overlap in time. */
    private static final int MAX_ROWS = 3;
    /** Preset colours, with their translation key suffix; -1 asks for a hex value. */
    private static final int[] COLORS = {0xFFFFFF, 0x000000, 0xFFD54F, 0xFF6B6B, 0x5BE49B, 0x5AB4FF, 0x55FFFF, 0xFF55FF, 0xAAAAAA, 0x0B0F16};
    private static final String[] COLOR_NAMES = {"white", "black", "gold", "red", "green", "blue", "aqua", "pink", "gray", "glass"};
    private static final int CUSTOM = -1;

    private static String selectedId;

    static {
        // The selected overlay is framed in the world while the editor is open.
        OverlayRenderer.highlight(() -> Minecraft.getInstance().screen instanceof EditorScreen ? selectedId : null);
    }

    private enum Drag { MOVE, START, END }

    private final EditorScreen screen;
    private final ReplaySession session;
    private Overlay dragging;
    private Drag dragMode;
    private boolean dragMoved;
    private double pressX;
    private double grab;
    /** Rows of the lane while a bar is dragged, so the lane keeps its height until the drop. */
    private Map<Overlay, Integer> frozenRows;

    OverlaysPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** Selects an overlay by id (null for none); kept across rebuilds and reopenings. */
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

    Overlay selected() {
        if (selectedId == null) {
            return null;
        }
        for (Overlay o : project().overlays) {
            if (selectedId.equals(o.id)) {
                return o;
            }
        }
        return null;
    }

    private int cursor() {
        return session.playback().cursor();
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

    // ---------------------------------------------------------------- tab

    void build(Form f) {
        Overlay selected = selected();
        f.header(tc("justreplay.overlay.section"), Icon.SCENE);
        if (project().overlays.isEmpty()) {
            f.paragraph(tc("justreplay.overlay.help"), Theme.TEXT_MUTED);
        }
        f.put(button(tc("justreplay.overlay.add.text"), Icon.EDIT, tc("justreplay.overlay.add.text.tip"), b -> add(Overlay.TEXT))
                .style(GlassButton.Style.PRIMARY), 0, 2);
        f.put(button(tc("justreplay.overlay.add.image"), Icon.FILM, tc("justreplay.overlay.add.image.tip"), b -> add(Overlay.IMAGE))
                .style(GlassButton.Style.PRIMARY), 1, 2);
        f.next(rowHeight());
        for (Overlay o : project().overlays) {
            f.full(new ListRow(Component.literal(o.displayName()), b -> {
                selectedId = o.id;
                screen.refresh();
            }).detail(Timeline.formatTicks(o.start)).icon(o.isImage() ? Icon.FILM : Icon.EDIT)
                    .action(() -> o.visible ? Icon.EYE : Icon.EYE_OFF, tc("justreplay.overlay.visible.tip"), () -> {
                        o.visible = !o.visible;
                        save();
                    }).selected(() -> o.id.equals(selectedId)).tip(tc("justreplay.overlay.row.tip")));
        }
        if (selected == null) {
            if (!project().overlays.isEmpty()) {
                f.paragraph(tc("justreplay.overlay.select"), Theme.TEXT_MUTED);
            }
            return;
        }
        buildSelected(f, selected);
    }

    private void buildSelected(Form f, Overlay o) {
        boolean simple = simple();
        f.header(Component.literal(o.displayName()), Icon.EDIT);
        f.put(button(Component.empty(), Icon.PIN, tc("justreplay.overlay.place.tip"), b -> OverlayPlacementTool.start(screen, session, o, false)), 0, 4);
        f.put(button(Component.empty(), Icon.COPY, tc("justreplay.overlay.duplicate.tip"), b -> duplicate(o)), 1, 4);
        f.put(button(Component.empty(), Icon.SKIP_START, tc("justreplay.overlay.goto.tip"), b -> session.playback().seek(o.start)), 2, 4);
        f.put(button(Component.empty(), Icon.TRASH, tc("justreplay.overlay.delete.tip"),
                b -> screen.confirm(tc("justreplay.overlay.delete.confirm"), () -> delete(o))).style(GlassButton.Style.DANGER), 3, 4);
        f.next(rowHeight());
        f.text(() -> anchorLine(o), Theme.TEXT_DIM);
        f.text(() -> Overlay.ENTITY.equals(o.anchor) && OverlayRenderer.entity(session, o) == null
                ? tc("justreplay.overlay.anchor.missing") : null, Theme.WARNING);

        if (o.isImage()) {
            buildImage(f, o, simple);
        } else {
            buildText(f, o, simple);
        }

        f.header(tc("justreplay.overlay.anchor"), Icon.PIN);
        f.full(button(tc("justreplay.overlay.place"), Icon.PIN, tc("justreplay.overlay.place.tip"),
                b -> OverlayPlacementTool.start(screen, session, o, false)));
        f.put(button(tc("justreplay.overlay.anchor.recorder"), Icon.PERSON, tc("justreplay.overlay.anchor.recorder.tip"),
                b -> follow(o, Overlay.RECORDER, tr("justreplay.overlay.anchor.recorder"))), 0, 2);
        f.put(button(tc("justreplay.overlay.anchor.actor"), Icon.PUZZLE, tc("justreplay.overlay.anchor.actor.tip"), b -> pickActor(o, b))
                .enabled(!project().actors.isEmpty()), 1, 2);
        f.next(rowHeight());
        f.full(button(tc("justreplay.overlay.anchor.camera"), Icon.CAMERA, tc("justreplay.overlay.anchor.camera.tip"), b -> inFrontOfCamera(o)));
        List<String> facings = new ArrayList<>(List.of(Overlay.BILLBOARD, Overlay.FIXED));
        if (Overlay.BLOCK.equals(o.anchor)) {
            facings.add(Overlay.FLAT);
        }
        f.full(new Dropdown<>(screen, tc("justreplay.overlay.facing"), facings, o.facing,
                v -> tc("justreplay.overlay.facing." + v.toLowerCase(Locale.ROOT)), v -> {
            if (Overlay.FIXED.equals(v) && !Overlay.FIXED.equals(o.facing) && !o.animated(Overlay.YAW)) {
                // Turning a billboard into a fixed overlay keeps it facing the current camera.
                o.setBase(Overlay.YAW, net.minecraft.util.Mth.wrapDegrees(Minecraft.getInstance().gameRenderer.getMainCamera().getYRot()));
            }
            o.facing = v;
            save();
            screen.refresh();
        }).icon(Icon.ROTATE).tip(tc("justreplay.overlay.facing.tip")));
        if (!simple) {
            f.full(new GlassSwitch(tc("justreplay.overlay.through"), () -> o.throughWalls, () -> {
                o.throughWalls = !o.throughWalls;
                save();
            }).icon(Icon.EYE).tip(tc("justreplay.overlay.through.tip")));
        }

        f.header(tc("justreplay.overlay.timing"), Icon.CLOCK);
        f.put(button(tc("justreplay.overlay.from"), Icon.SKIP_START, tc("justreplay.overlay.from.tip"), b -> {
            int duration = session.playback().duration();
            o.start = Math.min(cursor(), Math.max(0, duration - 1));
            if (o.end <= o.start) {
                o.end = Math.min(duration, o.start + 100);
                o.end = Math.max(o.end, o.start + 1);
            }
            save();
        }), 0, 2);
        f.put(button(tc("justreplay.overlay.to"), Icon.SKIP_END, tc("justreplay.overlay.to.tip"), b -> {
            o.end = Math.max(cursor(), o.start + 1);
            save();
        }), 1, 2);
        f.next(rowHeight());
        f.text(() -> Component.translatable("justreplay.overlay.range", Timeline.formatTicks(o.start), Timeline.formatTicks(o.end),
                String.format(Locale.ROOT, "%.1f", (o.end - o.start) / 20.0)), Theme.TEXT);
        if (!simple) {
            f.full(new Stepper(tc("justreplay.overlay.fade_in"), () -> o.fadeIn / 20.0, "s", 2,
                    () -> fade(o, true, -fadeStep()), () -> fade(o, true, fadeStep())).icon(Icon.ARROW_UP).tip(tc("justreplay.overlay.fade.tip")));
            f.full(new Stepper(tc("justreplay.overlay.fade_out"), () -> o.fadeOut / 20.0, "s", 2,
                    () -> fade(o, false, -fadeStep()), () -> fade(o, false, fadeStep())).icon(Icon.ARROW_DOWN).tip(tc("justreplay.overlay.fade.tip")));

            f.header(tc("justreplay.overlay.animation"), Icon.KEY);
            boolean fixed = Overlay.FIXED.equals(o.facing);
            // Yaw also turns an overlay lying on a floor or a ceiling; a billboard only rolls.
            boolean usesYaw = fixed || Overlay.FLAT.equals(o.facing) && ("UP".equals(o.face) || "DOWN".equals(o.face));
            for (FxParam param : Overlay.PARAMS) {
                if (param == Overlay.YAW && !usesYaw || param == Overlay.PITCH && !fixed) {
                    continue;
                }
                f.full(new ParamSlider(param, o));
            }
            f.text(() -> o.keyCount() == 0 ? tc("justreplay.overlay.keys.none") : Component.translatable("justreplay.overlay.keys", o.keyCount()),
                    Theme.TEXT_MUTED);
        }
    }

    private void buildText(Form f, Overlay o, boolean simple) {
        f.header(tc("justreplay.overlay.text"), Icon.EDIT);
        f.full(button(tc("justreplay.overlay.text.edit"), Icon.EDIT, tc("justreplay.overlay.text.edit.tip"), b -> editText(o)));
        f.full(new Stepper(tc("justreplay.overlay.size"), () -> o.fontSize, "", 2, () -> fontSize(o, -1), () -> fontSize(o, 1))
                .icon(Icon.FIT).tip(tc("justreplay.overlay.size.tip")));
        f.full(colors(tc("justreplay.overlay.color"), o.color, v -> o.color = v));
        f.full(new GlassSwitch(tc("justreplay.overlay.plate"), () -> o.plate, () -> {
            o.plate = !o.plate;
            save();
            screen.refresh();
        }).icon(Icon.SCENE).tip(tc("justreplay.overlay.plate.tip")));
        if (simple) {
            return;
        }
        f.full(new Segmented<>(List.of(Overlay.LEFT, Overlay.CENTER, Overlay.RIGHT), o.align,
                v -> tc("justreplay.overlay.align." + v.toLowerCase(Locale.ROOT)), null, v -> {
            o.align = v;
            save();
        }).tip(tc("justreplay.overlay.align.tip")));
        f.put(new GlassSwitch(tc("justreplay.overlay.shadow"), () -> o.shadow, () -> {
            o.shadow = !o.shadow;
            save();
        }), 0, 2);
        f.put(new GlassSwitch(tc("justreplay.overlay.outline"), () -> o.outline, () -> {
            o.outline = !o.outline;
            save();
            screen.refresh();
        }), 1, 2);
        f.next();
        if (o.outline) {
            f.full(colors(tc("justreplay.overlay.outline_color"), o.outlineColor, v -> o.outlineColor = v));
        }
        if (o.plate) {
            f.full(colors(tc("justreplay.overlay.plate_color"), o.plateColor, v -> o.plateColor = v));
            f.full(new Stepper(tc("justreplay.overlay.plate_opacity"), () -> Math.round(o.plateOpacity * 100.0), "%", 0, () -> {
                o.plateOpacity = Math.max(0.0F, Math.round(o.plateOpacity * 20.0F - 1.0F) / 20.0F);
                save();
            }, () -> {
                o.plateOpacity = Math.min(1.0F, Math.round(o.plateOpacity * 20.0F + 1.0F) / 20.0F);
                save();
            }).icon(Icon.EYE));
            f.full(new Stepper(tc("justreplay.overlay.padding"), () -> o.padding, "", 0, () -> {
                o.padding = Math.max(0.0F, o.padding - 1.0F);
                save();
            }, () -> {
                o.padding = Math.min(32.0F, o.padding + 1.0F);
                save();
            }).icon(Icon.FIT));
        }
        f.paragraph(tc("justreplay.overlay.text.help"), Theme.TEXT_MUTED);
    }

    private void buildImage(Form f, Overlay o, boolean simple) {
        f.header(tc("justreplay.overlay.image"), Icon.FILM);
        List<String> files = ImageLibrary.list();
        if (files.isEmpty()) {
            f.paragraph(tc("justreplay.overlay.image.none"), Theme.WARNING);
        } else {
            List<String> values = new ArrayList<>(files);
            if (!o.image.isBlank() && !values.contains(o.image)) {
                values.add(0, o.image);
            }
            f.full(new Dropdown<>(screen, tc("justreplay.overlay.image.file"), values, o.image.isBlank() ? null : o.image, Component::literal, v -> {
                o.image = v;
                save();
                screen.refresh();
            }).icon(Icon.FILM));
        }
        f.put(button(tc("justreplay.audio.folder"), Icon.FOLDER, tc("justreplay.overlay.image.folder.tip"),
                b -> Util.getPlatform().openPath(ImageLibrary.folder())), 0, 2);
        f.put(button(tc("justreplay.overlay.image.reload"), Icon.ROTATE, tc("justreplay.overlay.image.reload.tip"), b -> {
            ImageLibrary.reload();
            screen.refresh();
        }), 1, 2);
        f.next(rowHeight());
        if (!o.image.isBlank()) {
            // Loading here (on the render thread) tells at once when the file cannot be used.
            ImageLibrary.Loaded loaded = ImageLibrary.get(o.image);
            if (loaded == null) {
                f.paragraph(tc("justreplay.overlay.image.broken"), Theme.DANGER);
            } else {
                f.text(() -> Component.translatable("justreplay.overlay.image.size", loaded.width(), loaded.height()), Theme.TEXT_MUTED);
            }
        }
        f.full(new Stepper(tc("justreplay.overlay.width"), () -> o.width, "", 2, () -> size(o, true, -1), () -> size(o, true, 1))
                .icon(Icon.FIT).tip(tc("justreplay.overlay.width.tip")));
        if (simple) {
            return;
        }
        f.full(new GlassSwitch(tc("justreplay.overlay.keep_aspect"), () -> o.keepAspect, () -> {
            o.keepAspect = !o.keepAspect;
            save();
            screen.refresh();
        }).icon(Icon.FIT));
        if (!o.keepAspect) {
            f.full(new Stepper(tc("justreplay.overlay.height"), () -> o.height, "", 2, () -> size(o, false, -1), () -> size(o, false, 1))
                    .icon(Icon.FIT));
        }
        f.full(colors(tc("justreplay.overlay.tint"), o.tint, v -> o.tint = v));
    }

    // ---------------------------------------------------------------- actions

    private void add(String type) {
        Overlay o = new Overlay(type);
        int duration = Math.max(1, session.playback().duration());
        o.start = Math.min(cursor(), Math.max(0, duration - 1));
        o.end = Math.min(duration, o.start + 100);
        if (o.end - o.start < 20) {
            o.start = Math.max(0, o.end - 100);
        }
        o.end = Math.max(o.end, o.start + 1);
        if (o.isText()) {
            o.text = tr("justreplay.overlay.default_text");
        } else {
            List<String> files = ImageLibrary.list();
            o.image = files.isEmpty() ? "" : files.get(0);
        }
        project().overlays.add(o);
        selectedId = o.id;
        save();
        OverlayPlacementTool.start(screen, session, o, true);
    }

    private void duplicate(Overlay o) {
        Overlay copy = o.copy();
        List<Overlay> list = project().overlays;
        list.add(list.indexOf(o) + 1, copy);
        selectedId = copy.id;
        save();
        screen.refresh();
    }

    private void delete(Overlay o) {
        project().overlays.remove(o);
        if (o.id.equals(selectedId)) {
            selectedId = null;
        }
        save();
        screen.refresh();
    }

    private void editText(Overlay o) {
        String current = o.text.replace("\n", "\\n");
        Minecraft.getInstance().setScreen(new TextInputScreen(screen, tc("justreplay.overlay.text.title"), tc("justreplay.overlay.text.label"),
                current, s -> {
            o.text = s.replace("\\n", "\n");
            save();
        }));
    }

    /** Anchors an overlay to an entity: the recording player or an actor. */
    private void follow(Overlay o, String target, String name) {
        boolean wasEntity = Overlay.ENTITY.equals(o.anchor);
        o.anchor = Overlay.ENTITY;
        o.target = target;
        o.targetName = name;
        if (Overlay.FLAT.equals(o.facing)) {
            o.facing = Overlay.BILLBOARD;
        }
        if (!wasEntity) {
            o.setBase(Overlay.OFFSET_X, 0.0F);
            o.setBase(Overlay.OFFSET_Y, 0.35F);
            o.setBase(Overlay.OFFSET_Z, 0.0F);
        }
        save();
        screen.refresh();
    }

    private void pickActor(Overlay o, net.minecraft.client.gui.components.Button b) {
        List<ReplayProject.ActorData> actors = project().actors;
        List<String> labels = new ArrayList<>();
        for (ReplayProject.ActorData actor : actors) {
            labels.add(actorName(actor));
        }
        screen.openPopup(new ListPopup(b.getX(), b.getY(), b.getWidth(), b.getHeight(), screen.width, screen.height,
                Minecraft.getInstance().font, labels, -1, i -> {
            screen.closePopup();
            ReplayProject.ActorData actor = actors.get(i);
            follow(o, Overlay.ACTOR_PREFIX + actor.id, actorName(actor));
        }));
    }

    private static String actorName(ReplayProject.ActorData actor) {
        if (!actor.name.isBlank()) {
            return actor.name;
        }
        return net.minecraft.world.entity.EntityType.byString(actor.type).map(t -> t.getDescription().getString()).orElse(actor.type);
    }

    /** A fixed position three blocks in front of the camera, facing it. */
    private void inFrontOfCamera(Overlay o) {
        net.minecraft.client.Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 at = camera.getPosition().add(new Vec3(camera.getLookVector()).scale(3.0));
        o.anchor = Overlay.POSITION;
        o.x = at.x;
        o.y = at.y;
        o.z = at.z;
        if (Overlay.FLAT.equals(o.facing)) {
            o.facing = Overlay.BILLBOARD;
        }
        o.setBase(Overlay.OFFSET_X, 0.0F);
        o.setBase(Overlay.OFFSET_Y, 0.0F);
        o.setBase(Overlay.OFFSET_Z, 0.0F);
        o.setBase(Overlay.YAW, net.minecraft.util.Mth.wrapDegrees(camera.getYRot()));
        save();
        screen.refresh();
    }

    private Component anchorLine(Overlay o) {
        return switch (o.anchor) {
            case Overlay.ENTITY -> Component.translatable("justreplay.overlay.anchor.entity", o.targetName.isBlank() ? o.target : o.targetName);
            case Overlay.BLOCK -> Component.translatable("justreplay.overlay.anchor.block", o.blockX, o.blockY, o.blockZ,
                    tr("justreplay.overlay.face." + o.face.toLowerCase(Locale.ROOT)));
            default -> Component.translatable("justreplay.overlay.anchor.position", String.format(Locale.ROOT, "%.1f", o.x),
                    String.format(Locale.ROOT, "%.1f", o.y), String.format(Locale.ROOT, "%.1f", o.z));
        };
    }

    private static int fadeStep() {
        return Screen.hasShiftDown() ? 1 : 5;
    }

    private void fade(Overlay o, boolean in, int delta) {
        int max = Math.max(0, o.end - o.start);
        if (in) {
            o.fadeIn = Math.max(0, Math.min(max, o.fadeIn + delta));
        } else {
            o.fadeOut = Math.max(0, Math.min(max, o.fadeOut + delta));
        }
        save();
    }

    private void fontSize(Overlay o, int direction) {
        float step = Screen.hasShiftDown() ? 0.01F : 0.05F;
        o.fontSize = Math.max(0.02F, Math.min(8.0F, Math.round((o.fontSize + direction * step) * 100.0F) / 100.0F));
        save();
    }

    private void size(Overlay o, boolean width, int direction) {
        float value = width ? o.width : o.height;
        float step = Screen.hasShiftDown() ? 0.05F : value >= 4.0F ? 0.5F : 0.25F;
        value = Math.max(0.05F, Math.min(64.0F, Math.round((value + direction * step) * 100.0F) / 100.0F));
        if (width) {
            o.width = value;
        } else {
            o.height = value;
        }
        save();
    }

    /** Colour selector: the presets and a custom hex value. */
    private GlassButton colors(Component caption, int current, IntConsumer onChange) {
        List<Integer> values = new ArrayList<>();
        for (int c : COLORS) {
            values.add(c);
        }
        if (!values.contains(current & 0xFFFFFF)) {
            values.add(current & 0xFFFFFF);
        }
        values.add(CUSTOM);
        return new Dropdown<>(screen, caption, values, current & 0xFFFFFF, OverlaysPanel::colorLabel, v -> {
            if (v == CUSTOM) {
                Minecraft.getInstance().setScreen(new TextInputScreen(screen, caption, tc("justreplay.overlay.color.hex"),
                        String.format(Locale.ROOT, "%06X", current & 0xFFFFFF), s -> {
                    Integer parsed = parseHex(s);
                    if (parsed != null) {
                        onChange.accept(parsed);
                        save();
                    }
                }));
                return;
            }
            onChange.accept(v);
            save();
        }).icon(Icon.EDIT);
    }

    private static Component colorLabel(Integer value) {
        if (value == CUSTOM) {
            return tc("justreplay.overlay.color.custom");
        }
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == value) {
                return tc("justreplay.overlay.color." + COLOR_NAMES[i]);
            }
        }
        return Component.literal(String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF));
    }

    private static Integer parseHex(String s) {
        String v = s.trim();
        if (v.startsWith("#")) {
            v = v.substring(1);
        } else if (v.toLowerCase(Locale.ROOT).startsWith("0x")) {
            v = v.substring(2);
        }
        if (v.length() == 3) {
            v = "" + v.charAt(0) + v.charAt(0) + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2);
        }
        try {
            return v.length() == 6 ? Integer.parseInt(v, 16) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- timeline lane

    /** Row of each overlay in the lane: overlapping ranges go on separate rows. */
    private Map<Overlay, Integer> rows() {
        if (frozenRows != null) {
            return frozenRows;
        }
        Map<Overlay, Integer> rows = new IdentityHashMap<>();
        List<Overlay> sorted = new ArrayList<>(project().overlays);
        sorted.sort((a, b) -> Integer.compare(a.start, b.start));
        int[] ends = new int[MAX_ROWS];
        java.util.Arrays.fill(ends, Integer.MIN_VALUE);
        for (Overlay o : sorted) {
            int row = -1;
            for (int r = 0; r < MAX_ROWS; r++) {
                if (ends[r] < o.start) {
                    row = r;
                    break;
                }
            }
            if (row < 0) {
                // Every row is busy: the one that frees first.
                row = 0;
                for (int r = 1; r < MAX_ROWS; r++) {
                    if (ends[r] < ends[row]) {
                        row = r;
                    }
                }
            }
            ends[row] = Math.max(ends[row], o.end);
            rows.put(o, row);
        }
        return rows;
    }

    private static int rowCount(Map<Overlay, Integer> rows) {
        int n = 0;
        for (int r : rows.values()) {
            n = Math.max(n, r + 1);
        }
        return Math.max(1, n);
    }

    /** Height of the overlays lane: none without overlays, one bar, or thinner rows when they overlap. */
    int laneHeight(boolean compact) {
        if (project().overlays.isEmpty()) {
            return 0;
        }
        int rows = rowCount(rows());
        return rows == 1 ? (compact ? 10 : 12) : rows * (compact ? 6 : 7) + 1;
    }

    private record Bar(Overlay overlay, int x0, int x1, int y, int h) {
    }

    private List<Bar> bars(EditorTimeline t, int y, int h) {
        Map<Overlay, Integer> rows = rows();
        int count = rowCount(rows);
        int rowH = count == 1 ? h : (h - 1) / count;
        List<Bar> bars = new ArrayList<>();
        for (Overlay o : project().overlays) {
            int row = rows.getOrDefault(o, 0);
            bars.add(new Bar(o, t.xOf(o.start), t.xOf(o.end), y + 1 + row * rowH, Math.max(2, rowH - 2)));
        }
        return bars;
    }

    void renderLane(GuiGraphics g, Font font, EditorTimeline t, int y, int h) {
        for (Bar bar : bars(t, y, h)) {
            Overlay o = bar.overlay();
            if (bar.x1() < t.x - 2 || bar.x0() > t.x + t.w + 2) {
                continue;
            }
            boolean sel = o.id.equals(selectedId);
            int base = o.isImage() ? IMAGE_COLOR : TEXT_COLOR;
            int alpha = !o.visible ? 0x4D : sel ? 0xF2 : 0xA6;
            int x0 = bar.x0(), w = Math.max(2, bar.x1() - bar.x0());
            Paint.rect(g, x0, bar.y(), w, bar.h(), Theme.RADIUS_S, Theme.withAlpha(base, alpha),
                    Theme.withAlpha(Theme.mix(base, 0xFF000000, 0.25F), alpha));
            // Fades: a darker zone at each end.
            int fi = Math.min(w, t.xOf(o.start + o.fadeIn) - x0);
            int fo = Math.min(w, bar.x1() - t.xOf(o.end - o.fadeOut));
            if (fi > 1) {
                Paint.rect(g, x0, bar.y(), fi, bar.h(), Theme.RADIUS_S, 0x33000000);
            }
            if (fo > 1) {
                Paint.rect(g, bar.x1() - fo, bar.y(), fo, bar.h(), Theme.RADIUS_S, 0x33000000);
            }
            if (sel) {
                Paint.outline(g, x0 - 1, bar.y() - 1, w + 2, bar.h() + 2, Theme.RADIUS_S + 1, 0xFFFFFFFF);
                g.fill(x0, bar.y() + 1, x0 + 1, bar.y() + bar.h() - 1, 0xFFFFFFFF);
                g.fill(bar.x1() - 1, bar.y() + 1, bar.x1(), bar.y() + bar.h() - 1, 0xFFFFFFFF);
                for (List<FxEffect.Key> keys : o.keys.values()) {
                    for (FxEffect.Key key : keys) {
                        int kx = t.xOf(key.tick);
                        if (kx >= t.x - 3 && kx <= t.x + t.w + 3) {
                            Paint.rect(g, kx - 1.5F, bar.y() + bar.h() - 3, 3, 3, 1, 0xFFFFFFFF);
                        }
                    }
                }
            }
            if (bar.h() >= 9 && w > 14) {
                String label = font.plainSubstrByWidth(o.displayName(), w - 5);
                if (!label.isEmpty()) {
                    g.drawString(font, label, Math.max(x0, t.x) + 3, bar.y() + (bar.h() - 7) / 2, 0xFF0B0F16, false);
                }
            }
        }
    }

    /** The bar under the mouse, the selected one first when bars overlap. */
    private Bar barAt(EditorTimeline t, double mx, double my) {
        int y = t.laneY(EditorTimeline.Lane.OVERLAYS);
        if (y < 0) {
            return null;
        }
        List<Bar> bars = bars(t, y, t.heightOf(EditorTimeline.Lane.OVERLAYS));
        bars.sort((a, b) -> Boolean.compare(b.overlay().id.equals(selectedId), a.overlay().id.equals(selectedId)));
        for (Bar bar : bars) {
            if (my >= bar.y() - 1 && my <= bar.y() + bar.h() + 1 && mx >= bar.x0() - 3 && mx <= bar.x1() + 3) {
                return bar;
            }
        }
        return null;
    }

    boolean mouseClicked(EditorTimeline t, double mx, double my) {
        Bar bar = barAt(t, mx, my);
        if (bar == null) {
            return false;
        }
        Overlay o = bar.overlay();
        boolean wide = bar.x1() - bar.x0() >= 10;
        if (wide && Math.abs(mx - bar.x0()) <= 3) {
            dragMode = Drag.START;
            grab = mx - bar.x0();
        } else if (wide && Math.abs(mx - bar.x1()) <= 3) {
            dragMode = Drag.END;
            grab = mx - bar.x1();
        } else {
            dragMode = Drag.MOVE;
            grab = mx - bar.x0();
        }
        frozenRows = rows();
        dragging = o;
        dragMoved = false;
        pressX = mx;
        if (!o.id.equals(selectedId)) {
            selectedId = o.id;
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
        int duration = session.playback().duration();
        int tick = t.tickAt(mx - grab);
        Overlay o = dragging;
        switch (dragMode) {
            case START -> o.start = Math.max(0, Math.min(o.end - 1, tick));
            case END -> o.end = Math.max(o.start + 1, Math.min(duration, tick));
            default -> {
                int length = o.end - o.start;
                int start = Math.max(0, Math.min(Math.max(0, duration - length), tick));
                o.shift(start - o.start);
            }
        }
        return true;
    }

    boolean mouseReleased() {
        if (dragging == null) {
            return false;
        }
        dragging = null;
        frozenRows = null;
        save();
        if (dragMoved) {
            screen.refresh();
        }
        return true;
    }

    boolean dragging() {
        return dragging != null;
    }

    Component tooltip(EditorTimeline t, double mx, double my) {
        Bar bar = barAt(t, mx, my);
        if (bar == null) {
            return tc("justreplay.overlay.lane.tip");
        }
        Overlay o = bar.overlay();
        return Component.literal(o.displayName() + "  " + Timeline.formatTicks(o.start) + " - " + Timeline.formatTicks(o.end));
    }

    // ---------------------------------------------------------------- parameter slider

    /**
     * One animatable parameter: {@code label [track] value <>}. Dragging the track changes the fixed value, or the
     * keyframe at the play head once the parameter is animated. The diamond adds or removes a keyframe at the play
     * head; Shift + click on it clears every keyframe of the parameter.
     */
    private final class ParamSlider extends AbstractWidget implements Tipped {
        private static final int KEY_W = 12;
        private final FxParam param;
        private final Overlay overlay;
        private final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
        private boolean dragging;
        private float shownValue = Float.NaN;
        private String shownText = "";
        private final String label;

        ParamSlider(FxParam param, Overlay overlay) {
            super(0, 0, 0, Theme.ROW, Component.translatable("justreplay.overlay.param." + param.name()));
            this.param = param;
            this.overlay = overlay;
            this.label = getMessage().getString();
        }

        private int labelW() {
            return Math.min(58, width * 36 / 100);
        }

        private int trackX() {
            return getX() + labelW();
        }

        private int trackW() {
            return Math.max(10, width - labelW() - 30 - KEY_W);
        }

        private int keyX() {
            return getX() + width - KEY_W;
        }

        @Override
        public Component tooltip() {
            return tc("justreplay.overlay.param.tip");
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY();
            float h = hover.to(isHovered() || dragging ? 1.0F : 0.0F);
            float value = overlay.value(param, cursor());
            g.drawString(font, Paint.ellipsize(font, label, labelW() - 4), x + 3, y + (height - 7) / 2, Theme.TEXT_DIM, false);
            int tx = trackX(), tw = trackW();
            float t = (value - param.min()) / (param.max() - param.min());
            float ty = y + height / 2.0F - 2;
            Paint.rect(g, tx, ty, tw, 4, 2, Theme.TRACK);
            boolean animated = overlay.animated(param);
            int fill = animated ? TEXT_COLOR : Theme.ACCENT;
            Paint.rect(g, tx, ty, Math.max(4, tw * t), 4, 2, fill);
            Paint.circle(g, tx + tw * t, ty + 2, 3.5F + h, 0xFFFFFFFF);
            if (value != shownValue) {
                shownValue = value;
                float range = param.max() - param.min();
                shownText = range >= 20 ? Integer.toString(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
            }
            g.drawString(font, shownText, tx + tw + 3, y + (height - 7) / 2, Theme.TEXT, false);
            FxEffect.Key key = overlay.keyAt(param, cursor());
            int color = key != null ? TEXT_COLOR : animated ? Theme.mix(TEXT_COLOR, Theme.TEXT_MUTED, 0.5F) : Theme.TEXT_MUTED;
            Paint.iconCentered(g, Icon.KEY, keyX(), y, KEY_W, height, key != null ? 9 : 7, color);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (mouseX >= keyX()) {
                toggleKey();
                return;
            }
            dragging = true;
            setFrom(mouseX);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
            if (dragging) {
                setFrom(mouseX);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            if (dragging) {
                dragging = false;
                save();
            }
        }

        private void setFrom(double mouseX) {
            double t = Math.max(0.0, Math.min(1.0, (mouseX - trackX()) / Math.max(1, trackW())));
            float v = (float) (param.min() + t * (param.max() - param.min()));
            v = Math.round(v / param.step()) * param.step();
            overlay.edit(param, cursor(), param.clamp(v));
        }

        private void toggleKey() {
            if (Screen.hasShiftDown()) {
                overlay.keys.remove(param.name());
            } else {
                FxEffect.Key key = overlay.keyAt(param, cursor());
                if (key != null) {
                    overlay.removeKey(param, key);
                } else {
                    overlay.setKey(param, cursor(), overlay.value(param, cursor()));
                }
            }
            save();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
