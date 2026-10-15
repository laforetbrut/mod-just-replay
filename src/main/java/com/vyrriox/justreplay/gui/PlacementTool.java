package com.vyrriox.justreplay.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ActorAnimator;
import com.vyrriox.justreplay.editor.ActorPlacement;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * In-world placement of an actor. The editor steps aside so the free camera can fly and aim; the actor follows
 * the block under the crosshair as a live preview, the wheel turns it (Shift for fine steps), Ctrl snaps it to the
 * block center, a click writes a keyframe at the play head and Esc cancels. The same tool picks a seat, a bed or
 * a walking destination.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = JustReplay.MOD_ID, value = Dist.CLIENT)
public final class PlacementTool {
    private static final float STEP = 15.0F;
    private static final float FINE_STEP = 2.0F;
    private static PlacementTool active;

    private final EditorScreen editor;
    private final ReplaySession session;
    private final ReplayProject.ActorData actor;
    private final ActorPlacement.Kind kind;
    private final boolean isNew;
    private final float baseYaw;
    private final long startedAt = Anim.now();
    private float offset;
    private ActorPlacement.Spot spot;
    private long refusedAt;

    private PlacementTool(EditorScreen editor, ReplaySession session, ReplayProject.ActorData actor, ActorPlacement.Kind kind,
                          boolean isNew, float baseYaw) {
        this.editor = editor;
        this.session = session;
        this.actor = actor;
        this.kind = kind;
        this.isNew = isNew;
        this.baseYaw = baseYaw;
    }

    public static boolean isActive() {
        return active != null;
    }

    public static PlacementTool active() {
        return active;
    }

    public ReplayProject.ActorData actor() {
        return actor;
    }

    public ActorPlacement.Spot spot() {
        return spot;
    }

    /**
     * Starts placing an actor. {@code isNew} actors were just added to the project and are removed again when the
     * placement is cancelled.
     */
    public static void start(EditorScreen editor, ReplaySession session, ReplayProject.ActorData actor, ActorPlacement.Kind kind,
                             boolean isNew) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            active.finish(false);
        }
        if (!actor.visible) {
            actor.visible = true;
            session.actors().refresh(actor);
        }
        Entity entity = session.actors().entityOf(actor);
        float cameraYaw = mc.gameRenderer.getMainCamera().getYRot();
        // A new actor faces the camera; a moved one keeps its facing until the wheel turns it.
        float yaw = !isNew && entity != null && kind != ActorPlacement.Kind.WALK_TO ? entity.getYRot() : cameraYaw + 180.0F;
        active = new PlacementTool(editor, session, actor, kind, isNew, yaw);
        mc.setScreen(null);
        active.update();
    }

    /** Spot a placement gives for a ray hit; shared by the interactive tool and {@link #placeAt}. */
    static ActorPlacement.Spot spotFor(ActorPlacement.Kind kind, BlockHitResult hit, Entity entity, float yaw, float offset, boolean center) {
        return switch (kind) {
            case SIT -> ActorPlacement.seat(hit, yaw, offset);
            case BED -> {
                ActorPlacement.Spot bed = ActorPlacement.bed(hit, entity);
                if (bed.valid()) {
                    yield bed;
                }
                // Not a bed: show the actor standing where the crosshair is, refused on click.
                ActorPlacement.Spot ground = ActorPlacement.ground(hit, yaw + offset, center);
                yield new ActorPlacement.Spot(ground.x(), ground.y(), ground.z(), ground.yaw(), ActorAnimator.PreviewPose.STAND, null, false);
            }
            case WALK_TO -> ActorPlacement.ground(hit, yaw + offset, center);
            default -> ActorPlacement.ground(hit, yaw + offset, center);
        };
    }

    /** Places an actor without the interactive tool: the spot the hit gives, written at the play head. */
    public static ActorPlacement.Spot placeAt(ReplaySession session, ReplayProject.ActorData actor, ActorPlacement.Kind kind,
                                              BlockHitResult hit, float yaw) {
        ActorPlacement.Spot s = spotFor(kind, hit, session.actors().entityOf(actor), yaw, 0.0F, false);
        if (s.valid()) {
            ActorPlacement.apply(actor, kind, s, session.playback().cursor());
            session.saveProject();
        }
        return s;
    }

    private static boolean outline(ActorPlacement.Kind kind) {
        return kind == ActorPlacement.Kind.SIT || kind == ActorPlacement.Kind.BED;
    }

    /** Follows the crosshair; called every frame. */
    private void update() {
        if (ReplaySession.current() != session || !ReplaySession.isRunning() || VideoExporter.isActive()) {
            session.actors().clearPreview();
            active = null;
            return;
        }
        BlockHitResult hit = ActorPlacement.crosshair(outline(kind));
        spot = spotFor(kind, hit, session.actors().entityOf(actor), baseYaw, offset, Screen.hasControlDown());
        session.actors().setPreview(actor, spot);
    }

    private void rotate(double wheel) {
        offset += (float) Math.signum(wheel) * (Screen.hasShiftDown() ? FINE_STEP : STEP);
    }

    /** Writes the placement at the play head and returns to the editor. */
    public void confirm() {
        update();
        if (active != this) {
            return;
        }
        if (spot == null || !spot.valid()) {
            refusedAt = Anim.now();
            return;
        }
        ActorPlacement.apply(actor, kind, spot, session.playback().cursor());
        session.saveProject();
        finish(true);
    }

    /** Leaves without writing anything; a new actor is removed again. */
    public void cancel() {
        if (active == this) {
            finish(false);
        }
    }

    private void finish(boolean placed) {
        session.actors().clearPreview();
        if (!placed && isNew) {
            session.actors().remove(actor);
            session.project().actors.remove(actor);
            session.saveProject();
        }
        active = null;
        editor.afterPlacement(placed || !isNew ? actor : null);
        Minecraft.getInstance().setScreen(editor);
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
            active.rotate(event.getScrollDeltaY() != 0 ? event.getScrollDeltaY() : event.getScrollDeltaX());
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
        if (event.getAction() == GLFW.GLFW_PRESS && Anim.now() - active.startedAt > 150) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                active.confirm();
            } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                // A quarter turn, for lining up with walls and furniture.
                active.offset += 90.0F;
            }
        }
    }

    /** Esc opens the pause menu when no screen is shown: while placing it cancels instead. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (active == null || event.getNewScreen() == active.editor) {
            return;
        }
        PlacementTool tool = active;
        if (event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> {
                // pauseGame() pauses the sounds right after asking for the menu.
                mc.getSoundManager().resume();
                tool.cancel();
            });
        } else {
            // Another screen takes over (the editor key, a dialog): drop the placement without writing anything.
            tool.session.actors().clearPreview();
            if (tool.isNew) {
                tool.session.actors().remove(tool.actor);
                tool.session.project().actors.remove(tool.actor);
            }
            active = null;
        }
    }

    // ---------------------------------------------------------------- render

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || active == null || active.spot == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PlacementTool tool = active;
        ActorPlacement.Spot s = tool.spot;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float r = s.valid() ? 0.35F : 1.0F;
        float g = s.valid() ? 0.71F : 0.42F;
        float b = s.valid() ? 1.0F : 0.42F;
        if (s.block() != null && mc.level != null) {
            VoxelShape shape = mc.level.getBlockState(s.block()).getShape(mc.level, s.block());
            if (!shape.isEmpty()) {
                LevelRenderer.renderLineBox(pose, lines, shape.bounds().move(s.block()).inflate(0.004), 1.0F, 0.84F, 0.31F, 1.0F);
            }
        }
        Entity entity = tool.session.actors().entityOf(tool.actor);
        double half = entity != null ? Math.max(0.2, entity.getBbWidth() / 2.0) : 0.3;
        double height = entity != null ? entity.getBbHeight() : 1.8;
        if (s.pose() == ActorAnimator.PreviewPose.SLEEP) {
            height = 0.3;
        } else if (s.pose() == ActorAnimator.PreviewPose.SIT) {
            height = Math.max(0.5, height - 0.6);
        }
        LevelRenderer.renderLineBox(pose, lines, new AABB(s.x() - half, s.y(), s.z() - half, s.x() + half, s.y() + height, s.z() + half),
                r, g, b, 1.0F);
        // Facing arrow on the ground.
        Vec3 dir = Vec3.directionFromRotation(0.0F, s.yaw());
        Vec3 base = new Vec3(s.x(), s.y() + 0.03, s.z());
        Vec3 tip = base.add(dir.scale(half + 0.6));
        Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(0.18);
        Vec3 back = tip.subtract(dir.scale(0.25));
        PoseStack.Pose last = pose.last();
        line(lines, last, base, tip, r, g, b);
        line(lines, last, tip, back.add(side), r, g, b);
        line(lines, last, tip, back.subtract(side), r, g, b);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void line(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 b, float r, float g, float bl) {
        Vec3 n = b.subtract(a);
        if (n.lengthSqr() < 1.0E-8) {
            return;
        }
        n = n.normalize();
        c.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, 1.0F).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        c.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, 1.0F).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    /** Crosshair and the hint panel; a GUI layer kept by the clean HUD. */
    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        PlacementTool tool = active;
        if (tool == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int cy = h / 2;
        int color = tool.spot != null && tool.spot.valid() ? Theme.TEXT : Theme.DANGER;
        g.fill(cx - 5, cy, cx - 1, cy + 1, 0xA0000000);
        g.fill(cx + 2, cy, cx + 6, cy + 1, 0xA0000000);
        g.fill(cx, cy - 5, cx + 1, cy - 1, 0xA0000000);
        g.fill(cx, cy + 2, cx + 1, cy + 6, 0xA0000000);
        g.fill(cx - 4, cy, cx, cy + 1, color);
        g.fill(cx + 1, cy, cx + 5, cy + 1, color);
        g.fill(cx, cy - 4, cx + 1, cy, color);
        g.fill(cx, cy + 1, cx + 1, cy + 5, color);

        float a = Anim.easeOutCubic(Anim.progress(tool.startedAt, 220));
        String who = tool.actor.name.isBlank() ? tool.actor.type : tool.actor.name;
        Component title = Component.translatable("justreplay.place.title." + tool.kind.name().toLowerCase(java.util.Locale.ROOT), who);
        Component keys = Component.translatable("justreplay.place.keys");
        Component status = null;
        int statusColor = Theme.TEXT_MUTED;
        if (tool.spot != null && !tool.spot.valid()) {
            status = Component.translatable(tool.kind == ActorPlacement.Kind.BED ? "justreplay.place.need_bed" : "justreplay.place.need_block");
            statusColor = Anim.now() - tool.refusedAt < 900 ? Theme.DANGER : Theme.WARNING;
        }
        int boxW = Math.min(w - 16, Math.max(font.width(title) + 30, font.width(keys) + 16));
        int boxH = status == null ? 30 : 41;
        int bx = (w - boxW) / 2;
        int by = 8 - Math.round((1.0F - a) * 6);
        Paint.glass(g, bx, by, boxW, boxH, Theme.RADIUS_L);
        Icon icon = switch (tool.kind) {
            case SIT -> Icon.ARROW_DOWN;
            case BED -> Icon.CLOCK;
            case WALK_TO -> Icon.PATH;
            default -> Icon.PIN;
        };
        Paint.iconOnText(g, icon, bx + 8, by + 7, 9, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 30), bx + 21, by + 7, Theme.TEXT, false);
        g.drawString(font, Paint.ellipsize(font, keys.getString(), boxW - 16), bx + 8, by + 19, Theme.TEXT_DIM, false);
        if (status != null) {
            g.drawString(font, Paint.ellipsize(font, status.getString(), boxW - 16), bx + 8, by + 30, statusColor, false);
        }
    }
}
