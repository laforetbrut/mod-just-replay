package com.vyrriox.justreplay.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Draws the camera path in the world (never in exports): the curve as a line, each keyframe as a small box
 * with a short line showing where the camera looks.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class PathRenderer {
    private static final int STEPS_PER_SEGMENT = 24;

    private PathRenderer() {
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
        List<ReplayProject.CameraKeyframe> keys = session.project().cameraKeyframes;
        if (keys.isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        CameraPath path = CameraPath.of(session.project());
        if (path.size() > 1) {
            int steps = (path.size() - 1) * STEPS_PER_SEGMENT;
            double[] prev = path.evaluate(0);
            for (int i = 1; i <= steps; i++) {
                double[] cur = path.evaluate((double) i / STEPS_PER_SEGMENT);
                line(lines, pose, prev[0], prev[1] + 1.62, prev[2], cur[0], cur[1] + 1.62, cur[2], 0.31F, 0.76F, 0.97F);
                prev = cur;
            }
        }
        for (ReplayProject.CameraKeyframe k : keys) {
            double y = k.y + 1.62;
            box(lines, pose, k.x, y, k.z, 0.15, 1.0F, 0.84F, 0.31F);
            Vec3 look = Vec3.directionFromRotation(k.pitch, k.yaw).scale(0.8);
            line(lines, pose, k.x, y, k.z, k.x + look.x, y + look.y, k.z + look.z, 1.0F, 1.0F, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        poseStack.popPose();
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
