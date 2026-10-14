package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The replay editor: transport, timeline with markers and keyframes, camera path and actors. The world keeps
 * running behind it, the screen does not pause the game.
 *
 * @author vyrriox
 */
public class EditorScreen extends Screen {
    private static final int ROW = 18;

    private int panelW = 170;
    private int speedX;
    private int cameraInfoY;
    private int actorListY;
    private int actorRows = 4;

    private final ReplaySession session;
    private ReplayProject.CameraKeyframe selectedCamera;
    private ReplayProject.ActorData selectedActor;
    private ReplayProject.ActorKeyframe selectedActorKey;
    private int actorScroll;

    private int tlX, tlY, tlW, tlH;
    private boolean scrubbing;
    private int ghostTick = -1;
    private Object dragging;
    private boolean dragMoved;
    private double pressX;
    private double grabOffset;

    private Button playButton;
    private Button pathButton;
    private final List<Button> actorButtons = new ArrayList<>();

    public EditorScreen(ReplaySession session) {
        super(Component.translatable("justreplay.editor.title"));
        this.session = session;
    }

    @Override
    protected void init() {
        actorButtons.clear();
        panelW = Math.min(170, (width - 24) / 2 - 20);
        tlX = 8;
        tlW = width - 16;
        tlH = 12;
        tlY = height - 18;

        // Transport row
        int y = height - 40;
        int x = 8;
        x = add(x, y, 18, "|<", b -> session.playback().seek(0));
        x = add(x, y, 26, "-" + stepSeconds() + "s", b -> seekRelative(-stepTicks()));
        playButton = button(x, y, 34, playLabel(), b -> togglePlay());
        x += 36;
        x = add(x, y, 26, "+" + stepSeconds() + "s", b -> seekRelative(stepTicks()));
        x = add(x, y, 18, ">|", b -> session.playback().seek(session.playback().duration()));
        x += 4;
        x = add(x, y, 14, "-", b -> session.playback().changeSpeed(-1));
        speedX = x;
        x += 30;
        x = add(x, y, 14, "+", b -> session.playback().changeSpeed(1));
        x += 4;
        add(x, y, 44, tr("justreplay.editor.marker"), b -> addMarker());
        int rx = width - 8 - 46;
        button(rx, y, 46, tr("justreplay.editor.export"), b -> minecraft.setScreen(new ExportScreen(this, session)));
        rx -= 40;
        button(rx, y, 38, tr("justreplay.editor.save"), b -> session.saveProject());

        // Camera panel
        int px = 8;
        int py = 16;
        int third = (panelW - 4) / 3;
        int half = (panelW - 2) / 2;
        int quarter = (panelW - 6) / 4;
        button(px, py, third, tr("justreplay.editor.camera.add"), b -> addCameraKeyframe());
        button(px + third + 2, py, third, tr("justreplay.editor.delete"), b -> deleteSelected());
        button(px + 2 * third + 4, py, third, tr("justreplay.editor.goto"), b -> gotoSelected());
        py += ROW;
        pathButton = button(px, py, half, pathLabel(), b -> {
            session.setCameraPath(!session.isCameraPathActive());
            b.setMessage(Component.literal(pathLabel()));
        });
        button(px + half + 2, py, half, tr("justreplay.editor.camera.recorder"), b -> session.jumpToRecorder(minecraft));
        py += ROW;
        button(px, py, quarter, "Roll-", b -> adjust(0, -5));
        button(px + quarter + 2, py, quarter, "Roll+", b -> adjust(0, 5));
        button(px + 2 * (quarter + 2), py, quarter, "FOV-", b -> adjust(-5, 0));
        button(px + 3 * (quarter + 2), py, quarter, "FOV+", b -> adjust(5, 0));
        py += ROW;
        button(px, py, half, tr("justreplay.editor.camera.update"), b -> updateSelectedCamera());
        button(px + half + 2, py, half, tr("justreplay.editor.clearpath"), b -> confirm(
                Component.translatable("justreplay.editor.clearpath.confirm"), () -> {
                    session.project().cameraKeyframes.clear();
                    selectedCamera = null;
                    session.saveProject();
                }));
        cameraInfoY = py + ROW + 2;

        // Actor panel
        int ax = width - 8 - panelW;
        int ay = 16;
        button(ax, ay, half, tr("justreplay.editor.actor.player"), b -> addPlayerActor());
        button(ax + half + 2, ay, half, tr("justreplay.editor.actor.entity"), b -> addEntityActor());
        ay += ROW;
        button(ax, ay, third, tr("justreplay.editor.actor.keyframe"), b -> keyframeActor());
        button(ax + third + 2, ay, third, tr("justreplay.editor.actor.edit"), b -> {
            if (selectedActor != null) {
                minecraft.setScreen(new ActorScreen(this, session, selectedActor));
            }
        });
        button(ax + 2 * third + 4, ay, third, tr("justreplay.editor.actor.delete"), b -> {
            if (selectedActor != null) {
                confirm(Component.translatable("justreplay.editor.actor.delete.confirm"), this::deleteActor);
            }
        });
        ay += ROW + 4;
        actorListY = ay;
        actorRows = Math.max(1, (height - 64 - ay) / ROW);
        List<ReplayProject.ActorData> actors = session.project().actors;
        actorScroll = Math.max(0, Math.min(actorScroll, Math.max(0, actors.size() - actorRows)));
        for (int i = actorScroll; i < Math.min(actors.size(), actorScroll + actorRows); i++) {
            ReplayProject.ActorData actor = actors.get(i);
            Button b = button(ax, ay, panelW, actorLabel(actor), btn -> {
                selectedActor = actor;
                selectedActorKey = null;
                rebuildWidgets();
            });
            actorButtons.add(b);
            ay += ROW;
        }
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private int add(int x, int y, int w, String label, Button.OnPress onPress) {
        button(x, y, w, label, onPress);
        return x + w + 2;
    }

    private Button button(int x, int y, int w, String label, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, ROW - 2).build());
    }

    private String actorLabel(ReplayProject.ActorData actor) {
        String name = actor.name.isBlank() ? actor.type : actor.name + " (" + (actor.isPlayer() ? "player" : actor.type) + ")";
        return (actor == selectedActor ? "> " : "") + name + (actor.visible ? "" : " [hidden]");
    }

    private String playLabel() {
        return session.playback().isPaused() ? "Play" : "Pause";
    }

    private String pathLabel() {
        return Component.translatable(session.isCameraPathActive() ? "justreplay.editor.path.on" : "justreplay.editor.path.off").getString();
    }

    private static int stepSeconds() {
        return com.vyrriox.justreplay.config.JRConfig.SEEK_STEP_SECONDS.get();
    }

    private static int stepTicks() {
        return stepSeconds() * 20;
    }

    private void seekRelative(int delta) {
        session.playback().seek(session.playback().cursor() + delta);
    }

    private void togglePlay() {
        Playback playback = session.playback();
        if (playback.cursor() >= playback.duration()) {
            playback.seek(0);
        }
        playback.setPaused(!playback.isPaused());
    }

    // ---------------------------------------------------------------- camera

    private void addCameraKeyframe() {
        LocalPlayer p = minecraft.player;
        if (p == null) {
            return;
        }
        int tick = session.playback().cursor();
        session.project().cameraKeyframes.removeIf(k -> k.tick == tick);
        float roll = selectedCamera != null ? selectedCamera.roll : 0;
        float fov = selectedCamera != null ? selectedCamera.fov : minecraft.options.fov().get();
        ReplayProject.CameraKeyframe key = new ReplayProject.CameraKeyframe(tick, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), roll, fov);
        session.project().cameraKeyframes.add(key);
        session.project().sort();
        selectedCamera = key;
        selectedActorKey = null;
        session.saveProject();
    }

    private void updateSelectedCamera() {
        LocalPlayer p = minecraft.player;
        if (selectedCamera == null || p == null) {
            return;
        }
        selectedCamera.x = p.getX();
        selectedCamera.y = p.getY();
        selectedCamera.z = p.getZ();
        selectedCamera.yaw = p.getYRot();
        selectedCamera.pitch = p.getXRot();
        session.saveProject();
    }

    private void adjust(float fov, float roll) {
        if (selectedCamera != null) {
            selectedCamera.fov = Math.max(10, Math.min(150, selectedCamera.fov + fov));
            selectedCamera.roll = selectedCamera.roll + roll;
            session.saveProject();
        }
    }

    private void addMarker() {
        int tick = session.playback().cursor();
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.marker"),
                Component.translatable("justreplay.editor.marker.name"), "Marker " + (session.project().markers.size() + 1), name -> {
            session.project().markers.add(new ReplayProject.Marker(tick, name));
            session.saveProject();
        }));
    }

    private void deleteSelected() {
        if (selectedCamera != null) {
            session.project().cameraKeyframes.remove(selectedCamera);
            selectedCamera = null;
        } else if (selectedActor != null && selectedActorKey != null) {
            selectedActor.keyframes.remove(selectedActorKey);
            selectedActorKey = null;
        } else {
            int cursor = session.playback().cursor();
            session.project().markers.removeIf(m -> Math.abs(m.tick - cursor) <= 2);
        }
        session.saveProject();
    }

    private void gotoSelected() {
        if (selectedCamera != null) {
            session.playback().seek(selectedCamera.tick);
            if (minecraft.player != null) {
                Playback.placeCamera(minecraft.player, new net.minecraft.world.phys.Vec3(selectedCamera.x, selectedCamera.y, selectedCamera.z),
                        selectedCamera.yaw, selectedCamera.pitch);
            }
        } else if (selectedActorKey != null) {
            session.playback().seek(selectedActorKey.tick);
        }
    }

    // ---------------------------------------------------------------- actors

    private void addPlayerActor() {
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.actor.player"),
                Component.translatable("justreplay.editor.actor.skin"), minecraft.getUser().getName(), name -> {
            ReplayProject.ActorData actor = new ReplayProject.ActorData();
            actor.type = ReplayProject.ActorData.PLAYER;
            actor.name = name;
            addActor(actor);
        }));
    }

    private void addEntityActor() {
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.actor.entity"),
                Component.translatable("justreplay.editor.actor.entityid"), "minecraft:zombie", id -> {
            ReplayProject.ActorData actor = new ReplayProject.ActorData();
            actor.type = id.isBlank() ? "minecraft:zombie" : id;
            addActor(actor);
        }));
    }

    private void addActor(ReplayProject.ActorData actor) {
        LocalPlayer p = minecraft.player;
        if (p != null) {
            double groundY = groundBelow(p);
            actor.keyframes.add(new ReplayProject.ActorKeyframe(session.playback().cursor(), p.getX(), groundY, p.getZ(), p.getYRot(), 0));
        }
        session.project().actors.add(actor);
        selectedActor = actor;
        selectedActorKey = null;
        actorScroll = Math.max(0, session.project().actors.size() - actorRows);
        session.saveProject();
    }

    private void keyframeActor() {
        LocalPlayer p = minecraft.player;
        if (selectedActor == null || p == null) {
            return;
        }
        int tick = session.playback().cursor();
        selectedActor.keyframes.removeIf(k -> k.tick == tick);
        ReplayProject.ActorKeyframe key = new ReplayProject.ActorKeyframe(tick, p.getX(), groundBelow(p), p.getZ(), p.getYRot(), p.getXRot());
        selectedActor.keyframes.add(key);
        selectedActor.sort();
        selectedActorKey = key;
        selectedCamera = null;
        session.saveProject();
    }

    /** Feet height of an actor placed under the camera: the ground within 4 blocks, else the camera height. */
    private double groundBelow(LocalPlayer p) {
        net.minecraft.world.phys.Vec3 from = p.position();
        net.minecraft.world.phys.BlockHitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(from, from.subtract(0, 4, 0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getLocation().y : from.y;
    }

    private void deleteActor() {
        if (selectedActor == null) {
            return;
        }
        session.actors().remove(selectedActor);
        session.project().actors.remove(selectedActor);
        selectedActor = null;
        selectedActorKey = null;
        session.saveProject();
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- input

    private boolean inTimeline(double mx, double my) {
        return mx >= tlX - 3 && mx <= tlX + tlW + 3 && my >= tlY - 4 && my <= tlY + tlH + 4;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && inTimeline(mx, my)) {
            int duration = session.playback().duration();
            int lane = tlH / 2;
            boolean upper = my < tlY + lane;
            dragging = null;
            if (upper) {
                for (ReplayProject.CameraKeyframe k : session.project().cameraKeyframes) {
                    if (Math.abs(Timeline.xOf(k.tick, tlX, tlW, duration) - mx) <= 4) {
                        selectedCamera = k;
                        selectedActorKey = null;
                        startDrag(k, k.tick, mx, duration);
                        return true;
                    }
                }
            } else if (selectedActor != null) {
                for (ReplayProject.ActorKeyframe k : selectedActor.keyframes) {
                    if (Math.abs(Timeline.xOf(k.tick, tlX, tlW, duration) - mx) <= 4) {
                        selectedActorKey = k;
                        selectedCamera = null;
                        startDrag(k, k.tick, mx, duration);
                        return true;
                    }
                }
            }
            if (upper) {
                for (ReplayProject.Marker marker : session.project().markers) {
                    if (Math.abs(Timeline.xOf(marker.tick, tlX, tlW, duration) - mx) <= 3) {
                        session.playback().seek(marker.tick);
                        return true;
                    }
                }
            }
            selectedCamera = null;
            selectedActorKey = null;
            scrubbing = true;
            ghostTick = Timeline.tickAt(mx, tlX, tlW, duration);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void startDrag(Object keyframe, int tick, double mx, int duration) {
        dragging = keyframe;
        dragMoved = false;
        pressX = mx;
        grabOffset = mx - Timeline.xOf(tick, tlX, tlW, duration);
    }

    private void confirm(Component question, Runnable action) {
        minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(ok -> {
            if (ok) {
                action.run();
            }
            minecraft.setScreen(this);
        }, question, Component.translatable("justreplay.editor.cannot_undo")));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        int duration = session.playback().duration();
        if (dragging != null) {
            // A plain click selects; the keyframe only moves once the mouse travelled a few pixels.
            if (!dragMoved && Math.abs(mx - pressX) < 3) {
                return true;
            }
            dragMoved = true;
            int tick = Timeline.tickAt(mx - grabOffset, tlX, tlW, duration);
            if (dragging instanceof ReplayProject.CameraKeyframe k) {
                k.tick = tick;
            } else if (dragging instanceof ReplayProject.ActorKeyframe k) {
                k.tick = tick;
            }
            return true;
        }
        if (scrubbing) {
            ghostTick = Timeline.tickAt(mx, tlX, tlW, duration);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null) {
            dragging = null;
            session.project().sort();
            if (selectedActor != null) {
                selectedActor.sort();
            }
            session.saveProject();
            return true;
        }
        if (scrubbing) {
            scrubbing = false;
            session.playback().seek(ghostTick);
            ghostTick = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= width - 8 - panelW) {
            actorScroll = Math.max(0, actorScroll - (int) Math.signum(sy));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> {
                togglePlay();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                seekRelative(-stepTicks());
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                seekRelative(stepTicks());
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                deleteSelected();
                return true;
            }
            case GLFW.GLFW_KEY_K -> {
                addCameraKeyframe();
                return true;
            }
            default -> {
            }
        }
        if (com.vyrriox.justreplay.client.JRKeys.EDITOR.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------- render

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The world stays visible behind the editor.
    }

    @Override
    public void tick() {
        playButton.setMessage(Component.literal(playLabel()));
        pathButton.setMessage(Component.literal(pathLabel()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int ax = width - 8 - panelW;
        g.fill(4, 2, 12 + panelW, cameraInfoY + 22, 0x99000000);
        g.fill(ax - 4, 2, width - 4, actorListY + actorRows * ROW + 2, 0x99000000);
        g.fill(0, height - 56, width, height, 0xAA000000);
        g.drawString(font, Component.translatable("justreplay.editor.camera"), 8, 5, 0xFF4FC3F7, true);
        g.drawString(font, Component.translatable("justreplay.editor.actors"), ax, 5, 0xFF81C784, true);

        super.render(g, mouseX, mouseY, partialTick);

        Playback playback = session.playback();
        String info = session.meta().name + "   " + Timeline.formatTicks(playback.cursor()) + " / "
                + Timeline.formatTicks(playback.duration());
        g.drawString(font, info, 8, height - 52, 0xFFFFFFFF, true);
        Component help = Component.translatable("justreplay.editor.help");
        g.drawString(font, help, width - 8 - font.width(help), height - 52, 0xFF999999, true);
        g.drawCenteredString(font, "x" + ReplayHud.trim(playback.speed()), speedX + 14, height - 36, 0xFFFFFF);

        Component camInfo = selectedCamera != null
                ? Component.literal("t " + Timeline.formatTicks(selectedCamera.tick) + "  roll " + Math.round(selectedCamera.roll)
                + "  fov " + Math.round(selectedCamera.fov))
                : Component.translatable("justreplay.editor.camera.count", session.project().cameraKeyframes.size());
        g.drawString(font, camInfo, 8, cameraInfoY, 0xFFDDDDDD, true);
        if (selectedActorKey != null) {
            g.drawString(font, "actor key t " + Timeline.formatTicks(selectedActorKey.tick), 8, cameraInfoY + 10, 0xFF81C784, true);
        }

        Timeline.render(g, font, tlX, tlY, tlW, tlH, session, selectedCamera, selectedActor, selectedActorKey, ghostTick);
        for (ReplayProject.Marker marker : session.project().markers) {
            int mx = Timeline.xOf(marker.tick, tlX, tlW, Math.max(1, playback.duration()));
            if (Math.abs(mouseX - mx) <= 3 && inTimeline(mouseX, mouseY)) {
                g.renderTooltip(font, Component.literal(marker.name + "  " + Timeline.formatTicks(marker.tick)), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        session.saveProject();
    }
}
