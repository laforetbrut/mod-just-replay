package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.Marker;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The first tools of the MCP server, all read only: the state of the mod, the saved replays, the project of a replay
 * and its markers, each also offered as a resource. The open replay is read on the game thread from its live
 * project (edits not saved yet included); a saved replay is read from its files, which are never changed.
 *
 * @author vyrriox
 */
public final class McpReadTools {
    public static final String MIME = "application/json";
    public static final String STATE_URI = "justreplay://state";
    public static final String REPLAYS_URI = "justreplay://replays";
    public static final String PROJECT_URI = "justreplay://project";
    public static final String MARKERS_URI = "justreplay://markers";
    public static final String MARKER_TEMPLATE = "justreplay://markers/{id}";
    private static final List<String> DETAILS = List.of("summary", "full");
    private static final String REPLAY_ARG = "Id of a saved replay (the 'id' field of list_replays). Leave it out for the replay open in the game.";

    /** Instructions given to the model when it connects. */
    public static final String INSTRUCTIONS = "Just Replay records Minecraft sessions as replays and edits them into videos (cameras, "
            + "actors, audio, effects, overlays, markers), inside the player's game. These tools only read. Start with get_state "
            + "to see what the mod is doing and whether a replay is open; list_replays gives the saved replays and their ids; "
            + "get_project shows the edit of a replay; list_markers and get_marker give its markers with their notes, which "
            + "often say what the player wants. Times are ticks (20 per second), also given as text such as 1:02.5.";

    private McpReadTools() {
    }

    /** A registry holding every read tool and resource, reading the game through {@code game}. */
    public static McpRegistry registry(McpGame game) {
        McpRegistry registry = new McpRegistry();
        register(registry, game);
        return registry;
    }

    public static void register(McpRegistry registry, McpGame game) {
        registry.add(McpTool.builder("get_state")
                .title("Just Replay state")
                .description("What Just Replay is doing now: recording, instant replay buffer, the replay open for watching or "
                        + "editing (play head, length, speed), whether the editor is open, the video export in progress, the "
                        + "screen shown and the game version. Call it first.")
                .readOnly()
                .handler((args, context) -> McpToolResult.of(McpJson.state(game.state())))
                .build());
        registry.add(McpTool.builder("list_replays")
                .title("Saved replays")
                .description("The saved replays, newest first, with their metadata: id, name, server or singleplayer, date, "
                        + "length, game and mod versions, size, whether they have an edit (project) and which one is open.")
                .input(McpSchema.object()
                        .string("query", "Keeps only the replays whose name, id or server contains this text (any case).")
                        .integer("limit", "Most replays given.", 1, 500, 50)
                        .integer("offset", "Replays skipped first, to page through a long list.", 0, 1_000_000, 0))
                .readOnly()
                .offGameThread()
                .handler((args, context) -> McpToolResult.of(replays(game, context,
                        args.string("query", ""), args.integer("limit", 50, 1, 500), args.integer("offset", 0, 0, 1_000_000))))
                .build());
        registry.add(McpTool.builder("get_project")
                .title("Replay project")
                .description("The edit of a replay: cameras and their keyframes, cuts, actors, actor actions, audio tracks and "
                        + "clips, effects, overlays, world settings, speed changes and entity edits, with field names as in "
                        + "project.json. Summary by default (keyframe lists shortened to their count and first and last tick); "
                        + "detail=full gives everything. Pick sections to keep the answer short.")
                .input(McpSchema.object()
                        .string("replay", REPLAY_ARG)
                        .choices("sections", "Sections to give, all of them when left out.", McpJson.SECTIONS.toArray(new String[0]))
                        .choice("detail", "summary or full.", "summary", "summary", "full"))
                .readOnly()
                .offGameThread()
                .handler((args, context) -> McpToolResult.of(project(game, context, args.string("replay", null),
                        args.choices("sections", McpJson.SECTIONS), "full".equals(args.choice("detail", "summary", DETAILS)))))
                .build());
        registry.add(McpTool.builder("list_markers")
                .title("Markers")
                .description("The markers of a replay, in time order, with their notes: moments (INSTANT), stretches of time "
                        + "(RANGE), spots of the world (POINT) and entities (ENTITY). A note often says what the player wants "
                        + "done there.")
                .input(McpSchema.object()
                        .string("replay", REPLAY_ARG)
                        .choice("type", "Keeps only the markers of this type.", null, Marker.TYPES.toArray(new String[0]))
                        .string("query", "Keeps only the markers whose name or note contains this text (any case)."))
                .readOnly()
                .offGameThread()
                .handler((args, context) -> McpToolResult.of(markers(game, context, args.string("replay", null),
                        args.choice("type", null, Marker.TYPES), args.string("query", ""))))
                .build());
        registry.add(McpTool.builder("get_marker")
                .title("Marker")
                .description("One marker of a replay by its id (from list_markers): name, full note, type, times, colour, the "
                        + "spot or entity it is pinned to, and the ids of the markers before and after it.")
                .input(McpSchema.object()
                        .string("id", "Id of the marker.")
                        .string("replay", REPLAY_ARG)
                        .required("id"))
                .readOnly()
                .offGameThread()
                .handler((args, context) -> McpToolResult.of(marker(game, context, args.string("replay", null), args.required("id"))))
                .build());

        registry.add(new McpResource(STATE_URI, "state", "Just Replay state", "Same as the get_state tool.", MIME, true,
                context -> McpToolResult.GSON.toJson(McpJson.state(game.state()))));
        registry.add(new McpResource(REPLAYS_URI, "replays", "Saved replays", "The 500 newest saved replays, as list_replays gives them.",
                MIME, false, context -> McpToolResult.GSON.toJson(replays(game, context, "", 500, 0))));
        registry.add(new McpResource(PROJECT_URI, "project", "Open project", "Summary of the project of the replay open in the game.",
                MIME, false, context -> McpToolResult.GSON.toJson(openOrNothing(game, context, true))));
        registry.add(new McpResource(MARKERS_URI, "markers", "Markers", "The markers of the replay open in the game.",
                MIME, false, context -> McpToolResult.GSON.toJson(openOrNothing(game, context, false))));
        registry.add(new McpResource.Template(MARKER_TEMPLATE, "marker", "Marker", "One marker of the replay open in the game, by id.",
                MIME, false, (id, context) -> {
            try {
                return McpToolResult.GSON.toJson(marker(game, context, null, id));
            } catch (McpArgs.Invalid e) {
                return null;
            }
        }));
    }

    // ------------------------------------------------------------------ answers

    static JsonObject replays(McpGame game, McpContext context, String query, int limit, int offset) throws Exception {
        String open = context.onGameThread(() -> {
            McpGame.OpenReplay o = game.openReplay();
            return o == null ? "" : o.id();
        });
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ReplayStorage.Entry> matching = new ArrayList<>();
        for (ReplayStorage.Entry entry : ReplayStorage.list(game.replaysRoot())) {
            String id = entry.dir().getFileName().toString();
            ReplayMeta meta = entry.meta();
            if (q.isEmpty() || contains(id, q) || contains(meta.name, q) || contains(meta.server, q)) {
                matching.add(entry);
            }
        }
        JsonArray list = new JsonArray();
        for (int i = offset; i < matching.size() && list.size() < limit; i++) {
            ReplayStorage.Entry entry = matching.get(i);
            String id = entry.dir().getFileName().toString();
            list.add(McpJson.replayEntry(id, entry.meta(), entry.sizeBytes(),
                    Files.isRegularFile(entry.dir().resolve(ReplayStorage.PROJECT)), id.equals(open)));
        }
        JsonObject out = new JsonObject();
        out.addProperty("total", matching.size());
        out.addProperty("offset", offset);
        out.addProperty("count", list.size());
        out.addProperty("openReplay", open);
        out.add("replays", list);
        return out;
    }

    static JsonObject project(McpGame game, McpContext context, String replay, List<String> sections, boolean full) throws Exception {
        JsonObject live = context.onGameThread(() -> {
            McpGame.OpenReplay o = open(game, replay);
            return o == null ? null : McpJson.project(McpJson.replayInfo(o.id(), o.meta(), true, o.tick(), o.durationTicks()),
                    o.meta(), o.project(), sections, full, o.durationTicks());
        });
        if (live != null) {
            return live;
        }
        Stored s = stored(game, replay);
        return McpJson.project(McpJson.replayInfo(s.id, s.meta, false, 0, s.meta.durationTicks), s.meta, s.project, sections, full,
                s.meta.durationTicks);
    }

    static JsonObject markers(McpGame game, McpContext context, String replay, String type, String query) throws Exception {
        JsonObject live = context.onGameThread(() -> {
            McpGame.OpenReplay o = open(game, replay);
            return o == null ? null : markerList(McpJson.replayInfo(o.id(), o.meta(), true, o.tick(), o.durationTicks()),
                    o.project(), type, query);
        });
        if (live != null) {
            return live;
        }
        Stored s = stored(game, replay);
        return markerList(McpJson.replayInfo(s.id, s.meta, false, 0, s.meta.durationTicks), s.project, type, query);
    }

    static JsonObject marker(McpGame game, McpContext context, String replay, String id) throws Exception {
        // Null when the replay asked for is not the open one; empty when it is open but has no such marker.
        Optional<JsonObject> live = context.onGameThread(() -> {
            McpGame.OpenReplay o = open(game, replay);
            if (o == null) {
                return null;
            }
            JsonObject out = markerDetail(McpJson.replayInfo(o.id(), o.meta(), true, o.tick(), o.durationTicks()), o.project(), id);
            Marker m = o.project().marker(id);
            if (out != null && m != null) {
                out.getAsJsonObject("marker").addProperty("atPlayhead", m.activeAt(o.tick(), 0));
            }
            return Optional.ofNullable(out);
        });
        if (live != null) {
            return live.orElseThrow(() -> new McpArgs.Invalid("The open replay has no marker with the id '" + id
                    + "' (ids come from list_markers)"));
        }
        Stored s = stored(game, replay);
        JsonObject out = markerDetail(McpJson.replayInfo(s.id, s.meta, false, 0, s.meta.durationTicks), s.project, id);
        if (out == null) {
            throw new McpArgs.Invalid("Replay '" + s.id + "' has no marker with the id '" + id + "' (ids come from list_markers)");
        }
        return out;
    }

    /** The resource view of the open replay: its project summary or its markers, or {@code open: false}. */
    private static JsonObject openOrNothing(McpGame game, McpContext context, boolean project) throws Exception {
        JsonObject out = context.onGameThread(() -> {
            McpGame.OpenReplay o = game.openReplay();
            if (o == null) {
                return null;
            }
            JsonObject replay = McpJson.replayInfo(o.id(), o.meta(), true, o.tick(), o.durationTicks());
            return project ? McpJson.project(replay, o.meta(), o.project(), List.of(), false, o.durationTicks())
                    : markerList(replay, o.project(), null, "");
        });
        if (out == null) {
            out = new JsonObject();
            out.addProperty("open", false);
            out.addProperty("message", "No replay is open in the game.");
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** The open replay when it is the one asked for (or none was asked), else null. */
    private static McpGame.OpenReplay open(McpGame game, String replay) {
        McpGame.OpenReplay o = game.openReplay();
        return o != null && (replay == null || replay.equals(o.id())) ? o : null;
    }

    private static JsonObject markerList(JsonObject replay, ReplayProject project, String type, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JsonArray list = new JsonArray();
        for (Marker m : project.markers) {
            if ((type == null || type.equals(m.type)) && (q.isEmpty() || contains(m.name, q) || contains(m.note, q))) {
                list.add(McpJson.marker(m));
            }
        }
        JsonObject out = new JsonObject();
        out.add("replay", replay);
        out.addProperty("total", project.markers.size());
        out.addProperty("count", list.size());
        out.add("markers", list);
        return out;
    }

    private static JsonObject markerDetail(JsonObject replay, ReplayProject project, String id) {
        List<Marker> markers = project.markers;
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (m.id.equals(id)) {
                JsonObject marker = McpJson.marker(m);
                marker.addProperty("index", i);
                marker.addProperty("previous", i > 0 ? markers.get(i - 1).id : "");
                marker.addProperty("next", i + 1 < markers.size() ? markers.get(i + 1).id : "");
                JsonObject out = new JsonObject();
                out.add("replay", replay);
                out.add("marker", marker);
                return out;
            }
        }
        return null;
    }

    /** A saved replay read from its files, which stay as they are. */
    private record Stored(String id, ReplayMeta meta, ReplayProject project) {
    }

    private static Stored stored(McpGame game, String replay) throws McpArgs.Invalid {
        if (replay == null) {
            throw new McpArgs.Invalid("No replay is open in the game. Open one, or name a saved replay with the 'replay' argument "
                    + "(ids come from list_replays).");
        }
        Path dir = replayDir(game.replaysRoot(), replay);
        if (dir == null || !Files.isRegularFile(dir.resolve(ReplayStorage.STREAM))) {
            throw new McpArgs.Invalid("No saved replay has the id '" + replay + "' (ids come from list_replays)");
        }
        ReplayMeta meta = ReplayStorage.readMeta(dir);
        ReplayProject project = null;
        Path file = dir.resolve(ReplayStorage.PROJECT);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                project = ReplayStorage.GSON.fromJson(reader, ReplayProject.class);
            } catch (Exception e) {
                throw new McpArgs.Invalid("The project.json of replay '" + replay + "' cannot be read: " + McpProtocol.describe(e));
            }
        }
        if (project == null) {
            project = new ReplayProject();
        }
        project.normalize();
        return new Stored(dir.getFileName().toString(), meta, project);
    }

    /** The folder of replay {@code id}, right inside {@code root}, or null when the id names anything else. */
    static Path replayDir(Path root, String id) {
        if (id == null || id.isEmpty() || id.length() > 255 || id.equals(".") || id.equals("..")
                || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path base = root.toAbsolutePath().normalize();
            Path dir = base.resolve(id).normalize();
            return base.equals(dir.getParent()) ? dir : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean contains(String text, String lowerQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }
}
