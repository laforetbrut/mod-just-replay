package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.CameraPath;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.SpectateCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * The "Cameras" tab of the editor: camera list and management, the controls of the selected camera (path
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
    private GlassButton blendButton;
    private Component status;

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

    /** Adds the controls of the tab to a form. */
    void build(Form f) {
        ReplayProject.Camera camera = camera();
        if (selectedCut != null && !project().cuts.contains(selectedCut)) {
            selectedCut = null;
        }
        if (selectedKey != null && !camera.keyframes.contains(selectedKey)) {
            selectedKey = null;
        }

        // Camera list
        f.header(Component.translatable("justreplay.editor.cameras"), Icon.CAMERA);
        List<ReplayProject.Camera> cameras = project().cameras;
        for (int i = 0; i < cameras.size(); i++) {
            ReplayProject.Camera c = cameras.get(i);
            String detail = typeLabel(c) + (i < 9 ? "  " + (i + 1) : "");
            ListRow row = new ListRow(Component.literal(c.name), b -> select(c));
            row.dot(PathRenderer.cameraColor(i)).detail(detail).selected(() -> c == camera())
                    .tip(Component.translatable("justreplay.editor.cam.row.tip", i + 1));
            f.full(row);
        }
        f.caption(tc("justreplay.editor.cam.new"), 0, 1);
        f.next(9);
        f.put(new GlassButton(tc("justreplay.editor.cam.add.path"), b -> addCamera(ReplayProject.Camera.PATH))
                .icon(Icon.PATH).tip(tc("justreplay.editor.cam.new_path.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.cam.add.static"), b -> addCamera(ReplayProject.Camera.STATIC))
                .icon(Icon.PIN).tip(tc("justreplay.editor.cam.new_static.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.cam.add.follow"), b -> addCamera(ReplayProject.Camera.FOLLOW))
                .icon(Icon.TARGET).tip(tc("justreplay.editor.cam.new_follow.tip")), 2, 3);
        f.next();
        f.put(new GlassButton(tc("justreplay.editor.cam.duplicate"), b -> duplicate()).icon(Icon.COPY), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.cam.rename"), b -> rename()).icon(Icon.EDIT), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.delete"), b -> {
            if (project().cameras.size() > 1) {
                screen.confirm(Component.translatable("justreplay.editor.cam.delete.confirm"), this::deleteCamera);
            }
        }).icon(Icon.TRASH).style(GlassButton.Style.DANGER).enabled(cameras.size() > 1), 2, 3);
        f.next();
        f.full(new GlassSwitch(tc("justreplay.editor.preview"), session::isCameraPathActive,
                () -> session.setCameraPath(!session.isCameraPathActive()))
                .icon(Icon.EYE).tip(tc("justreplay.editor.preview.tip")));

        // Controls of the selected camera
        f.header(Component.translatable("justreplay.editor.cam.settings", camera.name), Icon.EDIT);
        if (camera.isPath()) {
            f.put(new GlassButton(tc("justreplay.editor.camera.add"), b -> addKeyframe()).icon(Icon.KEY)
                    .tip(tc("justreplay.editor.camera.add.tip")), 0, 2);
            f.put(new GlassButton(tc("justreplay.editor.delete"), b -> screen.deleteSelected()).icon(Icon.TRASH)
                    .tip(tc("justreplay.editor.delete.tip")), 1, 2);
            f.next();
            f.put(new GlassButton(tc("justreplay.editor.camera.update"), b -> updateKeyframe()).icon(Icon.CAMERA)
                    .tip(tc("justreplay.editor.camera.update.tip")), 0, 2);
            f.put(new GlassButton(tc("justreplay.editor.goto"), b -> gotoSelected()).icon(Icon.PIN)
                    .tip(tc("justreplay.editor.goto.tip")), 1, 2);
            f.next();
            f.full(new GlassButton(tc("justreplay.editor.clearpath"), b -> screen.confirm(
                    Component.translatable("justreplay.editor.clearpath.confirm"), () -> {
                        camera().keyframes.clear();
                        selectedKey = null;
                        session.saveProject();
                    })).icon(Icon.TRASH).style(GlassButton.Style.DANGER));
            f.full(new Dropdown<>(screen, tc("justreplay.editor.curve"), List.of(CameraPath.Curve.values()), curve(camera),
                    v -> Component.translatable("justreplay.editor.curve." + v.name().toLowerCase(Locale.ROOT)), v -> {
                camera().curve = v.name();
                session.saveProject();
            }).icon(Icon.PATH).tip(tc("justreplay.editor.curve.tip")));
            f.full(new Dropdown<>(screen, tc("justreplay.editor.timing"),
                    List.of(CameraPath.Timing.CONSTANT_SPEED, CameraPath.Timing.KEYFRAMES), timing(camera),
                    v -> Component.translatable(v == CameraPath.Timing.CONSTANT_SPEED ? "justreplay.editor.timing.constant"
                            : "justreplay.editor.timing.keyframes"), v -> {
                camera().timing = v.name();
                session.saveProject();
            }).icon(Icon.CLOCK).tip(tc("justreplay.editor.timing.tip")));
            f.full(new GlassSwitch(tc("justreplay.editor.ease"), () -> camera().ease, () -> {
                camera().ease = !camera().ease;
                session.saveProject();
            }).icon(Icon.ROTATE).tip(tc("justreplay.editor.ease.tip")));
        } else if (camera.isStatic()) {
            f.put(new GlassButton(tc("justreplay.editor.cam.set_view"), b -> placeAtView(camera())).icon(Icon.CAMERA)
                    .tip(tc("justreplay.editor.cam.set_view.tip")), 0, 2);
            f.put(new GlassButton(tc("justreplay.editor.goto"), b -> gotoSelected()).icon(Icon.PIN)
                    .tip(tc("justreplay.editor.goto.tip")), 1, 2);
            f.next();
        } else {
            f.put(new GlassButton(tc("justreplay.editor.cam.target"), b -> retarget()).icon(Icon.TARGET)
                    .tip(tc("justreplay.editor.cam.target.tip")), 0, 2);
            f.put(new Dropdown<>(screen, Component.empty(), List.of(FOLLOW_MODES), camera.followMode,
                    v -> Component.translatable("justreplay.editor.follow." + v.toLowerCase(Locale.ROOT)), v -> {
                camera().followMode = v;
                session.saveProject();
            }).tip(tc("justreplay.editor.follow.mode")), 1, 2);
            f.next();
            f.full(new Stepper(tc("justreplay.editor.follow.distance"), () -> camera().distance, "", 1,
                    () -> follow(-0.5, 0), () -> follow(0.5, 0)).icon(Icon.FIT));
            f.full(new Stepper(tc("justreplay.editor.follow.height"), () -> camera().height, "", 1,
                    () -> follow(0, -0.5), () -> follow(0, 0.5)).icon(Icon.ARROW_UP));
            f.full(new Dropdown<>(screen, tc("justreplay.editor.follow.smooth"), floats(SMOOTHING), SMOOTHING[nearest(SMOOTHING, camera.smoothing)],
                    v -> Component.literal(trim(v) + "s"), v -> {
                camera().smoothing = v;
                session.saveProject();
            }).icon(Icon.CLOCK));
            f.full(new Dropdown<>(screen, tc("justreplay.editor.follow.orbit_speed"), floats(ORBIT_SPEEDS),
                    ORBIT_SPEEDS[nearest(ORBIT_SPEEDS, camera.orbitSpeed)], v -> Component.literal(Math.round(v) + "°/s"), v -> {
                camera().orbitSpeed = v;
                session.saveProject();
            }).icon(Icon.ROTATE));
        }
        f.full(new Stepper(tc("justreplay.editor.roll"), () -> rollValue(), "°", 0, () -> adjust(0, -5), () -> adjust(0, 5))
                .icon(Icon.ROTATE).enabled(this::lensEditable).tip(tc("justreplay.editor.lens.tip")));
        f.full(new Stepper(tc("justreplay.editor.fov"), () -> fovValue(), "°", 0, () -> adjust(-5, 0), () -> adjust(5, 0))
                .icon(Icon.ANGLE).enabled(this::lensEditable).tip(tc("justreplay.editor.lens.tip")));
        f.text(this::infoLine, Theme.TEXT_DIM);

        // Cut track
        f.header(Component.translatable("justreplay.editor.cut.track"), Icon.CUT);
        f.put(new GlassButton(tc("justreplay.editor.cut.add"), b -> cutHere()).icon(Icon.CUT)
                .tip(tc("justreplay.editor.cut.add.tip")), 0, 2);
        blendButton = f.put(new GlassButton(Component.literal(blendLabel()), b -> cycleBlend()), 1, 2);
        blendButton.tip(tc("justreplay.editor.cut.blend.tip"));
        f.next();
        f.put(new GlassButton(tc("justreplay.editor.cut.end"), b -> toggleEnd()).icon(Icon.END)
                .tip(tc("justreplay.editor.cut.end.tip")), 0, 2);
        f.put(new GlassButton(tc("justreplay.editor.cut.delete"), b -> deleteCut()).icon(Icon.TRASH)
                .style(GlassButton.Style.DANGER).tip(tc("justreplay.editor.cut.delete.tip")), 1, 2);
        f.next();
        f.text(this::cutLine, Theme.TEXT_MUTED);
        f.text(() -> status, Theme.WARNING);
        EffectsPanel.buildTransition(f, screen, session, selectedCut);
        f.full(new GlassButton(tc("justreplay.editor.camera.recorder"), b -> session.jumpToRecorder(mc())).icon(Icon.PERSON)
                .tip(tc("justreplay.editor.camera.recorder.tip")));
    }

    void tick() {
        if (blendButton != null) {
            String label = blendLabel();
            if (!label.equals(blendButton.getMessage().getString())) {
                blendButton.setMessage(Component.literal(label));
            }
        }
    }

    private boolean lensEditable() {
        return !camera().isPath() || selectedKey != null;
    }

    private double rollValue() {
        ReplayProject.Camera camera = camera();
        if (camera.isPath()) {
            return selectedKey != null ? selectedKey.roll : Double.NaN;
        }
        return camera.roll;
    }

    private double fovValue() {
        ReplayProject.Camera camera = camera();
        if (camera.isPath()) {
            return selectedKey != null ? selectedKey.fov : Double.NaN;
        }
        return camera.fov;
    }

    /** Summary of the selected camera. */
    Component infoLine() {
        ReplayProject.Camera camera = camera();
        if (camera.isPath()) {
            return selectedKey != null
                    ? Component.translatable("justreplay.editor.camera.key_info", Timeline.formatTicks(selectedKey.tick))
                    : Component.translatable("justreplay.editor.camera.count", camera.keyframes.size());
        } else if (camera.isStatic()) {
            return Component.translatable("justreplay.editor.cam.static_info", Math.round(camera.roll), Math.round(camera.fov));
        }
        String target = camera.target.isEmpty() ? "-" : camera.targetName.isEmpty() ? camera.target : camera.targetName;
        return Component.translatable("justreplay.editor.cam.follow_info", target, trim(camera.distance), trim(camera.height));
    }

    /** Summary of the cut track. */
    Component cutLine() {
        if (project().cuts.isEmpty()) {
            return Component.translatable("justreplay.editor.cut.none");
        }
        int[] range = project().exportRange(session.playback().duration());
        return Component.translatable("justreplay.editor.cut.info", project().cuts.size(), Timeline.formatTicks(range[0]),
                Timeline.formatTicks(range[1]));
    }

    private static List<Float> floats(float[] values) {
        Float[] boxed = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return List.of(boxed);
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    // ---------------------------------------------------------------- cameras

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

    void cutHere() {
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

    private String blendLabel() {
        return selectedCut == null ? tr("justreplay.editor.cut.blend.none")
                : selectedCut.blend == 0 ? tr("justreplay.editor.cut.blend.hard")
                : Component.translatable("justreplay.editor.cut.blend", selectedCut.blend).getString();
    }

    private static CameraPath.Curve curve(ReplayProject.Camera camera) {
        return CameraPath.parse(CameraPath.Curve.class, camera.curve, CameraPath.Curve.CATMULL_ROM);
    }

    private static CameraPath.Timing timing(ReplayProject.Camera camera) {
        return CameraPath.parse(CameraPath.Timing.class, camera.timing, CameraPath.Timing.CONSTANT_SPEED);
    }
}
