package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.mixin.EntityBoundSoundAccessor;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Applies the project's edits of recorded entities: hidden entities are not drawn and their sounds are silent,
 * moved ones are drawn at an offset (their physics and interpolation stay untouched), and the sounds of an
 * entity follow its own volume. The same queries serve live playback, previews and video exports.
 *
 * @author vyrriox
 */
public final class EntityEdits {
    /** A positional sound starting this close to an edited entity is attributed to it. */
    private static final double SOUND_RADIUS = 0.75;

    /** Entity highlighted by the editor (outline), or null. Never drawn in exports. */
    private static UUID selected;
    /** Counters for the self test: renders skipped and renders drawn with an offset. */
    private static int skippedRenders;
    private static int offsetRenders;

    private EntityEdits() {
    }

    private static ReplaySession active() {
        ReplaySession s = ReplaySession.current();
        return s != null && ReplaySession.isRunning() ? s : null;
    }

    /** Edit of a recorded entity in the running replay; actors and the local camera have none. */
    public static ReplayProject.EntityEdit editOf(Entity entity) {
        ReplaySession s = active();
        if (s == null || s.project().entityEdits.isEmpty() || ActorManager.isActorEntity(entity)
                || entity == Minecraft.getInstance().player) {
            return null;
        }
        return s.project().entityEdit(entity.getUUID().toString());
    }

    /** Time of the frame being drawn, in ticks: the same clock for live playback, previews and exports. */
    public static double time() {
        ReplaySession s = active();
        if (s == null) {
            return 0.0;
        }
        return CameraController.frameTime(s, Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
    }

    /**
     * Skin of a recorded player at the current frame from its timed skin changes, or null to keep its own skin.
     * Called for every player at every frame: bails out early when the project has no entity edits.
     */
    public static net.minecraft.client.resources.PlayerSkin skinOf(Entity entity) {
        ReplayProject.EntityEdit edit = editOf(entity);
        if (edit == null || edit.skinChanges.isEmpty()) {
            return null;
        }
        ReplayProject.SkinChange change = ReplayProject.SkinChange.at(edit.skinChanges, time());
        if (change == null || change.file.isBlank()) {
            return null;
        }
        return ActorManager.fileSkin(change.file, change.slim);
    }

    /** True when the project switches off the outer skin layer of this recorded player. */
    public static boolean hidesSkinLayers(Entity entity) {
        ReplayProject.EntityEdit edit = editOf(entity);
        return edit != null && edit.hideSkinLayers;
    }

    public static boolean isHidden(Entity entity) {
        ReplayProject.EntityEdit edit = editOf(entity);
        return edit != null && edit.hiddenAt(time());
    }

    /** Offset at the current frame: x, y, z, yaw; null when the entity is not moved. */
    public static double[] offset(Entity entity) {
        ReplayProject.EntityEdit edit = editOf(entity);
        if (edit == null || edit.offsets.isEmpty()) {
            return null;
        }
        return Interpolation.actor(edit.offsets, time());
    }

    // ---------------------------------------------------------------- sounds

    /** Volume factor of a sound for the entity edits: 1 when no edited entity made it. */
    public static float soundGain(SoundInstance sound) {
        ReplaySession s = active();
        if (s == null || sound == null || s.project().entityEdits.isEmpty()) {
            return 1.0F;
        }
        if (sound instanceof EntityBoundSoundInstance bound) {
            Entity entity = ((EntityBoundSoundAccessor) bound).justreplay$getEntity();
            ReplayProject.EntityEdit edit = entity == null ? null : editOf(entity);
            return edit == null ? 1.0F : gainOf(edit);
        }
        if (sound.isRelative() || sound.getAttenuation() == SoundInstance.Attenuation.NONE) {
            return 1.0F;
        }
        return soundGainAt(sound.getX(), sound.getY(), sound.getZ());
    }

    /** Volume factor of a positional sound starting at this point. */
    public static float soundGainAt(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        ReplaySession s = active();
        if (s == null || mc.level == null || s.project().entityEdits.isEmpty()) {
            return 1.0F;
        }
        double r2 = SOUND_RADIUS * SOUND_RADIUS;
        for (Entity entity : mc.level.entitiesForRendering()) {
            // Feet or eyes: footsteps play at the feet, voices and eating near the head.
            double dx = entity.getX() - x;
            double dz = entity.getZ() - z;
            double h = dx * dx + dz * dz;
            if (h > r2) {
                continue;
            }
            double dy = Math.min(Math.abs(entity.getY() - y), Math.abs(entity.getEyeY() - y));
            if (h + dy * dy > r2) {
                continue;
            }
            ReplayProject.EntityEdit edit = editOf(entity);
            if (edit != null) {
                return gainOf(edit);
            }
        }
        return 1.0F;
    }

    private static float gainOf(ReplayProject.EntityEdit edit) {
        if (edit.muted || edit.hiddenAt(time())) {
            return 0.0F;
        }
        return edit.soundGain;
    }

    // ---------------------------------------------------------------- highlight

    public static void select(Entity entity) {
        selected = entity == null ? null : entity.getUUID();
    }

    public static void selectUuid(UUID uuid) {
        selected = uuid;
    }

    public static UUID selected() {
        return selected;
    }

    /** True for the entity selected in the open editor; never during an export. */
    public static boolean highlighted(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        return selected != null && active() != null && !VideoExporter.isActive()
                && (mc.screen instanceof com.vyrriox.justreplay.gui.EditorScreen || com.vyrriox.justreplay.gui.EntityTool.isActive()) && selected.equals(entity.getUUID());
    }

    // ---------------------------------------------------------------- self test

    public static void countSkipped() {
        skippedRenders++;
    }

    public static void countOffset() {
        offsetRenders++;
    }

    public static int skippedRenders() {
        return skippedRenders;
    }

    public static int offsetRenders() {
        return offsetRenders;
    }
}
