package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vyrriox.justreplay.export.ExportNames;
import com.vyrriox.justreplay.export.ExportProgress;
import com.vyrriox.justreplay.format.ReplayStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The export tools: start a video export of the open replay with its settings, follow it, cancel it, and list the
 * exported files; deleting one needs the player's permission in the settings. An export never overwrites a file: a
 * taken name gets {@code _2}, {@code _3}...
 *
 * @author vyrriox
 */
final class McpExportTools {
    static final List<String> TOOLS = List.of("start_export", "get_export", "cancel_export", "list_exports", "delete_export");
    /** The sizes of the export settings, as the export screen offers them. */
    static final List<String> RESOLUTIONS = List.of("WINDOW", "HD_720", "FULL_HD_1080", "QHD_1440", "UHD_2160");
    static final List<String> FORMATS = List.of("mp4", "png");
    static final int MAX_SIDE = 7680;

    private McpExportTools() {
    }

    static void register(McpRegistry registry, McpObserver observer, McpControl control) {
        registry.add(McpTool.builder("start_export")
                .title("Export a video")
                .description("Starts a video export of the open replay as the export screen does: the project's camera path "
                        + "and cuts with every effect, overlay and sound, into the export folder. The range is the cut track, "
                        + "else the keyframes of the selected camera, unless from and to are given. Settings left out are the "
                        + "player's export settings (which this never changes). The file never overwrites another: a taken "
                        + "name gets _2. The export runs on; get_export follows it, cancel_export stops it. Needs FFmpeg for "
                        + "an MP4.")
                .input(McpSchema.object()
                        .integer("from", "First tick exported.", 0, McpObserveTools.MAX_TICK)
                        .integer("to", "Last tick exported.", 1, McpObserveTools.MAX_TICK)
                        .string("name", "File name before the range and the extension; the replay's name when left out.")
                        .integer("fps", "Frames per second, 10 to 240.", 10, 240)
                        .integer("quality", "x264 quality factor (CRF), 0 to 51, lower is better (18 is visually lossless).", 0, 51)
                        .choice("resolution", "Size: WINDOW keeps the window's, the others render at that size.", null,
                                RESOLUTIONS.toArray(new String[0]))
                        .integer("width", "Exact width in pixels, with height (replaces resolution).", 16, MAX_SIDE)
                        .integer("height", "Exact height in pixels, with width.", 16, MAX_SIDE)
                        .choice("format", "mp4 (default), or png for a folder of PNG frames with the sound as a WAV.", "mp4", "mp4", "png")
                        .bool("rangeInName", "Ends the file name with the range it holds (the player's setting when left out)."))
                .additive()
                .handler((args, context) -> start(observer, control, args))
                .build());
        registry.add(McpTool.builder("get_export")
                .title("Export progress")
                .description("How the video export goes: running or not, file, range, frames written of how many, percent, time "
                        + "spent and left, and how the last export ended (written, failed or cancelled).")
                .readOnly()
                .handler((args, context) -> McpToolResult.of(progress(control.lastExport())))
                .build());
        registry.add(McpTool.builder("cancel_export")
                .title("Cancel the export")
                .description("Stops the running video export, as Escape does; its unfinished file is removed and the replay "
                        + "goes back to where it was.")
                .destructive()
                .handler((args, context) -> cancel(control))
                .build());
        registry.add(McpTool.builder("list_exports")
                .title("Exported videos")
                .description("The files of the export folder, newest first: videos, PNG frame folders and stills, with their "
                        + "size and date.")
                .input(McpSchema.object()
                        .integer("limit", "Most files given.", 1, 500, 50)
                        .integer("offset", "Files skipped first.", 0, 1_000_000, 0))
                .readOnly()
                .offGameThread()
                .handler((args, context) -> McpToolResult.of(list(control.exportRoot(), args.integer("limit", 50, 1, 500),
                        args.integer("offset", 0, 0, 1_000_000))))
                .build());
        registry.add(McpTool.builder("delete_export")
                .title("Delete an exported file")
                .description("Deletes an exported video, PNG frame folder or still for good. Refused unless the player switched on "
                        + "'Allow deleting files' in the MCP settings, and for the export running now. There is no undo.")
                .input(McpSchema.object()
                        .string("file", "Name of the file or folder, as list_exports gives it.")
                        .required("file"))
                .deletesFiles()
                .offGameThread()
                .handler((args, context) -> delete(control, args, context))
                .build());
    }

    static McpToolResult start(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        if (args.has("width") != args.has("height")) {
            throw new McpArgs.Invalid("Give width and height together");
        }
        if (args.has("width") && args.has("resolution")) {
            throw new McpArgs.Invalid("Give resolution or width and height, not both");
        }
        McpObserver.Replay r = McpControlTools.editable(observer);
        int duration = r.durationTicks();
        int from;
        int to;
        if (args.has("from") || args.has("to")) {
            if (!(args.has("from") && args.has("to"))) {
                throw new McpArgs.Invalid("Give from and to together, or neither for the range of the edit");
            }
            from = McpControlTools.tick(args, "from", r, 0);
            to = McpControlTools.tick(args, "to", r, duration);
            if (to <= from) {
                throw new McpArgs.Invalid("'to' (" + to + ") must be after 'from' (" + from + ")");
            }
        } else {
            int[] range = r.project().exportRange(duration);
            if (range == null) {
                throw new McpArgs.Invalid("Nothing to export: the edit has no cuts and the selected camera has fewer than two "
                        + "keyframes. Give from and to, or add keyframes (edit_camera_keyframe) or cuts (edit_cut).");
            }
            from = range[0];
            to = range[1];
        }
        String name = args.text("name", 128);
        if (name == null || name.isBlank()) {
            name = r.meta() == null || r.meta().name == null || r.meta().name.isBlank() ? ExportNames.DEFAULT_NAME : r.meta().name;
        }
        boolean png = "png".equals(args.choice("format", "mp4", FORMATS));
        McpControl.ExportRequest request = new McpControl.ExportRequest(from, to, name, args.optionalInteger("fps", 10, 240),
                args.optionalInteger("quality", 0, 51), args.choice("resolution", null, RESOLUTIONS),
                args.integer("width", 0, 16, MAX_SIDE), args.integer("height", 0, 16, MAX_SIDE), png,
                args.has("rangeInName") ? args.bool("rangeInName", true) : null);
        String error = control.startExport(request);
        if (error != null) {
            throw new McpArgs.Invalid(error);
        }
        JsonObject out = McpControlTools.header(r);
        out.addProperty("started", true);
        out.add("export", progress(control.lastExport()));
        return McpToolResult.of(out);
    }

    static JsonObject progress(McpControl.ExportInfo e) {
        JsonObject out = new JsonObject();
        if (e == null) {
            out.addProperty("active", false);
            out.addProperty("message", "No video export was started since the game started.");
            return out;
        }
        out.addProperty("active", e.active());
        out.addProperty("file", e.file());
        out.addProperty("from", e.from());
        out.addProperty("to", e.to());
        out.addProperty("fromTime", McpJson.time(e.from()));
        out.addProperty("toTime", McpJson.time(e.to()));
        out.addProperty("fps", e.fps());
        out.addProperty("frames", e.frames());
        out.addProperty("totalFrames", e.totalFrames());
        out.addProperty("percent", ExportProgress.percent(e.frames(), e.totalFrames()));
        out.addProperty("elapsedSeconds", e.elapsedMillis() / 1000L);
        long left = ExportProgress.remainingSeconds(e.frames(), e.totalFrames(), e.elapsedMillis());
        if (e.active() && left >= 0) {
            out.addProperty("remainingSeconds", left);
            out.addProperty("remaining", ExportProgress.clock(left));
        }
        out.addProperty("preparing", e.preparing());
        out.addProperty("finishing", e.finishing());
        out.addProperty("videoSeconds", McpCamera.round(e.videoSeconds(), 3));
        String state = e.active() ? e.preparing() ? "preparing" : e.finishing() ? "finishing" : "rendering"
                : !e.done() ? "finishing" : e.success() ? "written" : e.cancelled() ? "cancelled" : "failed";
        out.addProperty("state", state);
        out.addProperty("done", e.done());
        if (e.done()) {
            out.addProperty("written", e.success());
            out.addProperty("cancelled", e.cancelled());
        }
        return out;
    }

    static McpToolResult cancel(McpControl control) throws McpArgs.Invalid {
        McpControl.ExportInfo running = control.lastExport();
        if (running == null || !running.active() || !control.cancelExport()) {
            throw new McpArgs.Invalid("No video export is running (get_export tells how the last one ended)");
        }
        JsonObject out = new JsonObject();
        out.addProperty("cancelled", true);
        out.addProperty("file", running.file());
        out.add("export", progress(control.lastExport()));
        return McpToolResult.of(out);
    }

    // ------------------------------------------------------------------ files

    private record Entry(Path path, long size, long modified, boolean folder) {
    }

    static JsonObject list(Path root, int limit, int offset) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.list(root)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String n = p.getFileName().toString();
                    if (n.startsWith(".") || n.endsWith(".log")) {
                        continue;
                    }
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    entries.add(new Entry(p, a.isDirectory() ? folderSize(p) : a.size(), a.lastModifiedTime().toMillis(), a.isDirectory()));
                }
            }
        }
        entries.sort(Comparator.comparingLong(Entry::modified).reversed().thenComparing(e -> e.path().getFileName().toString()));
        JsonArray list = new JsonArray();
        for (int i = offset; i < entries.size() && list.size() < limit; i++) {
            Entry e = entries.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("file", e.path().getFileName().toString());
            o.addProperty("kind", e.folder() ? "frames" : e.path().getFileName().toString().endsWith(".png") ? "still" : "video");
            o.addProperty("sizeBytes", e.size());
            o.addProperty("modified", Instant.ofEpochMilli(e.modified()).toString());
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("total", entries.size());
        out.addProperty("offset", offset);
        out.addProperty("count", list.size());
        out.add("files", list);
        return out;
    }

    private static long folderSize(Path dir) throws IOException {
        long total = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(p)) {
                    total += Files.size(p);
                }
            }
        }
        return total;
    }

    /** The file of the export folder a name names, right inside it, or null for anything else. */
    static Path exportFile(Path root, String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path base = root.toAbsolutePath().normalize();
            Path file = base.resolve(name).normalize();
            return base.equals(file.getParent()) && Files.exists(file) ? file : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    static McpToolResult delete(McpControl control, McpArgs args, McpContext context) throws Exception {
        String name = args.required("file");
        Path file = exportFile(control.exportRoot(), name);
        if (file == null) {
            throw new McpArgs.Invalid("No file '" + name + "' in the export folder (list_exports gives them)");
        }
        McpControl.ExportInfo running = context.onGameThread(control::lastExport);
        if (running != null && !running.done() && running.output() != null
                && running.output().toAbsolutePath().normalize().equals(file)) {
            throw new McpArgs.Invalid("'" + name + "' is the export running now: cancel_export stops it and removes its file");
        }
        boolean folder = Files.isDirectory(file);
        ReplayStorage.deleteRecursively(file);
        JsonObject out = new JsonObject();
        out.addProperty("deleted", name);
        out.addProperty("kind", folder ? "frames" : "file");
        return McpToolResult.of(out);
    }
}
