package com.vyrriox.justreplay.editor.overlay;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Draws the text and image overlays of the project in the world: fullbright, depth tested (or over everything
 * when an overlay is set to show through walls), from the camera's point of view. They are drawn right after the
 * translucent blocks, inside the world pass, so the editor preview, playback, video exports and shader packs all
 * see them; if a chunk renderer skips that stage, they are drawn after the particles instead.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class OverlayRenderer {
    /** Text pixels per line: the font is 9 pixels high, plus one of spacing. */
    private static final int LINE = 10;
    /** Distance of the plate behind the text, in blocks (keeps the depth test apart at a distance). */
    private static final float PLATE_DEPTH = 0.01F;
    /** Distance of the shadow and the outline behind the text, in blocks. */
    private static final float DECO_DEPTH = 0.004F;
    /** Segments of each rounded corner of a plate. */
    private static final int CORNER_STEPS = 5;
    private static final int[][] OUTLINE_OFFSETS = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    /** Cached followed entity of each overlay, with the target it was found for. */
    private record Followed(String target, Entity entity) {
    }

    private static final Map<String, Followed> FOLLOWED = new HashMap<>();
    private static final Map<ResourceLocation, RenderType[]> IMAGE_TYPES = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();

    private static long frame;
    private static long drawnFrame = -1;
    /** Overlay drawn even outside its visible range (being placed), or null. */
    private static String forcedId;
    /** Id of the overlay framed in the world while editing (the one selected in the editor), or null. */
    private static java.util.function.Supplier<String> highlighted = () -> null;
    /** Overlays drawn in the last frame, and frames of the running export that showed at least one. */
    private static int lastDrawn;
    private static int exportFrames;

    private OverlayRenderer() {
    }

    /** Draws an overlay even outside its visible range, for a placement preview; null to stop. */
    public static void force(String overlayId) {
        forcedId = overlayId;
    }

    /** Tells which overlay to frame while editing; it is never framed in exports or with the interface hidden. */
    public static void highlight(java.util.function.Supplier<String> overlayId) {
        highlighted = overlayId == null ? () -> null : overlayId;
    }

    public static int lastDrawn() {
        return lastDrawn;
    }

    public static int exportFrames() {
        return exportFrames;
    }

    public static void resetExportFrames() {
        exportFrames = 0;
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        frame++;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean main = stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        boolean fallback = stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES && drawnFrame != frame;
        if (!main && !fallback) {
            return;
        }
        drawnFrame = frame;
        ReplaySession session = ReplaySession.current();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || !ReplaySession.isRunning() || mc.level == null || session.project().overlays.isEmpty()) {
            lastDrawn = 0;
            return;
        }
        render(mc, session, event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (ReplaySession.current() == null) {
            FOLLOWED.clear();
            WARNED.clear();
            forcedId = null;
            if (!ImageLibrary.isEmpty()) {
                ImageLibrary.releaseAll();
            }
        }
    }

    // ---------------------------------------------------------------- anchors

    /**
     * World point the overlay hangs from at a time, offsets included: the position, the block face, or above the
     * head of the followed entity (interpolated with the partial tick). Null when the followed entity is not in
     * the world.
     */
    public static Vec3 position(ReplaySession session, Overlay o, double time, float partialTick) {
        Vec3 base = anchorPoint(session, o, partialTick);
        if (base == null) {
            return null;
        }
        return base.add(o.value(Overlay.OFFSET_X, time), o.value(Overlay.OFFSET_Y, time), o.value(Overlay.OFFSET_Z, time));
    }

    /** The anchor itself, without the offsets. */
    public static Vec3 anchorPoint(ReplaySession session, Overlay o, float partialTick) {
        switch (o.anchor) {
            case Overlay.BLOCK -> {
                Direction face = face(o);
                return new Vec3(o.blockX + 0.5 + face.getStepX() * 0.51, o.blockY + 0.5 + face.getStepY() * 0.51,
                        o.blockZ + 0.5 + face.getStepZ() * 0.51);
            }
            case Overlay.ENTITY -> {
                Entity entity = entity(session, o);
                if (entity == null) {
                    return null;
                }
                return entity.getPosition(partialTick).add(0, entity.getBbHeight(), 0);
            }
            default -> {
                return new Vec3(o.x, o.y, o.z);
            }
        }
    }

    public static Direction face(Overlay o) {
        Direction d = Direction.byName(o.face == null ? "up" : o.face.toLowerCase(java.util.Locale.ROOT));
        return d == null ? Direction.UP : d;
    }

    /**
     * The entity an overlay follows: the recording player, an actor, or a recorded entity by UUID. Looked up again
     * when the world replaced it (seeks, dimension changes).
     */
    public static Entity entity(ReplaySession session, Overlay o) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || o.target == null || o.target.isEmpty()) {
            return null;
        }
        Followed cached = FOLLOWED.get(o.id);
        if (cached != null && cached.target().equals(o.target) && !cached.entity().isRemoved() && cached.entity().level() == mc.level) {
            return cached.entity();
        }
        FOLLOWED.remove(o.id);
        Entity found = null;
        if (Overlay.RECORDER.equals(o.target)) {
            found = session.findRecorder(mc);
        } else if (o.target.startsWith(Overlay.ACTOR_PREFIX)) {
            String actorId = o.target.substring(Overlay.ACTOR_PREFIX.length());
            for (ReplayProject.ActorData actor : session.project().actors) {
                if (actor.id.equals(actorId)) {
                    found = session.actors().entityOf(actor);
                    break;
                }
            }
            if (found != null && (found.isRemoved() || found.level() != mc.level)) {
                found = null;
            }
        } else {
            UUID uuid;
            try {
                uuid = UUID.fromString(o.target);
            } catch (IllegalArgumentException e) {
                return null;
            }
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e != mc.player && !e.isRemoved() && uuid.equals(e.getUUID())) {
                    found = e;
                    break;
                }
            }
        }
        if (found != null) {
            FOLLOWED.put(o.id, new Followed(o.target, found));
        }
        return found;
    }

    /** Orientation of an overlay: local +Z points towards its viewer, +X to its right, +Y up. */
    static Quaternionf orientation(Overlay o, double time, Camera camera) {
        float roll = o.value(Overlay.ROLL, time);
        if (o.isBillboard()) {
            return new Quaternionf(camera.rotation()).rotateZ((float) Math.toRadians(-roll));
        }
        float yaw = o.value(Overlay.YAW, time);
        float pitch = o.value(Overlay.PITCH, time);
        if (Overlay.FLAT.equals(o.facing)) {
            Direction face = face(o);
            if (face == Direction.UP) {
                pitch = 90.0F;
            } else if (face == Direction.DOWN) {
                pitch = -90.0F;
            } else {
                // Seen by a viewer looking into the face.
                yaw = face.getOpposite().toYRot();
                pitch = 0.0F;
            }
        }
        // The rotation of a camera with that yaw and pitch: the overlay faces such a camera.
        return new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yaw), (float) Math.toRadians(-pitch),
                (float) Math.toRadians(-roll));
    }

    // ---------------------------------------------------------------- drawing

    private record Pending(Overlay overlay, Vec3 at, double distance, float alpha) {
    }

    private static void render(Minecraft mc, ReplaySession session, Camera camera, float partialTick) {
        double time = CameraController.frameTime(session, partialTick);
        Vec3 cam = camera.getPosition();
        List<Pending> list = new ArrayList<>();
        for (Overlay o : session.project().overlays) {
            boolean forced = o.id.equals(forcedId);
            if (!forced && !o.visibleAt(time)) {
                continue;
            }
            float alpha = o.fade(time) * o.value(Overlay.OPACITY, time);
            if (forced) {
                // A placement preview stays readable whatever the play head and the fades.
                alpha = Math.max(alpha, 0.6F);
            }
            if (alpha <= 0.01F) {
                continue;
            }
            try {
                Vec3 at = position(session, o, time, partialTick);
                if (at != null) {
                    list.add(new Pending(o, at, at.distanceToSqr(cam), alpha));
                }
            } catch (Throwable t) {
                warn(o, t);
            }
        }
        lastDrawn = 0;
        if (list.isEmpty()) {
            return;
        }
        // Far ones first, so translucent overlays blend over the ones behind them.
        list.sort((a, b) -> Double.compare(b.distance(), a.distance()));
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack poseStack = new PoseStack();
        String highlightId = VideoExporter.isActive() || mc.options.hideGui ? null : highlighted.get();
        for (Pending p : list) {
            Overlay o = p.overlay();
            try {
                poseStack.pushPose();
                poseStack.translate(p.at().x - cam.x, p.at().y - cam.y, p.at().z - cam.z);
                poseStack.mulPose(orientation(o, time, camera));
                float scale = o.value(Overlay.SCALE, time);
                float[] half = null;
                int sides = o.isBillboard() ? 1 : 2;
                for (int side = 0; side < sides; side++) {
                    if (side == 1) {
                        // The back side reads the right way too, like a sign.
                        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                    }
                    half = o.isImage() ? drawImage(o, poseStack, buffers, scale, p.alpha())
                            : drawText(mc.font, o, poseStack, buffers, scale, p.alpha());
                }
                if (o.id.equals(highlightId) && half != null) {
                    frame(poseStack, buffers, half[0], half[1]);
                }
                lastDrawn++;
            } catch (Throwable t) {
                warn(o, t);
            } finally {
                poseStack.popPose();
            }
        }
        if (VideoExporter.isActive() && lastDrawn > 0) {
            exportFrames++;
        }
    }

    private static void warn(Overlay o, Throwable t) {
        if (WARNED.add(o.id)) {
            JustReplay.LOGGER.warn("Overlay {} cannot be drawn: {}", o.displayName(), t.toString());
        }
    }

    /** Draws a text overlay in the current pose; returns its half width and height in blocks. */
    private static float[] drawText(Font font, Overlay o, PoseStack poseStack, MultiBufferSource.BufferSource buffers, float scale, float alpha) {
        List<String> lines = o.lines();
        int n = lines.size();
        int[] widths = new int[n];
        int textW = 0;
        for (int i = 0; i < n; i++) {
            widths[i] = font.width(lines.get(i));
            textW = Math.max(textW, widths[i]);
        }
        float s = o.fontSize * scale / LINE;
        float textH = n * LINE - 2;
        float top = -textH / 2.0F;
        float pad = o.plate ? o.padding : 0.0F;
        float[] half = {(textW / 2.0F + pad) * s, (textH / 2.0F + pad) * s};
        boolean through = o.throughWalls;
        if (o.plate && o.plateOpacity > 0.0F) {
            int a = Math.round(255 * alpha * o.plateOpacity);
            if (a > 2) {
                poseStack.pushPose();
                poseStack.translate(0, 0, -PLATE_DEPTH);
                poseStack.scale(s, -s, s);
                RenderType type = through ? RenderType.textBackgroundSeeThrough() : RenderType.textBackground();
                float radius = Math.min(pad + 2.0F, Math.min(textW / 2.0F + pad, textH / 2.0F + pad));
                plate(buffers.getBuffer(type), poseStack.last().pose(), -textW / 2.0F - pad, top - pad, textW / 2.0F + pad,
                        top + textH + pad, radius, a, o.plateColor);
                buffers.endBatch(type);
                poseStack.popPose();
            }
        }
        int ta = Math.round(255 * alpha);
        if (ta < 4 || textW == 0) {
            // The font treats an alpha under 4 as opaque.
            return half;
        }
        Font.DisplayMode mode = through ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.POLYGON_OFFSET;
        int light = LightTexture.FULL_BRIGHT;
        if (o.outline || o.shadow) {
            poseStack.pushPose();
            poseStack.translate(0, 0, -DECO_DEPTH);
            poseStack.scale(s, -s, s);
            Matrix4f m = poseStack.last().pose();
            int decoColor = (ta << 24) | (o.outline ? o.outlineColor : darken(o.color));
            for (int i = 0; i < n; i++) {
                String plain = ChatFormatting.stripFormatting(lines.get(i));
                if (plain == null || plain.isEmpty()) {
                    continue;
                }
                float lx = lineX(o, widths[i], textW);
                float ly = top + i * LINE;
                if (o.outline) {
                    for (int[] d : OUTLINE_OFFSETS) {
                        font.drawInBatch(plain, lx + d[0], ly + d[1], decoColor, false, m, buffers, mode, 0, light);
                    }
                } else {
                    font.drawInBatch(plain, lx + 1, ly + 1, decoColor, false, m, buffers, mode, 0, light);
                }
            }
            poseStack.popPose();
        }
        poseStack.pushPose();
        poseStack.scale(s, -s, s);
        Matrix4f m = poseStack.last().pose();
        int color = (ta << 24) | (o.color & 0xFFFFFF);
        for (int i = 0; i < n; i++) {
            if (!lines.get(i).isEmpty()) {
                font.drawInBatch(lines.get(i), lineX(o, widths[i], textW), top + i * LINE, color, false, m, buffers, mode, 0, light);
            }
        }
        poseStack.popPose();
        buffers.endBatch();
        return half;
    }

    private static float lineX(Overlay o, int lineW, int textW) {
        return switch (o.align) {
            case Overlay.LEFT -> -textW / 2.0F;
            case Overlay.RIGHT -> textW / 2.0F - lineW;
            default -> -lineW / 2.0F;
        };
    }

    /** Shadow colour of a text colour, as the font darkens it. */
    private static int darken(int rgb) {
        int r = (rgb >> 16 & 0xFF) / 4;
        int g = (rgb >> 8 & 0xFF) / 4;
        int b = (rgb & 0xFF) / 4;
        return r << 16 | g << 8 | b;
    }

    /** Draws an image overlay in the current pose; returns its half width and height in blocks, or null. */
    private static float[] drawImage(Overlay o, PoseStack poseStack, MultiBufferSource.BufferSource buffers, float scale, float alpha) {
        ImageLibrary.Loaded image = ImageLibrary.get(o.image);
        if (image == null) {
            return null;
        }
        float w = o.width * scale;
        float h = o.keepAspect ? w * image.aspect() : o.height * scale;
        int a = Math.round(255 * alpha);
        if (a <= 0) {
            return new float[]{w / 2, h / 2};
        }
        RenderType type = imageType(image.texture(), o.throughWalls);
        VertexConsumer vc = buffers.getBuffer(type);
        Matrix4f m = poseStack.last().pose();
        int color = (a << 24) | (o.tint & 0xFFFFFF);
        int light = LightTexture.FULL_BRIGHT;
        float x0 = -w / 2, x1 = w / 2, y0 = -h / 2, y1 = h / 2;
        // Counter-clockwise seen from the front: top left, bottom left, bottom right, top right.
        vc.addVertex(m, x0, y1, 0).setColor(color).setUv(0, 0).setLight(light);
        vc.addVertex(m, x0, y0, 0).setColor(color).setUv(0, 1).setLight(light);
        vc.addVertex(m, x1, y0, 0).setColor(color).setUv(1, 1).setLight(light);
        vc.addVertex(m, x1, y1, 0).setColor(color).setUv(1, 0).setLight(light);
        buffers.endBatch(type);
        return new float[]{w / 2, h / 2};
    }

    /**
     * Render type of an image: the text shader (fullbright, translucent, understood by shader packs) with a smooth
     * texture filter, depth tested or drawn over everything.
     */
    private static RenderType imageType(ResourceLocation texture, boolean through) {
        RenderType[] types = IMAGE_TYPES.computeIfAbsent(texture, t -> new RenderType[2]);
        int i = through ? 1 : 0;
        if (types[i] == null) {
            RenderType.CompositeState.CompositeStateBuilder builder = RenderType.CompositeState.builder()
                    .setShaderState(through ? RenderStateShard.RENDERTYPE_TEXT_SEE_THROUGH_SHADER : RenderStateShard.RENDERTYPE_TEXT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(RenderStateShard.LIGHTMAP);
            if (through) {
                builder.setDepthTestState(RenderStateShard.NO_DEPTH_TEST).setWriteMaskState(RenderStateShard.COLOR_WRITE);
            }
            types[i] = RenderType.create(JustReplay.MOD_ID + "_overlay_image" + (through ? "_see_through" : ""),
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 256, false, true,
                    builder.createCompositeState(false));
        }
        return types[i];
    }

    /** Called when the images are released: their render types point at textures that no longer exist. */
    static void forgetImageTypes() {
        IMAGE_TYPES.clear();
    }

    /**
     * Rounded plate in text pixels (y down), as a fan from its center with a slightly lighter top, like the glass
     * panels of the interface.
     */
    private static void plate(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float radius, int alpha, int rgb) {
        List<float[]> outline = new ArrayList<>();
        float r = Math.max(0.0F, radius);
        float[][] centers = {{x1 - r, y0 + r}, {x1 - r, y1 - r}, {x0 + r, y1 - r}, {x0 + r, y0 + r}};
        float[] starts = {-90.0F, 0.0F, 90.0F, 180.0F};
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i <= CORNER_STEPS; i++) {
                double angle = Math.toRadians(starts[c] + 90.0 * i / CORNER_STEPS);
                outline.add(new float[]{centers[c][0] + (float) Math.cos(angle) * r, centers[c][1] + (float) Math.sin(angle) * r});
            }
        }
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        int top = alpha << 24 | mixWhite(rgb, 0.12F);
        int bottom = alpha << 24 | (rgb & 0xFFFFFF);
        int center = alpha << 24 | mixWhite(rgb, 0.06F);
        int light = LightTexture.FULL_BRIGHT;
        int n = outline.size();
        for (int i = 0; i < n; i++) {
            float[] a = outline.get(i);
            float[] b = outline.get((i + 1) % n);
            // Keep every triangle counter-clockwise on screen (y down here), the way the glyphs are wound.
            float cross = (a[0] - cx) * (b[1] - cy) - (a[1] - cy) * (b[0] - cx);
            if (cross > 0) {
                float[] t = a;
                a = b;
                b = t;
            }
            int ca = gradient(a[1], y0, y1, top, bottom);
            int cb = gradient(b[1], y0, y1, top, bottom);
            vc.addVertex(m, cx, cy, 0).setColor(center).setLight(light);
            vc.addVertex(m, a[0], a[1], 0).setColor(ca).setLight(light);
            vc.addVertex(m, b[0], b[1], 0).setColor(cb).setLight(light);
            vc.addVertex(m, b[0], b[1], 0).setColor(cb).setLight(light);
        }
    }

    private static int gradient(float y, float y0, float y1, int top, int bottom) {
        float t = (y - y0) / Math.max(0.001F, y1 - y0);
        int a = top >>> 24;
        int r = Math.round((top >> 16 & 0xFF) + ((bottom >> 16 & 0xFF) - (top >> 16 & 0xFF)) * t);
        int g = Math.round((top >> 8 & 0xFF) + ((bottom >> 8 & 0xFF) - (top >> 8 & 0xFF)) * t);
        int b = Math.round((top & 0xFF) + ((bottom & 0xFF) - (top & 0xFF)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int mixWhite(int rgb, float t) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        r = Math.round(r + (255 - r) * t);
        g = Math.round(g + (255 - g) * t);
        b = Math.round(b + (255 - b) * t);
        return r << 16 | g << 8 | b;
    }

    /** Selection frame around an overlay, in its plane. */
    private static void frame(PoseStack poseStack, MultiBufferSource.BufferSource buffers, float hw, float hh) {
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();
        float m = 0.04F;
        float[][] corners = {{-hw - m, hh + m}, {hw + m, hh + m}, {hw + m, -hh - m}, {-hw - m, -hh - m}};
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i];
            float[] b = corners[(i + 1) % 4];
            float nx = b[0] - a[0], ny = b[1] - a[1];
            float len = (float) Math.sqrt(nx * nx + ny * ny);
            if (len < 1.0E-6F) {
                continue;
            }
            lines.addVertex(pose, a[0], a[1], 0).setColor(1.0F, 0.84F, 0.31F, 1.0F).setNormal(pose, nx / len, ny / len, 0);
            lines.addVertex(pose, b[0], b[1], 0).setColor(1.0F, 0.84F, 0.31F, 1.0F).setNormal(pose, nx / len, ny / len, 0);
        }
        buffers.endBatch(RenderType.lines());
    }
}
