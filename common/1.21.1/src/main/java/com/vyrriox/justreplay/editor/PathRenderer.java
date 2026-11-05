package com.vyrriox.justreplay.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Draws the cameras in the world (never in exports, nor while previewing): path cameras as their curve with a
 * small box per keyframe, static and follow cameras as a frustum gizmo with their name. The selected camera is
 * bright, the others are dimmed.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class PathRenderer {
    private static final int STEPS_PER_SEGMENT = 24;
    /** Colours given to cameras by their position in the list; shared with the timeline. */
    private static final int[] COLORS = {0x4FC3F7, 0xFF8A65, 0xAED581, 0xBA68C8, 0xFFD54F, 0x4DB6AC, 0xF06292, 0x9575CD, 0xA1887F};

    private PathRenderer() {
    }

    /** Opaque ARGB colour of the camera at a list index. */
    public static int cameraColor(int index) {
        return 0xFF000000 | COLORS[Math.floorMod(index, COLORS.length)];
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        ReplaySession session = ReplaySession.current();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || !ReplaySession.isRunning() || VideoExporter.isActive() || mc.options.hideGui
                || !session.project().showPath || session.isCameraPathActive()) {
            return;
        }
        ReplayProject project = session.project();
        Camera view = event.getCamera();
        Vec3 cam = view.getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        ReplayProject.Camera selected = project.selected();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = CameraController.frameTime(session, partialTick);
        List<ReplayProject.Camera> cameras = project.cameras;
        for (int i = 0; i < cameras.size(); i++) {
            ReplayProject.Camera camera = cameras.get(i);
            boolean bright = camera == selected;
            float[] rgb = rgb(cameraColor(i), bright ? 1.0F : 0.45F);
            if (camera.isPath()) {
                drawPath(lines, pose, camera, rgb, bright);
            } else {
                double[] state = CameraRig.sample(camera, time, partialTick, false);
                if (state != null) {
                    frustum(lines, pose, state, rgb);
                }
            }
        }
        buffers.endBatch(RenderType.lines());

        // Names above the gizmos and at the start of each path.
        Font font = mc.font;
        for (int i = 0; i < cameras.size(); i++) {
            ReplayProject.Camera camera = cameras.get(i);
            double[] at = null;
            if (camera.isPath()) {
                if (!camera.keyframes.isEmpty()) {
                    ReplayProject.CameraKeyframe k = camera.keyframes.get(0);
                    at = new double[]{k.x, k.y, k.z};
                }
            } else {
                at = CameraRig.sample(camera, time, partialTick, false);
            }
            if (at == null) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(at[0], at[1] + CameraRig.EYE_HEIGHT + 0.45, at[2]);
            poseStack.mulPose(view.rotation());
            poseStack.scale(0.025F, -0.025F, 0.025F);
            String label = (i + 1) + " " + camera.name;
            int color = camera == selected ? 0xFFFFFFFF : (0xFF000000 | (cameraColor(i) & 0xFFFFFF));
            font.drawInBatch(label, -font.width(label) / 2.0F, 0, color, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0x55000000, LightTexture.FULL_BRIGHT);
            poseStack.popPose();
        }
        buffers.endBatch();
        poseStack.popPose();
    }

    private static void drawPath(VertexConsumer lines, PoseStack.Pose pose, ReplayProject.Camera camera, float[] rgb, boolean selected) {
        List<ReplayProject.CameraKeyframe> keys = camera.keyframes;
        if (keys.isEmpty()) {
            return;
        }
        CameraPath path = CameraPath.of(camera);
        if (path.size() > 1) {
            int steps = (path.size() - 1) * STEPS_PER_SEGMENT;
            double[] prev = path.evaluate(0);
            for (int i = 1; i <= steps; i++) {
                double[] cur = path.evaluate((double) i / STEPS_PER_SEGMENT);
                line(lines, pose, prev[0], prev[1] + CameraRig.EYE_HEIGHT, prev[2], cur[0], cur[1] + CameraRig.EYE_HEIGHT, cur[2],
                        rgb[0], rgb[1], rgb[2]);
                prev = cur;
            }
        }
        for (ReplayProject.CameraKeyframe k : keys) {
            double y = k.y + CameraRig.EYE_HEIGHT;
            if (selected) {
                box(lines, pose, k.x, y, k.z, 0.15, 1.0F, 0.84F, 0.31F);
            } else {
                box(lines, pose, k.x, y, k.z, 0.1, rgb[0], rgb[1], rgb[2]);
            }
            Vec3 look = Vec3.directionFromRotation(k.pitch, k.yaw).scale(0.8);
            line(lines, pose, k.x, y, k.z, k.x + look.x, y + look.y, k.z + look.z, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Pyramid from the eye towards the view, sized by the field of view, with a small mark on its top edge. */
    private static void frustum(VertexConsumer c, PoseStack.Pose pose, double[] state, float[] rgb) {
        double ex = state[0];
        double ey = state[1] + CameraRig.EYE_HEIGHT;
        double ez = state[2];
        Vec3 f = Vec3.directionFromRotation((float) state[4], (float) state[3]);
        Vec3 right = f.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(f).normalize();
        double roll = Math.toRadians(state[5]);
        Vec3 r = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
        Vec3 u = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll)));
        double depth = 0.7;
        double halfH = depth * Math.tan(Math.toRadians(state[6]) / 2.0);
        double halfW = halfH * 16.0 / 9.0;
        Vec3 center = new Vec3(ex, ey, ez).add(f.scale(depth));
        Vec3[] corners = {
                center.add(r.scale(-halfW)).add(u.scale(halfH)),
                center.add(r.scale(halfW)).add(u.scale(halfH)),
                center.add(r.scale(halfW)).add(u.scale(-halfH)),
                center.add(r.scale(-halfW)).add(u.scale(-halfH))};
        for (int i = 0; i < 4; i++) {
            Vec3 a = corners[i];
            Vec3 b = corners[(i + 1) % 4];
            line(c, pose, ex, ey, ez, a.x, a.y, a.z, rgb[0], rgb[1], rgb[2]);
            line(c, pose, a.x, a.y, a.z, b.x, b.y, b.z, rgb[0], rgb[1], rgb[2]);
        }
        Vec3 topMid = center.add(u.scale(halfH));
        Vec3 tip = topMid.add(u.scale(halfH * 0.6));
        line(c, pose, corners[0].x + (topMid.x - corners[0].x) * 0.5, corners[0].y + (topMid.y - corners[0].y) * 0.5,
                corners[0].z + (topMid.z - corners[0].z) * 0.5, tip.x, tip.y, tip.z, rgb[0], rgb[1], rgb[2]);
        line(c, pose, corners[1].x + (topMid.x - corners[1].x) * 0.5, corners[1].y + (topMid.y - corners[1].y) * 0.5,
                corners[1].z + (topMid.z - corners[1].z) * 0.5, tip.x, tip.y, tip.z, rgb[0], rgb[1], rgb[2]);
        box(c, pose, ex, ey, ez, 0.08, rgb[0], rgb[1], rgb[2]);
    }

    private static float[] rgb(int color, float brightness) {
        return new float[]{((color >> 16) & 0xFF) / 255.0F * brightness, ((color >> 8) & 0xFF) / 255.0F * brightness,
                (color & 0xFF) / 255.0F * brightness};
    }

    private static void line(VertexConsumer c, PoseStack.Pose pose, double x1, double y1, double z1, double x2, double y2, double z2,
                             float r, float g, float b) {
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6F) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        c.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, 1.0F).setNormal(pose, nx, ny, nz);
        c.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(r, g, b, 1.0F).setNormal(pose, nx, ny, nz);
    }

    private static void box(VertexConsumer c, PoseStack.Pose pose, double x, double y, double z, double s, float r, float g, float b) {
        double[][] corners = new double[8][];
        for (int i = 0; i < 8; i++) {
            corners[i] = new double[]{x + ((i & 1) == 0 ? -s : s), y + ((i & 2) == 0 ? -s : s), z + ((i & 4) == 0 ? -s : s)};
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            double[] a = corners[e[0]];
            double[] o = corners[e[1]];
            line(c, pose, a[0], a[1], a[2], o[0], o[1], o[2], r, g, b);
        }
    }
}
