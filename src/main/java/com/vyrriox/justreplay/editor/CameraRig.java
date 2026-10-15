package com.vyrriox.justreplay.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Computes what a camera sees at a replay time, and what the cut track shows: the live camera, blended with the
 * previous one during a smooth transition.
 *
 * <p>A camera state is {@code x, y, z, yaw, pitch, roll, fov} where the position is the feet of the free camera,
 * like the keyframes; the eyes are {@link #EYE_HEIGHT} above it.
 *
 * @author vyrriox
 */
public final class CameraRig {
    /** Eye height of the free (spectator) camera. */
    public static final double EYE_HEIGHT = 1.62;
    /** A follow camera that was not evaluated for this long (or went back in time) snaps to its target. */
    private static final double SMOOTH_RESET_TICKS = 10.0;

    private static final Map<String, Follow> FOLLOW = new HashMap<>();

    private CameraRig() {
    }

    /** Smoothed state of one follow camera, and the entity it last found. */
    private static final class Follow {
        double time = Double.NaN;
        double[] eye;
        double[] look;
        Entity entity;
    }

    /**
     * State of a camera at a replay time, or null when it has nothing to show (path without keyframes, follow
     * target never seen).
     *
     * @param live true for the frame actually rendered: advances the smoothing of follow cameras
     */
    public static double[] sample(ReplayProject.Camera camera, double time, float partialTick, boolean live) {
        if (camera == null) {
            return null;
        }
        if (camera.isStatic()) {
            return new double[]{camera.x, camera.y, camera.z, camera.yaw, camera.pitch, camera.roll, camera.fov};
        }
        if (camera.isFollow()) {
            return follow(camera, time, partialTick, live);
        }
        return CameraPath.of(camera).sample(time);
    }

    /** What the project shows at a time: the cut track when it has cuts, else the selected camera. */
    public static double[] sampleProject(ReplayProject project, double time, float partialTick, boolean live) {
        if (project.cuts.isEmpty()) {
            return sample(project.selected(), time, partialTick, live);
        }
        int index = project.cutIndexAt(time);
        ReplayProject.Cut cut = project.cuts.get(index);
        double[] state = sample(project.camera(cut.camera), time, partialTick, live);
        if (index > 0 && cut.blend > 0 && time >= cut.tick && time < cut.tick + cut.blend) {
            double[] previous = sample(project.camera(project.cuts.get(index - 1).camera), time, partialTick, live);
            double f = (time - cut.tick) / cut.blend;
            state = blend(previous, state, f * f * (3.0 - 2.0 * f));
        }
        return state;
    }

    /** Interpolates two states, angles the short way round; either may be null. */
    public static double[] blend(double[] a, double[] b, double f) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        double[] out = new double[7];
        for (int c = 0; c < 7; c++) {
            double delta = b[c] - a[c];
            if (c == 3 || c == 5) {
                delta = Mth.wrapDegrees(delta);
            }
            out[c] = a[c] + delta * f;
        }
        return out;
    }

    /** Forgets the smoothing of follow cameras, for a new session. */
    public static void reset() {
        FOLLOW.clear();
    }

    // ---------------------------------------------------------------- follow

    private static double[] follow(ReplayProject.Camera camera, double time, float partialTick, boolean live) {
        Follow follow = FOLLOW.computeIfAbsent(camera.id, k -> new Follow());
        Entity entity = find(camera, follow);
        if (entity == null) {
            return follow.eye == null ? null : state(camera, follow.eye, follow.look);
        }
        Vec3 pos = entity.getPosition(partialTick);
        double eyeY = pos.y + entity.getEyeHeight();
        float angle;
        switch (camera.followMode) {
            case "ORBIT" -> angle = (float) (camera.orbitSpeed * time / 20.0);
            case "SIDE" -> angle = bodyYaw(entity, partialTick) + 90.0F;
            case "FRONT" -> angle = bodyYaw(entity, partialTick) + 180.0F;
            default -> angle = bodyYaw(entity, partialTick);
        }
        double rad = Math.toRadians(angle);
        // Forward of the angle is (-sin, 0, cos); the camera stands back along it, looking at the target's eyes.
        double[] eye = {pos.x + Math.sin(rad) * camera.distance, eyeY + camera.height, pos.z - Math.cos(rad) * camera.distance};
        double[] look = {pos.x, eyeY, pos.z};
        if (!live) {
            return state(camera, eye, look);
        }
        double dt = time - follow.time;
        if (follow.eye == null || Double.isNaN(dt) || dt <= 0 || dt > SMOOTH_RESET_TICKS || camera.smoothing <= 0) {
            follow.eye = eye;
            follow.look = look;
        } else {
            double alpha = 1.0 - Math.exp(-dt / (camera.smoothing * 20.0));
            for (int i = 0; i < 3; i++) {
                follow.eye[i] += (eye[i] - follow.eye[i]) * alpha;
                follow.look[i] += (look[i] - follow.look[i]) * alpha;
            }
        }
        follow.time = time;
        return state(camera, follow.eye, follow.look);
    }

    private static float bodyYaw(Entity entity, float partialTick) {
        if (entity instanceof LivingEntity living) {
            return Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
        }
        return entity.getViewYRot(partialTick);
    }

    private static double[] state(ReplayProject.Camera camera, double[] eye, double[] look) {
        double dx = look[0] - eye[0];
        double dy = look[1] - eye[1];
        double dz = look[2] - eye[2];
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return CameraPath.clamp(new double[]{eye[0], eye[1] - EYE_HEIGHT, eye[2], yaw, pitch, camera.roll, camera.fov});
    }

    /** The followed entity, looked up again when the world replaced it (seeks, dimension changes). */
    private static Entity find(ReplayProject.Camera camera, Follow follow) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || camera.target.isEmpty()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(camera.target);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Entity cached = follow.entity;
        if (cached != null && !cached.isRemoved() && cached.level() == mc.level && uuid.equals(cached.getUUID())) {
            return cached;
        }
        follow.entity = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player && !entity.isRemoved() && uuid.equals(entity.getUUID())) {
                follow.entity = entity;
                return entity;
            }
        }
        return null;
    }
}
