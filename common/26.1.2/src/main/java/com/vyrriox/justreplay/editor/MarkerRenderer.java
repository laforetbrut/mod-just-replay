package com.vyrriox.justreplay.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Draws the markers in the world while editing (never in exports, nor while previewing the cameras): a point marker
 * as a post topped with a diamond, an entity marker as a box around its entity, each with its name, and the note of
 * the selected one. Markers away from the play head are dimmed. Called by {@link PathRenderer#onRenderLevel}.
 *
 * @author vyrriox
 */
public final class MarkerRenderer {
    /** Ticks around a marker's time during which it is drawn bright. */
    private static final double NEAR = 20.0;
    private static final double POST = 2.2;
    private static final int NOTE_WIDTH = 220;
    private static final int NOTE_LINES = 4;
    private static Supplier<String> highlighted = () -> null;
    private static final Map<String, Entity> FOUND = new HashMap<>();
    private static long calls;
    private static long drawnFrames;
    private static long exportFrames;

    private MarkerRenderer() {
    }

    /** Source of the id of the selected marker, drawn bright with its note. */
    public static void highlight(Supplier<String> source) {
        highlighted = source == null ? () -> null : source;
    }

    /** Level stage calls, frames that drew at least one marker, and frames an export kept free of markers. */
    public static long[] counters() {
        return new long[]{calls, drawnFrames, exportFrames};
    }

    /** Dimension id of the level shown, such as {@code minecraft:overworld}, or empty. */
    public static String dimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    /** The entity an entity marker points at in the level shown, or null when it is not there. */
    public static Entity entity(ReplaySession session, Marker m) {
        Minecraft mc = Minecraft.getInstance();
        if (session == null || mc.level == null || !m.isEntity() || m.target.isEmpty()) {
            return null;
        }
        Entity cached = FOUND.get(m.id + "|" + m.target);
        if (cached != null && !cached.isRemoved() && cached.level() == mc.level) {
            return cached;
        }
        Entity found = null;
        if (Marker.RECORDER.equals(m.target)) {
            found = session.findRecorder(mc);
        } else if (m.target.startsWith(Marker.ACTOR_PREFIX)) {
            String actorId = m.target.substring(Marker.ACTOR_PREFIX.length());
            for (ReplayProject.ActorData actor : session.project().actors) {
                if (actor.id.equals(actorId)) {
                    found = session.actors().entityOf(actor);
                    break;
                }
            }
        } else {
            UUID uuid;
            try {
                uuid = UUID.fromString(m.target);
            } catch (IllegalArgumentException e) {
                return null;
            }
            for (Entity e : mc.level.entitiesForRendering()) {
                if (uuid.equals(e.getUUID())) {
                    found = e;
                    break;
                }
            }
        }
        if (found == null || found.isRemoved() || found.level() != mc.level) {
            FOUND.remove(m.id + "|" + m.target);
            return null;
        }
        if (FOUND.size() > 256) {
            FOUND.clear();
        }
        FOUND.put(m.id + "|" + m.target, found);
        return found;
    }

    public static void onRenderLevel(Camera view, PoseStack poseStack, float partialTick) {
        calls++;
        ReplaySession session = ReplaySession.current();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || !ReplaySession.isRunning() || mc.level == null || !session.project().showMarkers
                || session.project().markers.isEmpty()) {
            return;
        }
        if (VideoExporter.isActive()) {
            exportFrames++;
            return;
        }
        if (mc.options.hideGui || session.isCameraPathActive()) {
            return;
        }
        String dimension = dimension();
        String selected = highlighted.get();
        double time = CameraController.frameTime(session, partialTick);
        List<Marker> shown = new ArrayList<>();
        List<double[]> places = new ArrayList<>();
        for (Marker m : session.project().markers) {
            double[] at = place(session, m, dimension, partialTick);
            if (at != null) {
                shown.add(m);
                places.add(at);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        drawnFrames++;
        Vec3 cam = view.position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        for (int i = 0; i < shown.size(); i++) {
            Marker m = shown.get(i);
            double[] at = places.get(i);
            boolean bright = m.id.equals(selected) || m.activeAt(time, NEAR);
            float[] rgb = rgb(m.color, bright ? 1.0F : 0.45F);
            if (m.isPoint()) {
                PathRenderer.line(lines, pose, at[0], at[1], at[2], at[0], at[1] + POST, at[2], rgb[0], rgb[1], rgb[2]);
                diamond(lines, pose, at[0], at[1] + POST, at[2], m.id.equals(selected) ? 0.3 : 0.22, rgb);
                PathRenderer.box(lines, pose, at[0], at[1], at[2], 0.08, rgb[0], rgb[1], rgb[2]);
            } else {
                double half = at[3];
                double height = at[4];
                double[][] c = new double[8][];
                for (int k = 0; k < 8; k++) {
                    c[k] = new double[]{at[0] + ((k & 1) == 0 ? -half : half), at[1] + ((k & 2) == 0 ? 0 : height),
                            at[2] + ((k & 4) == 0 ? -half : half)};
                }
                int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
                for (int[] e : edges) {
                    PathRenderer.line(lines, pose, c[e[0]][0], c[e[0]][1], c[e[0]][2], c[e[1]][0], c[e[1]][1], c[e[1]][2], rgb[0], rgb[1], rgb[2]);
                }
            }
        }
        buffers.endBatch(RenderTypes.lines());

        Font font = mc.font;
        for (int i = 0; i < shown.size(); i++) {
            Marker m = shown.get(i);
            double[] at = places.get(i);
            boolean sel = m.id.equals(selected);
            boolean bright = sel || m.activeAt(time, NEAR);
            // An entity's own name tag sits half a block over its head: the marker's name goes above it.
            double top = m.isPoint() ? at[1] + POST + 0.55 : at[1] + at[4] + 0.85;
            poseStack.pushPose();
            poseStack.translate(at[0], top, at[2]);
            poseStack.mulPose(view.rotation());
            poseStack.scale(0.025F, -0.025F, 0.025F);
            String label = m.name.isBlank() ? "-" : m.name;
            int color = sel ? 0xFFFFFFFF : (bright ? 0xFF000000 : 0x99000000) | (m.color & 0xFFFFFF);
            font.drawInBatch(label, -font.width(label) / 2.0F, 0, color, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0x55000000, LightCoordsUtil.FULL_BRIGHT);
            if (sel && !m.note.isBlank()) {
                int y = 11;
                for (String line : wrap(font, m.note, NOTE_WIDTH, NOTE_LINES)) {
                    font.drawInBatch(line, -font.width(line) / 2.0F, y, 0xFFD8E2EE, false, poseStack.last().pose(), buffers,
                            Font.DisplayMode.SEE_THROUGH, 0x55000000, LightCoordsUtil.FULL_BRIGHT);
                    y += 10;
                }
            }
            poseStack.popPose();
        }
        buffers.endBatch();
        poseStack.popPose();
    }

    /**
     * Where a marker is drawn in the level shown: x, y, z of a point; feet position, half width and height of an
     * entity's box. Null for a marker without a place, a point of another dimension or an entity that is not there.
     */
    private static double[] place(ReplaySession session, Marker m, String dimension, float partialTick) {
        if (m.isPoint()) {
            if (!m.dimension.isEmpty() && !m.dimension.equals(dimension)) {
                return null;
            }
            return new double[]{m.x, m.y, m.z};
        }
        if (m.isEntity()) {
            Entity e = entity(session, m);
            if (e == null || EntityEdits.isHidden(e)) {
                return null;
            }
            Vec3 p = e.getPosition(partialTick);
            double[] o = EntityEdits.offset(e);
            if (o != null) {
                p = p.add(o[0], o[1], o[2]);
            }
            return new double[]{p.x, p.y, p.z, Math.max(0.2, e.getBbWidth() / 2.0) + 0.05, e.getBbHeight() + 0.05};
        }
        return null;
    }

    /** Splits a note into lines of at most {@code width} pixels, on its line breaks and spaces. */
    static List<String> wrap(Font font, String text, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            String rest = paragraph.strip();
            while (!rest.isEmpty() && out.size() < maxLines) {
                String part = font.plainSubstrByWidth(rest, width);
                if (part.length() < rest.length()) {
                    int space = part.lastIndexOf(' ');
                    if (space > 0) {
                        part = part.substring(0, space);
                    }
                }
                if (part.isEmpty()) {
                    part = rest.substring(0, 1);
                }
                out.add(part);
                rest = rest.substring(part.length()).strip();
            }
            if (out.size() >= maxLines) {
                if (!rest.isEmpty() && !out.isEmpty()) {
                    out.set(out.size() - 1, out.get(out.size() - 1) + "...");
                }
                break;
            }
        }
        return out;
    }

    private static void diamond(VertexConsumer lines, PoseStack.Pose pose, double x, double y, double z, double s, float[] rgb) {
        double[][] tips = {{x + s, y, z}, {x, y, z + s}, {x - s, y, z}, {x, y, z - s}};
        for (int i = 0; i < 4; i++) {
            double[] a = tips[i];
            double[] b = tips[(i + 1) % 4];
            PathRenderer.line(lines, pose, a[0], a[1], a[2], b[0], b[1], b[2], rgb[0], rgb[1], rgb[2]);
            PathRenderer.line(lines, pose, a[0], a[1], a[2], x, y + s * 1.4, z, rgb[0], rgb[1], rgb[2]);
            PathRenderer.line(lines, pose, a[0], a[1], a[2], x, y - s * 1.4, z, rgb[0], rgb[1], rgb[2]);
        }
    }

    private static float[] rgb(int color, float brightness) {
        return new float[]{((color >> 16) & 0xFF) / 255.0F * brightness, ((color >> 8) & 0xFF) / 255.0F * brightness,
                (color & 0xFF) / 255.0F * brightness};
    }
}
