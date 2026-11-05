package com.vyrriox.justreplay.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;

/**
 * Wireframe boxes for the line render type. The 1.21 helper {@code LevelRenderer.renderLineBox} no longer exists,
 * and every line vertex now carries its own width.
 *
 * @author vyrriox
 */
public final class LineBoxes {
    /** Width of the placement tool lines, the thin line these tools drew before 26.1. */
    public static final float WIDTH = 1.0F;

    private LineBoxes() {
    }

    public static void box(PoseStack pose, VertexConsumer c, AABB b, float r, float g, float bl, float a) {
        PoseStack.Pose p = pose.last();
        float x0 = (float) b.minX;
        float y0 = (float) b.minY;
        float z0 = (float) b.minZ;
        float x1 = (float) b.maxX;
        float y1 = (float) b.maxY;
        float z1 = (float) b.maxZ;
        for (float y : new float[] {y0, y1}) {
            edge(c, p, x0, y, z0, x1, y, z0, r, g, bl, a);
            edge(c, p, x1, y, z0, x1, y, z1, r, g, bl, a);
            edge(c, p, x1, y, z1, x0, y, z1, r, g, bl, a);
            edge(c, p, x0, y, z1, x0, y, z0, r, g, bl, a);
        }
        edge(c, p, x0, y0, z0, x0, y1, z0, r, g, bl, a);
        edge(c, p, x1, y0, z0, x1, y1, z0, r, g, bl, a);
        edge(c, p, x1, y0, z1, x1, y1, z1, r, g, bl, a);
        edge(c, p, x0, y0, z1, x0, y1, z1, r, g, bl, a);
    }

    /** One line segment; a segment of no length is skipped, it has no normal. */
    public static void edge(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1,
                            float r, float g, float bl, float a) {
        float nx = x1 - x0;
        float ny = y1 - y0;
        float nz = z1 - z0;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-4F) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        c.addVertex(p, x0, y0, z0).setColor(r, g, bl, a).setNormal(p, nx, ny, nz).setLineWidth(WIDTH);
        c.addVertex(p, x1, y1, z1).setColor(r, g, bl, a).setNormal(p, nx, ny, nz).setLineWidth(WIDTH);
    }
}
