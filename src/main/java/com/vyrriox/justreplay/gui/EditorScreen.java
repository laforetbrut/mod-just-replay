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
 * The replay editor: transport, laned timeline (cuts, camera keyframes, actor keyframes, markers), cameras and
 * actors. The world keeps running behind it, the screen does not pause the game.
 *
 * @author vyrriox
 */
public class EditorScreen extends Screen {
    static final int ROW = 18;
    /** Drag handle for the end of the cut track. */
    private static final Object CUT_END = new Object();

    private int panelW = 170;
    private int speedX;
    private int cameraPanelBottom;
    private int actorListY;
    private int actorRows = 4;

    private final ReplaySession session;
    private final CameraPanel cameras;
    private ReplayProject.ActorData selectedActor;
    private ReplayProject.ActorKeyframe selectedActorKey;
    private int actorScroll;

    private int tlX, tlY, tlW;
    private boolean scrubbing;
    private int ghostTick = -1;
    private Object dragging;
    private boolean dragMoved;
    private double pressX;
    private double grabOffset;

    private Button playButton;
    private final List<Button> actorButtons = new ArrayList<>();

    public EditorScreen(ReplaySession session) {
        super(Component.translatable("justreplay.editor.title"));
        this.session = session;
        this.cameras = new CameraPanel(this, session);
    }

    @Override
    protected void init() {
        actorButtons.clear();
        panelW = Math.min(170, (width - 24) / 2 - 20);
        tlX = 8;
        tlW = width - 16;
        tlY = height - 8 - Timeline.EDITOR_HEIGHT;

        // Transport row
        int y = tlY - 24;
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

        // Cameras panel
        cameraPanelBottom = cameras.build(8, 16, panelW);

        // Actor panel
        int third = (panelW - 4) / 3;
        int half = (panelW - 2) / 2;
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
        actorRows = Math.max(1, (tlY - 40 - ay) / ROW);
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

    Button button(int x, int y, int w, String label, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, ROW - 2).build());
    }

    /** Rebuilds the widgets after a selection change. */
    void refresh() {
        rebuildWidgets();
    }

    void clearActorKey() {
        selectedActorKey = null;
    }

    private String actorLabel(ReplayProject.ActorData actor) {
        String name = actor.name.isBlank() ? actor.type : actor.name + " (" + (actor.isPlayer() ? "player" : actor.type) + ")";
        return (actor == selectedActor ? "> " : "") + name + (actor.visible ? "" : " [hidden]");
    }

    private String playLabel() {
        return session.playback().isPaused() ? "Play" : "Pause";
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

    private void addMarker() {
        int tick = session.playback().cursor();
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.marker"),
                Component.translatable("justreplay.editor.marker.name"), "Marker " + (session.project().markers.size() + 1), name -> {
            session.project().markers.add(new ReplayProject.Marker(tick, name));
            session.saveProject();
        }));
    }

    /** Deletes the selected cut, camera keyframe or actor keyframe, else the markers at the play head. */
    void deleteSelected() {
        if (cameras.selectedCut != null) {
            cameras.deleteCut();
            return;
        }
        if (cameras.selectedKey != null) {
            session.project().selected().keyframes.remove(cameras.selectedKey);
            cameras.selectedKey = null;
        } else if (selectedActor != null && selectedActorKey != null) {
            selectedActor.keyframes.remove(selectedActorKey);
            selectedActorKey = null;
        } else {
            int cursor = session.playback().cursor();
            session.project().markers.removeIf(m -> Math.abs(m.tick - cursor) <= 2);
        }
        session.saveProject();
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
        cameras.selectedKey = null;
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
        return mx >= tlX - 3 && mx <= tlX + tlW + 3 && my >= tlY - 4 && my <= tlY + Timeline.EDITOR_HEIGHT + 4;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && inTimeline(mx, my)) {
            int duration = session.playback().duration();
            ReplayProject project = session.project();
            dragging = null;
            if (my < tlY + Timeline.CUT_LANE) {
                if (clickCuts(project, mx, duration)) {
                    return true;
                }
            } else if (my < tlY + Timeline.CUT_LANE + Timeline.KEY_LANE) {
                for (ReplayProject.CameraKeyframe k : project.selected().keyframes) {
                    if (Math.abs(Timeline.xOf(k.tick, tlX, tlW, duration) - mx) <= 4) {
                        cameras.selectedKey = k;
                        cameras.selectedCut = null;
                        selectedActorKey = null;
                        startDrag(k, k.tick, mx, duration);
                        return true;
                    }
                }
            } else if (selectedActor != null) {
                for (ReplayProject.ActorKeyframe k : selectedActor.keyframes) {
                    if (Math.abs(Timeline.xOf(k.tick, tlX, tlW, duration) - mx) <= 4) {
                        selectedActorKey = k;
                        cameras.selectedKey = null;
                        cameras.selectedCut = null;
                        startDrag(k, k.tick, mx, duration);
                        return true;
                    }
                }
            }
            if (my >= tlY + Timeline.CUT_LANE) {
                for (ReplayProject.Marker marker : project.markers) {
                    if (Math.abs(Timeline.xOf(marker.tick, tlX, tlW, duration) - mx) <= 3) {
                        session.playback().seek(marker.tick);
                        return true;
                    }
                }
            }
            cameras.selectedKey = null;
            cameras.selectedCut = null;
            selectedActorKey = null;
            scrubbing = true;
            ghostTick = Timeline.tickAt(mx, tlX, tlW, duration);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    /** Cut lane: grab a boundary or the track end to drag it, or click a segment to select its cut and camera. */
    private boolean clickCuts(ReplayProject project, double mx, int duration) {
        if (project.cuts.isEmpty()) {
            return false;
        }
        if (project.cutsEnd >= 0) {
            int end = project.cutTrackEnd(duration);
            if (Math.abs(Timeline.xOf(end, tlX, tlW, duration) - mx) <= 3) {
                startDrag(CUT_END, end, mx, duration);
                return true;
            }
        }
        for (ReplayProject.Cut cut : project.cuts) {
            if (Math.abs(Timeline.xOf(cut.tick, tlX, tlW, duration) - mx) <= 3) {
                selectCut(project, cut);
                startDrag(cut, cut.tick, mx, duration);
                return true;
            }
        }
        for (int i = 0; i < project.cuts.size(); i++) {
            int[] seg = Timeline.segment(project, i, tlX, tlW, duration);
            if (mx >= seg[0] && mx <= seg[1]) {
                selectCut(project, project.cuts.get(i));
                return true;
            }
        }
        return false;
    }

    private void selectCut(ReplayProject project, ReplayProject.Cut cut) {
        selectedActorKey = null;
        cameras.select(project.camera(cut.camera));
        cameras.selectedCut = cut;
        cameras.selectedKey = null;
    }

    private void startDrag(Object item, int tick, double mx, int duration) {
        dragging = item;
        dragMoved = false;
        pressX = mx;
        grabOffset = mx - Timeline.xOf(tick, tlX, tlW, duration);
    }

    void confirm(Component question, Runnable action) {
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
            // A plain click selects; the item only moves once the mouse travelled a few pixels.
            if (!dragMoved && Math.abs(mx - pressX) < 3) {
                return true;
            }
            dragMoved = true;
            int tick = Timeline.tickAt(mx - grabOffset, tlX, tlW, duration);
            if (dragging instanceof ReplayProject.CameraKeyframe k) {
                k.tick = tick;
            } else if (dragging instanceof ReplayProject.ActorKeyframe k) {
                k.tick = tick;
            } else if (dragging instanceof ReplayProject.Cut cut) {
                // A cut boundary stays between its neighbours.
                List<ReplayProject.Cut> cuts = session.project().cuts;
                int i = cuts.indexOf(cut);
                int min = i > 0 ? cuts.get(i - 1).tick + 1 : 0;
                int max = i + 1 < cuts.size() ? cuts.get(i + 1).tick - 1 : duration;
                cut.tick = Math.max(min, Math.min(max, tick));
            } else if (dragging == CUT_END) {
                List<ReplayProject.Cut> cuts = session.project().cuts;
                session.project().cutsEnd = Math.max(cuts.get(cuts.size() - 1).tick + 1, Math.min(duration, tick));
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
            session.project().mergeCuts();
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
                cameras.addKeyframe();
                return true;
            }
            default -> {
            }
        }
        if (com.vyrriox.justreplay.client.JRKeys.EDITOR.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (com.vyrriox.justreplay.client.JRKeys.CAMERA_PATH.matches(keyCode, scanCode)) {
            session.setCameraPath(!session.isCameraPathActive());
            return true;
        }
        int cut = com.vyrriox.justreplay.client.JRKeys.cutKeyIndex(keyCode, scanCode);
        if (cut >= 0) {
            CameraPanel.quickSwitch(session, cut);
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
        cameras.tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int ax = width - 8 - panelW;
        int bottom = tlY - 40;
        g.fill(4, 2, 12 + panelW, cameraPanelBottom, 0x99000000);
        int shownActors = Math.min(actorRows, Math.max(1, session.project().actors.size() - actorScroll));
        g.fill(ax - 4, 2, width - 4, actorListY + shownActors * ROW + 2, 0x99000000);
        g.fill(0, bottom, width, height, 0xAA000000);
        g.drawString(font, Component.translatable("justreplay.editor.cameras"), 8, 5, 0xFF4FC3F7, true);
        g.drawString(font, Component.translatable("justreplay.editor.actors"), ax, 5, 0xFF81C784, true);

        super.render(g, mouseX, mouseY, partialTick);

        Playback playback = session.playback();
        String info = session.meta().name + "   " + Timeline.formatTicks(playback.cursor()) + " / "
                + Timeline.formatTicks(playback.duration());
        g.drawString(font, info, 8, bottom + 4, 0xFFFFFFFF, true);
        Component help = Component.translatable("justreplay.editor.help");
        g.drawString(font, help, width - 8 - font.width(help), bottom + 4, 0xFF999999, true);
        g.drawCenteredString(font, "x" + ReplayHud.trim(playback.speed()), speedX + 14, tlY - 20, 0xFFFFFF);

        cameras.renderInfo(g, font, 8);
        if (selectedActorKey != null) {
            g.drawString(font, "actor key t " + Timeline.formatTicks(selectedActorKey.tick), ax, actorListY - 12, 0xFF81C784, true);
        }

        Timeline.renderEditor(g, font, tlX, tlY, tlW, session, cameras.selectedKey, cameras.selectedCut, selectedActor,
                selectedActorKey, ghostTick);
        if (inTimeline(mouseX, mouseY) && dragging == null && !scrubbing) {
            renderTimelineTooltip(g, mouseX, mouseY, playback);
        }
    }

    private void renderTimelineTooltip(GuiGraphics g, int mouseX, int mouseY, Playback playback) {
        int duration = Math.max(1, playback.duration());
        ReplayProject project = session.project();
        if (mouseY < tlY + Timeline.CUT_LANE) {
            for (int i = 0; i < project.cuts.size(); i++) {
                int[] seg = Timeline.segment(project, i, tlX, tlW, duration);
                if (mouseX >= seg[0] && mouseX <= seg[1]) {
                    ReplayProject.Cut cut = project.cuts.get(i);
                    ReplayProject.Camera camera = project.camera(cut.camera);
                    String name = camera == null ? "?" : (project.cameras.indexOf(camera) + 1) + " " + camera.name;
                    g.renderTooltip(font, Component.literal(name + "  " + Timeline.formatTicks(cut.tick)
                            + (cut.blend > 0 ? "  blend " + cut.blend + "t" : "")), mouseX, mouseY);
                    return;
                }
            }
            return;
        }
        for (ReplayProject.Marker marker : project.markers) {
            int mx = Timeline.xOf(marker.tick, tlX, tlW, duration);
            if (Math.abs(mouseX - mx) <= 3) {
                g.renderTooltip(font, Component.literal(marker.name + "  " + Timeline.formatTicks(marker.tick)), mouseX, mouseY);
                return;
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
