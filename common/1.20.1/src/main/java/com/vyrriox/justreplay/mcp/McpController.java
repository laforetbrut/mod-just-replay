package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.JustReplayClient;
import com.vyrriox.justreplay.client.ReplayNaming;
import com.vyrriox.justreplay.client.SceneJob;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ActorPlacement;
import com.vyrriox.justreplay.editor.BodyParts;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioLibrary;
import com.vyrriox.justreplay.editor.audio.SoundKinds;
import com.vyrriox.justreplay.editor.fx.LutLibrary;
import com.vyrriox.justreplay.editor.model.ModelLibrary;
import com.vyrriox.justreplay.editor.overlay.ImageLibrary;
import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayRename;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.EditorScreen;
import com.vyrriox.justreplay.gui.ModsChangedScreen;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.VersionCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The game as the control tools of {@code core} drive it, on the client thread: every call is one the editor, the
 * keys, the export screen or the replay list makes, so a change made over MCP is saved, undone and shown exactly
 * like one made by hand.
 *
 * @author vyrriox
 */
final class McpController implements McpControl {
    /** A replay waiting to open once the one before it has closed, given up after this many ticks. */
    private static final int OPEN_WAIT_TICKS = 1200;
    /**
     * A replay counts as open once it has run this long with no loading overlay: its resource packs reload just after
     * it starts, and leaving during that reload would leave the overlay up for good.
     */
    private static final int SETTLE_TICKS = 20;
    private int runningTicks;
    private Path pendingOpen;
    private int pendingTicks;
    /** The last export started, by MCP or by hand, frame grabs left out. */
    private VideoExporter lastExport;

    /** Client tick: opens a replay waiting for the menu, follows the exports, drops the lens of a closed replay. */
    void tick(Minecraft mc) {
        runningTicks = ReplaySession.isRunning() && mc.getOverlay() == null ? runningTicks + 1 : 0;
        VideoExporter current = VideoExporter.current();
        if (current != null && !current.isGrab() && current != lastExport) {
            lastExport = current;
        }
        if (ReplaySession.current() == null && !ReplaySession.isOpening()) {
            CameraController.clearLens();
        }
        if (pendingOpen == null) {
            return;
        }
        if (atMenu(mc)) {
            Path dir = pendingOpen;
            pendingOpen = null;
            ReplaySession.open(dir, mc.screen);
        } else if (++pendingTicks > OPEN_WAIT_TICKS) {
            JustReplay.LOGGER.warn("MCP: replay {} not opened, the menu never came back", pendingOpen.getFileName());
            pendingOpen = null;
        }
    }

    // ------------------------------------------------------------------ replays

    @Override
    public String openReplay(Path dir) {
        Minecraft mc = Minecraft.getInstance();
        if (Recorder.isRecording()) {
            return "A recording is running: stop_recording stops it first";
        }
        if (VideoExporter.isActive()) {
            return "A video export or a frame is being drawn: wait for it, or cancel_export stops the export";
        }
        if (pendingOpen != null || ReplaySession.isOpening()) {
            return "A replay is already opening: get_state tells when it is open";
        }
        if (mc.getOverlay() != null) {
            return "The game is still loading resources: call open_replay again in a moment";
        }
        if (mc.level != null && ReplaySession.current() == null) {
            return "The player is in a world of their own: open_replay never leaves it for them. Ask them to save and quit "
                    + "(or disconnect), then call open_replay again.";
        }
        ReplayMeta meta = ReplayStorage.readMeta(dir);
        if (VersionCheck.check(meta) == VersionCheck.Result.WRONG_VERSION) {
            return "Replay '" + dir.getFileName() + "' was recorded on Minecraft " + VersionCheck.recordedVersion(meta)
                    + " and this game runs " + VersionCheck.runningVersion() + ": it cannot be opened here";
        }
        pendingOpen = dir;
        pendingTicks = 0;
        if (ReplaySession.current() != null) {
            // Leaving saves the project of the replay open now; the new one opens once the menu is back.
            SceneJob.leave(mc);
        } else {
            tick(mc);
        }
        return null;
    }

    @Override
    public Opening opening() {
        Minecraft mc = Minecraft.getInstance();
        if (pendingOpen != null) {
            return Opening.PENDING;
        }
        if (mc.screen instanceof ModsChangedScreen) {
            return Opening.MODS_WARNING;
        }
        if (ReplaySession.isOpening() || ReplaySession.current() != null && !ReplaySession.isRunning()) {
            return Opening.LOADING;
        }
        if (ReplaySession.current() != null && (mc.getOverlay() != null || runningTicks < SETTLE_TICKS)) {
            // Shown, but its resource packs may still be loading.
            return Opening.LOADING;
        }
        return Opening.NONE;
    }

    @Override
    public String closeReplay() {
        if (ReplaySession.current() == null) {
            return "No replay is open";
        }
        if (VideoExporter.isActive()) {
            return "A video export or a frame is being drawn: cancel_export stops the export first";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) {
            // Leaving drops the tasks a reload waits for: the loading screen would stay up for good.
            return "The game is still loading resources (the resource packs of the replay): call close_replay again in a moment";
        }
        SceneJob.leave(mc);
        return null;
    }

    @Override
    public boolean atMenu() {
        return atMenu(Minecraft.getInstance()) && pendingOpen == null && ReplaySession.current() == null && !ReplaySession.isOpening();
    }

    /** Out of any world, on a menu, with loading finished. */
    private static boolean atMenu(Minecraft mc) {
        return mc.level == null && mc.getOverlay() == null && mc.screen != null
                && !(mc.screen instanceof net.minecraft.client.gui.screens.GenericDirtMessageScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ProgressScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
    }

    // ------------------------------------------------------------------ playback

    private static ReplaySession session() {
        return ReplaySession.current();
    }

    @Override
    public void setPaused(boolean paused) {
        Playback playback = session().playback();
        if (!paused && playback.cursor() >= playback.duration()) {
            // As the play button does at the end: start over.
            playback.seek(0);
        }
        playback.setPaused(paused);
    }

    @Override
    public float[] speeds() {
        return Playback.SPEEDS.clone();
    }

    @Override
    public void setSpeed(float speed) {
        for (int i = 0; i < Playback.SPEEDS.length; i++) {
            if (Playback.SPEEDS[i] == speed) {
                session().playback().setSpeedIndex(i);
            }
        }
    }

    @Override
    public void playTo(int tick) {
        session().playback().playTo(tick);
    }

    // ------------------------------------------------------------------ view

    @Override
    public void placeCamera(McpCamera camera) {
        Minecraft mc = Minecraft.getInstance();
        ReplaySession s = session();
        s.setCameraPath(false);
        s.spectate().stop(mc);
        if (mc.player != null) {
            Playback.placeCamera(mc.player, new Vec3(camera.x(), camera.y() - CameraRig.EYE_HEIGHT, camera.z()), (float) camera.yaw(),
                    (float) camera.pitch());
        }
        CameraController.lens(camera.roll(), camera.fov());
    }

    @Override
    public String spectate(String key) {
        Minecraft mc = Minecraft.getInstance();
        ReplaySession s = session();
        McpEntity found = null;
        for (McpEntity e : new ReplayObserver().entities()) {
            if (e.matches(key)) {
                found = e;
                break;
            }
        }
        Entity entity = found == null || mc.level == null ? null : mc.level.getEntity(found.id);
        if (entity == null) {
            return null;
        }
        s.setCameraPath(false);
        s.spectate().start(mc, entity, entity == s.findRecorder(mc));
        return entity.getName().getString();
    }

    @Override
    public void stopSpectating() {
        session().spectate().stop(Minecraft.getInstance());
    }

    @Override
    public void setCameraPath(boolean active) {
        session().setCameraPath(active);
    }

    // ------------------------------------------------------------------ editing

    @Override
    public void projectChanged(Collection<ReplayProject.ActorData> rebuilt, Collection<ReplayProject.ActorData> removed) {
        ReplaySession s = session();
        for (ReplayProject.ActorData actor : removed) {
            s.actors().remove(actor);
        }
        for (ReplayProject.ActorData actor : rebuilt) {
            s.actors().refresh(actor);
        }
        s.saveProject();
        reopenEditor(s);
    }

    /** As undo does: selections point at objects that may be gone, so the editor opens again on the project. */
    private static void reopenEditor(ReplaySession s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof EditorScreen) {
            mc.setScreen(new EditorScreen(s));
        }
    }

    @Override
    public void saveProject() {
        session().saveProject();
    }

    @Override
    public double[] underCamera() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? new double[]{0, 0, 0, 0} : new double[]{p.getX(), EditorScreen.groundBelow(p), p.getZ(), p.getYRot()};
    }

    @Override
    public String defaultActionParam(ReplayProject.ActorData actor, String type) {
        return com.vyrriox.justreplay.editor.ActorManager.defaultActionParam(actor, ActionType.valueOf(type));
    }

    @Override
    public double[] lookSpot() {
        BlockHitResult hit = ActorPlacement.crosshair(false);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        ActorPlacement.Spot spot = ActorPlacement.ground(hit, p == null ? 0.0F : p.getYRot(), false);
        return spot.valid() ? new double[]{spot.x(), spot.y(), spot.z()} : null;
    }

    @Override
    public boolean canUndo() {
        return session().canUndo();
    }

    @Override
    public boolean canRedo() {
        return session().canRedo();
    }

    @Override
    public boolean undo() {
        ReplaySession s = session();
        boolean done = s.undo();
        if (done) {
            reopenEditor(s);
        }
        return done;
    }

    @Override
    public boolean redo() {
        ReplaySession s = session();
        boolean done = s.redo();
        if (done) {
            reopenEditor(s);
        }
        return done;
    }

    @Override
    public String playerName() {
        return Minecraft.getInstance().getUser().getName();
    }

    @Override
    public List<String> files(String kind) {
        return switch (kind) {
            case "skins" -> SkinLibrary.list();
            case "models" -> ModelLibrary.list();
            case "audio" -> AudioLibrary.list();
            case "images" -> ImageLibrary.list();
            case "luts" -> LutLibrary.list();
            default -> List.of();
        };
    }

    @Override
    public List<String> modelAnimations(String file) {
        return ModelLibrary.animations(file);
    }

    @Override
    public CompletableFuture<Double> audioLength(String file) {
        return AudioLibrary.load(file).thenApply(pcm -> (double) pcm.lengthTicks());
    }

    @Override
    public boolean known(String registry, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return false;
        }
        return switch (registry) {
            case "item" -> BuiltInRegistries.ITEM.containsKey(key);
            case "entity_type" -> BuiltInRegistries.ENTITY_TYPE.containsKey(key);
            default -> false;
        };
    }

    @Override
    public Map<String, Integer> bodyParts() {
        Map<String, Integer> parts = new LinkedHashMap<>();
        for (BodyParts.Part part : BodyParts.PARTS) {
            parts.put(part.key(), part.bit());
        }
        return parts;
    }

    @Override
    public List<String> soundKinds() {
        return SoundKinds.ALL;
    }

    // ------------------------------------------------------------------ recording

    @Override
    public String startRecording(int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (ReplaySession.current() != null) {
            return "A replay is open: recordings are made in a world (close_replay first, then the player joins a world)";
        }
        if (mc.level == null) {
            return "No world is loaded: the player joins a world or a server first";
        }
        if (Recorder.isRecording()) {
            return "A recording is already running (stop_recording stops it)";
        }
        return JustReplayClient.startRecording(ticks) ? null : "The recording could not start (the game's toast and log say why)";
    }

    @Override
    public Stopped stopRecording(boolean offerName) {
        if (!Recorder.isRecording()) {
            return null;
        }
        Path saved = JustReplayClient.stopRecording(offerName);
        return saved == null ? null : new Stopped(saved, Recorder.lastStopIncomplete());
    }

    @Override
    public CompletableFuture<Path> saveInstantReplay(boolean offerName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return JustReplayClient.saveBuffer(mc, offerName);
    }

    @Override
    public CompletableFuture<ReplayRename.Result> rename(Path dir, String name) {
        return ReplayNaming.rename(dir, name);
    }

    @Override
    public boolean busy(Path dir) {
        Path target = dir.toAbsolutePath().normalize();
        ReplaySession s = ReplaySession.current();
        if (s != null && s.dir().toAbsolutePath().normalize().equals(target)) {
            return true;
        }
        Recorder recorder = Recorder.active();
        if (recorder != null && recorder.dir() != null && recorder.dir().toAbsolutePath().normalize().equals(target)) {
            return true;
        }
        return ReplayNaming.renaming();
    }

    // ------------------------------------------------------------------ export

    @Override
    public String startExport(ExportRequest r) {
        ExportResolution resolution = r.resolution() == null ? JRConfig.EXPORT_RESOLUTION.get() : ExportResolution.valueOf(r.resolution());
        VideoExporter.Settings settings = new VideoExporter.Settings(r.fps() == null ? JRConfig.EXPORT_FPS.get() : r.fps(),
                r.crf() == null ? JRConfig.EXPORT_CRF.get() : r.crf(), resolution, r.width(), r.height(), r.pngSequence());
        Component error = VideoExporter.start(session(), r.from(), r.to(), r.name(), settings,
                r.rangeInName() == null ? JRConfig.EXPORT_RANGE_IN_NAME.get() : r.rangeInName());
        if (error != null) {
            return error.getString();
        }
        lastExport = VideoExporter.current();
        return null;
    }

    @Override
    public ExportInfo lastExport() {
        VideoExporter e = lastExport;
        if (e == null) {
            return null;
        }
        boolean active = VideoExporter.current() == e && VideoExporter.isActive();
        boolean done = e.completion().isDone();
        boolean success = done && Boolean.TRUE.equals(e.completion().getNow(false));
        return new ExportInfo(e.shownFileName(), e.output(), e.startTick(), e.endTick(), active, done, success, e.isCancelled(), e.frames(),
                e.totalFrames(), e.progress(), e.elapsedMillis(), e.isPreparing(), e.isDraining(), e.videoSeconds(), e.fps());
    }

    @Override
    public boolean cancelExport() {
        VideoExporter e = VideoExporter.current();
        if (!VideoExporter.isActive() || e == null || e.isGrab()) {
            return false;
        }
        JustReplayClient.cancelExport();
        return true;
    }

    @Override
    public Path exportRoot() {
        return ReplayStorage.exportRoot();
    }

}
