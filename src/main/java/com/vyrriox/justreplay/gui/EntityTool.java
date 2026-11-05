package com.vyrriox.justreplay.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.ActorPlacement;
import com.vyrriox.justreplay.editor.EntityEdits;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.SpectateCamera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Camera;
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
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * In-world tools of the Entities tab. PICK selects the recorded entity under the crosshair; MOVE drops the
 * selected entity on the block under the crosshair (the wheel turns it, Ctrl snaps to the block center) and
 * writes its offset keyframe at the play head. A click confirms, Esc or the editor key cancels.
 *
 * @author vyrriox
 */
public final class EntityTool {
    public enum Mode { PICK, MOVE }

    private static final float STEP = 15.0F;
    private static final float FINE_STEP = 2.0F;
    private static EntityTool active;

    private final EditorScreen editor;
    private final ReplaySession session;
    private final Mode mode;
    private final long startedAt = Anim.now();
    private float turn;
    private Entity target;
    private ActorPlacement.Spot spot;

    private EntityTool(EditorScreen editor, ReplaySession session, Mode mode) {
        this.editor = editor;
        this.session = session;
        this.mode = mode;
    }

    public static boolean isActive() {
        return active != null;
    }

    public static void start(EditorScreen editor, ReplaySession session, Mode mode) {
        if (active != null) {
            active.finish();
        }
        EntityTool tool = new EntityTool(editor, session, mode);
        if (mode == Mode.MOVE) {
            Entity entity = EntitiesPanel.selectedEntity();
            if (entity == null) {
                return;
            }
            ReplayProject.EntityEdit edit = session.project().entityEdit(entity.getUUID().toString());
            double[] o = edit == null ? null : EntityEdits.offset(entity);
            tool.turn = o == null ? 0.0F : (float) o[3];
        }
        active = tool;
        Minecraft.getInstance().setScreen(null);
        tool.update();
    }

    /** Follows the crosshair; called every frame. */
    private void update() {
        if (ReplaySession.current() != session || !ReplaySession.isRunning() || VideoExporter.isActive()) {
            active = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.PICK) {
            Entity e = SpectateCamera.pick(mc);
            target = e == null || ActorManager.isActorEntity(e) ? null : e;
        } else {
            target = EntitiesPanel.selectedEntity();
            spot = ActorPlacement.ground(ActorPlacement.crosshair(false), 0.0F, Screen.hasControlDown());
        }
    }

    private void confirm() {
        update();
        if (active != this || target == null) {
            return;
        }
        if (mode == Mode.PICK) {
            EntitiesPanel.select(target);
        } else {
            if (spot == null || !spot.valid()) {
                return;
            }
            float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            Vec3 real = target.getPosition(partial);
            ReplayProject.EntityEdit edit = EntitiesPanel.ensureEdit(session, target);
            int tick = session.playback().cursor();
            edit.offsets.removeIf(k -> k.tick == tick);
            edit.offsets.add(new ReplayProject.ActorKeyframe(tick, spot.x() - real.x, spot.y() - real.y, spot.z() - real.z, turn, 0.0F));
            edit.normalize();
            session.saveProject();
        }
        finish();
    }

    private void finish() {
        active = null;
        Minecraft.getInstance().setScreen(editor);
    }

    // ---------------------------------------------------------------- events

    public static void onRenderFrame() {
        if (active != null) {
            active.update();
        }
    }

    private static boolean using() {
        Minecraft mc = Minecraft.getInstance();
        return active != null && mc.screen == null && mc.getOverlay() == null;
    }

    public static boolean onScroll(double deltaX, double deltaY) {
        if (using() && active.mode == Mode.MOVE) {
            double wheel = deltaY != 0 ? deltaY : deltaX;
            active.turn += (float) Math.signum(wheel) * (Screen.hasShiftDown() ? FINE_STEP : STEP);
            return true;
        }
        return false;
    }

    public static boolean onMouse(int button, int action) {
        if (!using() || button > GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return false;
        }
        // Ignore the click that started the tool, still held down when the editor closed.
        if (action == GLFW.GLFW_PRESS && Anim.now() - active.startedAt > 150) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                active.confirm();
            } else if (active.mode == Mode.MOVE) {
                active.turn += 90.0F;
            }
        }
        return true;
    }

    /** Esc while using the tool cancels it instead of opening the pause menu; another screen drops it. */
    public static boolean onScreenOpening(Screen newScreen) {
        if (active == null || newScreen == active.editor) {
            return false;
        }
        EntityTool tool = active;
        if (newScreen instanceof PauseScreen) {
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> {
                mc.getSoundManager().resume();
                if (active == tool) {
                    tool.finish();
                }
            });
            return true;
        } else {
            active = null;
        }
        return false;
    }

    /** The editor key while using the tool goes back to the editor. */
    public static void cancel() {
        if (active != null) {
            active.finish();
        }
    }

    public static void onRenderLevel(Camera camera, PoseStack pose) {
        if (active == null || active.target == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EntityTool tool = active;
        Entity e = tool.target;
        Vec3 cam = camera.getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        double half = Math.max(0.2, e.getBbWidth() / 2.0);
        double height = e.getBbHeight();
        if (tool.mode == Mode.PICK) {
            float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
            Vec3 p = e.getPosition(partial);
            double[] o = EntityEdits.offset(e);
            if (o != null) {
                p = p.add(o[0], o[1], o[2]);
            }
            LevelRenderer.renderLineBox(pose, lines, new AABB(p.x - half, p.y, p.z - half, p.x + half, p.y + height, p.z + half),
                    1.0F, 0.84F, 0.31F, 1.0F);
        } else if (tool.spot != null) {
            ActorPlacement.Spot s = tool.spot;
            float g = s.valid() ? 0.71F : 0.42F;
            float b = s.valid() ? 1.0F : 0.42F;
            LevelRenderer.renderLineBox(pose, lines, new AABB(s.x() - half, s.y(), s.z() - half, s.x() + half, s.y() + height, s.z() + half),
                    s.valid() ? 0.35F : 1.0F, g, b, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** Crosshair and the hint panel; a GUI layer kept by the clean HUD. */
    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        EntityTool tool = active;
        if (tool == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        int w = g.guiWidth();
        int cx = w / 2;
        int cy = g.guiHeight() / 2;
        int color = tool.target != null ? Theme.TEXT : Theme.WARNING;
        g.fill(cx - 4, cy, cx + 5, cy + 1, color);
        g.fill(cx, cy - 4, cx + 1, cy + 5, color);
        String name = tool.target == null ? "-" : EntitiesPanel.labelOf(tool.target);
        Component title = Component.translatable("justreplay.entities.tool." + (tool.mode == Mode.PICK ? "pick" : "move"), name);
        Component keys = Component.translatable("justreplay.entities.tool.keys." + (tool.mode == Mode.PICK ? "pick" : "move"));
        int boxW = Math.min(w - 16, Math.max(font.width(title) + 30, font.width(keys) + 16));
        int bx = (w - boxW) / 2;
        int by = 8;
        Paint.glass(g, bx, by, boxW, 30, Theme.RADIUS_L);
        Paint.iconOnText(g, tool.mode == Mode.PICK ? Icon.TARGET : Icon.PIN, bx + 8, by + 7, 9, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 30), bx + 21, by + 7, Theme.TEXT, false);
        g.drawString(font, Paint.ellipsize(font, keys.getString(), boxW - 16), bx + 8, by + 19, Theme.TEXT_DIM, false);
    }
}
