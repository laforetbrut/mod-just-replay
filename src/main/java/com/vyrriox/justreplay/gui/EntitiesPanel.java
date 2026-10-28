package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.EntityEdits;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The "Entities" tab of the editor: the recorded entities loaded near the camera, a pick tool, and for the
 * selected one a hide range, a move to a new spot at the play head and the volume of its sounds.
 *
 * @author vyrriox
 */
final class EntitiesPanel {
    private static final double RANGE = 64.0;
    private static final int MAX_ROWS = 30;

    private final EditorScreen screen;
    private final ReplaySession session;

    /** Skin changes section of a selected player; keeps the picked file across refreshes. */
    private final SkinChangesSection skinSection;

    EntitiesPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
        this.skinSection = new SkinChangesSection("justreplay.entities.skinchange", () -> {
            ReplayProject.EntityEdit e = edit(false);
            return e == null ? List.of() : e.skinChanges;
        }, () -> session.playback().cursor(), next -> change(e -> e.skinChanges = next), screen::refresh);
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    // ---------------------------------------------------------------- selection

    static void select(Entity entity) {
        EntityEdits.select(entity);
    }

    /** The selected entity when it is loaded, else null. */
    static Entity selectedEntity() {
        UUID uuid = EntityEdits.selected();
        Minecraft mc = Minecraft.getInstance();
        if (uuid == null || mc.level == null) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (uuid.equals(e.getUUID()) && !ActorManager.isActorEntity(e) && e != mc.player) {
                return e;
            }
        }
        return null;
    }

    static String labelOf(Entity entity) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        String name = entity.getName().getString();
        return entity instanceof Player || entity.hasCustomName() ? type + " " + name : name;
    }

    static ReplayProject.EntityEdit ensureEdit(ReplaySession session, Entity entity) {
        String uuid = entity.getUUID().toString();
        ReplayProject.EntityEdit edit = session.project().entityEdit(uuid);
        if (edit == null) {
            edit = new ReplayProject.EntityEdit(uuid, labelOf(entity));
            session.project().entityEdits.add(edit);
        }
        return edit;
    }

    /** Edit of the selection, created on demand with the label of the loaded entity. */
    private ReplayProject.EntityEdit edit(boolean create) {
        UUID uuid = EntityEdits.selected();
        if (uuid == null) {
            return null;
        }
        ReplayProject.EntityEdit edit = session.project().entityEdit(uuid.toString());
        if (edit == null && create) {
            Entity e = selectedEntity();
            edit = e != null ? ensureEdit(session, e) : null;
        }
        return edit;
    }

    /** Saves the project; an edit that no longer changes anything is dropped. */
    private void save() {
        session.project().entityEdits.removeIf(ReplayProject.EntityEdit::isEmpty);
        session.saveProject();
        screen.refresh();
    }

    private void change(java.util.function.Consumer<ReplayProject.EntityEdit> action) {
        ReplayProject.EntityEdit edit = edit(true);
        if (edit != null) {
            action.accept(edit);
            save();
        }
    }

    // ---------------------------------------------------------------- layout

    void build(Form f) {
        Minecraft mc = Minecraft.getInstance();
        f.header(tc("justreplay.entities.section"), Icon.PERSON);
        f.full(new GlassButton(tc("justreplay.entities.pick"), b -> EntityTool.start(screen, session, EntityTool.Mode.PICK))
                .icon(Icon.TARGET).tip(tc("justreplay.entities.pick.tip")));
        buildSelected(f);

        f.header(tc("justreplay.entities.nearby"), Icon.EYE);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        List<Entity> near = new ArrayList<>();
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e != mc.player && !ActorManager.isActorEntity(e) && !e.isRemoved() && e.position().distanceTo(cam) <= RANGE) {
                    near.add(e);
                }
            }
        }
        near.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(cam)));
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < near.size() && i < MAX_ROWS; i++) {
            Entity e = near.get(i);
            listed.add(e.getUUID().toString());
            row(f, e.getUUID(), labelOf(e), String.format(java.util.Locale.ROOT, "%.0f m", e.position().distanceTo(cam)));
        }
        if (near.isEmpty()) {
            f.paragraph(tc("justreplay.entities.none"), Theme.TEXT_MUTED);
        }
        // Edited entities that are not loaded here stay reachable.
        boolean header = false;
        for (ReplayProject.EntityEdit edit : session.project().entityEdits) {
            if (listed.contains(edit.uuid)) {
                continue;
            }
            if (!header) {
                f.header(tc("justreplay.entities.edited"), Icon.EDIT);
                header = true;
            }
            try {
                row(f, UUID.fromString(edit.uuid), edit.label, tr("justreplay.entities.not_loaded"));
            } catch (IllegalArgumentException ignored) {
                // A hand edited project with a bad UUID: nothing to select.
            }
        }
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private void row(Form f, UUID uuid, String label, String detail) {
        ReplayProject.EntityEdit edit = session.project().entityEdit(uuid.toString());
        ListRow row = new ListRow(Component.literal(label), b -> {
            EntityEdits.selectUuid(uuid);
            screen.refresh();
        }).detail(detail).icon(edit != null && edit.hidden ? Icon.EYE_OFF : Icon.PERSON).selected(() -> uuid.equals(EntityEdits.selected()));
        if (edit != null) {
            row.dot(Theme.ACCENT);
        }
        f.full(row);
    }

    private void buildSelected(Form f) {
        UUID uuid = EntityEdits.selected();
        if (uuid == null) {
            f.paragraph(tc("justreplay.entities.select_hint"), Theme.TEXT_MUTED);
            return;
        }
        Entity entity = selectedEntity();
        ReplayProject.EntityEdit current = edit(false);
        if (entity == null && current == null) {
            f.paragraph(tc("justreplay.entities.select_hint"), Theme.TEXT_MUTED);
            return;
        }
        String label = entity != null ? labelOf(entity) : current.label;
        f.header(Component.literal(label), Icon.EDIT);

        f.full(new GlassSwitch(tc("justreplay.entities.hidden"), () -> {
            ReplayProject.EntityEdit e = edit(false);
            return e != null && e.hidden;
        }, () -> change(e -> e.hidden = !e.hidden)).icon(Icon.EYE_OFF).tip(tc("justreplay.entities.hidden.tip")));
        f.put(new GlassButton(tc("justreplay.entities.hide_from"), b -> change(e -> {
            e.hidden = true;
            e.hideFrom = session.playback().cursor();
            if (e.hideTo >= 0 && e.hideTo < e.hideFrom) e.hideTo = -1;
        })).tip(tc("justreplay.entities.hide_from.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.entities.hide_until"), b -> change(e -> {
            e.hidden = true;
            e.hideTo = session.playback().cursor();
            if (e.hideFrom > e.hideTo) e.hideFrom = 0;
        })).tip(tc("justreplay.entities.hide_until.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.entities.hide_all"), b -> change(e -> {
            e.hideFrom = 0;
            e.hideTo = -1;
        })).tip(tc("justreplay.entities.hide_all.tip")), 2, 3);
        f.next();
        f.text(() -> {
            ReplayProject.EntityEdit e = edit(false);
            if (e == null || !e.hidden) {
                return null;
            }
            return Component.translatable("justreplay.entities.range", Timeline.formatTicks(e.hideFrom),
                    e.hideTo < 0 ? tr("justreplay.entities.range.end") : Timeline.formatTicks(e.hideTo));
        }, Theme.TEXT_DIM);

        f.put(new GlassButton(tc("justreplay.entities.move"), b -> EntityTool.start(screen, session, EntityTool.Mode.MOVE))
                .icon(Icon.PIN).tip(tc("justreplay.entities.move.tip")), 0, 2);
        f.put(new GlassButton(tc("justreplay.entities.move.reset"), b -> change(e -> e.offsets.clear()))
                .icon(Icon.TRASH).tip(tc("justreplay.entities.move.reset.tip")), 1, 2);
        f.next();

        f.full(new Stepper(tc("justreplay.entities.volume"), () -> {
            ReplayProject.EntityEdit e = edit(false);
            return Math.round((e == null ? 1.0F : e.soundGain) * 100);
        }, "%", 0, () -> change(e -> e.soundGain = step(e.soundGain, -0.1F)), () -> change(e -> e.soundGain = step(e.soundGain, 0.1F)))
                .icon(Icon.SPEAKER).tip(tc("justreplay.entities.volume.tip")));
        f.full(new GlassSwitch(tc("justreplay.entities.mute"), () -> {
            ReplayProject.EntityEdit e = edit(false);
            return e != null && e.muted;
        }, () -> change(e -> e.muted = !e.muted)).icon(Icon.SPEAKER_OFF));
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            f.full(new GlassSwitch(tc("justreplay.entities.skinlayers"), () -> {
                ReplayProject.EntityEdit e = edit(false);
                return e == null || !e.hideSkinLayers;
            }, () -> change(e -> e.hideSkinLayers = !e.hideSkinLayers)).icon(Icon.PERSON).tip(tc("justreplay.entities.skinlayers.tip")));
            f.paragraph(tc("justreplay.bodyparts.title"), Theme.TEXT_MUTED);
            int column = 0;
            for (com.vyrriox.justreplay.editor.BodyParts.Part part : com.vyrriox.justreplay.editor.BodyParts.PARTS) {
                f.put(new GlassSwitch(tc("justreplay.bodyparts." + part.key()), () -> {
                    ReplayProject.EntityEdit e = edit(false);
                    return e == null || (e.hiddenParts & part.bit()) == 0;
                }, () -> change(e -> e.hiddenParts ^= part.bit())), column, 2);
                if (++column == 2) {
                    f.next();
                    column = 0;
                }
            }
            if (column != 0) {
                f.next();
            }
            skinSection.build(f, EntitiesPanel::selectedEntity, () -> labelOf(entity));
        }

        f.full(new GlassButton(tc("justreplay.entities.reset"), b -> {
            session.project().entityEdits.removeIf(e -> e.uuid.equals(uuid.toString()));
            save();
        }).icon(Icon.TRASH).style(GlassButton.Style.DANGER).tip(tc("justreplay.entities.reset.tip")));
    }

    private static float step(float value, float delta) {
        return Math.max(0.0F, Math.min(4.0F, Math.round((value + delta) * 10) / 10.0F));
    }
}
