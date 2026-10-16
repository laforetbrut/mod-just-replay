package com.vyrriox.justreplay.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unattended job for external tools: {@code -Djustreplay.job=<file.json>} lists steps run in order in one launch
 * (record a world, open a replay, take screenshots, export ranges, quit), so a caller makes several clips and
 * pictures without starting the game again. Every step is logged with the {@code SCENE} prefix; the last line is
 * {@code SCENE done}, after {@code SCENE job finished} or {@code SCENE job failed ...}. The format is described in
 * the README, "Scene jobs".
 *
 * @author vyrriox
 */
final class SceneJob {
    /** Ticks a step may wait for a world or a replay to load before the job gives up. */
    private static final int LOAD_TIMEOUT = 20 * 60 * 5;

    private sealed interface Step permits Record, Open, Shots, Export, Quit {
    }

    private record Record(String world, List<String> commands, int delay, int length) implements Step {
    }

    /** {@code replay} null opens the last recording of this job. */
    private record Open(String replay, int delay) implements Step {
    }

    private record Shots(List<Integer> ticks, String prefix) implements Step {
    }

    private record Export(int start, int end, String name, VideoExporter.Settings settings) implements Step {
    }

    private record Quit() implements Step {
    }

    private static List<Step> steps;
    private static int index;
    private static int phase;
    private static int wait;
    private static int waited;
    private static int shot;
    private static boolean announced;
    private static boolean quitting;
    private static boolean failed;
    private static volatile String worldError;
    private static Path lastRecording;
    private static VideoExporter exporter;

    private SceneJob() {
    }

    private static void log(String message, Object... args) {
        SceneRecorder.log(message, args);
    }

    static void tick(Minecraft mc, String file) {
        if (steps == null) {
            load(mc, file);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (quitting || index >= steps.size()) {
            quit(mc);
            return;
        }
        Step step = steps.get(index);
        if (!announced) {
            announced = true;
            log("step {}/{} {}", index + 1, steps.size(), describe(step));
        }
        switch (step) {
            case Record r -> record(mc, r);
            case Open o -> open(mc, o);
            case Shots s -> shots(mc, s);
            case Export e -> export(mc, e);
            case Quit q -> quitting = true;
        }
    }

    private static void next(int delay) {
        log("step {}/{} done", index + 1, steps.size());
        index++;
        phase = 0;
        waited = 0;
        announced = false;
        wait = delay;
    }

    private static void fail(String reason, Object... args) {
        Object[] all = new Object[args.length + 2];
        all[0] = index + 1;
        all[1] = steps.size();
        System.arraycopy(args, 0, all, 2, args.length);
        log("job failed at step {}/{}: " + reason, all);
        quitting = true;
        failed = true;
        phase = 0;
    }

    /** True once a waiting phase has waited too long; counts the ticks of the current phase. */
    private static boolean timedOut() {
        return ++waited > LOAD_TIMEOUT;
    }

    // ---------------------------------------------------------------- steps

    private static void record(Minecraft mc, Record r) {
        switch (phase) {
            case 0 -> {
                if (leaveIfInWorld(mc) || !atMenu(mc)) {
                    return;
                }
                if (!mc.getLevelSource().levelExists(r.world())) {
                    fail("no world named \"{}\" in the saves folder", r.world());
                    return;
                }
                log("opening world {}", r.world());
                worldError = null;
                mc.createWorldOpenFlows().openWorld(r.world(), () -> worldError = "the world could not be opened");
                phase = 1;
            }
            case 1 -> {
                SceneRecorder.skipBackupScreen(mc);
                if (worldError != null) {
                    fail(worldError);
                } else if (mc.player != null && mc.level != null && mc.screen == null) {
                    SceneRecorder.runCommands(mc, r.commands());
                    phase = 2;
                    waited = 0;
                    wait = r.delay();
                } else if (timedOut()) {
                    fail("the world {} did not load", r.world());
                }
            }
            case 2 -> {
                if (Recorder.startManual()) {
                    log("recording for {} s", r.length() / 20.0);
                    phase = 3;
                    wait = r.length();
                } else if (++waited > 200) {
                    fail("the recording could not start");
                }
            }
            default -> {
                Path dir = Recorder.stop();
                if (dir == null) {
                    fail("the recording was lost");
                    return;
                }
                lastRecording = dir;
                log("recording saved: {}", dir);
                log("replay folder: {}", dir.getFileName());
                leave(mc);
                next(40);
            }
        }
    }

    private static void open(Minecraft mc, Open o) {
        switch (phase) {
            case 0 -> {
                if (leaveIfInWorld(mc) || !atMenu(mc) || ReplaySession.current() != null) {
                    return;
                }
                Path dir;
                if (o.replay() != null) {
                    dir = ReplayStorage.root().resolve(o.replay());
                } else if (lastRecording != null) {
                    dir = lastRecording;
                } else {
                    fail("no replay named and nothing recorded earlier in this job");
                    return;
                }
                if (!Files.isRegularFile(dir.resolve(ReplayStorage.META))) {
                    fail("no replay in {}", dir);
                    return;
                }
                log("opening replay {}", dir);
                ReplaySession.open(dir, mc.screen);
                phase = 1;
            }
            default -> {
                if (ReplaySession.isRunning() && mc.player != null) {
                    log("replay running, {} ticks", ReplaySession.current().playback().duration());
                    next(o.delay());
                } else if (timedOut() || waited > 40 && ReplaySession.current() == null && atMenu(mc)) {
                    // A replay that cannot be read sends the game back to the menu without a session.
                    fail("the replay did not open");
                }
            }
        }
    }

    private static void shots(Minecraft mc, Shots s) {
        ReplaySession session = ReplaySession.current();
        if (session == null) {
            fail("no replay open: put an open step before shots");
            return;
        }
        if (shot == 0) {
            session.setCameraPath(true);
        }
        if (shot >= s.ticks().size() * 2) {
            shot = 0;
            next(10);
            return;
        }
        int tick = s.ticks().get(shot / 2);
        if (shot % 2 == 0) {
            // Seek, then let the world settle before the picture.
            mc.setScreen(null);
            session.playback().seek(tick);
            session.playback().setPaused(true);
            wait = 60;
        } else {
            String name = s.prefix() + "-" + tick + ".png";
            Path file = mc.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR).resolve(name).toAbsolutePath().normalize();
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> log("screenshot at tick {}: {}", tick, file));
            wait = 10;
        }
        shot++;
    }

    private static void export(Minecraft mc, Export e) {
        switch (phase) {
            case 0 -> {
                ReplaySession session = ReplaySession.current();
                if (session == null) {
                    fail("no replay open: put an open step before export");
                    return;
                }
                if (VideoExporter.isActive()) {
                    return;
                }
                mc.setScreen(null);
                session.setCameraPath(true);
                Component error = VideoExporter.start(session, e.start(), e.end(), e.name(), e.settings());
                if (error != null) {
                    fail("the export could not start: {}", error.getString());
                    return;
                }
                exporter = VideoExporter.current();
                log("export started: {}", exporter.output());
                phase = 1;
            }
            default -> {
                if (!exporter.completion().isDone()) {
                    return;
                }
                if (Boolean.TRUE.equals(exporter.completion().join())) {
                    log("export finished: {} ({} frames)", exporter.output(), exporter.frames());
                    exporter = null;
                    next(20);
                } else {
                    fail("the export failed, see {}.log", exporter.output());
                    exporter = null;
                }
            }
        }
    }

    private static void quit(Minecraft mc) {
        switch (phase) {
            case 0 -> {
                if (VideoExporter.isActive()) {
                    VideoExporter.cancel();
                }
                if (Recorder.stop() != null) {
                    log("recording stopped before quitting");
                }
                // Steps after a quit step never run.
                index = steps.size();
                phase = 1;
                if (mc.level != null) {
                    mc.level.disconnect();
                }
                mc.disconnect();
                wait = 40;
            }
            case 1 -> {
                phase = 2;
                if (!failed) {
                    log("job finished");
                }
                log("done");
                mc.stop();
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Leaves a world or a replay for the title screen; true while that is going on. */
    private static boolean leaveIfInWorld(Minecraft mc) {
        if (mc.level == null) {
            return false;
        }
        leave(mc);
        wait = 40;
        return true;
    }

    private static void leave(Minecraft mc) {
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.disconnect(new TitleScreen());
    }

    private static boolean atMenu(Minecraft mc) {
        return mc.level == null && mc.getOverlay() == null && mc.screen instanceof TitleScreen;
    }

    private static String describe(Step step) {
        return switch (step) {
            case Record r -> "record: world " + r.world() + ", " + r.length() / 20.0 + " s";
            case Open o -> "open: replay " + (o.replay() == null ? "recorded last" : o.replay());
            case Shots s -> "shots: ticks " + s.ticks();
            case Export e -> "export: ticks " + e.start() + ".." + e.end() + " as " + e.name() + ", "
                    + (e.settings().width() > 0 ? e.settings().width() + "x" + e.settings().height() : e.settings().resolution().name())
                    + ", " + e.settings().fps() + " fps" + (e.settings().pngSequence() ? ", PNG sequence" : ", crf " + e.settings().crf());
            case Quit q -> "quit";
        };
    }

    // ---------------------------------------------------------------- parsing

    private static void load(Minecraft mc, String file) {
        steps = new ArrayList<>();
        Path path = mc.gameDirectory.toPath().resolve(file).toAbsolutePath().normalize();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray list = root.getAsJsonArray("steps");
            if (list == null || list.isEmpty()) {
                throw new IllegalArgumentException("\"steps\" is missing or empty");
            }
            for (int i = 0; i < list.size(); i++) {
                try {
                    steps.add(parse(list.get(i).getAsJsonObject()));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("step " + (i + 1) + ": " + e.getMessage(), e);
                }
            }
            log("job {}: {} steps", path, steps.size());
        } catch (Exception e) {
            log("job error in {}: {}", path, e.getMessage());
            steps = List.of();
            quitting = true;
            failed = true;
        }
    }

    private static Step parse(JsonObject o) {
        String type = text(o, "type", null);
        if (type == null) {
            throw new IllegalArgumentException("\"type\" is missing");
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "record" -> new Record(required(o, "world"), commands(o.get("commands")), ticks(o, "delay", 5), ticks(o, "seconds", 60));
            case "open" -> new Open(text(o, "replay", null), ticks(o, "delay", 5));
            case "shots" -> {
                JsonArray array = o.getAsJsonArray("ticks");
                if (array == null || array.isEmpty()) {
                    throw new IllegalArgumentException("\"ticks\" is missing or empty");
                }
                List<Integer> ticks = new ArrayList<>();
                array.forEach(t -> ticks.add(t.getAsInt()));
                yield new Shots(ticks, safe(text(o, "prefix", "scene")));
            }
            case "export" -> export(o);
            case "quit" -> new Quit();
            default -> throw new IllegalArgumentException("unknown type \"" + type + "\"");
        };
    }

    private static Export export(JsonObject o) {
        if (!o.has("start") || !o.has("end")) {
            throw new IllegalArgumentException("export needs \"start\" and \"end\" ticks");
        }
        int start = o.get("start").getAsInt();
        int end = o.get("end").getAsInt();
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("export range " + start + ".." + end + " is empty");
        }
        String format = text(o, "format", "mp4").toLowerCase(Locale.ROOT);
        if (!format.equals("mp4") && !format.equals("png")) {
            throw new IllegalArgumentException("format must be mp4 or png, not " + format);
        }
        boolean png = format.equals("png");
        int width = o.has("width") ? o.get("width").getAsInt() : 0;
        int height = o.has("height") ? o.get("height").getAsInt() : 0;
        if (width > 0 != height > 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("give both \"width\" and \"height\", above zero");
        }
        if (!png && (width % 2 != 0 || height % 2 != 0)) {
            throw new IllegalArgumentException("an MP4 needs an even width and height, not " + width + "x" + height);
        }
        int fps = o.has("fps") ? o.get("fps").getAsInt() : 60;
        if (fps < 10 || fps > 240) {
            throw new IllegalArgumentException("fps must be between 10 and 240");
        }
        int crf = o.has("crf") ? o.get("crf").getAsInt() : 16;
        if (crf < 0 || crf > 51) {
            throw new IllegalArgumentException("crf must be between 0 and 51");
        }
        ExportResolution resolution = resolution(text(o, "resolution", "1080p"));
        return new Export(start, end, safe(text(o, "name", "scene")),
                new VideoExporter.Settings(fps, crf, resolution, width, height, png));
    }

    private static ExportResolution resolution(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "window" -> ExportResolution.WINDOW;
            case "720p", "hd_720" -> ExportResolution.HD_720;
            case "1080p", "full_hd_1080" -> ExportResolution.FULL_HD_1080;
            case "1440p", "qhd_1440" -> ExportResolution.QHD_1440;
            case "2160p", "4k", "uhd_2160" -> ExportResolution.UHD_2160;
            default -> throw new IllegalArgumentException("unknown resolution \"" + value + "\" (window, 720p, 1080p, 1440p or 2160p)");
        };
    }

    /** Commands as a list, or one string separated by {@code ;} like {@code -Djustreplay.sceneCommands}. */
    private static List<String> commands(JsonElement element) {
        List<String> commands = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return commands;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(c -> commands.add(c.getAsString()));
        } else {
            commands.addAll(List.of(element.getAsString().split(";")));
        }
        commands.replaceAll(c -> c.strip().startsWith("/") ? c.strip().substring(1) : c.strip());
        return commands;
    }

    /** A duration in seconds, possibly fractional, as ticks. */
    private static int ticks(JsonObject o, String key, double seconds) {
        double value = o.has(key) ? o.get(key).getAsDouble() : seconds;
        if (value < 0) {
            throw new IllegalArgumentException("\"" + key + "\" cannot be negative");
        }
        return (int) Math.round(value * 20);
    }

    private static String required(JsonObject o, String key) {
        String value = text(o, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("\"" + key + "\" is missing");
        }
        return value;
    }

    private static String text(JsonObject o, String key, String fallback) {
        JsonElement element = o.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    /** File names keep to letters, digits, dot, dash and underscore. */
    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
