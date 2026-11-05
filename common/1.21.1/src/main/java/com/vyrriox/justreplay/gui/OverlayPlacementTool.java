package com.vyrriox.justreplay.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ActorPlacement;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.fx.FxParam;
import com.vyrriox.justreplay.editor.overlay.Overlay;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Optional;

/**
 * In-world placement of an overlay, like the actor placement tool: the editor steps aside, the overlay follows the
 * crosshair as a live preview and a click anchors it to what is aimed at. An entity (the recording player, an
 * actor or any recorded entity) is followed, a block face holds it, and empty air (or anything while Ctrl is held)
 * gives a fixed position at a distance the wheel changes. Esc cancels.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class OverlayPlacementTool {
    private static final double MIN_DISTANCE = 0.5;
    private static final double MAX_DISTANCE = 64.0;
    private static OverlayPlacementTool active;

    /** What the crosshair aims at. */
    private enum Kind { ENTITY, BLOCK, POSITION }

    private record Aim(Kind kind, Vec3 point, BlockPos block, Direction face, Entity entity, String target, String name) {
    }

    private final EditorScreen editor;
    private final ReplaySession session;
    private final Overlay overlay;
    private final boolean isNew;
    /** Anchor of the overlay before the placement, put back on cancel. */
    private final Overlay before;
    private final long startedAt = Anim.now();
    private double distance = 4.0;
    private Aim aim;

    private OverlayPlacementTool(EditorScreen editor, ReplaySession session, Overlay overlay, boolean isNew) {
        this.editor = editor;
        this.session = session;
        this.overlay = overlay;
        this.isNew = isNew;
        this.before = overlay.copy();
    }

    public static boolean isActive() {
        return active != null;
    }

    public static OverlayPlacementTool active() {
        return active;
    }

    /** Starts placing an overlay; {@code isNew} overlays are removed again when the placement is cancelled. */
    public static void start(EditorScreen editor, ReplaySession session, Overlay overlay, boolean isNew) {
        if (active != null) {
            active.cancel();
        }
        overlay.visible = true;
        active = new OverlayPlacementTool(editor, session, overlay, isNew);
        OverlayRenderer.force(overlay.id);
        Minecraft.getInstance().setScreen(null);
        active.update();
    }

    /** Anchors an overlay where a ray hits, without the interactive tool (self test, quick actions). */
    public static void anchorAt(ReplaySession session, Overlay overlay, Vec3 from, Vec3 direction, double freeDistance, boolean isNew) {
        Aim aim = aim(session, from, direction, freeDistance, false);
        apply(overlay, aim, isNew, Minecraft.getInstance().gameRenderer.getMainCamera().getYRot());
    }

    // ---------------------------------------------------------------- aiming

    private static Aim aim(ReplaySession session, Vec3 from, Vec3 direction, double freeDistance, boolean free) {
        Vec3 dir = direction.normalize();
        Vec3 freePoint = from.add(dir.scale(freeDistance));
        if (free) {
            return new Aim(Kind.POSITION, freePoint, null, null, null, "", "");
        }
        BlockHitResult hit = ActorPlacement.raycast(from, dir, true);
        double blockDistance = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().distanceToSqr(from) : Double.MAX_VALUE;
        Minecraft mc = Minecraft.getInstance();
        Entity best = null;
        double bestDistance = blockDistance;
        Vec3 to = from.add(dir.scale(ActorPlacement.REACH));
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == mc.player || e == mc.getCameraEntity() || e.isRemoved()) {
                    continue;
                }
                AABB box = e.getBoundingBox().inflate(Math.max(0.1, e.getPickRadius()));
                Optional<Vec3> clip = box.contains(from) ? Optional.of(from) : box.clip(from, to);
                if (clip.isPresent()) {
                    double d = clip.get().distanceToSqr(from);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = e;
                    }
                }
            }
        }
        if (best != null) {
            String[] target = targetOf(session, best);
            return new Aim(Kind.ENTITY, best.position().add(0, best.getBbHeight(), 0), null, null, best, target[0], target[1]);
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            return new Aim(Kind.BLOCK, hit.getLocation(), hit.getBlockPos(), hit.getDirection(), null, "", "");
        }
        return new Aim(Kind.POSITION, freePoint, null, null, null, "", "");
    }

    /** Target string and display name of an entity: the recording player, an actor, or a recorded entity. */
    static String[] targetOf(ReplaySession session, Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == session.findRecorder(mc)) {
            return new String[]{Overlay.RECORDER, Component.translatable("justreplay.overlay.anchor.recorder").getString()};
        }
        for (ReplayProject.ActorData actor : session.project().actors) {
            if (session.actors().entityOf(actor) == entity) {
                String name = actor.name.isBlank() ? entity.getName().getString() : actor.name;
                return new String[]{Overlay.ACTOR_PREFIX + actor.id, name};
            }
        }
        return new String[]{entity.getUUID().toString(), entity.getName().getString()};
    }

    /** Writes an aim into the overlay; a new overlay also gets the facing and offsets that suit the anchor. */
    private static void apply(Overlay o, Aim aim, boolean isNew, float cameraYaw) {
        switch (aim.kind()) {
            case ENTITY -> {
                o.anchor = Overlay.ENTITY;
                o.target = aim.target();
                o.targetName = aim.name();
            }
            case BLOCK -> {
                o.anchor = Overlay.BLOCK;
                o.blockX = aim.block().getX();
                o.blockY = aim.block().getY();
                o.blockZ = aim.block().getZ();
                o.face = aim.face().getName().toUpperCase(Locale.ROOT);
            }
            default -> {
                o.anchor = Overlay.POSITION;
                o.x = aim.point().x;
                o.y = aim.point().y;
                o.z = aim.point().z;
            }
        }
        if (isNew) {
            o.facing = aim.kind() == Kind.BLOCK ? Overlay.FLAT : Overlay.BILLBOARD;
            for (FxParam p : new FxParam[]{Overlay.OFFSET_X, Overlay.OFFSET_Y, Overlay.OFFSET_Z}) {
                o.setBase(p, 0.0F);
            }
            if (aim.kind() == Kind.ENTITY) {
                // Just above the head.
                o.setBase(Overlay.OFFSET_Y, 0.35F);
            }
            // Fixed overlays and overlays lying on a floor or a ceiling read the right way from where the camera is.
            o.setBase(Overlay.YAW, net.minecraft.util.Mth.wrapDegrees(cameraYaw));
        } else if (Overlay.FLAT.equals(o.facing) && aim.kind() != Kind.BLOCK) {
            o.facing = Overlay.BILLBOARD;
        }
    }

    /** Follows the crosshair; called every frame. */
    private void update() {
        if (ReplaySession.current() != session || !ReplaySession.isRunning() || VideoExporter.isActive()) {
            OverlayRenderer.force(null);
            active = null;
            return;
        }
        net.minecraft.client.Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        aim = aim(session, camera.getPosition(), new Vec3(camera.getLookVector()), distance, Screen.hasControlDown());
        apply(overlay, aim, isNew, camera.getYRot());
    }

    /** Anchors the overlay where the crosshair aims and returns to the editor. */
    public void confirm() {
        update();
        if (active != this) {
            return;
        }
        session.saveProject();
        finish(true);
    }

    /** Leaves without changing the anchor; a new overlay is removed again. */
    public void cancel() {
        if (active != this) {
            return;
        }
        if (isNew) {
            session.project().overlays.remove(overlay);
        } else {
            overlay.copyAnchor(before);
        }
        session.saveProject();
        finish(false);
    }

    private void finish(boolean placed) {
        OverlayRenderer.force(null);
        active = null;
        OverlaysPanel.select(placed || !isNew ? overlay.id : null);
        Minecraft.getInstance().setScreen(editor);
        editor.openTab("overlays");
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (active != null) {
            active.update();
        }
    }

    private static boolean placing() {
        Minecraft mc = Minecraft.getInstance();
        return active != null && mc.screen == null && mc.getOverlay() == null;
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (placing()) {
            double wheel = event.getScrollDeltaY() != 0 ? event.getScrollDeltaY() : event.getScrollDeltaX();
            double step = Screen.hasShiftDown() ? 0.1 : 0.5;
            active.distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, active.distance + Math.signum(wheel) * step));
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Pre event) {
        if (!placing() || event.getButton() > GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }
        event.setCanceled(true);
        // Ignore the click that started the tool, still held down when the editor closed.
        if (event.getAction() == GLFW.GLFW_PRESS && Anim.now() - active.startedAt > 150 && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            active.confirm();
        }
    }

    /** Esc opens the pause menu when no screen is shown: while placing it cancels instead. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (active == null || event.getNewScreen() == active.editor) {
            return;
        }
        OverlayPlacementTool tool = active;
        if (event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> {
                // pauseGame() pauses the sounds right after asking for the menu.
                mc.getSoundManager().resume();
                tool.cancel();
            });
        } else {
            // Another screen takes over: drop the placement without changing anything.
            if (tool.isNew) {
                tool.session.project().overlays.remove(tool.overlay);
            } else {
                tool.overlay.copyAnchor(tool.before);
            }
            OverlayRenderer.force(null);
            active = null;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || active == null || active.aim == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Aim aim = active.aim;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = new PoseStack();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        switch (aim.kind()) {
            case ENTITY -> LevelRenderer.renderLineBox(pose, lines, aim.entity().getBoundingBox().inflate(0.05), 1.0F, 0.84F, 0.31F, 1.0F);
            case BLOCK -> {
                if (mc.level != null) {
                    var shape = mc.level.getBlockState(aim.block()).getShape(mc.level, aim.block());
                    AABB box = shape.isEmpty() ? new AABB(aim.block()) : shape.bounds().move(aim.block());
                    LevelRenderer.renderLineBox(pose, lines, box.inflate(0.004), 0.35F, 0.71F, 1.0F, 1.0F);
                }
            }
            default -> {
                Vec3 p = aim.point();
                double s = 0.15;
                LevelRenderer.renderLineBox(pose, lines, new AABB(p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s), 0.36F, 0.89F, 0.61F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    /** Crosshair and the hint panel, over the world. */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        OverlayPlacementTool tool = active;
        if (tool == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int cy = h / 2;
        g.fill(cx - 5, cy, cx - 1, cy + 1, 0xA0000000);
        g.fill(cx + 2, cy, cx + 6, cy + 1, 0xA0000000);
        g.fill(cx, cy - 5, cx + 1, cy - 1, 0xA0000000);
        g.fill(cx, cy + 2, cx + 1, cy + 6, 0xA0000000);
        g.fill(cx - 4, cy, cx, cy + 1, Theme.TEXT);
        g.fill(cx + 1, cy, cx + 5, cy + 1, Theme.TEXT);
        g.fill(cx, cy - 4, cx + 1, cy, Theme.TEXT);
        g.fill(cx, cy + 1, cx + 1, cy + 5, Theme.TEXT);

        float a = Anim.easeOutCubic(Anim.progress(tool.startedAt, 220));
        Component title = Component.translatable(tool.overlay.isImage() ? "justreplay.overlay.place.title.image" : "justreplay.overlay.place.title.text",
                tool.overlay.displayName());
        String keys = Component.translatable("justreplay.overlay.place.keys").getString();
        String status = tool.aim == null ? "" : switch (tool.aim.kind()) {
            case ENTITY -> Component.translatable("justreplay.overlay.place.on_entity", tool.aim.name()).getString();
            case BLOCK -> Component.translatable("justreplay.overlay.place.on_block",
                    mc.level == null ? "?" : mc.level.getBlockState(tool.aim.block()).getBlock().getName().getString(),
                    Component.translatable("justreplay.overlay.face." + tool.aim.face().getName()).getString()).getString();
            default -> Component.translatable("justreplay.overlay.place.free", String.format(Locale.ROOT, "%.1f", tool.distance)).getString();
        };
        // The key groups wrap onto more lines in a narrow window instead of being cut.
        java.util.List<String> keyLines = new java.util.ArrayList<>();
        String line = "";
        for (String group : keys.split(" {2,}")) {
            String joined = line.isEmpty() ? group : line + "   " + group;
            if (line.isEmpty() || font.width(joined) <= w - 32) {
                line = joined;
            } else {
                keyLines.add(line);
                line = group;
            }
        }
        keyLines.add(line);
        int keysW = font.width(status);
        for (String l : keyLines) {
            keysW = Math.max(keysW, font.width(l));
        }
        int boxW = Math.min(w - 16, Math.max(font.width(title) + 30, keysW + 16));
        int boxH = 19 + keyLines.size() * 11 + 11;
        int bx = (w - boxW) / 2;
        int by = 8 - Math.round((1.0F - a) * 6);
        Paint.glass(g, bx, by, boxW, boxH, Theme.RADIUS_L);
        Paint.iconOnText(g, Icon.PIN, bx + 8, by + 7, 9, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 30), bx + 21, by + 7, Theme.TEXT, false);
        int ly = by + 19;
        for (String l : keyLines) {
            g.drawString(font, Paint.ellipsize(font, l, boxW - 16), bx + 8, ly, Theme.TEXT_DIM, false);
            ly += 11;
        }
        g.drawString(font, Paint.ellipsize(font, status, boxW - 16), bx + 8, ly, Theme.ACCENT, false);
    }
}
