package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayRename;
import com.vyrriox.justreplay.format.ReplayStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The game as the control tools drive it, over {@link FakeObserver}: opening takes a couple of looks, the project's
 * history works as the editor's (one step per changed save, a selection alone is no step), an export renders five
 * frames each time it is looked at, recordings and the last minutes are written as replay folders.
 *
 * @author vyrriox
 */
final class FakeControl implements McpControl {
    static final float[] SPEEDS = {0.1F, 0.25F, 0.5F, 1.0F, 2.0F, 4.0F, 8.0F};

    final FakeObserver world;
    final Path replays;
    final Path exports;
    final List<String> calls = new ArrayList<>();
    final List<String> rebuilt = new ArrayList<>();
    final List<String> removed = new ArrayList<>();
    int changes;
    Path pending;
    boolean pendingSeen;
    boolean modsWarning;
    boolean cameraPath;
    String spectating = "";
    boolean recording;
    int recordingTicks;
    int recordings;
    boolean bufferOn = true;
    final Set<String> busy = new HashSet<>();
    McpControl.ExportRequest lastRequest;
    private ExportInfo export;
    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();
    private String last;

    FakeControl(FakeObserver world, Path replays, Path exports) {
        this.world = world;
        this.replays = replays;
        this.exports = exports;
        this.last = ReplayStorage.GSON.toJson(world.project);
    }

    /** Writes a saved replay folder holding a stream, a meta.json and, when given, a project. */
    static Path writeReplay(Path root, String id, String name, int duration, ReplayProject project) {
        try {
            Path dir = Files.createDirectories(root.resolve(id));
            Files.write(dir.resolve(ReplayStorage.STREAM), new byte[]{1, 2, 3});
            ReplayMeta meta = new ReplayMeta();
            meta.name = name;
            meta.durationTicks = duration;
            ReplayStorage.writeMeta(dir, meta);
            if (project != null) {
                ReplayStorage.writeProject(dir, project);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ replays

    @Override
    public String openReplay(Path dir) {
        calls.add("open " + dir.getFileName());
        if (recording) {
            return "A recording is running: stop_recording stops it first";
        }
        if (world.exporting) {
            return "A video export is running";
        }
        pending = dir;
        pendingSeen = false;
        return null;
    }

    @Override
    public Opening opening() {
        if (pending == null) {
            return Opening.NONE;
        }
        if (modsWarning) {
            return Opening.MODS_WARNING;
        }
        if (!pendingSeen) {
            pendingSeen = true;
            world.open = false;
            return Opening.LOADING;
        }
        ReplayMeta meta = ReplayStorage.readMeta(pending);
        world.id = pending.getFileName().toString();
        world.meta.name = meta.name;
        world.duration = meta.durationTicks;
        world.meta.durationTicks = meta.durationTicks;
        world.project = ReplayStorage.readProject(pending);
        world.project.normalize();
        world.cursor = 0;
        world.open = true;
        last = ReplayStorage.GSON.toJson(world.project);
        undo.clear();
        redo.clear();
        pending = null;
        return Opening.NONE;
    }

    @Override
    public String closeReplay() {
        if (!world.open) {
            return "No replay is open";
        }
        saveProject();
        world.open = false;
        calls.add("close");
        return null;
    }

    @Override
    public boolean atMenu() {
        return !world.open && pending == null;
    }

    // ------------------------------------------------------------------ playback

    @Override
    public void setPaused(boolean paused) {
        world.paused = paused;
    }

    @Override
    public float[] speeds() {
        return SPEEDS.clone();
    }

    @Override
    public void setSpeed(float speed) {
        world.speed = speed;
    }

    @Override
    public void playTo(int tick) {
        world.paused = false;
        calls.add("playTo " + tick);
    }

    @Override
    public void placeCamera(McpCamera camera) {
        world.camera = camera.withSource("current");
        cameraPath = false;
        spectating = "";
    }

    @Override
    public String spectate(String key) {
        McpEntity e = world.entity(key);
        if (e == null) {
            return null;
        }
        spectating = e.name;
        return e.name;
    }

    @Override
    public void stopSpectating() {
        spectating = "";
    }

    @Override
    public void setCameraPath(boolean active) {
        cameraPath = active;
    }

    // ------------------------------------------------------------------ editing

    @Override
    public void projectChanged(Collection<ReplayProject.ActorData> rebuilt, Collection<ReplayProject.ActorData> removed) {
        changes++;
        rebuilt.forEach(a -> this.rebuilt.add(a.id));
        removed.forEach(a -> this.removed.add(a.id));
        saveProject();
    }

    @Override
    public void saveProject() {
        world.project.sort();
        String json = ReplayStorage.GSON.toJson(world.project);
        if (!json.equals(last)) {
            if (!stripSelection(json).equals(stripSelection(last))) {
                undo.push(last);
                redo.clear();
            }
            last = json;
        }
        if (world.open && Files.isDirectory(replays.resolve(world.id))) {
            ReplayStorage.writeProject(replays.resolve(world.id), world.project);
        }
    }

    private static String stripSelection(String json) {
        return json.replaceAll("\"selectedCamera\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null),?", "");
    }

    @Override
    public double[] underCamera() {
        return new double[]{world.camera.x(), 64.0, world.camera.z(), world.camera.yaw()};
    }

    @Override
    public String defaultActionParam(ReplayProject.ActorData actor, String type) {
        ActionType t = ActionType.valueOf(type);
        if (t == ActionType.WALK_TO || t == ActionType.RUN_TO) {
            return "0.00 64.00 0.00";
        }
        if (t == ActionType.ANIMATION && actor.isModel()) {
            return "idle|once";
        }
        return t.defaultParam;
    }

    @Override
    public double[] lookSpot() {
        return new double[]{1.0, 64.0, 2.0};
    }

    @Override
    public boolean canUndo() {
        return !undo.isEmpty();
    }

    @Override
    public boolean canRedo() {
        return !redo.isEmpty();
    }

    @Override
    public boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        redo.push(last);
        restore(undo.pop());
        return true;
    }

    @Override
    public boolean redo() {
        if (redo.isEmpty()) {
            return false;
        }
        undo.push(last);
        restore(redo.pop());
        return true;
    }

    /** As the editor restores a state: the fields go into the same project object. */
    private void restore(String json) {
        ReplayProject state = ReplayStorage.GSON.fromJson(json, ReplayProject.class);
        state.normalize();
        for (java.lang.reflect.Field field : ReplayProject.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            try {
                field.set(world.project, field.get(state));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        world.project.sort();
        last = ReplayStorage.GSON.toJson(world.project);
    }

    @Override
    public String playerName() {
        return "Steve";
    }

    @Override
    public List<String> files(String kind) {
        return switch (kind) {
            case "skins" -> List.of("hero.png");
            case "models" -> List.of("robot.bbmodel");
            case "audio" -> List.of("theme.ogg", "broken.ogg");
            case "images" -> List.of("logo.png");
            case "luts" -> List.of("warm.png");
            default -> List.of();
        };
    }

    @Override
    public List<String> modelAnimations(String file) {
        return "robot.bbmodel".equals(file) ? List.of("idle", "wave") : List.of();
    }

    @Override
    public CompletableFuture<Double> audioLength(String file) {
        return "theme.ogg".equals(file) ? CompletableFuture.completedFuture(400.0)
                : CompletableFuture.failedFuture(new IOException("not a sound file"));
    }

    @Override
    public boolean known(String registry, String id) {
        return switch (registry) {
            case "item" -> Set.of("minecraft:diamond_sword", "minecraft:stick", "minecraft:iron_helmet").contains(id);
            case "entity_type" -> Set.of("minecraft:villager", "minecraft:zombie", "minecraft:pig", "minecraft:bat").contains(id);
            default -> false;
        };
    }

    @Override
    public Map<String, Integer> bodyParts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("head", 1);
        m.put("body", 2);
        m.put("left_arm", 4);
        m.put("right_arm", 8);
        m.put("left_leg", 16);
        m.put("right_leg", 32);
        return m;
    }

    @Override
    public List<String> soundKinds() {
        return List.of("footsteps", "players", "blocks", "hostile", "neutral", "ambient", "weather", "records");
    }

    // ------------------------------------------------------------------ recording

    @Override
    public String startRecording(int ticks) {
        if (world.open) {
            return "A replay is open: close_replay closes it first";
        }
        if (recording) {
            return "A recording is already running";
        }
        recording = true;
        recordingTicks = ticks;
        return null;
    }

    @Override
    public Stopped stopRecording(boolean offerName) {
        if (!recording) {
            return null;
        }
        recording = false;
        calls.add("stop offerName=" + offerName);
        return new Stopped(writeReplay(replays, "2026-11-23_10-00-0" + (++recordings), "2026-11-23 10:00:0" + recordings, 400, null), false);
    }

    @Override
    public CompletableFuture<Path> saveInstantReplay(boolean offerName) {
        if (!bufferOn) {
            return null;
        }
        calls.add("buffer offerName=" + offerName);
        return CompletableFuture.completedFuture(writeReplay(replays, "2026-11-23_11-00-0" + (++recordings) + "_buffer",
                "2026-11-23 11:00:0" + recordings, 1200, null));
    }

    @Override
    public CompletableFuture<ReplayRename.Result> rename(Path dir, String name) {
        calls.add("rename " + dir.getFileName() + " to " + name);
        return CompletableFuture.completedFuture(ReplayRename.apply(dir, name));
    }

    @Override
    public boolean busy(Path dir) {
        String id = dir.getFileName().toString();
        return world.open && world.id.equals(id) || busy.contains(id);
    }

    // ------------------------------------------------------------------ export

    @Override
    public String startExport(ExportRequest request) {
        if (export != null && export.active()) {
            return "An export is already running";
        }
        lastRequest = request;
        int fps = request.fps() == null ? 60 : request.fps();
        long total = (long) (request.to() - request.from()) * fps / 20L;
        String file = request.name() + (request.pngSequence() ? "" : ".mp4");
        export = new ExportInfo(file, exports.resolve(file), request.from(), request.to(), true, false, false, false, 0, total, 0.0F,
                0L, false, false, 0.0, fps);
        world.exporting = true;
        return null;
    }

    @Override
    public ExportInfo lastExport() {
        if (export != null && export.active() && export.frames() < export.totalFrames()) {
            long frames = Math.min(export.totalFrames(), export.frames() + 5);
            export = new ExportInfo(export.file(), export.output(), export.from(), export.to(), true, false, false, false, frames,
                    export.totalFrames(), (float) frames / export.totalFrames(), export.elapsedMillis() + 100L, false, false,
                    frames / (double) export.fps(), export.fps());
        }
        return export;
    }

    @Override
    public boolean cancelExport() {
        if (export == null || !export.active()) {
            return false;
        }
        export = new ExportInfo(export.file(), export.output(), export.from(), export.to(), false, true, false, true, export.frames(),
                export.totalFrames(), export.progress(), export.elapsedMillis(), false, false, export.videoSeconds(), export.fps());
        world.exporting = false;
        return true;
    }

    @Override
    public Path exportRoot() {
        return exports;
    }
}
