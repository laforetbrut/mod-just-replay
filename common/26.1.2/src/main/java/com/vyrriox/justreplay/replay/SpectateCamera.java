package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Views the replay through a recorded entity, like the vanilla spectator mode. The target is remembered by UUID
 * because recorded packets rebuild the world (seeks, dimension changes) and replace entity instances; it is
 * looked up again every tick. The free camera (the local player) follows the target's eyes, so leaving puts it
 * exactly where the view was.
 *
 * @author vyrriox
 */
public final class SpectateCamera {
    /** Distance at which a left click can pick an entity to spectate. */
    private static final double PICK_RANGE = 64.0;
    /** Client ticks a target may be missing (respawn, dimension change) before the free camera takes over. */
    private static final int MISSING_GRACE_TICKS = 40;

    private UUID target;
    private boolean recorder;
    private Component name = Component.empty();
    private int missingTicks;

    public boolean isActive() {
        return target != null;
    }

    /** True while following the recording player. */
    public boolean isFollowingRecorder() {
        return target != null && recorder;
    }

    public Component targetName() {
        return name;
    }

    /** Starts viewing through an entity; the local camera itself can never be a target. */
    public void start(Minecraft mc, Entity entity, boolean isRecorder) {
        if (entity == null || mc.player == null || entity == mc.player) {
            return;
        }
        target = entity.getUUID();
        recorder = isRecorder;
        name = entity.getDisplayName();
        missingTicks = 0;
        attach(mc, entity);
    }

    /** Returns to the free camera, placed at the eyes of the entity that was viewed. */
    public void stop(Minecraft mc) {
        if (target == null) {
            return;
        }
        Entity viewed = mc.getCameraEntity();
        target = null;
        recorder = false;
        missingTicks = 0;
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (viewed != null && viewed != player && !viewed.isRemoved()) {
            followEyes(player, viewed);
        }
        mc.setCameraEntity(player);
    }

    /** Forgets the target without touching the camera, for when the world it belonged to is gone. */
    public void clear() {
        target = null;
        recorder = false;
        missingTicks = 0;
    }

    /** Re-acquires the target after world resets and keeps the free camera on its eyes. */
    public void tick(Minecraft mc, ReplaySession session) {
        if (target == null) {
            return;
        }
        if (mc.player == null || mc.level == null || session.isCameraPathActive()) {
            stop(mc);
            return;
        }
        if (mc.screen == null && mc.options.keyShift.isDown()) {
            stop(mc);
            return;
        }
        Entity entity = find(mc);
        if (entity == null) {
            if (mc.getCameraEntity() != mc.player) {
                mc.setCameraEntity(mc.player);
            }
            if (++missingTicks > MISSING_GRACE_TICKS) {
                JustReplay.LOGGER.debug("Spectated entity {} is gone, back to the free camera", target);
                stop(mc);
            }
            return;
        }
        missingTicks = 0;
        name = entity.getDisplayName();
        attach(mc, entity);
    }

    private void attach(Minecraft mc, Entity entity) {
        if (mc.getCameraEntity() != entity) {
            mc.setCameraEntity(entity);
        }
        followEyes(mc.player, entity);
    }

    private Entity find(Minecraft mc) {
        Entity current = mc.getCameraEntity();
        if (current != null && current != mc.player && !current.isRemoved() && current.level() == mc.level
                && target.equals(current.getUUID())) {
            return current;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (target.equals(entity.getUUID()) && entity != mc.player && !entity.isRemoved()) {
                return entity;
            }
        }
        return null;
    }

    /** Keeps the invisible free camera on the target's eyes, so chunks and sounds follow and leaving is seamless. */
    private static void followEyes(LocalPlayer player, Entity entity) {
        Vec3 eye = entity.position().add(0, entity.getEyeHeight() - player.getEyeHeight(), 0);
        Playback.placeCamera(player, eye, entity.getYRot(), entity.getXRot());
    }

    /**
     * Entity under the crosshair of the current view, further away than the spectator reach and blocked by
     * terrain. The local camera and spectators are never picked.
     */
    public static Entity pick(Minecraft mc) {
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null || mc.player == null) {
            return null;
        }
        Vec3 eye = camera.getEyePosition(1.0F);
        Vec3 look = camera.getViewVector(1.0F);
        double range = PICK_RANGE;
        HitResult block = camera.pick(PICK_RANGE, 1.0F, false);
        if (block.getType() != HitResult.Type.MISS) {
            range = block.getLocation().distanceTo(eye);
        }
        Vec3 end = eye.add(look.scale(range));
        AABB box = camera.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, eye, end, box,
                e -> e != mc.player && !e.isSpectator() && e.isPickable()
                        && !com.vyrriox.justreplay.editor.EntityEdits.isHidden(e), range * range);
        return hit == null ? null : hit.getEntity();
    }
}
