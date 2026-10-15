package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.CameraPath;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.SpectateCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * The "Cameras" section of the editor: camera list and management, the controls of the selected camera (path
 * keyframes, static placement or follow settings), roll and field of view, and the cut track.
 *
 * @author vyrriox
 */
public final class CameraPanel {
    private static final int[] BLENDS = {0, 5, 10, 20, 40};
    private static final float[] SMOOTHING = {0.0F, 0.15F, 0.3F, 0.6F, 1.0F};
    private static final float[] ORBIT_SPEEDS = {-40.0F, -20.0F, -10.0F, 10.0F, 20.0F, 40.0F};
    private static final String[] FOLLOW_MODES = {"BEHIND", "SIDE", "FRONT", "ORBIT"};

    private final EditorScreen screen;
    private final ReplaySession session;
    /** Keyframe of the selected path camera, or null. */
    ReplayProject.CameraKeyframe selectedKey;
    /** Cut selected on the timeline, or null. */
    ReplayProject.Cut selectedCut;
    private Button previewButton;
    private Button blendButton;
    private Component status;
    private int infoY;

    CameraPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private ReplayProject project() {
        return session.project();
    }

    private ReplayProject.Camera camera() {
        return project().selected();
    }

    private Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ---------------------------------------------------------------- layout

    /** Adds the widgets from (x, y) in a column of width w; returns the y below them. */
    int build(int x, int y, int w) {
        int row = EditorScreen.ROW;
        int third = (w - 4) / 3;
        int half = (w - 2) / 2;
        int quarter = (w - 6) / 4;
        ReplayProject.Camera camera = camera();
        if (selectedCut != null && !project().cuts.contains(selectedCut)) {
            selectedCut = null;
        }
        if (selectedKey != null && !camera.keyframes.contains(selectedKey)) {
            selectedKey = null;
        }

        // Camera selector
        screen.button(x, y, 16, "<", b -> cycle(-1));
        int index = project().cameras.indexOf(camera);
        screen.button(x + 18, y, w - 36, (index + 1) + ": " + camera.name + " (" + typeLabel(camera) + ")", b -> cycle(1));
        screen.button(x + w - 16, y, 16, ">", b -> cycle(1));
        y += row;
        screen.button(x, y, quarter, tr("justreplay.editor.cam.new_path"), b -> addCamera(ReplayProject.Camera.PATH));
        screen.button(x + quarter + 2, y, quarter, tr("justreplay.editor.cam.new_static"), b -> addCamera(ReplayProject.Camera.STATIC));
        screen.button(x + 2 * (quarter + 2), y, quarter, tr("justreplay.editor.cam.new_follow"), b -> addCamera(ReplayProject.Camera.FOLLOW));
        screen.button(x + 3 * (quarter + 2), y, quarter, tr("justreplay.editor.cam.duplicate"), b -> duplicate());
        y += row;
        screen.button(x, y, third, tr("justreplay.editor.cam.rename"), b -> rename());
        screen.button(x + third + 2, y, third, tr("justreplay.editor.cam.delete"), b -> {
            if (project().cameras.size() > 1) {
                screen.confirm(Component.translatable("justreplay.editor.cam.delete.confirm"), this::deleteCamera);
            }
        });
        previewButton = screen.button(x + 2 * third + 4, y, third, previewLabel(), b -> {
            session.setCameraPath(!session.isCameraPathActive());
            b.setMessage(Component.literal(previewLabel()));
        });
        previewButton.setTooltip(Tooltip.create(Component.translatable("justreplay.editor.preview.tip")));
        y += row;

        // Controls of the selected camera
        if (camera.isPath()) {
            screen.button(x, y, third, tr("justreplay.editor.camera.add"), b -> addKeyframe());
            screen.button(x + third + 2, y, third, tr("justreplay.editor.delete"), b -> screen.deleteSelected());
            screen.button(x + 2 * third + 4, y, third, tr("justreplay.editor.goto"), b -> gotoSelected());
            y += row;
            screen.button(x, y, half, tr("justreplay.editor.camera.update"), b -> updateKeyframe());
            screen.button(x + half + 2, y, half, tr("justreplay.editor.clearpath"), b -> screen.confirm(
                    Component.translatable("justreplay.editor.clearpath.confirm"), () -> {
                        camera().keyframes.clear();
                        selectedKey = null;
                        session.saveProject();
                    }));
            y += row;
            screen.button(x, y, third, curveLabel(camera), b -> {
                CameraPath.Curve[] all = CameraPath.Curve.values();
                camera().curve = all[(curve(camera()).ordinal() + 1) % all.length].name();
                b.setMessage(Component.literal(curveLabel(camera())));
                session.saveProject();
            }).setTooltip(Tooltip.create(Component.translatable("justreplay.editor.curve.tip")));
            screen.button(x + third + 2, y, third, timingLabel(camera), b -> {
                camera().timing = (timing(camera()) == CameraPath.Timing.CONSTANT_SPEED ? CameraPath.Timing.KEYFRAMES
                        : CameraPath.Timing.CONSTANT_SPEED).name();
                b.setMessage(Component.literal(timingLabel(camera())));
                session.saveProject();
            }).setTooltip(Tooltip.create(Component.translatable("justreplay.editor.timing.tip")));
            screen.button(x + 2 * third + 4, y, third, easeLabel(camera), b -> {
                camera().ease = !camera().ease;
                b.setMessage(Component.literal(easeLabel(camera())));
                session.saveProject();
            }).setTooltip(Tooltip.create(Component.translatable("justreplay.editor.ease.tip")));
            y += row;
        } else if (camera.isStatic()) {
            screen.button(x, y, half, tr("justreplay.editor.cam.set_view"), b -> placeAtView(camera()));
            screen.button(x + half + 2, y, half, tr("justreplay.editor.goto"), b -> gotoSelected());
            y += row;
        } else {
            screen.button(x, y, half, tr("justreplay.editor.cam.target"), b -> retarget())
                    .setTooltip(Tooltip.create(Component.translatable("justreplay.editor.cam.target.tip")));
            screen.button(x + half + 2, y, half, followModeLabel(camera), b -> {
                ReplayProject.Camera c = camera();
                c.followMode = FOLLOW_MODES[(indexOf(FOLLOW_MODES, c.followMode) + 1) % FOLLOW_MODES.length];
                b.setMessage(Component.literal(followModeLabel(c)));
                session.saveProject();
            });
            y += row;
            screen.button(x, y, quarter, "Dist-", b -> follow(-0.5, 0));
            screen.button(x + quarter + 2, y, quarter, "Dist+", b -> follow(0.5, 0));
            screen.button(x + 2 * (quarter + 2), y, quarter, "High-", b -> follow(0, -0.5));
            screen.button(x + 3 * (quarter + 2), y, quarter, "High+", b -> follow(0, 0.5));
            y += row;
            screen.button(x, y, half, smoothLabel(camera), b -> {
                ReplayProject.Camera c = camera();
                c.smoothing = SMOOTHING[(nearest(SMOOTHING, c.smoothing) + 1) % SMOOTHING.length];
                b.setMessage(Component.literal(smoothLabel(c)));
                session.saveProject();
            });
            screen.button(x + half + 2, y, half, orbitLabel(camera), b -> {
                ReplayProject.Camera c = camera();
                c.orbitSpeed = ORBIT_SPEEDS[(nearest(ORBIT_SPEEDS, c.orbitSpeed) + 1) % ORBIT_SPEEDS.length];
                b.setMessage(Component.literal(orbitLabel(c)));
                session.saveProject();
            });
            y += row;
        }
        screen.button(x, y, quarter, "Roll-", b -> adjust(0, -5));
        screen.button(x + quarter + 2, y, quarter, "Roll+", b -> adjust(0, 5));
        screen.button(x + 2 * (quarter + 2), y, quarter, "FOV-", b -> adjust(-5, 0));
        screen.button(x + 3 * (quarter + 2), y, quarter, "FOV+", b -> adjust(5, 0));
        y += row;

        // Cut track
        screen.button(x, y, quarter, tr("justreplay.editor.cut.add"), b -> cutHere())
                .setTooltip(Tooltip.create(Component.translatable("justreplay.editor.cut.add.tip")));
        blendButton = screen.button(x + quarter + 2, y, quarter, blendLabel(), b -> cycleBlend());
        blendButton.setTooltip(Tooltip.create(Component.translatable("justreplay.editor.cut.blend.tip")));
        screen.button(x + 2 * (quarter + 2), y, quarter, tr("justreplay.editor.cut.delete"), b -> deleteCut());
        screen.button(x + 3 * (quarter + 2), y, quarter, tr("justreplay.editor.cut.end"), b -> toggleEnd())
                .setTooltip(Tooltip.create(Component.translatable("justreplay.editor.cut.end.tip")));
        y += row;
        screen.button(x, y, half, tr("justreplay.editor.camera.recorder"), b -> session.jumpToRecorder(mc()));
        screen.button(x + half + 2, y, half, showPathLabel(), b -> {
            project().showPath = !project().showPath;
            b.setMessage(Component.literal(showPathLabel()));
            session.saveProject();
        }).setTooltip(Tooltip.create(Component.translatable("justreplay.editor.showpath.tip")));
        y += row;
        infoY = y + 2;
        return infoY + 22;
    }

    void tick() {
        if (previewButton != null) {
            previewButton.setMessage(Component.literal(previewLabel()));
        }
        if (blendButton != null) {
            blendButton.setMessage(Component.literal(blendLabel()));
        }
    }

    void renderInfo(GuiGraphics g, Font font, int x) {
        ReplayProject.Camera camera = camera();
        Component line;
        if (camera.isPath()) {
            line = selectedKey != null
                    ? Component.literal("t " + Timeline.formatTicks(selectedKey.tick) + "  roll " + Math.round(selectedKey.roll)
                    + "  fov " + Math.round(selectedKey.fov))
                    : Component.translatable("justreplay.editor.camera.count", camera.keyframes.size());
        } else if (camera.isStatic()) {
            line = Component.translatable("justreplay.editor.cam.static_info", Math.round(camera.roll), Math.round(camera.fov));
        } else {
            String target = camera.target.isEmpty() ? "-" : camera.targetName.isEmpty() ? camera.target : camera.targetName;
            line = Component.translatable("justreplay.editor.cam.follow_info", target, trim(camera.distance), trim(camera.height));
        }
        g.drawString(font, line, x, infoY, 0xFFDDDDDD, true);
        Component cuts;
        if (status != null) {
            cuts = status;
        } else if (project().cuts.isEmpty()) {
            cuts = Component.translatable("justreplay.editor.cut.none");
        } else {
            int[] range = project().exportRange(session.playback().duration());
            cuts = Component.translatable("justreplay.editor.cut.info", project().cuts.size(), Timeline.formatTicks(range[0]),
                    Timeline.formatTicks(range[1]));
        }
        g.drawString(font, cuts, x, infoY + 10, status != null ? 0xFFFF8A65 : 0xFFAAAAAA, true);
    }

    // ---------------------------------------------------------------- cameras

    private void cycle(int direction) {
        var cameras = project().cameras;
        int index = Math.floorMod(cameras.indexOf(camera()) + direction, cameras.size());
        select(cameras.get(index));
    }

    void select(ReplayProject.Camera camera) {
        if (camera != camera()) {
            selectedKey = null;
        }
        project().select(camera);
        status = null;
        screen.refresh();
    }

    private void addCamera(String type) {
        ReplayProject.Camera camera = new ReplayProject.Camera(project().nextCameraName(), type);
        LocalPlayer p = mc().player;
        camera.fov = mc().options.fov().get();
        if (type.equals(ReplayProject.Camera.STATIC)) {
            placeAtView(camera);
        } else if (type.equals(ReplayProject.Camera.FOLLOW)) {
            Entity target = pickTarget();
            if (target == null) {
                status = Component.translatable("justreplay.editor.cam.target.none");
                return;
            }
            setTarget(camera, target);
        } else if (p != null) {
            camera.keyframes.add(keyframeAtView(session.playback().cursor(), 0, camera.fov));
        }
        project().cameras.add(camera);
        session.saveProject();
        select(camera);
    }

    private void duplicate() {
        ReplayProject.Camera source = camera();
        ReplayProject.Camera copy = source.copy(source.name + " copy");
        project().cameras.add(project().cameras.indexOf(source) + 1, copy);
        session.saveProject();
        select(copy);
    }

    private void rename() {
        ReplayProject.Camera camera = camera();
        mc().setScreen(new TextInputScreen(screen, Component.translatable("justreplay.editor.cam.rename"),
                Component.translatable("justreplay.editor.cam.rename.label"), camera.name, name -> {
            if (!name.isBlank()) {
                camera.name = name.trim();
                session.saveProject();
            }
        }));
    }

    private void deleteCamera() {
        project().removeCamera(camera());
        selectedKey = null;
        selectedCut = null;
        session.saveProject();
    }

    private void placeAtView(ReplayProject.Camera camera) {
        LocalPlayer p = mc().player;
        if (p == null) {
            return;
        }
        camera.x = p.getX();
        camera.y = p.getY();
        camera.z = p.getZ();
        camera.yaw = p.getYRot();
        camera.pitch = p.getXRot();
        session.saveProject();
    }

    /** The entity viewed through, else the one under the crosshair, else the recording player. */
    private Entity pickTarget() {
        Minecraft mc = mc();
        Entity viewed = mc.getCameraEntity();
        if (viewed != null && viewed != mc.player) {
            return viewed;
        }
        Entity picked = SpectateCamera.pick(mc);
        return picked != null ? picked : session.findRecorder(mc);
    }

    private void retarget() {
        Entity target = pickTarget();
        if (target == null) {
            status = Component.translatable("justreplay.editor.cam.target.none");
            return;
        }
        setTarget(camera(), target);
        status = null;
        session.saveProject();
    }

    private static void setTarget(ReplayProject.Camera camera, Entity target) {
        camera.target = target.getUUID().toString();
        camera.targetName = target.getName().getString();
    }

    private void follow(double distance, double height) {
        ReplayProject.Camera camera = camera();
        camera.distance = Math.max(0.5, Math.min(64, camera.distance + distance));
        camera.height = Math.max(-16, Math.min(32, camera.height + height));
        session.saveProject();
    }

    // ---------------------------------------------------------------- keyframes

    private ReplayProject.CameraKeyframe keyframeAtView(int tick, float roll, float fov) {
        LocalPlayer p = mc().player;
        return new ReplayProject.CameraKeyframe(tick, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), roll, fov);
    }

    void addKeyframe() {
        ReplayProject.Camera camera = camera();
        if (mc().player == null) {
            return;
        }
        if (!camera.isPath()) {
            status = Component.translatable("justreplay.editor.cam.not_path");
            return;
        }
        int tick = session.playback().cursor();
        camera.keyframes.removeIf(k -> k.tick == tick);
        float roll = selectedKey != null ? selectedKey.roll : 0;
        float fov = selectedKey != null ? selectedKey.fov : mc().options.fov().get();
        ReplayProject.CameraKeyframe key = keyframeAtView(tick, roll, fov);
        camera.keyframes.add(key);
        camera.sort();
        selectedKey = key;
        screen.clearActorKey();
        session.saveProject();
    }

    private void updateKeyframe() {
        LocalPlayer p = mc().player;
        if (selectedKey == null || p == null) {
            return;
        }
        selectedKey.x = p.getX();
        selectedKey.y = p.getY();
        selectedKey.z = p.getZ();
        selectedKey.yaw = p.getYRot();
        selectedKey.pitch = p.getXRot();
        session.saveProject();
    }

    private void adjust(float fov, float roll) {
        ReplayProject.Camera camera = camera();
        if (camera.isPath()) {
            if (selectedKey != null) {
                selectedKey.fov = Math.max(10, Math.min(150, selectedKey.fov + fov));
                selectedKey.roll = selectedKey.roll + roll;
            }
        } else {
            camera.fov = Math.max(10, Math.min(150, camera.fov + fov));
            camera.roll = camera.roll + roll;
        }
        session.saveProject();
    }

    /** Seeks to the selected keyframe, or puts the free camera where the selected camera is at the play head. */
    void gotoSelected() {
        LocalPlayer p = mc().player;
        if (selectedKey != null) {
            session.playback().seek(selectedKey.tick);
            if (p != null) {
                Playback.placeCamera(p, new Vec3(selectedKey.x, selectedKey.y, selectedKey.z), selectedKey.yaw, selectedKey.pitch);
            }
            return;
        }
        double[] state = CameraRig.sample(camera(), session.playback().cursor(), 0.0F, false);
        if (state != null && p != null) {
            session.spectate().stop(mc());
            Playback.placeCamera(p, new Vec3(state[0], state[1], state[2]), (float) state[3], (float) state[4]);
        }
    }

    // ---------------------------------------------------------------- cuts

    private void cutHere() {
        ReplayProject.Cut cut = project().cutTo(session.playback().cursor(), camera(), 0);
        if (cut != null) {
            selectedCut = cut;
        }
        session.saveProject();
    }

    /** Live switching: camera {@code index} becomes live at the play head. Returns the camera, or null. */
    public static ReplayProject.Camera quickSwitch(ReplaySession session, int index) {
        ReplayProject project = session.project();
        if (index < 0 || index >= project.cameras.size()) {
            return null;
        }
        ReplayProject.Camera camera = project.cameras.get(index);
        project.cutTo(session.playback().cursor(), camera, 0);
        session.saveProject();
        return camera;
    }

    private void cycleBlend() {
        if (selectedCut == null) {
            return;
        }
        int i = 0;
        while (i < BLENDS.length && BLENDS[i] <= selectedCut.blend) {
            i++;
        }
        selectedCut.blend = i < BLENDS.length ? BLENDS[i] : 0;
        session.saveProject();
    }

    void deleteCut() {
        if (selectedCut == null) {
            return;
        }
        project().cuts.remove(selectedCut);
        project().mergeCuts();
        selectedCut = null;
        session.saveProject();
    }

    private void toggleEnd() {
        int cursor = session.playback().cursor();
        project().cutsEnd = project().cutsEnd == cursor ? -1 : cursor;
        session.saveProject();
    }

    // ---------------------------------------------------------------- labels

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static String trim(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static int nearest(float[] values, float value) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    private static String typeLabel(ReplayProject.Camera camera) {
        return tr("justreplay.editor.cam.type." + camera.type.toLowerCase(Locale.ROOT));
    }

    private String previewLabel() {
        return tr(session.isCameraPathActive() ? "justreplay.editor.preview.on" : "justreplay.editor.preview.off");
    }

    private String blendLabel() {
        return selectedCut == null ? tr("justreplay.editor.cut.blend.none")
                : Component.translatable("justreplay.editor.cut.blend", selectedCut.blend).getString();
    }

    private String showPathLabel() {
        return tr(project().showPath ? "justreplay.editor.showpath.on" : "justreplay.editor.showpath.off");
    }

    private static CameraPath.Curve curve(ReplayProject.Camera camera) {
        return CameraPath.parse(CameraPath.Curve.class, camera.curve, CameraPath.Curve.CATMULL_ROM);
    }

    private static CameraPath.Timing timing(ReplayProject.Camera camera) {
        return CameraPath.parse(CameraPath.Timing.class, camera.timing, CameraPath.Timing.CONSTANT_SPEED);
    }

    private static String curveLabel(ReplayProject.Camera camera) {
        return tr("justreplay.editor.curve." + curve(camera).name().toLowerCase(Locale.ROOT));
    }

    private static String timingLabel(ReplayProject.Camera camera) {
        return tr(timing(camera) == CameraPath.Timing.CONSTANT_SPEED ? "justreplay.editor.timing.constant" : "justreplay.editor.timing.keyframes");
    }

    private static String easeLabel(ReplayProject.Camera camera) {
        return tr(camera.ease ? "justreplay.editor.ease.on" : "justreplay.editor.ease.off");
    }

    private static String followModeLabel(ReplayProject.Camera camera) {
        return tr("justreplay.editor.follow." + camera.followMode.toLowerCase(Locale.ROOT));
    }

    private static String smoothLabel(ReplayProject.Camera camera) {
        return Component.translatable("justreplay.editor.cam.smooth", trim(camera.smoothing)).getString();
    }

    private static String orbitLabel(ReplayProject.Camera camera) {
        return Component.translatable("justreplay.editor.cam.orbit", Math.round(camera.orbitSpeed)).getString();
    }
}
